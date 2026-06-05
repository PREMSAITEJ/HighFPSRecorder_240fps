cmake_minimum_required(VERSION 3.18.1)
project(highfps_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add native libraries
add_library(highfps_native SHARED
    native_bridge.cpp
    frame_processor.cpp
)

# Include directories
target_include_directories(highfps_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ANDROID_NDK}/sources/android/native_app_glue
)

# Link libraries
target_link_libraries(highfps_native
    android
    log
    jnigraphics
    mediandk
)

# Set optimization flags
set_target_properties(highfps_native PROPERTIES
    CXX_FLAGS_RELEASE "-O3 -march=armv8-a"
    CXX_FLAGS_DEBUG "-g -O0"
)
