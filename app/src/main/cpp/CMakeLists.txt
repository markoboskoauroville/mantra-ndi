cmake_minimum_required(VERSION 3.22.1)
project("ndi_bridge")

add_library(ndi_bridge SHARED
        ndi_bridge.cpp)

# --- NDI Advanced SDK for Android ---
# After registering at ndi.video and downloading the NDI Advanced SDK for Android,
# copy:
#   <sdk>/include/Processing.NDI.Lib.h  -> app/src/main/cpp/ndi/include/
#   <sdk>/lib/<ABI>/libndi.so           -> app/src/main/jniLibs/<ABI>/libndi.so
# for each ABI you're targeting (arm64-v8a, armeabi-v7a).
# The .so is loaded at runtime from jniLibs (that's why it's not listed as a
# link target file here — it's a prebuilt import lib pointing at the same soname).

target_include_directories(ndi_bridge PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/ndi/include)

add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so)

find_library(log-lib log)

target_link_libraries(ndi_bridge
        ndi
        ${log-lib})
