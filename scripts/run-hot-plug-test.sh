#!/usr/bin/env bash
# 在本地 JVM 跑语言包热插拔验证（不走 Quarkus / Podman）。
#
# 用法：
#   cd aster-lang-core
#   ./scripts/run-hot-plug-test.sh
#
# classpath、语言包制品名与版本全部由 Gradle 从共享 catalog 解析（见 build.gradle.kts 的
# hotPlugTest 任务），本脚本只是薄封装；不要在这里手写 ~/.m2 路径或版本号。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

echo "[runner] startup classpath = aster-lang-core + aster-lang-locales-en (no zh/de)"
exec ./gradlew --quiet hotPlugTest "$@"
