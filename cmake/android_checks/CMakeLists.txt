cmake_minimum_required(VERSION 3.24)
project(GalaxyAndroidChecks LANGUAGES C CXX)

if(NOT ANDROID OR NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Configure using the Android NDK toolchain and ANDROID_ABI=arm64-v8a")
endif()
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
get_filename_component(galaxy_root "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(rt "${galaxy_root}/runtime/src")

# Same portable native-input implementation as the main project; no test stubs.
add_library(android_check_input STATIC
    "${rt}/controller_roster.cpp" "${rt}/generic_hid.cpp"
    "${rt}/input/wiimote_input.cpp" "${rt}/input/wiimote_motion.cpp"
    "${rt}/native_input.cpp" "${rt}/playstation_controller.cpp"
    "${rt}/simple_pads.cpp" "${rt}/switch2_ble.cpp" "${rt}/pointer_control.cpp"
    "${rt}/rumble.cpp" "${rt}/steam_controller.cpp" "${rt}/switch_controller.cpp"
    "${rt}/tilt.cpp" "${rt}/wiimote_protocol.cpp" "${rt}/wiimote_speaker.cpp")
target_include_directories(android_check_input PUBLIC "${galaxy_root}/runtime/include")

function(android_check name)
    add_executable(${name} ${ARGN})
    target_include_directories(${name} PRIVATE "${galaxy_root}/runtime/include")
    # These tests use their own predicates, including in optimized builds.
    target_compile_options(${name} PRIVATE -UNDEBUG)
endfunction()
android_check(galaxy_native_input_tests "${rt}/native_input_tests.cpp")
target_link_libraries(galaxy_native_input_tests PRIVATE android_check_input)
android_check(galaxy_mouse_projection_tests "${rt}/input/mouse_projection_tests.cpp")
target_link_libraries(galaxy_mouse_projection_tests PRIVATE android_check_input)
android_check(galaxy_live_controls_engine_tests "${rt}/input/live_controls_engine_tests.cpp"
    "${rt}/input/live_controls_engine.cpp" "${rt}/input/controls_engine.cpp"
    "${rt}/input_bindings.cpp")
target_link_libraries(galaxy_live_controls_engine_tests PRIVATE android_check_input)
android_check(galaxy_save_files_tests "${rt}/save_files_tests.cpp"
    "${rt}/save_files.cpp" "${rt}/atomic_file.cpp")

# Preserve the game-module flags from native/tests/run_tests.sh, including the
# force-included type/prelude definitions, while exercising the ARM NEON path.
android_check(galaxy_math_tests "${galaxy_root}/native/math/tests/math_tests.c"
    "${galaxy_root}/native/math/src/nebula_math.c")
target_include_directories(galaxy_math_tests PRIVATE "${galaxy_root}/native/math/include"
    "${galaxy_root}/native/petari/include" "${galaxy_root}/native/include")
target_compile_definitions(galaxy_math_tests PRIVATE VERSION=1 NEBULA_GAME_MODULE=1)
target_compile_options(galaxy_math_tests PRIVATE -fsigned-char -fno-strict-aliasing
    -fno-strict-float-cast-overflow -fno-math-errno
    -include "${galaxy_root}/native/include/nebula_prelude.h")
target_link_libraries(galaxy_math_tests PRIVATE m)
