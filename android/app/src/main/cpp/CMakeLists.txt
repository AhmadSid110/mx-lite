cmake_minimum_required(VERSION 3.22)

project(mxlite LANGUAGES C CXX)

# --------------------------------------------------
# C++ STANDARD (MANDATORY)
# --------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --------------------------------------------------
# SOURCE ROOT
# --------------------------------------------------
set(SRC_ROOT ${CMAKE_CURRENT_SOURCE_DIR})

# --------------------------------------------------
# LIBRARY
# --------------------------------------------------
add_library(
    mxplayer
    SHARED
    ${SRC_ROOT}/player/AudioEngine.cpp
    ${SRC_ROOT}/player/AudioDebug.cpp
    ${SRC_ROOT}/player/Clock.cpp
    ${SRC_ROOT}/JniBridge.cpp
)

# --------------------------------------------------
# INCLUDE DIRECTORIES
# --------------------------------------------------
target_include_directories(
    mxplayer
    PRIVATE
    ${SRC_ROOT}
    ${SRC_ROOT}/player
)

# --------------------------------------------------
# ANDROID SYSTEM LIBRARIES (MUST BE DECLARED)
# --------------------------------------------------
find_library(log-lib log)
find_library(android-lib android)
find_library(mediandk-lib mediandk)
find_library(aaudio-lib aaudio)

# --------------------------------------------------
# LINK
# --------------------------------------------------
target_link_libraries(
    mxplayer
    ${log-lib}
    ${android-lib}
    ${mediandk-lib}
    ${aaudio-lib}
)