cmake_minimum_required(VERSION 3.18.1)
project(highfps_ndk)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find required packages
find_package(camera2ndk REQUIRED)
find_package(mediandk REQUIRED)

# Main library
add_library(
    highfps_native
    SHARED
    frame_processor.cpp
    frame_buffer.cpp
    tiff_encoder.cpp
    native_bridge.cpp
    stride_corrector.cpp
)

# Link libraries
target_link_libraries(
    highfps_native
    PRIVATE
    camera2ndk
    mediandk
    android
    log
)

# Include directories
target_include_directories(
    highfps_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
