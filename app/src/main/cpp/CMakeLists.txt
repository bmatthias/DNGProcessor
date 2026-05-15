cmake_minimum_required(VERSION 3.22.1)

project("ultrahdr-jni")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Ninja executable for ExternalProject builds (needed for turbojpeg dependency)
# ExternalProject_Add needs CMAKE_MAKE_PROGRAM to be set
get_filename_component(CMAKE_DIR "${CMAKE_COMMAND}" DIRECTORY)
find_program(CMAKE_MAKE_PROGRAM
    NAMES ninja
    PATHS
        ${CMAKE_DIR}
        ${CMAKE_DIR}/..
        /usr/local/bin
        /usr/bin
    DOC "Ninja build tool"
)
if(CMAKE_MAKE_PROGRAM)
    message(STATUS "Found Ninja: ${CMAKE_MAKE_PROGRAM}")
    # Set it as a cache variable so ExternalProject can use it
    set(CMAKE_MAKE_PROGRAM ${CMAKE_MAKE_PROGRAM} CACHE FILEPATH "Path to ninja executable")
else()
    message(WARNING "Could not find Ninja. ExternalProject builds may fail.")
endif()

# Disable building examples, tests, etc. for libultrahdr
set(UHDR_BUILD_EXAMPLES OFF CACHE BOOL "Build sample application")
set(UHDR_BUILD_TESTS OFF CACHE BOOL "Build unit tests")
set(UHDR_BUILD_BENCHMARK OFF CACHE BOOL "Build benchmark tests")
set(UHDR_BUILD_FUZZERS OFF CACHE BOOL "Build fuzz test applications")
set(UHDR_BUILD_JAVA ON CACHE BOOL "Build JNI wrapper and Java front-end classes")
# Build dependencies from source for Android (ensures compatibility)
set(UHDR_BUILD_DEPS ON CACHE BOOL "Build deps and not use pre-installed packages")
set(UHDR_ENABLE_INSTALL OFF CACHE BOOL "Enable install and uninstall targets")
set(UHDR_ENABLE_LOGS OFF CACHE BOOL "Build with verbose logging")

# Add libultrahdr as subdirectory
# This will build the "uhdrjni" shared library that the Java classes need
add_subdirectory(libultrahdr)

# Note: The libultrahdr build will create the "uhdrjni" library automatically
# when UHDR_BUILD_JAVA is ON. We use the existing Java classes (UltraHDRDecoder)
# which are already copied to our source tree.

