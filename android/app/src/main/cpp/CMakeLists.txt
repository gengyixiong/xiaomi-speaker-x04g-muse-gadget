cmake_minimum_required(VERSION 3.22)
project(muse LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(CORE "${ROOT}/upstream/muse-gadget-sdk/esp32/components/noise_core")
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
add_subdirectory("${ROOT}/upstream/mbedtls" mbedtls EXCLUDE_FROM_ALL)
# Bionic does not take mbedTLS's glibc getrandom branch; /dev/random blocked
# on this clock after the first connection. Use Android's OS CSPRNG device.
target_compile_definitions(mbedcrypto PRIVATE MBEDTLS_PLATFORM_DEV_RANDOM="/dev/urandom")
set(AVATAR "${ROOT}/upstream/muse-gadget-sdk/esp32/avatar/muse_pixel.c")
file(GLOB CUSTOM_AVATAR CONFIGURE_DEPENDS "${ROOT}/avatar/custom/muse_pixel.c")
if(CUSTOM_AVATAR)
    set(AVATAR "${CUSTOM_AVATAR}")
endif()
add_library(muse SHARED native.cpp "${AVATAR}"
    "${CORE}/src/ClientSession.cpp" "${CORE}/src/InitiatorHandshake.cpp"
    "${CORE}/src/PsaCryptoBackend.cpp" "${CORE}/src/ServiceCodec.cpp"
    "${CORE}/src/Status.cpp" "${CORE}/src/Transport.cpp" "${CORE}/src/TransportFrameCodec.cpp")
target_include_directories(muse PRIVATE "${CORE}/include" "${ROOT}/upstream/muse-gadget-sdk/esp32/components/muse")
target_link_libraries(muse PRIVATE mbedcrypto jnigraphics m)
target_compile_options(muse PRIVATE -Wall -Wextra)
add_executable(muse-supervisor supervisor.c)
target_compile_options(muse-supervisor PRIVATE -Wall -Wextra)
