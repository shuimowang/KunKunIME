cmake_minimum_required(VERSION 3.21)
project(KunKunRichEditCheck LANGUAGES CXX)

foreach(target IN ITEMS rich-edit-check rich-edit-installed)
    add_executable(${target} rich-edit-check.cpp)
    target_compile_features(${target} PRIVATE cxx_std_17)
    target_compile_options(${target} PRIVATE /utf-8 /W4)
    target_link_libraries(${target} PRIVATE ole32 oleaut32 uuid user32 advapi32)
endforeach()

# The installed test must obtain its class from the actual Windows COM registration.
target_link_options(rich-edit-check PRIVATE "/MANIFEST:EMBED" "/MANIFESTINPUT:${CMAKE_CURRENT_SOURCE_DIR}/rich-edit-check.manifest")
