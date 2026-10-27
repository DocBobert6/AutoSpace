cmake_minimum_required(VERSION 3.22.1)
project(autospace_retro)

# Oboe (audio) is fetched and hash-pinned by CI into
# ${OBOE_DIR} - see .github/workflows/release.yml and
# codeql.yml. It is built from source there and never
# vendored into this repository. Pass -DoboeDir=<path>
# (or -DOBOE_DIR=<path> to CMake directly) to build
# outside CI.
if(NOT DEFINED OBOE_DIR)
    message(FATAL_ERROR "OBOE_DIR is not set (the CI fetch step provides it)")
endif()
add_subdirectory(${OBOE_DIR} oboe-build)

# The libretro frontend shim: hosts a core .so loaded by
# path at runtime (cores are separate libraries, downloaded
# and hash-verified by the app, never linked here).
add_library(autospace_retro SHARED retro_shim.cpp)
target_compile_features(autospace_retro PRIVATE cxx_std_17)
target_include_directories(autospace_retro PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(autospace_retro
    oboe
    EGL
    GLESv3
    android
    log
    dl
)
