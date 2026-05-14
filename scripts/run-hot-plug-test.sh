#!/usr/bin/env bash
# 在 macOS 本地 JVM 跑语言包热插拔验证（不走 Quarkus / Podman）。
#
# 用法：
#   cd aster-lang-core
#   ./scripts/run-hot-plug-test.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M2="$HOME/.m2/repository"
ALC_VERSION="0.0.1"
JACKSON_VERSION="2.18.2"

# 验证前置 jar
require() {
  if [ ! -f "$1" ]; then
    echo "FATAL: missing $1" >&2
    echo "       run \`./gradlew publishToMavenLocal\` in the corresponding module first" >&2
    exit 1
  fi
}

CORE_JAR="$M2/cloud/aster-lang/aster-lang-core/$ALC_VERSION/aster-lang-core-$ALC_VERSION.jar"
EN_JAR="$M2/cloud/aster-lang/aster-lang-en/$ALC_VERSION/aster-lang-en-$ALC_VERSION.jar"
ZH_JAR="$M2/cloud/aster-lang/aster-lang-zh/$ALC_VERSION/aster-lang-zh-$ALC_VERSION.jar"
DE_JAR="$M2/cloud/aster-lang/aster-lang-de/$ALC_VERSION/aster-lang-de-$ALC_VERSION.jar"
J_DB="$M2/com/fasterxml/jackson/core/jackson-databind/$JACKSON_VERSION/jackson-databind-$JACKSON_VERSION.jar"
J_CO="$M2/com/fasterxml/jackson/core/jackson-core/$JACKSON_VERSION/jackson-core-$JACKSON_VERSION.jar"
J_AN="$M2/com/fasterxml/jackson/core/jackson-annotations/$JACKSON_VERSION/jackson-annotations-$JACKSON_VERSION.jar"

require "$CORE_JAR"
require "$EN_JAR"
require "$ZH_JAR"
require "$DE_JAR"
require "$J_DB"
require "$J_CO"
require "$J_AN"

# 注意：zh / de jar 出现在 m2 中是预期的——它们是"准备插入的耗材"，
# 而非启动 classpath 的一部分。启动 CP 只有 core + en。
CP="$CORE_JAR:$EN_JAR:$J_DB:$J_CO:$J_AN"

cd "$REPO_ROOT"

echo "[runner] starting JVM (Java $(java -version 2>&1 | head -1))"
echo "[runner] startup classpath = aster-lang-core + aster-lang-en (no zh/de)"
exec java --source 25 -cp "$CP" scripts/HotPlugTest.java
