#!/usr/bin/env python3
"""Run synthetic ARM64 checks; upload only known ELF binaries and libc++.

No APK installation, application-data access, game files or user saves are used.
All device writes stay below /data/local/tmp/galaxy-checks/<unique run>.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import uuid

SUITES = (
    "galaxy_native_input_tests",
    "galaxy_mouse_projection_tests",
    "galaxy_live_controls_engine_tests",
    "galaxy_save_files_tests",
    "galaxy_math_tests",
)
ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT.parent / ".tools" / "android-sdk"


def arm64_elf(path: Path) -> Path:
    with path.open("rb") as source:
        header = source.read(20)
    if (len(header) < 20 or header[:6] != b"\x7fELF\x02\x01"
            or struct.unpack_from("<H", header, 18)[0] != 183):
        raise ValueError(f"Expected a little-endian ARM64 ELF: {path}")
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, default=SDK / "platform-tools" / "adb.exe")
    parser.add_argument("--serial", help="adb device serial (required if multiple devices are connected)")
    parser.add_argument("--build-dir", type=Path, default=ROOT / "build" / "android-checks")
    parser.add_argument("--ndk", type=Path, default=SDK / "ndk" / "30.0.16248370")
    parser.add_argument("--suite", action="append", choices=SUITES,
                        help="Run selected suite; repeat to select several (default: all)")
    parser.add_argument("--timeout", type=int, default=120, help="Seconds per suite")
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("--timeout must be positive")
    selected = list(dict.fromkeys(args.suite or SUITES))
    # Complete local validation before touching a device. Never upload a directory.
    executables = [arm64_elf(args.build_dir / "bin" / name) for name in selected]
    cpp = arm64_elf(args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
    adb = [str(args.adb)] + (["-s", args.serial] if args.serial else [])
    remote = "/data/local/tmp/galaxy-checks/" + uuid.uuid4().hex

    def invoke(*command: str, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess:
        return subprocess.run(adb + list(command), check=check, timeout=timeout)

    def shell(command: str, **kwargs) -> subprocess.CompletedProcess:
        return invoke("shell", command, **kwargs)

    # 'timeout' on the device prevents a disconnected/timed-out host leaving a test running.
    shell("command -v timeout >/dev/null && test \"$(getprop ro.product.cpu.abi)\" = arm64-v8a")
    shell(f"mkdir -p {shlex.quote(remote + '/bin')} {shlex.quote(remote + '/tmp')}")
    failures = []
    try:
        for executable in [*executables, cpp]:
            invoke("push", str(executable), remote + "/bin/" + executable.name)
        shell(f"chmod 700 {' '.join(shlex.quote(remote + '/bin/' + name) for name in selected)}")
        for name in selected:
            print(f"\n=== {name} ===", flush=True)
            command = (
                f"cd {shlex.quote(remote + '/tmp')} && "
                f"TMPDIR={shlex.quote(remote + '/tmp')} HOME={shlex.quote(remote + '/tmp')} "
                f"LD_LIBRARY_PATH={shlex.quote(remote + '/bin')} "
                f"timeout {args.timeout} {shlex.quote(remote + '/bin/' + name)}"
            )
            try:
                result = shell(command, timeout=args.timeout + 20, check=False)
                if result.returncode:
                    failures.append(f"{name} (exit {result.returncode})")
            except subprocess.TimeoutExpired:
                failures.append(f"{name} (adb timeout)")
    finally:
        # This path is generated internally beneath a fixed test-only directory.
        # The parent folder and all application/user data are deliberately untouched.
        try:
            shell(f"rm -rf {shlex.quote(remote)}")
        except (subprocess.SubprocessError, OSError) as error:
            print(f"Could not remove synthetic test directory {remote}: {error}", file=sys.stderr)
    if failures:
        print("FAILED: " + ", ".join(failures), file=sys.stderr)
        return 1
    print(f"Passed {len(selected)} Android ARM64 suites (synthetic fixtures only).")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
