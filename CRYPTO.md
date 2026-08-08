# Peri Display — 암호화 스펙 (v0.1, 구현 기준)

Mac(소스) ↔ Android(싱크) 간 **E2E 암호화**. 양자내성 하이브리드 키교환 후 AES-256-GCM.
이 문서는 현재 코드에 구현된 그대로를 기술한다 (DESIGN.md §3의 구체화).

- Mac: Apple **CryptoKit** (macOS 26+)
- Android: **BouncyCastle** `bcprov-jdk18on` **1.84** (비FIPS) lightweight API + JCE(AES-GCM)
- 역할: Mac = **responder/encapsulator**, Android = **initiator/decapsulator**
- 상태: 양쪽 빌드 + **런타임 end-to-end 복호화 확인됨**

---

## 1. 세션 핸드셰이크 — PQ-STS (Station-to-Station)

매 세션 **임시 키쌍**을 생성한다 → 세션 단위 전방향 비밀성. Android = 개시자(Alice),
Mac = 응답자(Bob). **TCP(또는 AOA 파이프)만으로 수립된다** — UDP는 영상 앱이 뜰 때 열린다.

```
Android (Alice)                                   Mac (Bob)
 x25519 키쌍, ML-KEM-768 키쌍 생성

 ── StsHello { core } ──────────────────────────►
    core = { ver, session_id, X_A(32B), PK_A(1184B),
             client_name, codecs, display, network, decoders }

                                    (ct_B, k_PQC) = MLKEM.Encaps(PK_A)
                                    k_ECDH = X25519(y_B, X_A)
                                    K   = HKDF(salt=∅, k_ECDH‖k_PQC, "PQ-STS Session Key")
                                    th  = SHA256("PeriSTS-v1" ‖ LP(hello.core) ‖ LP(resp.core))
                                    S_B = Ed25519(sk_B, "PeriSTS-v1/server" ‖ th)
 ◄── StsResponse { core, E_K(cert_B ‖ S_B) } ────
    core = { ver, session_id(echo), X_B(32B), ct_B(1088B), server_name }

 K 합성 → 봉인 해제 → cert_B 자기서명 + S_B 검증 → TrustStore 대조
 S_A = Ed25519(sk_A, "PeriSTS-v1/client" ‖ th)
 ── StsConfirm { E_K(cert_A ‖ S_A) } ───────────►
                                    봉인 해제 → 검증 → 상호 인증 완료
```

### 옛 방식(ClientHello/ServerHello/ClientAuth)에서 무엇이 고쳐졌나

**① 서명이 협상 내용까지 덮는다.** 이게 실제 구멍이었다. 옛 전사 해시는 KEX 재료(nonce·
공개키·암호문)만 묶었고, ClientHello의 나머지 — `supported_codecs`, `display`(해상도·주사율·
`supported_dynamic_ranges`), `decoders`, `protocol_version` — 는 **평문이면서 아무 서명도
덮지 않았다.** 경로상의 공격자가 H265를 지워 H264로, HDR을 지워 SDR로 강등해도 양쪽 서명이
그대로 검증됐다. 지금은 `core` 바이트 전체가 서명 대상이라 그 강등이 불가능하다.

**② 신원이 봉인된다.** 인증서와 서명이 평문으로 실리던 걸 K로 봉인한다. 같은 망의 수동
관찰자에게 "이 맥과 이 폰이 붙는다"가 새지 않고, 봉인이 열렸다는 사실 자체가 키 확인이다.

**③ `core`가 `bytes`인 이유.** 서명 대상은 **선에 실제로 나간 바이트**여야 한다. 중첩 메시지로
두면 받은 쪽이 재직렬화해 해시해야 하는데 protobuf 재직렬화가 바이트 단위로 같다는 보장이
없다(미지 필드·필드 순서). 불투명 바이트로 실으면 양쪽이 같은 걸 본다.

**④ nonce 필드가 사라졌다.** 임시 키쌍이 매번 새로 생기므로 그게 곧 신선도다(HKDF salt를
비우는 이유도 같다). 재전송은 `session_id`가 `core` 안에서 서명에 덮여 막힌다.

---

## 2. 키 파생 — HKDF-SHA256

```
ikm = k_ECDH ‖ k_PQC                    # 32B + 32B (X25519 먼저, KEM 나중)
K       = HKDF(salt=∅, ikm, info="PQ-STS Session Key",      L=32)  # 신원 봉인 전용
key_m2a = HKDF(salt=∅, ikm, info="PQ-STS Session Key/m2a",  L=32)  # Mac → Android
key_a2m = HKDF(salt=∅, ikm, info="PQ-STS Session Key/a2m",  L=32)  # Android → Mac
udp_mk  = HKDF(salt=∅, ikm, info="PeriUDP-v1/master",       L=32)  # 데이터그램 세대의 뿌리(§2e)
```

- **하이브리드 근거**: X25519(고전) 또는 ML-KEM(양자내성) 중 하나가 깨져도 나머지가 보호.
- **방향별 키 분리** → 두 방향이 같은 (key, nonce)를 절대 공유하지 않음.
- 신원 봉인 nonce = `[2][0,0,0][8B BE 역할]` (server=0, client=1). 각 방향이 정확히 한 번만
  봉인하고 **받은 nonce를 믿지 않는다** — 우리가 계산한 값과 다르면 거부한다.
- Mac: send=key_m2a, recv=key_a2m / Android: send=key_a2m, recv=key_m2a.

---

## 2b. BLE 페어링 핸드셰이크 v2 — PIN 기반 PAKE

위 §1–2는 **이미 서로를 신뢰하는** 두 기기의 LAN/USB 세션이다. 그 신뢰가 어디서 오는지가
이 절이다. 역할이 §1과 **반대**라는 점에 주의: 여기선 Mac이 먼저 말하므로 Mac이 ML-KEM
키쌍을 만든다.

```
Mac (개시자 / SPAKE2 client)                    Android (응답자 / SPAKE2 server)
 nonce_M(32B), ML-KEM 키쌍 생성

 ── HELLO ────────────────────────────────────►
    { nonce_M, ek_M(1184B) }
                                                 PIN 4자리 생성 → **화면에 표시**
                                                 (ct, ss_kem) = MLKEM.Encapsulate(ek_M)
                                                 stored = Argon2id(PIN); public_data = step0
 ◄── OFFER ───────────────────────────────────
    { nonce_A, ct(1088B), public_data(36B) }

 ss_kem = MLKEM.Decapsulate(ct)
 ── 사람이 PIN을 Mac에 옮겨 적는다 ──

 ── PAKE1 (spake_step1) ──────────────────────►
 ◄── PAKE2 (spake_step2) ──────────────────────
 ── PAKE3 (spake_step3) ─────────────────────►   spake_step4 → 상호 인증 완료
```

**전사 바인딩.** 평문으로 오간 flight 1·2 전부를 해시해 SPAKE2의 신원 문자열로 넣는다:

```
th        = SHA256("PeriBLE-pair-v2" ‖ LP(nonce_M) ‖ LP(ek_M) ‖ LP(nonce_A) ‖ LP(ct) ‖ LP(public_data))
client_id = "peri/mac"     ‖ th
server_id = "peri/android" ‖ th
```

중간자가 nonce든 KEM 재료든 한 바이트라도 건드리면 양쪽 `th`가 갈라져 PAKE 검증이 그
자리에서 죽는다.

**키 결합.** PAKE와 KEM 결과를 **한 ikm에** 넣는다 — 둘 다 깨야 열린다.

```
ikm  = ss_kem ‖ client_sk ‖ server_sk
salt = nonce_M ‖ nonce_A
key_m2a = HKDF-SHA256(ikm, salt, info="PeriBLE-v2/m2a", L=32)
key_a2m = HKDF-SHA256(ikm, salt, info="PeriBLE-v2/a2m", L=32)
```

**라운드가 남기는 것 — 링크 키.** 같은 재료에서 라벨 하나만 갈아 32바이트를 더 뽑는다.

```
link_key = HKDF-SHA256(ikm, salt, info="PeriBLE-v2/link", L=32)
```

세션 키는 이 라운드와 함께 죽지만 이건 살아남아, **상대 신원 옆에 짝지어 저장된다** — 사람이
PIN을 옮겨 적은 그 한 번의 확인이 응결돼 남는 값이다. 방향을 가르지 않는다(양쪽이 똑같은
하나를 든다). HKDF라 세션 키와 서로를 유도하지 못하므로 이번 채널 키가 새도 링크 키는 무사하다.

- 쓰임새: **호출(paging) 토큰의 유도 재료**다. 링크 키를 가진 쪽만 남의 폰을 깨울 수 있다
  (§2c). 재연결 시 PAKE 대신 STS로 갈 근거이기도 하다.
- 선에 실리지 않는다. 각자 계산해 각자 저장할 뿐이다.
- 저장은 평문 저장소(`trust.json`)가 **아니다** — Mac은 Keychain(ThisDeviceOnly),
  Android는 AndroidKeyStore KEK로 감싼 SharedPreferences(`SecretVault`)에 넣는다.
- 신뢰 해제(`revoke`)는 이 키도 같이 지운다. 안 그러면 해제한 기기와 다시 만났을 때
  사람 확인 없이 되살아날 재료가 남는다.
- 케이블(AOA) 부트스트랩으로 세운 신뢰에는 이 키가 없다. PAKE를 거치지 않았기 때문이다.
- 두 구현이 같은 바이트를 내는지는 `PeriBleHandshakeTest`가 Mac 코드로 뽑은 벡터로 지킨다.
  이 값은 선에 실리지 않아 **어긋나도 페어링은 성공한다** — 다음 재연결에 가서야 드러난다.

**신원 교환은 그 뒤에.** 장기 Ed25519 인증서는 봉인된 채널 안에서 오간다. 평문 flight에
싣지 않으므로 지나가는 스캐너에게 기기 신원이 새지 않고, 채널이 이미 중간자 없는 상태라
따로 서명을 붙일 필요도 없다. 양쪽은 상대 인증서를 열어 본 뒤에만 TrustStore에 핀한다.

**PIN이 4자리인 이유.** PAKE라 오프라인 대입이 불가능하고 틀린 추측은 아무 정보도 남기지
않는다. 남는 건 온라인 추측 1/10⁴뿐이고, **기회를 한 번으로 묶어** 그걸 반복 불가능하게
한다(틀리면 라운드 폐기 → 새 임시키·새 PIN). 그 규칙은 양쪽 상태 기계가 지킨다.

- 구현: `ThirdParty/spake2-ee` (SPAKE2+EE, libsodium) — Mac과 Android가 **같은 C를 컴파일**한다.
- Argon2id 파라미터(alg=Argon2id13, ops=2, mem=64MiB)는 양쪽에 못 박고 수신측이 대조한다.
  대조하지 않으면 상대가 memlimit에 4GiB를 적어 보내는 것만으로 우리를 죽일 수 있다.

### v1에서 무엇이 왜 사라졌나

v1은 commitment + 6자리 SAS 비교(또는 OTP)였고, **코드가 키 교환을 전혀 묶지 않았다.**
SAS 입력이 nonce와 인증서뿐이라, 중간자가 그 둘을 그대로 흘려보내면서 x25519와 ML-KEM만
자기 것으로 갈아끼우면 **두 기기 화면에 같은 6자리가 떴다.** 사람이 눈으로 맞다고 확인해도
실제로는 각자 공격자와 키를 나눠 가진 상태였다. OTP도 봉인해 보내는 값이라 중간자가 풀었다
다시 봉하면 그만이었다.

같이 사라진 것: 모드 협상(SAS/OTP/재인증)과 그걸 실어 나르던 프레임 다섯 개. 모드가 양쪽
합의 사항이라 어긋나면 두 기기가 서로 다른 프레임을 기다리며 멈추는 버그의 원천이었다.

---

## 2c. 호출(paging) 토큰 — 링크 키 기반 TOTP

폰이 상시 광고를 그만두는 대신, **Mac이 특정 기기 하나를 지목해 부른다.** 광고에 실을 수
있는 게 128비트 서비스 UUID뿐이라(macOS 제약) 거기에 전부 넣는다.

```
UUID = [4B "PERI"][4B nonce][8B OTP]
dk   = HKDF-SHA256(ikm = link_key, salt = nonce, info = "PeriPage-v2", L=32)
otp  = HMAC-SHA256(dk, epoch(8B BE))[0..8]                epoch = ⌊unixtime / 10s⌋
```

**Mac 공개키는 어디에도 안 들어간다.** 링크 키가 이미 상대별이라 **어느 링크 키로 값이
맞았는가**가 곧 "누가 부르는가"다. 공개키를 메시지에 더 넣어 봐야 이미 정해진 답을 한 번 더
적는 것이고, 그만큼 양쪽이 맞춰야 할 바이트만 는다. 아래 응답·힌트도 같다.

**ikm이 링크 키다.** 그래서 이 값은 페어링이 남긴 비밀을 가진 쪽만 만들 수 있다. 전에는
ikm이 폰 공개키였는데, 공개키는 정의상 비밀이 아니라 **한 번이라도 그 값을 본 사람이면
누구나 남의 폰을 깨울 수 있었다.**

- **nonce(전송) = 불연결성** — 광고마다 새로 뽑으므로 같은 상대끼리도 매번 달라 보인다.
  소금이 공격자 선택이어도 무방하다. ikm이 비밀이라 HKDF-Extract가 상정하는 그림 그대로다.
- **epoch(비전송) = 신선도** — 없으면 캡처한 광고를 언제든 재생해 "그 폰이 지금 여기 있나"를
  물을 수 있고, nonce로 만든 불연결성이 통째로 무너진다. 폰은 ±1 창까지 연다(시계 오차).
- 폰은 신뢰 목록을 훑어 후보마다 HKDF 1 + HMAC 3을 돌린다. 맞으면 **누가 부르는지**까지 나온다.
- 링크 키가 없는 상대는 조용히 건너뛴다 — 첫 페어링 전이거나 케이블로만 만난 Mac이다.
  그래서 **첫 페어링 한 번은 여전히 폰이 광고하는 경로**여야 한다.

**세션 인증자는 아니다.** 한 방향 방송이라 창(≤30초) 안에서는 재생된다. 이걸로 정해지는 건
"부를 자격이 있는 쪽이 부른다"까지고, 상대가 누구인지는 그 뒤 STS에서만 확정된다.

### 응답 — GATT를 여는 쪽은 언제나 Mac이다

> **응답 OTP는 5바이트다**(호출 OTP는 8바이트). 호출은 128비트 UUID 안에 실려 여유가 있지만
> 응답은 제조사 데이터에 실리는데, 레거시 광고 31B에서 Flags 3 + 서비스 UUID 18 + 제조사
> 머리 4를 빼면 본문이 **6바이트**뿐이다(버전 1 + 응답 5). 그 Flags 3바이트는 스택이 붙이는
> 것이라 우리 코드에 안 보이고, 세지 않아서 응답 광고가 통째로 거절되던 시기가 있었다.
> 40비트로 충분한 이유: 이 값이 정하는 건 "Mac이 GATT를 붙어 볼 것인가" 하나이고, 위조에
> 성공해도 STS에서 튕긴다.

폰은 호출을 알아들으면 **이름 없는 짧은 광고**로 답한다(제조사 데이터 `[1B 버전=2][5B 응답]`,
스캔 응답 없음, 20초). Mac이 그걸 보고 central로 붙는다. 역할이 라운드마다 뒤집히지 않는다.

```
resp_dk = HKDF-SHA256(ikm = link_key, salt = nonce, info = "PeriPage-v2/resp", L=32)
resp    = DT(HMAC-SHA256(resp_dk, epoch(8B BE)))[0..5]      # DT = RFC 4226 동적 절단
```

`nonce`는 **그 호출의 nonce**다(Mac은 호출 한 번에 하나를 쓴다). 그래서 응답이 어떤 호출에
대한 것인지가 묶인다. 라벨이 호출과 다르므로 둘이 서로를 계산하지 못한다.

---

## 2d. BLE 재연결 — PIN 없는 STS

페어링한 상대와 다시 만날 때. HELLO에 **호출 토큰과 같은 구성의 TOTP**를 실어 보낸다.

```
1. Mac → HELLO : [1B 모드=2][nonce_M][hint(32B)]
2. And → STS1  : X_A ‖ PK_A                        (Android = client, LAN과 같은 역할)
3. Mac → STS2  : X_B ‖ ct ‖ E_K(S_mac)
4. And → STS3  : E_K(S_and)

hint_dk = HKDF-SHA256(link_key, salt = nonce_M, info = "PeriBLE-sts-v1/hint")
hint    = HMAC-SHA256(hint_dk, epoch(8B BE))          ← **자르지 않는다** (32B)
th      = SHA256( LP("PeriBLE-sts-v1") ‖ LP(hello) ‖ LP(sts1) ‖ LP(sts2core) )
K       = HKDF-SHA256(k_ECDH ‖ k_PQC, salt = th, info = "PeriBLE-sts-v1/auth")
```

**폰은 아무것도 기억하지 않는다.** "아까 호출당했다" 같은 깃발을 두면 그 깃발이 선 짧은 창에
아무나 끼어들어 소비할 수 있다. 매 연결이 스스로 자격을 증명하고, 기억할 게 없으니 훔칠
것도 없다.

**모드 바이트는 요청이 아니라 통보다.** 받는 쪽은 그 바이트를 보고 갈라지지 않는다 — 힌트가
실제로 아는 Mac의 링크 키에 맞아떨어질 때만 STS로 간다. 안 맞으면 **PAKE로 물러서지 않고
라운드가 죽는다.** 물러서는 순간 그게 강등이다(익명이 언제나 더 약한 인증자를 고를 수 있게
된다). 사유도 알리지 않는다 — "너를 모른다"와 "PIN이 틀렸다"를 구분해 주면 익명 프로버가
상대의 신뢰 목록 유무를 알아낸다.

`hello`가 전사에 통째로 들어가므로 **모드 바이트와 힌트가 양쪽 서명에 덮인다.** 중간자가
모드를 2→1로 바꿔 PIN 라운드로 끌어내리려 하면 전사가 갈라져 서명 검증이 그 자리에서 죽는다.

- 힌트의 유도 라벨은 호출·응답 토큰과 **전부 다르다.** 호출 토큰은 방송이라 표본을 모을 수
  있는데, 같은 `dk`를 재사용하면 안전이 창 길이에만 기대게 된다.
- 힌트는 **자르지 않는다.** 호출 토큰이 8바이트, 응답이 5바이트인 건 광고 예산(31B) 때문이지
  그게 충분해서가 아니다. GATT에는 그 예산이 없고, 자르면 아래 대조가 어긋날 여지가 생긴다.
  짧게 온 값은 거부한다 — 관용하면 자른 것과 같아진다.
- **짐작과 서명이 같은 사람을 가리키는지 대조한다.** 서명의 주인을 신뢰 목록에서 따로 찾아낸
  뒤(`resolveSigner`) 짐작한 상대와 같은지 본다 — Mac은 자기가 부른 상대와, 폰은 힌트가 짚은
  Mac과. 짐작한 키 하나로만 검증해도 결과는 같지만 그러면 이 등식이 코드 구조에 숨는다.
  둘이 갈릴 수 있는 경로가 실제로 있다: 힌트·응답이 짧아 충돌이 가능하고, 복원·중복
  등록으로 링크 키가 두 항목에 걸릴 수도 있다. 그때 조회는 `firstOrNull`이라 먼저 걸린 쪽을
  짚는다. 대조를 밖으로 꺼내 두면 그 상황이 "확인 실패"가 아니라 무엇이 어긋났는지로 남는다.
- 신뢰를 새로 세우지 않는다(핀도 인증서 교환도 없다). 그 경로는 PAKE 하나뿐이다 —
  양쪽 상태 기계 테스트가 그 불변식을 지킨다.
- 봉인된 이후 프레임(PARAMS·CANDIDATES·PUNCH)은 PAKE 경로와 **같은 라벨**의 세션을 탄다.
  그 뒤의 코드는 어느 경로로 왔는지 몰라도 된다.

---

## 2e. 데이터그램(UDP) 채널 — 0-RTT 세대 키

### 왜 세션과 분리했나

세션 수립은 **TCP만으로** 끝난다. UDP를 같이 열지 않는 이유는 순전히 NAT다:

- UDP 매핑은 수명이 짧다(흔히 30초 안팎, 캐리어 CGNAT은 더). 붙들고 있으려면 아무도 안 보는
  동안에도 계속 두드려야 하고, 그건 무선 라디오를 계속 깨우는 일이다.
- TCP 매핑은 분~시간 단위로 산다. 제어 채널 하나만 쥐고 있으면 세션은 값싸게 유지된다.

그래서 데이터그램 경로는 **영상을 나르는 앱이 실제로 떠 있는 동안만** 산다. 알림만 쓰는
세션은 UDP 소켓도 NAT 매핑도 keepalive도 갖지 않는다. 경로를 잃어도 BLE가 대역 밖에서
재수립을 돕는다는 것이 이 선택의 안전망이다.

### 세대 키 (0-RTT)

```
channel_id                                          # 16B 난수, **Mac이 세대마다 부여**
udp_m2a = HKDF(salt=channel_id, udp_mk, info="PeriUDP-v1/m2a", L=32)   # Mac → Android
udp_a2m = HKDF(salt=channel_id, udp_mk, info="PeriUDP-v1/a2m", L=32)   # Android → Mac
```

- **왕복이 없다.** 재료는 TCP에서 이미 합의한 `udp_mk`이고, 소금은 `UdpOpen`으로 건너오는
  이름 하나다. 즉 그 메시지를 보는 순간 양쪽이 같은 키에 도달하고, **첫 데이터그램부터
  봉인돼 나간다.** UDP 위에는 핸드셰이크가 없다.
- **세대 격리.** 영상 nonce는 `frame_id`에서 유도되는데, 채널을 닫았다 다시 열면서 프레임
  번호가 되감기면 같은 (키, nonce)가 두 번 나온다. 세대마다 소금이 바뀌므로 그 사고가
  **구조적으로** 불가능하다 — 카운터 규율에 기대지 않는다.
- **세션 키와 갈려 있다.** `udp_mk`는 별도 라벨이라 세션 키를 알아도 세대 키가 나오지 않고,
  그 반대도 마찬가지다.
- **AOA(케이블)에는 세대가 없다.** 파이프가 링크 수명 내내 열려 있고 NAT도 포트도 없어서
  세대 키가 존재할 이유(짧은 수명·되감기)가 통째로 성립하지 않는다. 그 경로의 영상은
  `key_m2a`/`key_a2m`을 그대로 쓴다.

### 여는 순서 — Mac이 개시한다

```
Mac                                                     Android
 최신성 경로를 쓰는 앱이 떴다
 channel_id 생성 → 그 자리에서 udp_m2a/udp_a2m 유도
 UDP 후보 수집(STUN)
 ── UdpOpen { channel_id, 후보묶음 } ─────────────────►   (CONTROL, 세션 키로 봉인)
                                        세대 키 유도 — **이 시점에 0-RTT 준비 완료**
                                        UDP 후보 수집
 ◄── UdpAccept { channel_id, 후보묶음 } ────────────────   (CONTROL)
 양쪽 홀펀칭 ────────────────────────────────────────────
 ◄── UDP HELLO ─────────────────────────────────────────   (봉인된 채로 도착)
     [1B 0][16B channel_id][12B nonce][AEAD_udp_a2m(channel_id)]
 세대 조회 → 봉인 검증 → 영상 피어 링크
 ...
 마지막 앱이 내려감
 ── UdpClose { channel_id, reason } ─────────────────────►  양쪽이 키·소켓을 놓는다
```

- **부여자가 하나여야 한다.** 데이터그램은 연결이 없어 발신자를 스스로 증명하지 못하므로,
  받는 쪽이 "이건 어느 세션의 것인가"를 먼저 알아야 열어 볼 키를 고를 수 있다. 그 이름을
  나눠 갖는 가장 싼 장소가 이미 상호 인증된 TCP 제어 채널이다.
- **시그널링이 BLE가 아니다.** 세션이 이미 서 있으므로 신원을 다시 세울 필요가 없고,
  BLE 왕복(연결 간격·MTU)도 타지 않는다.
- HELLO의 평문 접두로 무엇이 새는가: 같은 세대의 데이터그램들이 서로 묶인다는 것뿐이다.
  그건 출발지 주소로 이미 묶이고, id는 세대마다 새 난수라 기기를 가리키지 않는다.
  **봉인의 평문도 같은 channel_id**라 접두만 바꿔치기하면 열리지 않는다.
- 예전에는 살아 있는 세션들의 키로 **시도복호화**해 HELLO를 디먹스했다. 세션이 몇 개 안 되니
  값은 쌌지만 답을 아는 방법이 "다 해 보기"였다. 지금은 조회 한 번 + 봉인 검증이다.

### fast UDP 모드

Mac 설정(`f fastudp` 플래그로 후보 묶음에 실려 전달). 켜면 **접속하는 라운드에서 UDP까지
함께 뚫어 두고**, 그 세션의 첫 세대가 ICE 라운드 없이(`UdpOpen.candidates`가 비어서 온다)
그 경로를 물려받는다. 세션이 서는 순간 영상 앱이 곧바로 붙어야 할 때를 위한 것이다 —
지난번에 켜 둔 앱이 그런 앱이거나, 망이 바뀌어 채널이 통째로 다시 서는 중일 때.

**수명 규칙은 켜도 그대로다.** 마지막 영상 앱이 내려가면 세대는 닫힌다. 이 스위치가 바꾸는
것은 "언제 뚫는가"이지 "얼마나 붙들고 있는가"가 아니다.

재협상(망 전환) 라운드에서는 설정과 무관하게, **지금 열려 있는 세대가 있으면** UDP 후보를
함께 싣는다. 그 순간 화면이 이미 흐르고 있었다는 뜻이므로 새 망에서 세대를 처음부터 다시
여는 공백이 그대로 사용자에게 보이기 때문이다. 세대 자체는 경로가 갈려도 살아남는다
(세션 키가 그대로이므로 세대 키도 그대로다) — 바뀌는 건 주소뿐이고 HELLO가 다시 등록한다.

---

## 3. 대칭 암호 — AES-256-GCM

- 키 256-bit, 인증 태그 128-bit(16B), nonce 96-bit(12B).
- **Nonce = 단조 증가 카운터**: 12바이트 중 마지막 8바이트가 카운터(big-endian), 앞 4바이트 0.
  - 방향별 키마다 **독립 카운터**(0부터). 같은 키로 nonce 재사용 없음.
  - control/video가 같은 송신 키를 공유하므로 **하나의 송신 카운터**를 원자적으로 증가.
- 전송 레이아웃: `nonce(12B) ‖ ciphertext ‖ tag(16B)`.
  - CryptoKit: `AES.GCM.SealedBox.ciphertext + .tag`
  - JCE: `Cipher(AES/GCM/NoPadding).doFinal` 출력 = `ciphertext ‖ tag`
- AAD: 현재 미사용(향후 영상 헤더를 AAD로 바인딩 가능 — §6).

---

## 4. 와이어 프레이밍

프레임: `[4B big-endian length N][1B type][payload]`

| type | 이름 | payload | 암호화 |
|------|------|---------|--------|
| 0 | HANDSHAKE | 직렬화된 `ControlMessage` (ClientHello/ServerHello) | **평문** (키 합의 전) |
| 1 | CONTROL | `[12B nonce][ciphertext‖tag]` → `ControlMessage` | AES-GCM(송신 키) |
| 2 | VIDEO | `[12B VideoHeader(평문)][12B nonce][ciphertext‖tag]` → bitstream | AES-GCM(송신 키) |

- VIDEO 헤더는 **평문** — UDP 단편화/재조립이 복호화 전에 일어나야 하므로(§6 예정).
- 현재(슬라이스 단계) 영상도 TCP로 전송. UDP 분리는 다음 단계.

---

## 5. 라이브러리 매핑 (구현 API)

| 단계 | Mac (CryptoKit) | Android (BouncyCastle lightweight) |
|------|-----------------|------------------------------------|
| X25519 키생성 | `Curve25519.KeyAgreement.PrivateKey()` | `X25519KeyPairGenerator` |
| X25519 합의 | `sharedSecretFromKeyAgreement(with:)` | `X25519Agreement.calculateAgreement` |
| X25519 pub raw | `publicKey.rawRepresentation` (32B) | `X25519PublicKeyParameters.getEncoded()` |
| ML-KEM 키생성 | (responder는 불필요) | `MLKEMKeyPairGenerator` (`MLKEMParameters.ml_kem_768`) |
| ML-KEM encap | `MLKEM768.PublicKey(rawRepresentation:).encapsulate()` → `(sharedSecret, encapsulated)` | — |
| ML-KEM decap | — | `MLKEMExtractor(priv).extractSecret(ct)` |
| ML-KEM pub raw | `rawRepresentation` (1184B) | `MLKEMPublicKeyParameters.getEncoded()` |
| HKDF | `HKDF<SHA256>.deriveKey(...)` | `HKDFBytesGenerator(SHA256Digest())` |
| AES-GCM | `AES.GCM.seal/open` | `Cipher("AES/GCM/NoPadding")` + `GCMParameterSpec(128, nonce)` |

소스: `MacApp/.../Crypto/PeriCrypto.swift`, `AndroidApp/.../crypto/PeriCrypto.kt`.

### 상호운용 확인됨
CryptoKit `rawRepresentation` ↔ BouncyCastle `getEncoded()` 가 둘 다 FIPS 203 / RFC 7748 raw
인코딩이라 바이트가 일치. 런타임 영상 복호화로 검증됨.

---

## 6. 현재 한계 / TODO (보안)

> v0.1은 **수동 연결 + 기밀성** 중심. 아래는 명시적 미구현 항목.

- ~~**핸드셰이크 미인증 (MITM 취약)**~~ → 해결. §2b의 PIN 기반 PAKE로 신뢰를 부트스트랩하고,
  LAN/USB 핸드셰이크는 그렇게 핀된 장기 신원으로 전사에 서명한다.
- **BLE 재연결 경로 없음** ⚠ v2에서 새로 생긴 구멍이다. v1의 재인증(핀된 신원으로 서명해
  사람을 건너뛰는 경로)이 모드 축과 함께 사라져서, **망이 바뀔 때마다 PIN을 다시 요구한다.**
  flight 1·2는 그대로 두고 PAKE 자리에 전사 서명을 넣는 경로를 얹어야 한다.
- **재전송(replay) 방어 없음**: 수신측이 nonce 카운터 단조성을 검증하지 않음. → 수신 카운터
  윈도우 검증 추가 예정.
- **AAD 미바인딩**: 영상 헤더(seq/keyframe/frag)가 평문이며 GCM AAD로 묶이지 않아 변조 시
  복호화 단계에서만 탐지됨(태그 불일치로 프레임 드롭). → 헤더를 AAD로 바인딩 검토.
- **키 회전 없음**: 세션 내 단일 키. 장시간 세션이면 rekey 도입 검토.
- 전방향 비밀성: 세션마다 ephemeral 키라 **세션 간 FS**는 확보. 세션 내 단일 키.

---

## 7. 버전/식별

- 프로토콜 버전: `protocol_version = 1`
- KDF info 라벨: `PeriDisplay-v1/{m2a,a2m}` — 버전 변경 시 라벨도 갱신해 키 분리.
