# Android shader build tools on Windows

This standalone CMake project builds the same `galaxy_native_spirv` and
`galaxy_utility_spirv` sources as the main Vulkan build, using official
LLVM-MinGW UCRT x86_64 rather than Visual Studio. glslang, SPIRV-Tools and
SPIRV-Headers match the pins in `cmake/vulkan_deps.cmake`.

From the repository root in PowerShell:

```powershell
$Python = "$env:USERPROFILE/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe"
& $Python cmake/android_host_tools/provision.py
& ./cmake/android_host_tools/build.ps1 -Jobs 3 -PythonExecutable $Python
```

The SDK's CMake and Ninja must already be installed in
`../.tools/android-sdk/cmake/3.31.6/bin`. The provisioner checks SHA256 for
every archive, records URLs and digests in `../.tools/galaxy-deps/manifest.json`,
and downloads only into `../.tools/galaxy-deps` and `../.tools/llvm-mingw`.
GitHub release metadata additionally verifies the pinned LLVM archive digest.

Pass these paths to the Android build:

```powershell
$Deps = (Resolve-Path ../.tools/galaxy-deps).Path
$HostTools = Join-Path $Deps host-build
# NEBULA_DEPS=$Deps; NEBULA_HOST_TOOLS=$HostTools
```

The executables are in `$HostTools`, alongside `configure.log` and `build.log`.
They statically link their C++ runtime and shader dependencies. No game assets
are required to compile or validate the tools. A real utility shader compile:

```powershell
& "$HostTools/galaxy_utility_spirv.exe" "$HostTools/utility_spirv_smoke.h"
& "$HostTools/galaxy_native_spirv.exe" `
  runtime/src/render/materials/flat_color.hlsl runtime/src/render/materials `
  vs native_vs "$HostTools/flat_color_vs.spv"
```

The host project also provides a synthetic mapped-buffer test of the real
upload-ring allocator. It checks storage descriptor alignment at 16, 64 and
256 bytes across three frame slots, including the matrix row indices after
padding. No Vulkan device is needed:

```powershell
& ../.tools/android-sdk/cmake/3.31.6/bin/cmake.exe --build $HostTools --target galaxy_upload_ring_tests --parallel 3
& ../.tools/android-sdk/cmake/3.31.6/bin/ctest.exe --test-dir $HostTools -R galaxy_upload_ring_alignment --output-on-failure
```
