cmake_minimum_required(VERSION 3.22.1...3.31.6)
project("a21s_fm_jni")

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

add_library(a21s_fm_jni SHARED
    fm_tuner_jni.cpp
)

add_executable(a21s_fm_rootd
    fm_root_daemon.cpp
)
set_target_properties(a21s_fm_rootd PROPERTIES
    OUTPUT_NAME "a21s_fm_rootd"
    PREFIX "lib"
    SUFFIX ".so"
)

find_library(log-lib log)

target_link_libraries(a21s_fm_jni
    ${log-lib}
    dl
)

target_link_libraries(a21s_fm_rootd
    ${log-lib}
)
