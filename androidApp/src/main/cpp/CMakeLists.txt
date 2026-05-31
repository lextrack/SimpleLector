cmake_minimum_required(VERSION 3.22.1)

project(simplelector_native)

add_library(
    simplelector_native
    SHARED
    simplelector_native.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

target_link_libraries(
    simplelector_native
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)
