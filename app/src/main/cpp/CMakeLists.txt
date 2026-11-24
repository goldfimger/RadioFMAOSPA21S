cmake_minimum_required(VERSION 3.22.1)
project("a21s_fm_jni")

add_library(a21s_fm_jni SHARED
    fm_tuner_jni.cpp
)

find_library(log-lib log)

target_link_libraries(a21s_fm_jni
    ${log-lib}
    dl
)
