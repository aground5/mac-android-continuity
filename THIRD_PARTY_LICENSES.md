# 서드파티 라이선스

Peri Display(MacApp · AndroidApp)와 보조 도구(HardwareInformation)가 쓰는 외부 코드와
그 라이선스를 정리한다. 의존성을 더하거나 버전을 올리면 이 문서도 같이 고친다.

> 2026-11-15 기준. 버전은 `AndroidApp/gradle/libs.versions.toml`,
> `HardwareInformation/gradle/libs.versions.toml`, MacApp의 `Package.resolved`,
> `scripts/build-sodium.sh`에서 가져왔다.

## 요약

| 구성요소 | 버전 | 라이선스 | 쓰는 곳 | 앱에 포함 |
|---|---|---|---|---|
| [spake2-ee](https://github.com/jedisct1/spake2-ee) | `fd3ea61` | BSD-2-Clause | Mac · Android (BLE 페어링 PAKE) | ✅ 소스 벤더링 |
| [libsodium](https://libsodium.org) | 1.0.22 | ISC | Mac · Android (spake2-ee 의존) | ✅ 정적 링크 |
| [Bouncy Castle](https://www.bouncycastle.org) `bcprov-jdk18on` | 1.84 | Bouncy Castle Licence (MIT 계열) | Android | ✅ |
| [Protocol Buffers](https://github.com/protocolbuffers/protobuf) `protobuf-kotlin-lite` | 4.35.0 | BSD-3-Clause | Android | ✅ |
| [swift-protobuf](https://github.com/apple/swift-protobuf) | 1.38.1 | Apache-2.0 | Mac | ✅ |
| AndroidX (core-ktx, lifecycle, activity-compose) | 1.10.1 / 2.6.1 / 1.8.0 | Apache-2.0 | Android | ✅ |
| Jetpack Compose (BOM, ui, material3) | BOM 2024.09.00 | Apache-2.0 | Android | ✅ |
| Kotlin 표준 라이브러리 | 2.2.10 | Apache-2.0 | Android | ✅ |
| AndroidX · Compose (material3-adaptive, icons-extended 포함) | BOM 2026.06.01 | Apache-2.0 | HardwareInformation | ✅ (해당 앱) |

### 빌드·테스트에만 쓰는 것 (배포물에 들어가지 않음)

| 구성요소 | 버전 | 라이선스 |
|---|---|---|
| Android Gradle Plugin | 9.2.1 | Apache-2.0 |
| Gradle Wrapper (`gradlew`, `gradlew.bat`) | 9.4.1 | Apache-2.0 |
| protobuf-gradle-plugin | 0.10.0 | BSD-3-Clause |
| `protoc` (컴파일러) | 4.35.0 | BSD-3-Clause |
| `protoc-gen-swift` (`scripts/gen-swift.sh`, Homebrew) | — | Apache-2.0 |
| JUnit | 4.13.2 | EPL-1.0 |
| AndroidX Test (junit 1.1.5, espresso 3.5.1) | — | Apache-2.0 |

생성 코드(`extend.pb.swift`, Kotlin protobuf 클래스)는 우리 `.proto`에서 나온 것이라
우리 코드로 취급한다. 다만 런타임 라이브러리 라이선스는 위 표대로 따른다.

## 배포할 때 해야 할 일

- **고지문 포함**: BSD-2/BSD-3/ISC/Bouncy Castle 라이선스는 바이너리를 배포할 때 저작권
  고지와 라이선스 전문을 함께 실으라고 요구한다. Android에는 "오픈소스 라이선스" 화면을,
  Mac에는 About 창이나 번들 리소스로 고지문을 넣어야 한다. **아직 구현되지 않았다.**
- **Apache-2.0**: 라이선스 사본을 함께 배포하고, 업스트림에 `NOTICE` 파일이 있으면
  그 내용도 고지에 포함한다.
- **카피레프트(GPL/LGPL) 의존성은 없다.** 예전에 참고용으로 두었던
  `ThirdParty/kdeconnect-android`(GPL)는 코드에서 쓰지 않아 삭제했다.
  KDE Connect 코드를 가져오면 앱 전체에 GPL 의무가 생기므로 새로 넣지 않는다.
- `ThirdParty/sodium/`은 `.gitignore` 대상(빌드 산출물)이라 저장소에 없다.
  라이선스 전문은 아래에 옮겨 둔다.

---

## 라이선스 전문 (벤더링·정적 링크)

### spake2-ee — BSD 2-Clause

`ThirdParty/spake2-ee/LICENSE`에 원본이 있다.

```
BSD 2-Clause License

Copyright (c) 2017-2026, Frank Denis
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### libsodium — ISC

```
ISC License

Copyright (c) 2013-2026
Frank Denis <j at pureftpd dot org>

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## 라이선스 원문 링크 (패키지 저장소에서 받는 의존성)

- Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
- Bouncy Castle Licence: https://www.bouncycastle.org/about/license/
- Protocol Buffers (BSD-3-Clause): https://github.com/protocolbuffers/protobuf/blob/main/LICENSE
- swift-protobuf (Apache-2.0): https://github.com/apple/swift-protobuf/blob/main/LICENSE.txt
- EPL-1.0 (JUnit 4): https://www.eclipse.org/legal/epl-v10.html
