"""Bridge GNU/MSVCRT Rust startup objects to LLVM-MinGW, including @response DLL links."""
import shlex
import subprocess
import sys
from pathlib import Path
root = Path(__file__).resolve().parents[2] / '.tools'
support = root / 'rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/lib/self-contained'
def inspected_args(args, seen=None):
    seen = set() if seen is None else seen
    for arg in args:
        if arg.startswith('@'):
            path = Path(arg[1:]).resolve()
            if path in seen:
                raise ValueError(f'Recursive linker response file: {path}')
            seen.add(path)
            yield from inspected_args(shlex.split(path.read_text(encoding='utf-8-sig'), posix=True), seen)
            seen.remove(path)
        else:
            yield arg

def main():
    global root, support
    args = sys.argv[1:]
    if args[:1] == ['--tools-dir']:
        root = Path(args[1]).resolve()
        args = args[2:]
        support = root / 'rustup/toolchains/stable-x86_64-pc-windows-gnu/lib/rustlib/x86_64-pc-windows-gnu/lib/self-contained'
    if args[:1] == ['--']:
        args = args[1:]
    shared = any(arg in ('-shared', '--shared', '--dll') for arg in inspected_args(args))
    crt = support / ('dllcrt2.o' if shared else 'crt2.o')
    # Only inspect the expansion: keep Clang's response-file interpretation intact.
    return subprocess.call([str(root / 'llvm-mingw/bin/x86_64-w64-mingw32-gcc.exe'),
                            '-nostartfiles', str(crt), '-L' + str(support), *args])
if __name__ == '__main__':
    sys.exit(main())
