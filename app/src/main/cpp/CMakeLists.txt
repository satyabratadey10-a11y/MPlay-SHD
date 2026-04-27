cmake_minimum_required(VERSION 3.22.1)
project(TuneAiAudioEngine LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(oboe REQUIRED CONFIG)

add_library(
    audioengine
    SHARED
    AudioEngine.cpp
)

target_compile_options(audioengine PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -Wall
    -Wextra
    -Wpedantic
)

find_library(log-lib log)
find_library(android-lib android)
find_library(mediandk-lib mediandk)

target_link_libraries(
    audioengine
    oboe::oboe
    ${log-lib}
    ${android-lib}
    ${mediandk-lib}
)
