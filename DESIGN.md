# Peri Display — 설계 문서 (v0.2)

Mac과 Android를 잇는 연속성(continuity) 시스템.

- **Mac 앱**: `com.peri.display.mac` (Swift / SwiftUI, 타겟 **macOS 26 Tahoe**)
- **Android 앱**: `com.peri.display` (Kotlin / Compose, **minSdk 29** / targetSdk 36)
- **공통 프로토콜**: Protocol Buffers (proto3) — `proto/` 아래 서비스별로 갈려 있다:
  `common` (공통 어휘) · `session` (신원·생존·앱 수명) · `extend` (앱 #1) · `share` (앱 #2) ·
  `continuity` (봉투 — service_id가 생기면 사라진다)

---

## v0.1 → v0.2에서 바뀌는 것

v0.1은 **"세션 = 화면 미러링"** 이었다. 암호 세션이 곧 미러였고, `MirrorSession`이라는
이름이 그걸 그대로 드러낸다. 그래서 두 번째 기능을 얹을 자리가 없다.

v0.2는 세션을 **여러 서비스가 공유하는 암호화된 다중화 채널**로 재정의한다.
화면 확장은 그 위의 서비스 하나가 된다.

| # | 서비스 | 방향 | 비고 |
|---|--------|------|------|
| 1 | **extend** — 화면 확장 | Mac → Android 영상 | v0.1에 있던 것 |
| 2 | **share** — 화면 공유 + 원격 조작 | Android → Mac 영상 | 조작은 **USB 전용** |
| 3 | **files** — 파일 전송 | 양방향 벌크 | 1·2와 동시 실행 |

**1과 2는 상호 배타적**이고(영상 스트림은 언제나 최대 하나), **3은 어느 상황에서도
동시에** 돈다.

> 배타성 덕분에 "스트림 id로 방향을 매개변수화"할 필요가 없다. 방향은 스트림의 속성이
> 아니라 **세션의 모드**다. 기존 영상 제어 메시지(`SessionParams`·`KeyframeRequest`·
> `LtrAck`·`TransportFeedback`)는 정의가 안 바뀐다 — `KeyframeRequest`는 "싱크가 소스에게"
> 라는 뜻이지 "Android가 Mac에게"가 아니다. 공유 모드에선 보내는 쪽만 바뀐다.

---

## 설계 원칙 — 검사 가능한 형태로

"계층이 분리됐다"를 말이 아니라 **검사할 수 있는 문장**으로 못 박는다.

1. **세션 계층 소스에 `display`·`video`·`capture`·`file`이 한 번도 나오지 않는다.**
   (grep으로 검사한다. 지금 `MirrorSession`은 이름부터 이 규칙을 어긴다.)
2. **새 서비스 추가 = 파일 추가 + 등록 한 줄.** 세션·멀티플렉스 파일 수정 0줄.
3. **각 서비스 상태 기계는 독립적으로 모델 검사된다.** 지금 `DeviceMachine`처럼 순수
   함수 `reduce(state, event) → (state, effects)`로.
4. **서비스는 자기가 어떤 파이프를 타는지 모른다.** 전달 *보장*만 말하고, 파이프 선택은
   Bearer의 몫이다.

4번의 반대 방향 검사: **LAN Bearer를 QUIC으로 통째로 갈아치워도 서비스 코드가 0줄
바뀌어야 한다.**

---

## 레이어 스택

```
┌──────────────────────────────────────────────────────────────┐
│ 5. Service    │ extend · share · files · (미래)               │
│               │ 각자 상태 기계 / 각자 메시지 집합              │
│               │ "이걸 보내줘 + 이 정도 보장이면 돼"            │
├──────────────────────────────────────────────────────────────┤
│ 4. Session    │ 신원 · 키 · 생존성 · 경로복구 · 수명           │
│               │ **서비스가 뭔지 모른다**                       │
├──────────────────────────────────────────────────────────────┤
│ 3. Mux        │ serviceId · lane · epoch · 조각화 · 우선순위   │
│               │ **파이프가 뭔지 모른다**                       │
├──────────────────────────────────────────────────────────────┤
│ 2. Crypto     │ X25519 + ML-KEM-768 → HKDF → AES-256-GCM      │
├──────────────────────────────────────────────────────────────┤
│ 1. Bearer     │ USB(AOA) / LAN(TCP+UDP) / NAT(ICE+UDP)        │
│               │ 전달 계약 → 실제 파이프 매핑                   │
└──────────────────────────────────────────────────────────────┘

        out-of-band: BLE = discovery · 페어링 · ICE 후보 시그널링
```

가운데 두 층이 서로를 모르는 게 핵심이다. **Mux는 위로 서비스를, 아래로 파이프를 가린다.**

> v0.1 문서에서 Crypto가 Framing 위였는데 순서를 바꿨다. 다중화 메타데이터(serviceId,
> 조각 인덱스)가 AEAD의 **인증 대상**이어야 하므로, Mux가 Crypto를 호출하는 관계가 맞다.

---

## 1. Bearer Layer — 파이프가 아니라 전달 계약

서비스가 "TCP로 보내줘"라고 말하는 순간 추상화는 실패한다. **"이 정도 보장이면 된다"**
고만 말해야 한다. 계약은 둘뿐이다.

```swift
enum Delivery {
    case reliable(Class)   // 반드시, 순서대로.  Class = .control | .bulk
    case datagram          // 최신성 우선. 손실·재정렬 허용.
}
```

Bearer가 이걸 자기 방식으로 매핑한다:

| | `reliable` | `datagram` |
|---|---|---|
| **USB (AOA)** | bulk 파이프 | bulk 파이프 (**강등** — 아래 참고) |
| **LAN** | TCP | UDP |
| **NAT** | TCP (또는 relay) | UDP (ICE 홀펀칭) |

Bearer는 능력을 **선언**한다. 서비스는 이 값만 보고 행동을 조절한다:

```swift
struct BearerCaps {
    let hasDatagramPath: Bool   // AOA = false
    let maxMessageSize: Int     // LAN ≈ 1200B, AOA = 사실상 무한
    let isMetered: Bool         // LTE — 서비스의 품질/전송 정책 입력
}
```

### 강등이 지연 폭증이 되면 안 된다

AOA에서 `datagram`을 그냥 신뢰 파이프로 흘리면 **손실 대신 지연이 쌓인다**. 혼잡할 때
낡은 프레임이 큐에 줄줄이 서서 나가는데, 그건 UDP보다 나쁘다.

그래서 강등의 정의는 이렇다: **`datagram`은 신뢰 파이프 위의 "폐기 가능 큐"가 된다.**

- 큐가 임계를 넘으면 **오래된 `datagram` 메시지부터 버린다.**
- `reliable` 프레임은 절대 버리지 않는다.

전송 수단은 바뀌어도 **"최신성 우선"이라는 약속의 의미는 지켜진다.**

### 1a. USB Bearer — AOA (Android Open Accessory)

Mac이 USB **호스트**, Android가 **액세서리**. 사용자 액션은 케이블 연결뿐.

- Google Vendor ID `0x18D1`, Accessory PID `0x2D00`(bulk 2) / `0x2D01`(ADB 포함, bulk 4)

초기화 (Mac → Android, EP0 제어 전송):

```
1. Req 51 GET_PROTOCOL   → 버전 확인 (HID는 v2 이상 필요 — §5b)
2. Req 52 SEND_STRING    → 식별 문자열 6개
                           idx0 manufacturer, 1 model, 2 description,
                           3 version, 4 uri, 5 serial
3. Req 53 START          → 액세서리 모드 진입 → Android 재열거 (PID 0x2D00)
4. SET_CONFIGURATION     → bulk endpoint 활성화
5. bulk IN/OUT 통신 시작
```

- ⚠ version 문자열(idx3)을 비우고 version-only 매칭을 하면 **Android 10 이하에서 리붓**
  하는 버그가 있다. 반드시 채워 보낸다.
- **파이프는 하나다.** v0.1 문서엔 "Control/Video를 bulk 2개로 분리"라고 썼지만 실제
  구현은 단일 bulk 쌍에 모든 프레임을 흘린다. 다중화가 필요한 이유가 바로 이것.
- Mac: `IOUSBHost`(IOKit, ObjC). Android: `UsbManager.openAccessory()` → `ParcelFileDescriptor`.

### 1b. LAN / NAT Bearer

둘은 별개 Bearer가 아니라 **같은 Bearer의 두 후보 경로**다. v0.1의 "수동 선택"은 없어졌다.

- Mac = 서버(`NWListener`, **동적 포트**), Android = 클라이언트.
- 접속점은 **오직 BLE로만** 전달된다. 후보(host + STUN srflx)를 모으는 순간이 곧
  "곧 누가 붙는다"는 신호이므로, LAN 수신도 그때 자동으로 켜진다.
- ICE 유사 후보 교환 + 우선순위 지명. **클라이언트는 LTE를 가장 낮은 우선순위로 둔다.**
- `reliable` = TCP, `datagram` = UDP (별도 소켓).
- Symmetric NAT에서 홀펀칭 실패 시 TURN relay는 아직 없다.

### 파이프가 둘이라 생기는 것 — 실패 도메인이 갈린다

TCP는 멀쩡한데 UDP만 막히는 경우가 실제로 있다(NAT 리바인딩, 방화벽). keepalive가
TCP를 타므로 **세션은 건강해 보이는데 화면만 멈춘다.** 경계를 이렇게 긋는다:

- **세션은 `reliable` 경로가 죽을 때만 죽는다.** 신원·제어가 거기 있으므로.
- **`datagram` 경로가 죽는 건 서비스 사건이다.** 서비스가 "피드백이 안 온다"를 스스로
  알아채고 재협상·재펀칭을 요청한다.
- 경로를 **세우는** 건 Bearer(ICE) 책임, 그게 **쓸모없어진 걸 알아채는** 건 서비스 책임.

### `datagram`은 세션이 아니라 **앱의 수명**을 따른다

세션 수립은 `reliable`(TCP)만으로 끝난다. UDP를 같이 열지 않는 이유는 순전히 NAT다:
UDP 매핑은 수명이 짧아(흔히 30초 안팎) 붙들려면 계속 두드려야 하고, 그건 아무도 안 보는
동안에도 라디오를 깨우는 일이다. TCP 매핑은 분~시간 단위로 산다.

그래서 데이터그램 경로는 **`needsDatagram`을 선언한 앱이 떠 있는 동안만** 산다. 알림만 쓰는
세션은 UDP 소켓도 NAT 매핑도 갖지 않는다. 여닫는 순서와 키 파생은 CRYPTO.md §2e.

경계는 여기서도 같은 모양이다: 어느 앱이 왜 필요한지는 **앱 등록부**가 알고(`needsDatagram`),
그걸 어떻게 실현할지(세대 id 부여·후보 수집·펀칭·정리)는 **세션**이 안다. 앱은 UDP도 NAT도
모르고, 세션은 그 앱이 무엇을 하는지 모른다.

경로를 잃어도 BLE가 대역 밖에서 재수립을 돕는다는 것이 이 선택의 안전망이다. **UDP 매핑
수명을 실측해 keepalive 주기를 스스로 맞추는 알고리즘**은 이 구조 위에 얹힐 다음 단계다.

---

## 2. Crypto Layer (E2E)

모든 Bearer 위에서 동일. **양자내성 하이브리드.**

### 키 교환 — X25519 + ML-KEM-768 (FIPS 203)

```
Initiator(Android)                          Responder(Mac)
│ x25519_i, mlkem_i 키쌍 생성                 │
│── ClientHello ─────────────────────────────►│
│   {session_id, nonce_i, x25519_pub_i,       │ x25519_r 생성
│    mlkem768_pub_i, identity_cert}           │ (ct, ss_kem) = MLKEM.Encap(mlkem768_pub_i)
│◄── ServerHello ─────────────────────────────│
│   {session_id, nonce_r, x25519_pub_r,       │
│    mlkem768_ct, identity_cert, identity_sig}│
│── ClientAuth (sealed) ─────────────────────►│  전사 서명으로 클라 신원 증명
│
│ session_key = HKDF-SHA256(
│     ikm  = ss_x || ss_kem,
│     salt = nonce_i XOR nonce_r,
│     info = "PeriDisplay-v1", len = 32)
```

- 하이브리드: 한쪽이 뚫려도 나머지가 보호(Signal/Apple 방식).
- 신원은 장기 Ed25519 키(`PeriCert`)에 매이고, **전사 해시에 서명**해 세션에 바인딩한다.
- 신뢰 수립은 BLE 페어링에서 **양방향 SAS** 또는 **OTP**로 한다(§6). TOFU는 없다.
- Mac `CryptoKit` ↔ Android `BouncyCastle` 상호운용 라운드트립 테스트 필수.

### 대칭 암호화 — AES-256-GCM

**nonce가 다중화의 토대다.** 레인마다 카운터 공간이 갈려야 nonce 재사용이 없다:

```
nonce = [1B lane][3B zero][8B counter]
```

`lane`은 **(서비스, 전달종류) 쌍마다 하나씩** 배정된다. 이 바이트는 GCM이 인증하므로
공격자가 프레임을 다른 레인으로 옮기면 태그가 깨진다.

> 이 구조는 v0.1에도 이미 있었다(제어=0, 영상=1). 서비스가 늘어나면서 의미만 넓어진다.

### AAD — 라우팅 메타데이터를 암호문에 묶는다

nonce는 인증되지만 **헤더의 `serviceId`는 아니다.** 공격자가 헤더만 바꾸면 복호화는
성공하고 **엉뚱한 서비스로 배달된다.** 서비스가 하나일 땐 없던 문제다.

```
AAD = [1B type][1B serviceId][미디어 헤더(있으면)]
```

덤으로 지금 평문·미인증인 12B 영상 헤더(키프레임 비트·seq·조각 인덱스)도 같이 인증된다.

---

## 3. Mux Layer — 위로 서비스를, 아래로 파이프를 가린다

### 와이어 포맷

```
reliable 프레임 (TCP / AOA bulk):
  [4B len][1B type][1B serviceId][payload]

    type 0  HANDSHAKE   세션 계층, 평문 (serviceId 없음)
    type 1  SERVICE     [12B nonce][sealed(...)]
    type 2  MEDIA       [조각 헤더][12B nonce][sealed(...)]
    type 3  RESUME      세션 계층 — 새 연결의 첫 프레임

datagram (UDP):  길이 프리픽스 없음. [1B type][1B serviceId][조각 헤더][sealed(...)]
```

### 조각화는 서비스가 아니라 Mux가 한다

이게 확장성에 제일 크게 기여한다. v0.1은 영상만 Mini-RTP로 조각냈는데, 그걸 일반화해
Mux로 내리면 **미래 서비스(오디오·센서·무엇이든)가 조각화·재조립을 공짜로 얻는다.**

```
조각 헤더: [2B epoch][4B msgId][2B fragIdx][2B fragCount][1B serviceFlags]
```

- Mux가 `maxMessageSize`에 맞춰 쪼개고, 받는 쪽에서 재조립한다.
- 조각이 하나라도 빠지면 **메시지를 통째로 버리고** 서비스에 `lost(msgId)`를 알린다.
  재전송은 하지 않는다 — `datagram` 계약이 그렇다.
- `serviceFlags`는 **Mux가 해석하지 않는 1바이트**다. 모든 조각에 복사만 한다. 영상은
  여기에 키프레임·LTR 비트를 넣는다 — 재조립이 실패해도 수신측이 "이건 키프레임이었다"
  를 알아야 대응(키프레임 요청 vs 그냥 무시)을 고를 수 있으므로.
- **AOA는 `maxMessageSize`가 무한이라 조각화가 일어나지 않는다.** 현재 AOA 경로가
  통짜로 보내는 동작과 정확히 같다 — 추상화가 기존 동작을 그대로 표현한다.

### epoch — 두 파이프 사이엔 순서 보장이 없다

모드 전환(확장→공유)이나 재협상을 `reliable`로 알려도, **옛 모드의 UDP 영상이 그 뒤에
도착한다.** 서비스가 하나일 땐 안 터졌지만 앞으로는 터진다.

`reliable` 채널이 모드 전환·재협상마다 `epoch`를 올리고, 수신측은 **낡은 epoch의
datagram을 조용히 버린다.** 헤더 2바이트로 버그 한 종류가 통째로 사라진다.

### 흐름제어는 둘이다 — 하나로 합치지 않는다

성격이 달라서 하나인 척하면 나중에 꼬인다.

| | 목표 | 수단 |
|---|---|---|
| `reliable` 경로 | **HOL 지연 제한** (TCP가 백프레셔를 주므로) | 우선순위 큐(`control` > `bulk`) + 청크 상한 32KB |
| `datagram` 경로 | **혼잡 회피** (백프레셔가 없으므로) | Transport-CC 송신측 추정 |

**AOA에선 둘이 한 파이프로 모인다.** 이때는 `reliable` 스케줄러가 전부를 지배한다 —
USB는 손실이 없어 혼잡 제어가 할 일이 없고, 대신 **파일이 영상을 굶기지 않게 하는 게
전부**다. 송신 큐는 이미 양쪽 모두 단일 직렬 큐라(`AoaClient.txScope`), 우선순위 큐로
바꾸는 것으로 족하다.

---

## 4. Session Layer — 서비스를 모른다

세션이 소유하는 것: **신원, 키, 생존성, 경로 복구, 수명.** 그게 전부다.

### 축

축은 서로 직교한다(Mac `DeviceMachine`, Android `MirrorMachine` 공통):

```
Sess     : 암호 세션    — 연결보다 오래 산다 (RESUME으로 채널만 갈아끼움)
Channel  : 제어 채널    — 지금 어느 연결로 말하고 있는가
```

> v0.1의 `Pipe` 축(캡처·인코더)은 세션 축이 아니다. **extend 서비스로 내려간다**(§5a).

### 무손실 재개 (RESUME)

IP가 바뀌면 TCP는 4-tuple이 깨져 못 살린다. 하지만 살릴 필요가 있는 건 연결이 아니라
**세션**이다 — AEAD 키도, 가상 디스플레이도, 인코더도 전송에 독립적이다.

새로 뚫은 연결의 첫 프레임으로 `RESUME`을 보내면 Mac이 살아 있는 세션들의 수신키로
시도복호화해 검증하고, 통과하면 **제어 채널만 갈아끼운다.** 재핸드셰이크도, 키프레임도,
검은 화면도 없다.

### Keepalive / 종료

- `reliable` 채널로 1초 주기 Ping/Pong. 3회 무응답 → 복구 시도.
- `Bye`는 **"잠깐 끊김"과 "끝냄"을 구별**한다. 끊김은 늘 일어나고(전송 전환·USB 글리치·
  재접속) 그때 자원을 부수면 안 된다. `Bye`는 유예를 건너뛰고 즉시 정리해도 좋다는 확답이다.
- **전송 전환(USB↔Wi-Fi)에서는 절대 보내지 않는다** — 그건 끝냄이 아니라 경로 변경이다.

---

## 5. Service Layer

### 서비스는 세션이 아니라 **기기**에 매인다

이게 이 설계에서 제일 중요한 결정이다.

```
Device (지문)  ─── 오래 산다
 ├─ Session ─── 갈린다 (RESUME / 콜드 재수립 / LAN↔AOA 전환)
 │   └─ Mux ─── 레인 0..N
 └─ AppHost ─── 기기에 매인다. 세션이 갈려도 죽지 않는다.
     ├─ 0  session-control   (Ping/Bye/AppControl — 세션 자신)
     ├─ 1  extend            (가상 디스플레이 소유)
     ├─ 2  share             (MediaProjection 동의 소유)
     ├─ 3  files             (전송 오프셋 소유 — 아직 자리만)
     └─ 4  notify            (알림 목록 소유)
```

> **알림 앱이 원칙 2의 세 번째 증명이다.** 파일 하나(`NotifyService.swift`)와 등록 한 줄,
> 그리고 `notify.proto`. 세션·기기 기계·먹스는 **0줄** 바뀌었다. 상태 기계도 없다 —
> 세울 파이프라인도 받을 동의도 없어서 `active`·`linked` 두 불리언이 상태의 전부다.
> 기계는 필요할 때 만드는 것이지 모든 앱이 갖춰야 할 장식이 아니다.

세션↔기기는 어느 순간에는 1:1이지만 시간축으로는 아니다. 기기는 세션보다 오래 산다:
무손실 재개가 실패하면 콜드 경로로 새 세션이 서고, LAN↔AOA 전환은 세션을 **일부러**
죽이고 새로 만든다(케이블 한 번 꽂을 때마다 갈린다).

여기에 자원을 세션 수명으로 묶으면 케이블 탈착마다 파괴·재생성이 일어나는데, 가상
디스플레이의 경우 그건 ScreenCaptureKit 버그(FB17797423)를 직접 건드린다 — 구성이
바뀌면 이후 스트림이 필터와 무관하게 *다른 기기의 프레임버퍼*를 물고 온다(실측).
유일한 복구인 재열거 사이클은 붙어 있는 **모든** 기기를 1초씩 끊는다.

### 서비스 계약 — 세션이 서비스에게 하는 말은 세 마디뿐

```swift
@MainActor
protocol PeriApp: AnyObject, Sendable {
    nonisolated static var descriptor: AppDescriptor { get }   // 홈 화면이 읽는 정적 서술

    func attach(_ ch: ServiceChannel) async    // 말할 채널이 생겼다 (caps 포함)
    func detach(_ reason: DetachReason) async  // 채널이 끊겼다 (세션은 살아 있을 수도)
    nonisolated func receive(_ msg: AppMessage)

    func launch()                              // 사용자가 아이콘을 눌렀다
    func quit(_ reason: String) async
}
```

`AppMessage`는 레인이 둘이다 — `.control(ControlMessage)`와 `.media(Data, MediaMeta)`.
앱은 어느 파이프로 왔는지 모른다: 재조립도 복호도 `ServiceChannel`이 끝낸 뒤에 도착한다.
그 둘이 채널에 있는 이유는 **봉인이 프레임 전체를 덮기 때문**이다. 조각이 다 모이기 전에는
풀 수 없으니, 재조립을 앱에 맡기면 앱이 세션 키를 만져야 한다.

**`detach`에서 무엇을 지키고 무엇을 버릴지는 서비스가 스스로 정한다.** 세션은 관여하지
않는다:

| 서비스 | detach 때 **지키는** 것 | **버리는** 것 |
|---|---|---|
| extend | 가상 디스플레이, spec | 캡처·인코더 파이프라인 |
| share | (없음 — 동의가 만료된다) | 디코더, 창 |
| files | 오프셋, 파일 핸들 | 전송 중인 청크 |

> **왜 계약으로 만드나.** Wi-Fi→LTE 전환 시 화면이 마지막 프레임에 얼어붙는 버그가
> 정확히 이 계약의 부재였다. `pipe`가 기기 축으로 선언돼 있는데 파이프라인은 세션이
> 소유해서, 새 세션이 붙어도 파이프라인이 재건되지 않았다. 그 어긋남을 주석으로만
> 막아뒀다. 계약으로 만들면 **새 서비스가 같은 실수를 반복할 수 없다** — `attach`를
> 구현하는 순간 "재수립될 때 뭘 다시 세울지"를 답하게 되므로.

### 서비스는 앱이다 — 세션이 붙었다고 뜨지 않는다

**(구현됨. 이 절은 한 번 틀렸다가 고친 자리라 그 경위를 남긴다.)**

처음 설계는 배타성을 기기 기계 안의 enum 하나로 풀려 했다:

```swift
enum Screen { case off, extend(Pipe), share(Recv) }   // ← 폐기
```

둘이 동시에 켜진 상태를 표현할 수 없다는 게 매력이었다. 그런데 이걸 쓰려면 기기 기계가
`Pipe`(캡처·인코더)와 `Recv`(디코더·창)를 **알아야 한다.** 그건 원칙 1·3을 정면으로
어긴다 — 세션 계층이 서비스 어휘를 갖고, 서비스 기계를 독립적으로 모델 검사할 수 없게 된다.
상태공간도 곱셈으로 커진다.

지금 구조는 이렇다:

```
DeviceMachine      신원·경로만 안다. 앱을 모른다.
  └ 부작용 둘뿐:  appsLinked / appsUnlinked
ExtendMachine      active × linked × Pipe × Display     (독립 BFS: 32 상태)
ShareMachine       active × linked × Consent × Recv     (독립 BFS: 8 상태)
```

세 기계가 **서로를 모른다.** `Pipe.suspended`(SCK 재열거)가 공유 쪽에 나올 수 없는 건
이제 주석이 아니라 타입이다 — 그 값이 사는 enum이 애초에 다른 파일에 있다.

배타성은 **런처가** 막는다. 앱이 자기 서술에 `conflicts: Set<AppID>`를 선언하고
(`extend ↔ share`, 대칭이어야 한다) 런처가 그것만 보고 아이콘을 비활성화한다.
서비스끼리는 여전히 서로를 모르고, 런처는 어떤 앱이 있는지 알 필요가 없다.

> 왜 대칭이 요건인가: 한쪽에만 적으면 켜는 순서에 따라 뚫린다. 확장이 공유를 막지만
> 공유가 확장을 안 막으면, 확장을 먼저 켠 뒤 공유를 켤 수 있고 그게 곧 무한 거울이다.

**직교축이 하나 더 있다.** "앱이 켜져 있는가"와 "말할 채널이 있는가"는 다른 축이다.
앱은 채널 없이 켜져 있을 수 있고(경로 복귀 대기), 채널이 있어도 꺼져 있을 수 있다(홈 화면).
이 둘을 한 축에 뭉치면 "세션이 서면 화면이 켜진다"가 되고, 그러면 끌 방법이 없다.

**런처는 Mac에 있다.** Android에는 누를 아이콘이 없고, `AppControl`을 받아 자기 쪽 절반만
세우고 내린다. 경합이 구조적으로 생기지 않는 이유는 결정하는 곳이 하나이기 때문이다.

파일 전송은 진짜 직교축이라 옆에 둔다. 전송의 진행(오프셋·재개·취소)은 **전송별 작은
기계**로 따로 둔다 — 앱 기계에 넣으면 상태공간이 전송 개수만큼 터진다.

### 5a. extend — 화면 확장 (Mac → Android)

v0.1의 미러링. Mac이 `CGVirtualDisplay`를 세우고 ScreenCaptureKit으로 캡처, VideoToolbox로
인코딩해 `datagram`으로 보낸다. Android는 MediaCodec → Surface.

- 상태: `Pipe(down/building/up/suspended)` × 디스플레이 보유
- 코덱 협상 **H.265 → H.264 fallback**. H.265 HW 디코더는 기기 의존이라 H.264는 항상 보장.
- 수신측 재생 정책(`PlayoutConfig`)은 Mac UI 프로파일 토글로 라이브 조절:
  저지연(도착 즉시 렌더) / 고품질(고정 지터버퍼).

### 5b. share — 화면 공유 + 원격 조작 (Android → Mac)

**영상**: Android가 `MediaProjection` → MediaCodec으로 인코딩, Mac이 VideoToolbox로 디코드해
앱 창에 그린다. 영상 배관은 extend와 **그대로 공유된다** — 키가 이미 방향별(`sendKey`/
`recvKey`)이라 Android→Mac 영상은 Android의 송신키를 쓰면 되고 nonce 공간도 겹치지 않는다.

⚠ **MediaProjection 동의는 세션보다 수명이 짧다.** Android 14+는 `mediaProjection` 타입
포그라운드 서비스가 선행돼야 하고 동의 토큰이 1회용이다. 무손실 재개로 **세션은 살아남아도
이 서비스는 못 살아난다** — 재동의가 필요하다. 서비스마다 재개 정책이 다르다는 걸 보여주는
실제 사례이고, `detach` 계약이 이걸 자연스럽게 표현한다.

**원격 조작**: AOAv2 HID. Mac이 디지타이저를 에뮬레이션해 Android 커널에 터치를 주입한다.
EP0 제어 전송이므로 **우리 암호 세션도, bulk 파이프도 타지 않는다**:

```
Req 54 REGISTER_HID          wValue=HID id, wIndex=리포트 디스크립터 총 길이
Req 56 SET_HID_REPORT_DESC   wValue=HID id, wIndex=오프셋, data=디스크립터
Req 57 SEND_HID_EVENT        wValue=HID id, data=HID 리포트
Req 55 UNREGISTER_HID        wValue=HID id
```

- **AOA 프로토콜 v2 이상**이 필요하다. 링크를 열 때 `GET_PROTOCOL`로 확인해 링크에 들고 있는다.
  (전환 때 받은 값을 넘기지 않는다 — 이미 액세서리 모드로 부착된 기기는 전환 경로를 거치지 않아
  넘겨받을 값이 애초에 없다.)
- **무선에는 동등물이 없다.** 서명 권한 없이 되는 건 `AccessibilityService.dispatchGesture()`
  뿐이고 정밀도가 떨어진다. 그래서 **네트워크로 붙으면 화면 공유만** 한다.

#### 협상이 없다 → 서비스 상태 기계가 직접 구동한다

와이어에 오갈 게 없으므로 조작에는 **별도 협상 단계가 없다.** 등록/해제를 `ShareMachine`의
**효과(effect)** 로 두면 그걸로 끝이다. 새 메시지도, 새 레인도, 새 합의 절차도 필요 없다.

다만 경계가 하나 있다:

```
ShareMachine ──effect──► registerHid / unregisterHid      ← 기계가 관여한다
포인터 이벤트 ─────────────────────────────► EP0 Req 57   ← 기계 밖이다
```

**기계는 문을 여닫고, 이벤트는 통과만 한다.** 120Hz로 들어오는 터치를 리듀서에 통과시키면
상태공간이 오염돼 모델 검사가 무의미해진다. `extend`가 이미 같은 관계다 — `Pipe`는 `.up`/`.down`
을 말하지만 개별 영상 프레임은 기계를 지나가지 않는다.

#### 검사할 불변식

`Input`이 기계 안의 축이고 등록이 효과이므로, 짝 맞춤을 BFS로 검사할 수 있다:

```
① Input == .hid  ⟹  Sess == .live ∧ Channel == .live(via: .aoa) ∧ Screen == .share
② Input이 .hid에서 벗어나는 **모든** 전이가 unregisterHid를 낸다
```

②가 "화면 공유를 껐는데 터치는 계속 먹는" 상태를 구조적으로 막는다. 이탈 경로를 하나라도
빠뜨리면 검사가 잡는다(`trustRevoked`를 허용목록에서 빠뜨렸을 때 실제로 잡혔다).

①의 따름정리로 **경로가 바뀌면 조작이 자동으로 꺼지고 켜진다** — 플래그가 아니라 축이라서.

#### 입력은 왜 별도 서비스가 아닌가

입력은 share가 만드는 좌표 공간이 없으면 의미가 없다. 별도 서비스로 떼면 그 서비스가 share의
영상 기하를 알아야 하는데, **그건 서비스 간 의존이고 이 설계가 없애려는 바로 그것이다.**
"서비스는 서로를 모른다"가 확장성의 근거인데 첫 사례부터 예외를 만들면 원칙이 죽는다.
수명도 같다 — 화면 공유가 끝나면 조작도 끝나야 한다. 축이 하나면 그게 공짜로 보장된다.

#### 기계가 못 덮는 구멍

⚠ **Android는 HID를 거부할 수 없다.** 커널이 받고 InputReader가 실제 터치와 구분하지 못한다.
페어링·TrustStore는 EP0를 막지 못하므로 **접근 통제는 "언제 `REGISTER_HID`를 부르는가" 뿐이다.**
프로토콜 강제가 아니라 코드 규율이고, 위 불변식이 그 규율을 기계화한 것이다.

⚠ **Mac 앱이 강제 종료되면 `UNREGISTER_HID`를 보낼 기회가 없다.** 케이블은 그대로라 USB
detach도 일어나지 않는다. macOS가 USB 핸들을 회수하며 `f_accessory`가 HID를 정리하는 것이
기대 동작이지만 **실기기 확인 항목**이다. 유령 디지타이저가 남는다면, 링크 재수립 시 같은
`hidID`로 등록하기 전에 `UNREGISTER_HID`를 먼저 쏘는 것으로 방어한다.

### 5c. files — 파일 전송

`reliable(.bulk)`로 나간다. 제어(오퍼/수락/완료/취소)는 protobuf, 데이터는 청크.

- 전송 하나당 상태 기계 하나. 오프셋을 서비스가 소유하므로 `detach` 후 **이어받기**가 된다.
- 청크 상한 32KB — `control` 클래스의 HOL 지연을 유계로 만든다.
- 여러 전송은 `bulk` 클래스를 나눠 쓰며 스케줄러가 교대시킨다.

---

## 6. 페어링 · 신뢰 (out-of-band, BLE)

- BLE는 영상을 못 나르므로 Bearer가 아니라 **discovery · 페어링 · ICE 후보 시그널링** 전용.
- 신뢰 수립은 **양방향 SAS**(전사에서 유도한 숫자 비교) 또는 **OTP**(Android가 crypto-random
  코드를 띄우고 Mac에 입력). OTP는 **1회 시도**이고, 틀리면 라운드 전체를 중단하고 처음부터
  다시 페어링한다.
- **TOFU 없음.** 미페어링 기기는 거부하고 `Bye(AUTH_REJECTED)`를 보낸다 — 그게 없으면
  클라이언트가 평범한 끊김과 구분하지 못해 거부하는 Mac에게 영원히 재접속한다.
- **USB 신뢰 부트스트랩**: 케이블을 꽂았다는 건 기기를 소유했다는 뜻이므로, AOA로 들어온
  미페어링 기기는 Mac에서 확인 프롬프트를 띄워 신뢰를 세울 수 있다. **LAN에서는 불가**.

---

## 상태 기계 지도

```
SessionMachine    Sess × Channel                    ← 서비스를 모른다
DeviceMachine     신원 · Screen 중재 · transfer 축
  ├─ ExtendMachine    Pipe × Display
  ├─ ShareMachine     Consent × Recv × Input
  └─ TransferMachine  전송 하나당 하나
PairingMachine    Phase × Mode                      (BLE, 별도 수명)
```

모두 **순수 함수**로 유지한다 — CoreGraphics·ScreenCaptureKit·AppKit에 의존하지 않아야
`swiftc` 한 줄로 모델 검사(도달 가능 상태 BFS + 불변식 + "모든 대기 상태에 마감이 있다"는
활성 검사)를 돌릴 수 있다.

---

## 구현 현황

| 계층 | 상태 |
|---|---|
| Bearer — AOA / LAN / ICE 후보 교환 | ✅ 있음. 단 `Delivery` 추상이 아니라 `sendControl`/`sendVideo`로 갈라져 있고 LAN 타입이 인터페이스에 샌다 |
| Crypto — 하이브리드 KEX, 방향별 AEAD, lane nonce | ✅ 있음. **AAD 없음** — 추가 필요 |
| Mux — serviceId · epoch · 일반 조각화 · 우선순위 큐 | ❌ 없음. 영상 전용 Mini-RTP 조각화만 있음 |
| Session — 상태 기계, RESUME, Bye, 페어링 | ✅ 있음. **서비스를 모른다** — 자기 것이 아닌 제어 메시지는 등록부로 넘긴다. 단 인코더·비트레이트·fps 도우미 클래스가 아직 `MirrorSession.swift` 파일에 얹혀 있음(파일 이사만 남음) |
| Service — 계약 · 등록부 | ✅ 있음. `Service`(attach/detach/receive) + `ServiceRegistry`. 서비스는 **기기**가 소유하고 갈리는 건 채널뿐 |
| extend | ✅ 동작 중 |
| share — 영상 | ❌ 없음 |
| share — HID 조작 | ✅ **실기기 검증됨**. 단 아직 서비스가 아니라 진단 패널의 수동 버튼 |
| files | ❌ 없음 |

### 이행 순서

1. ~~**AOA HID 디지타이저 스파이크**~~ — ✅ 완료. EP0 등록·리포트·좌표 매핑 전부 실기기에서
   동작 확인. Win8식 2접점 디지타이저, Report ID 없음, 논리 0…32767.
   구현: `Transport/AoaServer.m`(EP0 54~57) + `Input/TouchInjector.swift`.
   **남은 확인**: 앱 강제 종료 시 유령 HID 잔존 여부(§5b).
2. **계층 분리 리팩터** — 서비스 0·1만. 동작은 그대로 유지하고 구조만 바꿔, 기존 기능이
   안 깨지는 걸 모델 검사와 실기기로 확인한다.
   - ✅ 계약(`Service`)과 등록부(`ServiceRegistry`). 협상·통계·재생 정책이 `Services/`로 내려갔고,
     UI는 세션이 되비친 값이 아니라 서비스를 직접 본다.
   - 남음: 도우미 클래스 파일 이사, 실기기 회귀(전송 교체·회전·SCK 사이클).
3. **files** — 새 서비스 추가가 원칙 2("파일 추가 + 등록 한 줄")를 만족하는지 검증하는 첫 시험.
4. **share** — 영상 배관 역방향. 여기서 HID 등록을 진단 버튼에서 `ShareMachine` 효과로 옮기고,
   §5b의 불변식 ①②를 모델 검사에 추가한다.

`protocol_version`이 이미 있고 외부 사용자가 없으므로 **호환 계층 없이 깨끗하게 끊는다**
(`LanEndpoint` 제거 때와 같은 방식).

---

## 결정 요약

| 항목 | 값 |
|------|-----|
| Mac 타겟 | macOS 26 Tahoe |
| Android | minSdk 29, targetSdk 36 |
| 전달 계약 | `reliable(control\|bulk)` / `datagram` — 파이프는 Bearer가 고른다 |
| AOA datagram | 신뢰 파이프로 강등하되 **폐기 가능 큐**(오래된 것부터 버림) |
| 조각화 | **Mux 책임**. `serviceFlags` 1B만 서비스가 채움 |
| 순서 위험 | `epoch` — 낡은 datagram 폐기 |
| 키교환 | X25519 + ML-KEM-768 하이브리드 |
| 대칭암호 | AES-256-GCM. nonce = `[lane][000][counter]`, AAD = `[type][serviceId][미디어 헤더]` |
| 서비스 수명 | **기기에 매인다** (세션이 아니라) |
| 배타성 | `Screen` enum 하나로 기기 기계가 중재 — 동시 상태를 표현 불가 |
| 원격 조작 | AOAv2 HID (EP0). **USB 전용**, 협상 없음 — `ShareMachine` 효과로 등록/해제 |
| 조작 이벤트 | 기계 밖. 기계는 문만 여닫고 리포트는 직접 EP0로 나간다 |
| 신뢰 수립 | 양방향 SAS 또는 OTP(1회 시도). TOFU 없음. USB 부트스트랩 허용 |
| 코덱 | H.265 → H.264 fallback |
| 대역폭 | `datagram` 경로만 Transport-CC. `reliable`은 우선순위 큐 |
