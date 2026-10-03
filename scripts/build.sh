#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  if [[ -d "$PROJECT_ROOT/.local/jdk-17/Contents/Home" ]]; then
    export JAVA_HOME="$PROJECT_ROOT/.local/jdk-17/Contents/Home"
  elif [[ -d "$PROJECT_ROOT/.local/jdk-17" ]]; then
    export JAVA_HOME="$PROJECT_ROOT/.local/jdk-17"
  else
    printf '%s\n' '请先将 JAVA_HOME 设置为 JDK 17 的路径。' >&2
    exit 1
  fi
fi
cd "$PROJECT_ROOT"
if [[ $# -eq 0 ]]; then
  set -- assembleDebug testDebugUnitTest lintDebug
fi
exec ./gradlew "-Dorg.gradle.java.home=$JAVA_HOME" "$@"
