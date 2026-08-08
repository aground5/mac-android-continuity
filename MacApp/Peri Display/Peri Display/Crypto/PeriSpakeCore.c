//
//  PeriSpakeCore.c
//  Peri Display
//
//  벤더링한 SPAKE2+EE 구현을 앱 타깃 안으로 끌어들이는 **한 줄짜리 파일**.
//
//  왜 이렇게 하나
//  ─────────────
//  이 프로젝트는 동기화 폴더(fileSystemSynchronizedGroups)를 쓴다 — `Peri Display/` 밑에
//  있는 소스는 자동으로 타깃에 들어간다. 그런데 실제 구현은 저장소 루트의
//  `ThirdParty/spake2-ee/`에 있어서 그 자동 배선 바깥이고, 그러면 Xcode에 파일 참조를
//  손으로 등록해야 한다. 그 참조는 pbxproj에 남아서 나중에 경로가 바뀌면 조용히 깨진다.
//
//  여기 이 파일이 폴더 안에 있으므로 자동으로 컴파일되고, 진짜 코드는 헤더 검색 경로를 타고
//  따라 들어온다. Android의 CMake가 같은 .c를 직접 컴파일하는 것과 결과가 같다 —
//  **양쪽이 같은 구현을 돌린다**는 게 이 배선 전체의 요점이다.
//
//  .c를 include하는 건 흔한 모양은 아니지만, 대안(pbxproj에 외부 파일 참조 등록)보다
//  덜 깨진다. 벤더링한 원본을 한 글자도 안 고친다는 규칙도 이렇게 해야 지켜진다.
//
//  필요한 빌드 설정은 둘뿐이다:
//    HEADER_SEARCH_PATHS  ← ThirdParty/spake2-ee, ThirdParty/sodium/include
//    OTHER_LDFLAGS        ← ThirdParty/sodium/macos/libsodium.a
//

#include "crypto_spake.c"
