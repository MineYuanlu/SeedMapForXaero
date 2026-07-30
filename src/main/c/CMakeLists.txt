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

# module sources
file(GLOB_RECURSE XSM_APIS_SOURCES
    xsm/apis/*.h
    xsm/apis/*.c
    xsm/apis/*.cpp
)

file(GLOB_RECURSE XSM_TEST_SOURCES
    xsm/test/*.h
    xsm/test/*.c
    xsm/test/*.cpp
)

file(GLOB_RECURSE XSM_SANDBOX_SOURCES
    xsm/sandbox/*.h
    xsm/sandbox/*.c
    xsm/sandbox/*.cpp
)

file(GLOB_RECURSE XSM_PRETOOLS_SOURCES
    xsm/pretools/*.h
    xsm/pretools/*.c
    xsm/pretools/*.cpp
)

# xsmcore
add_library(xsmcore SHARED ${XSM_APIS_SOURCES})
target_link_libraries(xsmcore PRIVATE cubiomes_static)
target_include_directories(xsmcore PRIVATE cubiomes)

if (WIN32)
    set_target_properties(xsmcore PROPERTIES PREFIX "")
    target_link_options(xsmcore PRIVATE -static -static-libgcc -static-libstdc++)
endif()

# xsmtest
add_executable(xsmtest ${XSM_TEST_SOURCES})
target_include_directories(xsmtest PRIVATE cubiomes xsm/test)
target_link_libraries(xsmtest PRIVATE xsmcore cubiomes_static)

# xsmsandbox
add_executable(xsmsandbox ${XSM_SANDBOX_SOURCES})
target_include_directories(xsmsandbox PRIVATE cubiomes)
target_link_libraries(xsmsandbox PRIVATE xsmcore cubiomes_static)

# xsmpretools
add_executable(xsmpretools ${XSM_PRETOOLS_SOURCES})
target_include_directories(xsmpretools PRIVATE cubiomes)
target_link_libraries(xsmpretools PRIVATE xsmcore cubiomes_static)
