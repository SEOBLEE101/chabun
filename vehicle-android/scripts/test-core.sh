#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/chabun-vehicle-tests.XXXXXX")"
trap 'rm -rf -- "$build_dir"' EXIT
sources=(
  "$project_dir/app/src/main/java/ai/pitin/vehicle/SafetyGate.java"
  "$project_dir/app/src/main/java/ai/pitin/vehicle/RestSession.java"
  "$project_dir/tests/SafetyGateTest.java"
)
if command -v javac >/dev/null 2>&1; then
  javac -encoding UTF-8 -d "$build_dir" "${sources[@]}"
else
  java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d "$build_dir" "${sources[@]}"
fi
java -ea -cp "$build_dir" ai.pitin.vehicle.SafetyGateTest
