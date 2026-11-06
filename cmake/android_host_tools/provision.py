"""Download and verify the pinned Android dependencies and Windows host compiler.

Run with Python 3.12+; output lives only under Ports/.tools/galaxy-deps and
Ports/.tools/llvm-mingw. Re-running reuses archives after hashing them again.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request
import zipfile

PORTS = Path(__file__).resolve().parents[3]
DEPS = PORTS / ".tools/galaxy-deps"
LLVM = PORTS / ".tools/llvm-mingw"
HEADERS = {"User-Agent": "Nebula-Android-provision"}


def api(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=90) as response:
        return json.load(response)


def download(url, digest, cache):
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        temporary = cache.with_suffix(cache.suffix + ".part")
        with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=180) as response:
            with temporary.open("wb") as output:
                while data := response.read(1024 * 1024):
                    output.write(data)
        temporary.replace(cache)
    actual = hashlib.file_digest(cache.open("rb"), "sha256").hexdigest()
    if actual != digest:
        raise RuntimeError(f"SHA256 mismatch for {cache}: {actual} != {digest}")
    print(f"verified {cache.name}: {actual}", flush=True)


def provision(item):
    name, url, digest, destination = item
    cache = (LLVM if name == "llvm-mingw" else DEPS) / "archives" / (name + "-" + url.rsplit("/", 1)[1])
    download(url, digest, cache)
    marker = destination / ".nebula-archive-sha256"
    if marker.exists() and marker.read_text().strip() == digest:
        return
    destination.mkdir(parents=True, exist_ok=True)
    # Official source archives and the LLVM release contain a single root folder.
    if zipfile.is_zipfile(cache):
        with zipfile.ZipFile(cache) as archive:
            for member in archive.infolist():
                relative = Path(*Path(member.filename).parts[1:])
                if str(relative) == ".":
                    continue
                target = destination / relative
                if not target.resolve().is_relative_to(destination.resolve()):
                    raise RuntimeError("Archive path escapes destination")
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(member) as source, target.open("wb") as output:
                        import shutil
                        shutil.copyfileobj(source, output)
    else:
        with tarfile.open(cache) as archive:
            members = []
            for member in archive.getmembers():
                parts = Path(member.name).parts[1:]
                if not parts:
                    continue
                member.name = Path(*parts).as_posix()
                members.append(member)
            archive.extractall(destination, members=members, filter="data")
    marker.write_text(digest + "\n")


def main():
    # This exact official release is pinned; its GitHub API digest authenticates
    # the archive against the metadata returned over TLS.
    release = api("https://api.github.com/repos/mstorsjo/llvm-mingw/releases/tags/20261104")
    compiler = next(asset for asset in release["assets"]
                    if asset["name"] == "llvm-mingw-20261104-ucrt-x86_64.zip")
    expected_compiler_hash = "552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f"
    if compiler.get("digest") != "sha256:" + expected_compiler_hash:
        raise RuntimeError("LLVM release metadata digest changed")
    items = [
        ("llvm-mingw", compiler["browser_download_url"], expected_compiler_hash, LLVM),
        ("SDL3", "https://github.com/libsdl-org/SDL/releases/download/release-3.4.18/SDL3-3.4.18.tar.gz",
         "9c75cf16330322c217dedd2e0609f1124f1b54b8633e763467b4684d0f4334a3", DEPS / "SDL3-3.4.18"),
        ("Vulkan-Headers", "https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/vulkan-sdk-1.4.350.1.tar.gz",
         "6d1bb65e49520344cc0a48af3dc02e993781efff14c7ebdcb8ae9fa23ddf7e83", DEPS / "Vulkan-Headers-vulkan-sdk-1.4.350.1"),
        ("volk", "https://github.com/zeux/volk/archive/refs/tags/vulkan-sdk-1.4.350.1.tar.gz",
         "078a9411298e4e0f60f5f5398c890783427c25a414619294ca8e69587bbd5eae", DEPS / "volk-vulkan-sdk-1.4.350.1"),
        ("VMA", "https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator/archive/refs/tags/v3.4.0.tar.gz",
         "822aa850c6ce77346ae96a8a1d351d52e77e85929f35363849a0a4e638e0a2a1", DEPS / "VulkanMemoryAllocator-v3.4.0"),
        ("glslang", "https://github.com/KhronosGroup/glslang/archive/refs/tags/16.6.0.tar.gz",
         "9c09b901149c729df745057dafa815278aaa101b84d2b6e14f16a42de52f97f2", DEPS / "glslang-src"),
    ]
    # Independent downloads/extractions may run concurrently. Nested glslang
    # dependencies are populated afterward so extraction cannot overwrite them.
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(provision, items))
    for item in [
        ("SPIRV-Tools", "https://github.com/KhronosGroup/SPIRV-Tools/archive/ef96ed763b43b59b33b31b362f09a02b729fa1c9.tar.gz",
         "82c62146083fd558735a3171cf97cfc47903ca7d368482e87f94bd44883c0f00", DEPS / "glslang-src/External/spirv-tools"),
        ("SPIRV-Headers", "https://github.com/KhronosGroup/SPIRV-Headers/archive/496543121ce6419f23d6fa5d7194ba66c36212d2.tar.gz",
         "a9bb9c48713245eacf97cc539b6f1d45405a92d8813f8b82e635f0a085ee9898", DEPS / "glslang-src/External/spirv-tools/external/spirv-headers"),
    ]:
        provision(item)
        items.append(item)
    (DEPS / "manifest.json").write_text(json.dumps([
        {"name": n, "url": u, "sha256": h, "destination": str(p)} for n, u, h, p in items
    ], indent=2) + "\n")
    print(f"Dependencies: {DEPS}\nCompiler: {LLVM / 'bin/x86_64-w64-mingw32-clang++.exe'}", flush=True)


if __name__ == "__main__":
    main()
