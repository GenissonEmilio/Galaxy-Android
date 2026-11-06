cmake_minimum_required(VERSION 3.24)
project(NebulaAndroidHostTools LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
get_filename_component(nebula_root "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(NEBULA_DEPS "${nebula_root}/../.tools/galaxy-deps" CACHE PATH "Pinned Android source dependencies")

# This small native Windows project builds the real shader compilers without
# configuring the game's Windows renderer or requiring Visual Studio.
set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
set(ENABLE_HLSL ON CACHE BOOL "" FORCE)
set(ENABLE_OPT ON CACHE BOOL "" FORCE)
set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(SPIRV_SKIP_TESTS ON CACHE BOOL "" FORCE)
set(SPIRV_SKIP_EXECUTABLES ON CACHE BOOL "" FORCE)
set(SPIRV_TOOLS_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(glslang_source "${NEBULA_DEPS}/glslang-src")
add_subdirectory("${glslang_source}" "${CMAKE_BINARY_DIR}/glslang" EXCLUDE_FROM_ALL)
file(GLOB spirv_headers "${glslang_source}/SPIRV/*.h" "${glslang_source}/SPIRV/*.hpp")
file(COPY ${spirv_headers} DESTINATION "${CMAKE_BINARY_DIR}/glslang-include/glslang/SPIRV")

add_library(nebula_host_shader_deps INTERFACE)
target_include_directories(nebula_host_shader_deps SYSTEM INTERFACE
    "${NEBULA_DEPS}/Vulkan-Headers-vulkan-sdk-1.4.350.1/include"
    "${NEBULA_DEPS}/volk-vulkan-sdk-1.4.350.1"
    "${NEBULA_DEPS}/VulkanMemoryAllocator-v3.4.0/include"
    "${CMAKE_BINARY_DIR}/glslang-include" "${glslang_source}"
    "${glslang_source}/External/spirv-tools/include")
target_compile_definitions(nebula_host_shader_deps INTERFACE NEBULA_GPU_VULKAN VK_NO_PROTOTYPES
    VK_USE_PLATFORM_WIN32_KHR NOMINMAX)
target_link_libraries(nebula_host_shader_deps INTERFACE glslang SPIRV glslang-default-resource-limits
    SPIRV-Tools-opt SPIRV-Tools-static)

include("${nebula_root}/cmake/post_shader_sources.cmake")
set(post_embed "${CMAKE_BINARY_DIR}/generated/post_shader_embed.cpp")
galaxy_embed_post_sources("${post_embed}" "${nebula_root}")
add_executable(galaxy_native_spirv "${nebula_root}/tools/dev/native_spirv.cpp"
    "${nebula_root}/runtime/src/render/vk/shader_compile_vk.cpp")
add_executable(galaxy_utility_spirv "${nebula_root}/tools/dev/utility_spirv.cpp"
    "${nebula_root}/runtime/src/render/vk/shader_compile_vk.cpp"
    "${nebula_root}/runtime/src/render/renderer_common.cpp" "${post_embed}")
foreach(target galaxy_native_spirv galaxy_utility_spirv)
    target_include_directories(${target} PRIVATE "${nebula_root}/runtime/include" "${nebula_root}/native/include")
    target_link_libraries(${target} PRIVATE nebula_host_shader_deps)
    if(MINGW)
        target_link_options(${target} PRIVATE -static)
    endif()
endforeach()

# Exercise the real upload allocator without a Vulkan device. The test supplies
# only a mapped allocation backend, so descriptor offsets and row addressing can
# be checked on the Windows build machine.
add_executable(galaxy_upload_ring_tests "${nebula_root}/runtime/src/render/gpu_upload_ring_tests.cpp"
    "${nebula_root}/runtime/src/render/gpu_upload_ring.cpp")
target_compile_definitions(galaxy_upload_ring_tests PRIVATE NEBULA_GPU_VULKAN)
target_include_directories(galaxy_upload_ring_tests PRIVATE "${nebula_root}/runtime/include"
    "${NEBULA_DEPS}/Vulkan-Headers-vulkan-sdk-1.4.350.1/include"
    "${NEBULA_DEPS}/volk-vulkan-sdk-1.4.350.1")
if(MINGW)
    target_link_options(galaxy_upload_ring_tests PRIVATE -static)
endif()
enable_testing()
add_test(NAME galaxy_upload_ring_alignment COMMAND galaxy_upload_ring_tests)
