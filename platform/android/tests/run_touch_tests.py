# SPDX-License-Identifier: GPL-3.0-only
"""JDK 17 input tests; optional Android SDK compile check. No device or Gradle required.

python platform/android/tests/run_touch_tests.py --jdk <jdk17> [--android-jar <android.jar>]
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path)
    parser.add_argument("--android-jar", type=Path)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    sources = here.parent / "app/src/main/java/dev/nebula/galaxy"
    javac = str(args.jdk / "bin/javac") if args.jdk else shutil.which("javac")
    java = str(args.jdk / "bin/java") if args.jdk else shutil.which("java")
    if not javac or not java:
        parser.error("JDK 17 or later required; pass --jdk")
    with tempfile.TemporaryDirectory(prefix="galaxy-touch-") as temp:
        work = Path(temp)
        stubs = work / "android/content"
        stubs.mkdir(parents=True)
        (stubs / "Context.java").write_text(
            "package android.content; public abstract class Context { public static final int MODE_PRIVATE=0; "
            "public abstract SharedPreferences getSharedPreferences(String key,int mode); }", encoding="utf-8")
        (stubs / "SharedPreferences.java").write_text(
            "package android.content; public interface SharedPreferences { String getString(String key,String fallback); "
            "Editor edit(); interface Editor { Editor putString(String key,String value); Editor remove(String key); void apply(); } }",
            encoding="utf-8")
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                        str(stubs / "Context.java"), str(stubs / "SharedPreferences.java"),
                        str(sources / "TouchLayouts.java"), str(here / "TouchInputHostTest.java")], check=True)
        subprocess.run([java, "-cp", str(classes), "dev.nebula.galaxy.TouchInputHostTest"], check=True)
        if args.android_jar:
            resource = work / "R.java"
            resource.write_text("package dev.nebula.galaxy; final class R { static final class string { "
                                "static final int arrange_hint=1,arrange_ok=2; } }", encoding="utf-8")
            android_classes = work / "android-classes"
            android_classes.mkdir()
            subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", str(args.android_jar),
                            "-d", str(android_classes), str(resource), str(sources / "TouchLayouts.java"),
                            str(sources / "TouchControlsView.java"),
                            str(here.parent / "app/src/androidTest/java/dev/nebula/galaxy/TouchControlsInstrumentation.java")], check=True)
            print("PASS: production touch View and device Instrumentation compile against Android SDK")


if __name__ == "__main__":
    main()
