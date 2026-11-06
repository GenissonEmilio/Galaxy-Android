"""Check the ARM64 Android package, ELF alignment, and absence of private disc data."""
import argparse
import os
import re
import shutil
import subprocess
from pathlib import Path, PurePosixPath
import struct
import zipfile



def find_aapt(explicit=None):
    if explicit is not None:
        assert explicit.is_file(), f"aapt not found: {explicit}"
        return explicit
    roots = [Path(os.environ[key]) for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(key)]
    roots.append(Path(__file__).resolve().parents[2] / ".tools/android-sdk")
    for root in roots:
        candidates = list((root / "build-tools").glob("*/aapt.exe")) + list((root / "build-tools").glob("*/aapt"))
        if candidates:
            return max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)))
    executable = shutil.which("aapt")
    assert executable, "aapt is required to resolve APK icons; use --aapt or ANDROID_HOME/ANDROID_SDK_ROOT"
    return Path(executable)


def aapt_dump(aapt, *args):
    return subprocess.run([str(aapt), "dump", *map(str, args)], check=True,
                          capture_output=True, encoding="utf-8", errors="replace").stdout


def verify_manifest_resources(apk, aapt):
    manifest = aapt_dump(aapt, "xmltree", apk.filename, "AndroidManifest.xml")
    assert not re.search(r"android:debuggable\(", manifest), "Manifest contains a debuggable attribute"
    icons = re.findall(r"android:(?:icon|roundIcon)\([^)]*\)=@0x([0-9a-fA-F]+)", manifest)
    assert icons, "Manifest has no launcher icon resource reference"
    # AGP resource optimization can rename every ZIP entry. Resolve IDs through
    # resources.arsc rather than assuming the source name survives packaging.
    table = aapt_dump(aapt, "--values", "resources", apk.filename)
    resources = {}
    current = None
    for line in table.splitlines():
        entry = re.match(r"\s*resource 0x([0-9a-fA-F]+)\s", line)
        if entry:
            current = int(entry[1], 16)
            resources.setdefault(current, {"files": set(), "refs": set()})
            alias = re.search(r"t=0x01 d=0x([0-9a-fA-F]+)", line)
            if alias:
                resources[current]["refs"].add(int(alias[1], 16))
        elif current is not None:
            string = re.match(r'\s*\(string(?:8|16)?\) "(res/[^"\n]+)"', line)
            if string:
                resources[current]["files"].add(string[1])
    names = set(apk.namelist())
    checked_ids, checked_files = set(), set()

    def check_resource(resource_id):
        if resource_id in checked_ids or resource_id >> 24 == 1:  # platform resource
            return
        checked_ids.add(resource_id)
        assert resource_id in resources, f"Missing icon resource ID: 0x{resource_id:08x}"
        entry = resources[resource_id]
        for reference in entry["refs"]:
            check_resource(reference)
        for name in entry["files"]:
            assert name in names, f"Missing icon resource file: {name}"
            if name in checked_files:
                continue
            checked_files.add(name)
            if name.endswith(".xml"):
                xml = aapt_dump(aapt, "xmltree", apk.filename, name)
                for reference in re.findall(r"=@0x([0-9a-fA-F]+)", xml):
                    check_resource(int(reference, 16))

    for icon in icons:
        resource_id = int(icon, 16)
        check_resource(resource_id)
        assert resources[resource_id]["files"] or resources[resource_id]["refs"], "Launcher icon has no drawable file"
    assert checked_files, "Launcher icon has no packaged drawable files"
    return sorted(checked_files)


def verify(path, aapt=None):
    required = {"libmain.so", "libsmg_game.so", "libSDL3.so", "libnebula_import.so"}
    forbidden = {".iso", ".rvz", ".wbfs", ".dol", ".pak", ".sav", ".gci", ".szs",
                 ".thp", ".bcsv", ".jks", ".keystore", ".key", ".log"}
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        assert apk.testzip() is None, "ZIP CRC failure"
        for name in names:
            p = PurePosixPath(name)
            assert not p.is_absolute() and ".." not in p.parts, name
            assert p.suffix.lower() not in forbidden, f"Private game/signing/log file: {name}"
            if name.startswith("lib/"):
                assert name.startswith("lib/arm64-v8a/"), f"Unexpected ABI: {name}"
                data = apk.read(name)
                assert data[:6] == b"\x7fELF\x02\x01", f"Not ELF64 little endian: {name}"
                assert struct.unpack_from("<H", data, 18)[0] == 183, f"Not AArch64: {name}"
                phoff = struct.unpack_from("<Q", data, 32)[0]
                entsize, count = struct.unpack_from("<HH", data, 54)
                assert entsize == 56 and count > 0, f"Invalid ELF program headers: {name}"
                loads = 0
                for i in range(count):
                    header = phoff + i * entsize
                    if struct.unpack_from("<I", data, header)[0] != 1:
                        continue
                    offset, address = struct.unpack_from("<QQ", data, header + 8)
                    alignment = struct.unpack_from("<Q", data, header + 48)[0]
                    assert alignment >= 16384 and offset % 16384 == address % 16384, f"ELF lacks 16 KiB alignment: {name}"
                    loads += 1
                assert loads, f"No loadable ELF segments: {name}"
                required.discard(p.name)
        assert not required, f"Missing runtime libraries: {sorted(required)}"
        assert "AndroidManifest.xml" in names and "classes.dex" in names
        icons = verify_manifest_resources(apk, find_aapt(aapt))
    print("APK verified: four ARM64 libraries, 16 KiB ELF alignment, resolved icon resources, "
          "debuggable absent, no disc/save/key/log files")
    print("Icon files: " + ", ".join(icons))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", type=Path, help="Android SDK aapt executable (otherwise SDK env/local tools/PATH)")
    args = parser.parse_args()
    verify(args.apk, args.aapt)
