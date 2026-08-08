# libperi_spake.so — spake2-ee + libsodium 을 JNI로 내보낸다.
#
# libsodium은 ABI별 정적 라이브러리를 **미리 빌드해 둔다**:
#     ./scripts/build-sodium.sh android
# Gradle이 알아서 부르게 하지 않는 이유는 그게 몇 분짜리 autotools 빌드라서다 —
# 매 빌드마다 확인하러 들어가는 것만으로 개발 루프가 느려진다. 없으면 여기서
# 무엇을 실행하라고 말해 주는 쪽이 낫다.

cmake_minimum_required(VERSION 3.22.1)
project(peri_spake C)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(SPAKE_DIR  "${REPO_ROOT}/ThirdParty/spake2-ee")
set(SODIUM_DIR "${REPO_ROOT}/ThirdParty/sodium")
set(SODIUM_LIB "${SODIUM_DIR}/android-${ANDROID_ABI}/libsodium.a")

if(NOT EXISTS "${SODIUM_LIB}")
    message(FATAL_ERROR
        "libsodium(${ANDROID_ABI})이 없다: ${SODIUM_LIB}\n"
        "  → 저장소 루트에서 `./scripts/build-sodium.sh android` 를 먼저 실행할 것")
endif()

add_library(sodium STATIC IMPORTED)
set_target_properties(sodium PROPERTIES IMPORTED_LOCATION "${SODIUM_LIB}")

add_library(peri_spake SHARED
    peri_spake_jni.c
    "${SPAKE_DIR}/crypto_spake.c")

target_include_directories(peri_spake PRIVATE
    "${SPAKE_DIR}"
    "${SODIUM_DIR}/include")

# spake2-ee는 assert()로 직렬화 길이를 확인한다. 릴리스에서 NDEBUG로 그게 지워지면
# 확인이 통째로 사라지므로 여기서만 되살린다 — 페어링은 조용히 틀리면 안 되는 코드다.
target_compile_options(peri_spake PRIVATE -UNDEBUG -Wall -Wextra)

target_link_libraries(peri_spake sodium)
