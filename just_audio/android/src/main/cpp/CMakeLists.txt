cmake_minimum_required(VERSION 3.22.1)
project("just_audio_usb")

add_library(just_audio_usb SHARED usb_audio_stream.cpp)
target_compile_features(just_audio_usb PRIVATE cxx_std_17)
target_compile_options(just_audio_usb PRIVATE -O3 -Wall -Wextra)
find_library(log-lib log)
target_link_libraries(just_audio_usb ${log-lib})
target_link_options(just_audio_usb PRIVATE "-Wl,-z,max-page-size=16384")
