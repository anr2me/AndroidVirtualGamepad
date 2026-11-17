cmake_minimum_required(VERSION 3.22.1)
project(agp LANGUAGES CXX)

add_library(agp SHARED agp_native.cpp)
find_library(log-lib log)
target_link_libraries(agp ${log-lib})
target_link_options(agp PRIVATE "-Wl,-z,max-page-size=16384")
