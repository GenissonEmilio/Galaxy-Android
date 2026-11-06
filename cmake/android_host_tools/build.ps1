param(
    [int]$Jobs = 3,
    [string]$PythonExecutable = "$env:USERPROFILE/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe"
)
$ErrorActionPreference = 'Stop'
$NebulaRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$PortsRoot = (Resolve-Path (Join-Path $NebulaRoot '..')).Path
$ToolsRoot = Join-Path $PortsRoot '.tools'
$DepsRoot = Join-Path $ToolsRoot 'galaxy-deps'
$HostBuild = Join-Path $DepsRoot 'host-build'
$CMakeBin = Join-Path $ToolsRoot 'android-sdk/cmake/3.31.6/bin'
$CompilerBin = Join-Path $ToolsRoot 'llvm-mingw/bin'
New-Item -ItemType Directory -Path $HostBuild -Force | Out-Null
$env:PATH = "$CompilerBin;$CMakeBin;$env:PATH"
& (Join-Path $CMakeBin 'cmake.exe') -S $PSScriptRoot -B $HostBuild -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$CMakeBin/ninja.exe" `
    "-DCMAKE_C_COMPILER=$CompilerBin/x86_64-w64-mingw32-clang.exe" `
    "-DCMAKE_CXX_COMPILER=$CompilerBin/x86_64-w64-mingw32-clang++.exe" `
    '-DCMAKE_BUILD_TYPE=Release' '-DCMAKE_CXX_FLAGS_RELEASE=-O2 -DNDEBUG' `
    "-DNEBULA_DEPS=$DepsRoot" "-DPython3_EXECUTABLE=$PythonExecutable" `
    2>&1 | Tee-Object -FilePath (Join-Path $HostBuild 'configure.log')
if ($LASTEXITCODE -ne 0) { throw 'Host tools configure failed' }
& (Join-Path $CMakeBin 'cmake.exe') --build $HostBuild --target galaxy_native_spirv galaxy_utility_spirv --parallel $Jobs `
    2>&1 | Tee-Object -FilePath (Join-Path $HostBuild 'build.log')
if ($LASTEXITCODE -ne 0) { throw 'Host tools build failed' }
Write-Output "NEBULA_DEPS=$DepsRoot"
Write-Output "NEBULA_HOST_TOOLS=$HostBuild"
