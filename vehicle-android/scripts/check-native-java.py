#!/usr/bin/env python3
"""Compile Java against supplied real API jars. This does not build an APK."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--android-jar", type=Path, required=True)
parser.add_argument("--car-jar", type=Path)
parser.add_argument("--pleos-aar", type=Path)
parser.add_argument("--kotlin-stdlib", type=Path)
parser.add_argument("--flavor", choices=["demo", "pleosReadOnly", "all"], default="demo")
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
flavors = ["demo", "pleosReadOnly"] if args.flavor == "all" else [args.flavor]
compiler = ["javac"] if shutil.which("javac") else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]

with tempfile.TemporaryDirectory(prefix="pitin-native-java-") as temporary:
    temp = Path(temporary)
    classpath = [args.android_jar.resolve()]
    if "pleosReadOnly" in flavors:
        for name in ["car_jar", "pleos_aar", "kotlin_stdlib"]:
            value = getattr(args, name)
            if value is None or not value.is_file():
                parser.error("pleosReadOnly requires --car-jar, --pleos-aar and --kotlin-stdlib")
        digest = hashlib.sha256(args.pleos_aar.read_bytes()).hexdigest()
        if digest != "e11fdfe498cad3ecc0d5a2a6992b0c4a4d135195f1455018127bc069fd5ef937":
            parser.error("AAR differs from the verified Vehicle 2.3.2 artifact; re-audit its signatures first")
        with zipfile.ZipFile(args.pleos_aar) as archive:
            sdk = temp / "vehicle-classes.jar"
            sdk.write_bytes(archive.read("classes.jar"))
        classpath += [args.car_jar.resolve(), args.kotlin_stdlib.resolve(), sdk]
    for flavor in flavors:
        output = temp / flavor
        output.mkdir()
        config = output / "BuildConfig.java"
        simulation = "true" if flavor == "demo" else "false"
        config.write_text("package ai.pitin.vehicle; public final class BuildConfig { "
                          "public static final boolean DEBUG=true; "
                          f"public static final boolean SIMULATED_VEHICLE={simulation}; }}")
        sources = sorted((project / "app/src/main/java").rglob("*.java"))
        sources += sorted((project / f"app/src/{flavor}/java").rglob("*.java")) + [config]
        import os
        subprocess.run(compiler + ["-encoding", "UTF-8", "-cp", os.pathsep.join(map(str, classpath)),
                       "-d", str(output)] + list(map(str, sources)), check=True)
        print(f"PASS: {flavor} Java source compilation against supplied API binaries.")
print("Not checked here: Gradle resolution, manifest/resource merging, APK packaging, emulator or vehicle runtime.")
