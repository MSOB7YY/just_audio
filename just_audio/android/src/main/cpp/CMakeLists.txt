cmake_minimum_required(VERSION 3.22.1)
project("just_audio_native")

add_library(just_audio_native SHARED usb_audio_stream.cpp sound_effects.cpp)
target_compile_features(just_audio_native PRIVATE cxx_std_17)
target_compile_options(just_audio_native PRIVATE -O3 -Wall -Wextra)
find_library(log-lib log)
target_link_libraries(just_audio_native ${log-lib})
target_link_options(just_audio_native PRIVATE "-Wl,-z,max-page-size=16384")
