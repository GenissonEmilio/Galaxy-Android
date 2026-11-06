# SPDX-License-Identifier: GPL-3.0-only
"""JDK-only first-run graphics profile regression; no Android device or Gradle.

python platform/android/tests/run_render_profile_tests.py --jdk <jdk17>
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    helper = here.parent / "app/src/main/java/dev/nebula/galaxy/AndroidRenderProfile.java"
    javac = str(args.jdk / "bin/javac") if args.jdk else shutil.which("javac")
    java = str(args.jdk / "bin/java") if args.jdk else shutil.which("java")
    if not javac or not java:
        parser.error("JDK 17 or later required; pass --jdk")
    with tempfile.TemporaryDirectory(prefix="galaxy-render-profile-") as temp:
        work = Path(temp)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                        str(helper), str(here / "AndroidRenderProfileHostTest.java")], check=True)
        subprocess.run([java, "-cp", str(classes), "dev.nebula.galaxy.AndroidRenderProfileHostTest", str(work)], check=True)


if __name__ == "__main__":
    main()
