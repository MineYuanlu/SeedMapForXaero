cmake_minimum_required(VERSION 3.15)
project(xsmcore)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

enable_language(CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(DEBUG_TIMINGS "Enable debug timings" OFF)
if(DEBUG_TIMINGS)
    add_compile_definitions(DEBUG_TIMINGS)
endif()

set(CMAKE_CXX_FLAGS_RELEASE "-O3")
set(CMAKE_CXX_FLAGS_DEBUG "-O0 -g")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O0 -g")
set(CMAKE_CXX_FLAGS_MINSIZEREL "-O0 -g")

add_subdirectory(cubiomes)

target_compile_options(objects PRIVATE -w)

file(GLOB_RECURSE XSM_TEST_SOURCES
    xsm/test/*.h
    xsm/test/*.c
    xsm/test/*.cpp
)

file(GLOB_RECURSE XSM_SOURCES
    xsm/*.h
    xsm/*.c
    xsm/*.cpp
)
list(REMOVE_ITEM XSM_SOURCES ${XSM_TEST_SOURCES})
add_library(xsmcore SHARED ${XSM_SOURCES})
target_link_libraries(xsmcore PRIVATE cubiomes_static)
target_include_directories(xsmcore PRIVATE cubiomes)

add_executable(xsmtest
    xsm/test/unit_tests.cpp
)
target_include_directories(xsmtest PRIVATE cubiomes xsm/test)
target_link_libraries(xsmtest PRIVATE xsmcore cubiomes_static)

add_executable(xsmsandbox
    xsm/test/sandbox.cpp
)
target_include_directories(xsmsandbox PRIVATE cubiomes xsm/test)
target_link_libraries(xsmsandbox PRIVATE xsmcore cubiomes_static)

if (WIN32)
    set_target_properties(xsmcore PROPERTIES PREFIX "")
    target_link_options(xsmcore PRIVATE -static-libgcc -static-libstdc++)
endif()
