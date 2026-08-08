# spake2-ee (벤더링)

SPAKE2+EE — 비밀번호 인증 키합의(PAKE). BLE 첫 페어링에서 4자리 PIN 하나로
능동 중간자를 막는 데 쓴다.

- 출처: https://github.com/jedisct1/spake2-ee
- 고정 커밋: `fd3ea61f27a75ff63b0f192c9e619b5a494d048e` (2026-01-01)
- 라이선스: BSD 2-Clause (Frank Denis) — `LICENSE`
- 의존: libsodium 1.0.17+ (`scripts/build-sodium.sh`가 1.0.22를 빌드한다)

## 왜 소스를 통째로 들고 오나

**Mac과 Android가 같은 C를 돌려야 한다.** 양쪽에 각자 구현을 두면 상호운용이
구현 차이로 깨지는데, 그게 깨지는 지점이 하필 페어링이라 증상이 "가끔 안 붙는다"로
나온다. 같은 소스를 컴파일해 쓰면 그 실패 모드가 통째로 없어진다.

Swift는 브리징 헤더로 직접 부르고(`crypto_spake.h`의 구조체를 그대로 쓴다),
Kotlin은 `app/src/main/cpp/peri_spake_jni.c`가 감싼다.

## 수정 금지

`crypto_spake.c` / `crypto_spake.h` / `pushpop.h`는 **한 줄도 고치지 않았다.**
고쳐야 할 일이 생기면 여기 말고 우리 쪽 래퍼에서 처리한다 — 업스트림을 다시
당길 때 diff가 0이어야 무엇이 우리 것인지 헷갈리지 않는다.

## 프로토콜에서 이게 어디 있나

역할은 **PIN을 만든 쪽이 server**다. 안드로이드가 PIN을 띄우므로:

| Peri            | SPAKE2+EE |
|-----------------|-----------|
| Android (기기)   | server — `server_store`, `step0`, `step2`, `step4` |
| Mac (사람이 입력) | client — `step1`, `step3` |

`client_id` / `server_id`에는 **장기 Ed25519 신원키 지문**을 넣는다. 그래서
PAKE가 통과했다는 사실 자체가 "이 PIN을 본 사람이 이 신원키의 주인"이라는
증명이 된다 — 중간자가 인증서를 바꿔치면 id가 어긋나 step3/step4에서 죽는다.
