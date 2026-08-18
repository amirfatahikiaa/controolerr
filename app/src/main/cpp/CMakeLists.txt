cmake_minimum_required(VERSION 3.22.1)
project("gpmapper_native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(gpmapper_native SHARED
    native_input_core.cpp
    touch_injector.cpp
    analog_processor.cpp
)

target_compile_options(gpmapper_native PRIVATE
    -O3
    -flto=full
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(gpmapper_native
    android
    log
)
