# Dot-source before building the GNU host importer and Android ARM64 importer:
# . ./tools/importer_env.ps1
# cargo build --release --locked -p nebula-import --lib --bins --target x86_64-pc-windows-gnu --target aarch64-linux-android -j2
$importerRoot = (Resolve-Path "$PSScriptRoot/..").Path
$importerTools = (Resolve-Path "$importerRoot/../.tools").Path
$importerLlvm = "$importerTools/llvm-mingw/bin"
$importerNdk = "$importerTools/android-sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/windows-x86_64/bin"
$env:RUSTUP_HOME = "$importerTools/rustup"
$env:CARGO_HOME = "$importerTools/cargo"
$env:RUSTUP_TOOLCHAIN = 'stable-x86_64-pc-windows-gnu'
$env:CARGO_TARGET_DIR = "$importerRoot/build/cargo"
$env:PATH = "$importerTools/cargo/bin;$importerLlvm;$importerNdk;$env:PATH"
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = "$importerTools/rust-gcc.cmd"
$env:CC_x86_64_pc_windows_gnu = "$importerLlvm/x86_64-w64-mingw32-gcc.exe"
$env:CXX_x86_64_pc_windows_gnu = "$importerLlvm/x86_64-w64-mingw32-g++.exe"
$env:AR_x86_64_pc_windows_gnu = "$importerLlvm/llvm-ar.exe"
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$importerNdk/aarch64-linux-android31-clang.cmd"
$env:CC_aarch64_linux_android = "$importerNdk/aarch64-linux-android31-clang.cmd"
$env:CXX_aarch64_linux_android = "$importerNdk/aarch64-linux-android31-clang++.cmd"
$env:AR_aarch64_linux_android = "$importerNdk/llvm-ar.exe"

# Generate only the private command launcher. The maintained implementation is
# tracked in this checkout; a fresh checkout does not need rust_link.py in .tools.
$importerWrapper = @"
@echo off
"%~dp0llvm-mingw\python\bin\python.exe" "$importerRoot\tools\rust_gnu_link.py" --tools-dir "%~dp0." -- %*
"@
Set-Content -LiteralPath "$importerTools/rust-gcc.cmd" -Value $importerWrapper -Encoding ascii
