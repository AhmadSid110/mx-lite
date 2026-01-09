cmake_minimum_required(VERSION 3.22)
project(mxlite)

add_library(
    mxplayer
    SHARED
    player/AudioEngine.cpp
    player/Clock.cpp
    JniBridge.cpp
)

find_library(log-lib log)

target_include_directories(
    mxplayer
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    mxplayer
    ${log-lib}
    android
    OpenSLES
    mediandk
)
