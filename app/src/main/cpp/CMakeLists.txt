cmake_minimum_required(VERSION 3.22.1)
project("ndi_bridge")

add_library(ndi_bridge SHARED ndi_bridge.cpp)

# NDI Advanced SDK for Android. The headers live in cpp/ndi/include and the
# runtime in jniLibs/<abi>/libndi_advanced.so — note the name, the Android
# build ships libndi_advanced.so rather than libndi.so.
target_include_directories(ndi_bridge PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/ndi/include)

add_library(ndi_advanced SHARED IMPORTED)
set_target_properties(ndi_advanced PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi_advanced.so)

find_library(log-lib log)

target_link_libraries(ndi_bridge ndi_advanced ${log-lib})
