/*
 * peri_spake_jni.c — spake2-ee(ThirdParty/spake2-ee)를 Kotlin으로 내보내는 얇은 셰임.
 *
 * 설계 규칙 두 가지:
 *
 *  1. **핸들이 없다.** 프로토콜 상태(client_state/server_state)는 불투명 byte[]로 오간다.
 *     jlong 포인터를 넘기면 페어링이 중간에 끊길 때마다 해제 경로를 따로 관리해야 하고,
 *     그 경로가 곧 누수(그리고 누수된 게 하필 키 재료)가 된다. 복사가 몇십 바이트라
 *     성능 얘기가 될 여지도 없다.
 *
 *  2. **출력이 하나다.** 여러 값을 돌려줄 땐 이어 붙여 한 배열로 낸다. out-param을 쓰면
 *     JNI 쪽에 부분 실패 상태가 생기는데, 여기서 부분 실패는 "키 절반만 세워진 세션"이다.
 *
 * 실패는 전부 null(또는 false)이다 — 이유를 나누지 않는다. PAKE에서 "왜 틀렸는지"를
 * 돌려주는 건 그 자체로 오라클이다.
 */

#include <jni.h>
#include <string.h>

#include <sodium.h>

#include "crypto_spake.h"

#define CLIENT_STATE_BYTES ((jsize) sizeof(crypto_spake_client_state))
#define SERVER_STATE_BYTES ((jsize) sizeof(crypto_spake_server_state))

/* ── 도우미 ───────────────────────────────────────────────────────────────── */

/** 길이가 정확히 want인 배열의 내용을 dst로 복사. 아니면 0. */
static int
copy_exact(JNIEnv *env, jbyteArray src, void *dst, jsize want)
{
    if (src == NULL || (*env)->GetArrayLength(env, src) != want) {
        return 0;
    }
    (*env)->GetByteArrayRegion(env, src, 0, want, (jbyte *) dst);
    return 1;
}

/** 임의 길이 배열을 힙으로. 호출자가 sodium_free 한다. len은 0일 수 있다. */
static unsigned char *
take_bytes(JNIEnv *env, jbyteArray src, size_t *len_out)
{
    jsize          len;
    unsigned char *buf;

    if (src == NULL) {
        *len_out = 0;
        return NULL;
    }
    len = (*env)->GetArrayLength(env, src);
    buf = sodium_malloc((size_t) len + 1U); /* +1: len==0 도 유효한 포인터를 받게 */
    if (buf == NULL) {
        *len_out = 0;
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, src, 0, len, (jbyte *) buf);
    *len_out = (size_t) len;
    return buf;
}

static jbyteArray
to_jbytes(JNIEnv *env, const void *src, jsize len)
{
    jbyteArray out = (*env)->NewByteArray(env, len);
    if (out != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *) src);
    }
    return out;
}

/* ── 초기화 ───────────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) vm;
    (void) reserved;
    /* 0 = 초기화됨, 1 = 이미 초기화됨. 둘 다 정상. */
    if (sodium_init() < 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

/* ── 공통 ─────────────────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_com_peri_display_crypto_Spake2_nativeAlgDefault(JNIEnv *env, jobject thiz)
{
    (void) env;
    (void) thiz;
    return (jint) crypto_pwhash_alg_default();
}

/* ── 서버 역할 (Android — PIN을 만든 쪽) ──────────────────────────────────── */

/** PIN → 검증자(stored_data). Argon2id가 도므로 호출자가 워커 스레드에서 부른다. */
JNIEXPORT jbyteArray JNICALL
Java_com_peri_display_crypto_Spake2_nativeServerStore(JNIEnv *env, jobject thiz,
                                                      jbyteArray jpin,
                                                      jlong opslimit,
                                                      jlong memlimit)
{
    unsigned char  stored[crypto_spake_STOREDBYTES];
    unsigned char *pin;
    size_t         pin_len;
    jbyteArray     out = NULL;

    (void) thiz;
    pin = take_bytes(env, jpin, &pin_len);
    if (pin == NULL) {
        return NULL;
    }
    if (crypto_spake_server_store(stored, (const char *) pin,
                                  (unsigned long long) pin_len,
                                  (unsigned long long) opslimit,
                                  (size_t) memlimit) == 0) {
        out = to_jbytes(env, stored, crypto_spake_STOREDBYTES);
    }
    sodium_free(pin);
    sodium_memzero(stored, sizeof stored);
    return out;
}

/** stored_data → (server_state ‖ public_data). public_data만 선에 싣는다. */
JNIEXPORT jbyteArray JNICALL
Java_com_peri_display_crypto_Spake2_nativeStep0(JNIEnv *env, jobject thiz,
                                                jbyteArray jstored)
{
    crypto_spake_server_state st = { 0 };
    unsigned char             stored[crypto_spake_STOREDBYTES] = { 0 };
    unsigned char             out[SERVER_STATE_BYTES + crypto_spake_PUBLICDATABYTES] = { 0 };
    jbyteArray                ret = NULL;

    (void) thiz;
    if (!copy_exact(env, jstored, stored, crypto_spake_STOREDBYTES)) {
        return NULL;
    }
    if (crypto_spake_step0(&st, out + SERVER_STATE_BYTES, stored) == 0) {
        memcpy(out, &st, SERVER_STATE_BYTES);
        ret = to_jbytes(env, out, (jsize) sizeof out);
    }
    sodium_memzero(&st, sizeof st);
    sodium_memzero(stored, sizeof stored);
    sodium_memzero(out, sizeof out);
    return ret;
}

/** response1 → (server_state' ‖ response2). */
JNIEXPORT jbyteArray JNICALL
Java_com_peri_display_crypto_Spake2_nativeStep2(JNIEnv *env, jobject thiz,
                                                jbyteArray jstate,
                                                jbyteArray jstored,
                                                jbyteArray jresponse1,
                                                jbyteArray jclient_id,
                                                jbyteArray jserver_id)
{
    crypto_spake_server_state st = { 0 };
    unsigned char             stored[crypto_spake_STOREDBYTES] = { 0 };
    unsigned char             response1[crypto_spake_RESPONSE1BYTES] = { 0 };
    unsigned char             out[SERVER_STATE_BYTES + crypto_spake_RESPONSE2BYTES] = { 0 };
    unsigned char            *client_id = NULL, *server_id = NULL;
    size_t                    client_id_len = 0, server_id_len = 0;
    jbyteArray                ret = NULL;

    (void) thiz;
    if (!copy_exact(env, jstate, &st, SERVER_STATE_BYTES) ||
        !copy_exact(env, jstored, stored, crypto_spake_STOREDBYTES) ||
        !copy_exact(env, jresponse1, response1, crypto_spake_RESPONSE1BYTES)) {
        goto done;
    }
    client_id = take_bytes(env, jclient_id, &client_id_len);
    server_id = take_bytes(env, jserver_id, &server_id_len);
    if (client_id == NULL || server_id == NULL) {
        goto done;
    }
    if (crypto_spake_step2(&st, out + SERVER_STATE_BYTES,
                           (const char *) client_id, client_id_len,
                           (const char *) server_id, server_id_len,
                           stored, response1) == 0) {
        memcpy(out, &st, SERVER_STATE_BYTES);
        ret = to_jbytes(env, out, (jsize) sizeof out);
    }

done:
    if (client_id != NULL) { sodium_free(client_id); }
    if (server_id != NULL) { sodium_free(server_id); }
    sodium_memzero(&st, sizeof st);
    sodium_memzero(stored, sizeof stored);
    sodium_memzero(out, sizeof out);
    return ret;
}

/** response3 검증 → (client_sk ‖ server_sk). 여기서 상대가 PIN을 알았음이 증명된다. */
JNIEXPORT jbyteArray JNICALL
Java_com_peri_display_crypto_Spake2_nativeStep4(JNIEnv *env, jobject thiz,
                                                jbyteArray jstate,
                                                jbyteArray jresponse3)
{
    crypto_spake_server_state st = { 0 };
    crypto_spake_shared_keys  keys = { 0 };
    unsigned char             response3[crypto_spake_RESPONSE3BYTES] = { 0 };
    jbyteArray                ret = NULL;

    (void) thiz;
    if (!copy_exact(env, jstate, &st, SERVER_STATE_BYTES) ||
        !copy_exact(env, jresponse3, response3, crypto_spake_RESPONSE3BYTES)) {
        goto done;
    }
    if (crypto_spake_step4(&st, &keys, response3) == 0) {
        ret = to_jbytes(env, &keys, (jsize) sizeof keys);
    }

done:
    sodium_memzero(&st, sizeof st);
    sodium_memzero(&keys, sizeof keys);
    sodium_memzero(response3, sizeof response3);
    return ret;
}

/* ── 클라이언트 역할 (Mac이 맡지만, 자체 검증 테스트가 양쪽을 다 돌린다) ──── */

JNIEXPORT jboolean JNICALL
Java_com_peri_display_crypto_Spake2_nativeValidatePublicData(JNIEnv *env, jobject thiz,
                                                             jbyteArray jpublic,
                                                             jint alg,
                                                             jlong opslimit,
                                                             jlong memlimit)
{
    unsigned char public_data[crypto_spake_PUBLICDATABYTES];

    (void) thiz;
    if (!copy_exact(env, jpublic, public_data, crypto_spake_PUBLICDATABYTES)) {
        return JNI_FALSE;
    }
    return crypto_spake_validate_public_data(public_data, (int) alg,
                                             (unsigned long long) opslimit,
                                             (unsigned long long) memlimit) == 0
               ? JNI_TRUE
               : JNI_FALSE;
}

/** public_data + PIN → (client_state ‖ response1). Argon2id가 돈다. */
JNIEXPORT jbyteArray JNICALL
Java_com_peri_display_crypto_Spake2_nativeStep1(JNIEnv *env, jobject thiz,
                                                jbyteArray jpublic,
                                                jbyteArray jpin)
{
    crypto_spake_client_state st = { 0 };
    unsigned char             public_data[crypto_spake_PUBLICDATABYTES] = { 0 };
    unsigned char             out[CLIENT_STATE_BYTES + crypto_spake_RESPONSE1BYTES] = { 0 };
    unsigned char            *pin = NULL;
    size_t                    pin_len = 0;
    jbyteArray                ret = NULL;

    (void) thiz;
    if (!copy_exact(env, jpublic, public_data, crypto_spake_PUBLICDATABYTES)) {
        goto done;
    }
    pin = take_bytes(env, jpin, &pin_len);
    if (pin == NULL) {
        goto done;
    }
    if (crypto_spake_step1(&st, out + CLIENT_STATE_BYTES, public_data,
                           (const char *) pin,
                           (unsigned long long) pin_len) == 0) {
        memcpy(out, &st, CLIENT_STATE_BYTES);
        ret = to_jbytes(env, out, (jsize) sizeof out);
    }

done:
    if (pin != NULL) { sodium_free(pin); }
    sodium_memzero(&st, sizeof st);
    sodium_memzero(out, sizeof out);
    return ret;
}

/** response2 검증 → (response3 ‖ client_sk ‖ server_sk). */
JNIEXPORT jbyteArray JNICALL
Java_com_peri_display_crypto_Spake2_nativeStep3(JNIEnv *env, jobject thiz,
                                                jbyteArray jstate,
                                                jbyteArray jresponse2,
                                                jbyteArray jclient_id,
                                                jbyteArray jserver_id)
{
    crypto_spake_client_state st = { 0 };
    crypto_spake_shared_keys  keys = { 0 };
    unsigned char             response2[crypto_spake_RESPONSE2BYTES] = { 0 };
    unsigned char             out[crypto_spake_RESPONSE3BYTES + sizeof(crypto_spake_shared_keys)] = { 0 };
    unsigned char            *client_id = NULL, *server_id = NULL;
    size_t                    client_id_len = 0, server_id_len = 0;
    jbyteArray                ret = NULL;

    (void) thiz;
    if (!copy_exact(env, jstate, &st, CLIENT_STATE_BYTES) ||
        !copy_exact(env, jresponse2, response2, crypto_spake_RESPONSE2BYTES)) {
        goto done;
    }
    client_id = take_bytes(env, jclient_id, &client_id_len);
    server_id = take_bytes(env, jserver_id, &server_id_len);
    if (client_id == NULL || server_id == NULL) {
        goto done;
    }
    if (crypto_spake_step3(&st, out, &keys,
                           (const char *) client_id, client_id_len,
                           (const char *) server_id, server_id_len,
                           response2) == 0) {
        memcpy(out + crypto_spake_RESPONSE3BYTES, &keys, sizeof keys);
        ret = to_jbytes(env, out, (jsize) sizeof out);
    }

done:
    if (client_id != NULL) { sodium_free(client_id); }
    if (server_id != NULL) { sodium_free(server_id); }
    sodium_memzero(&st, sizeof st);
    sodium_memzero(&keys, sizeof keys);
    sodium_memzero(out, sizeof out);
    return ret;
}
