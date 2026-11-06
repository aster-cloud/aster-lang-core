#!/usr/bin/env bash
# 语料快照对比：在 BASE（默认 origin/main）与当前工作区分别运行 CorpusSnapshotTest，
# 逐字节比较 canonicalize 文本 / AST JSON / Core JSON。输入清单固定为 classpath 上
# aster-lang-test 的 corpus/ 与本仓 src/test/resources（两侧都只取 BASE 已有的资源条目对比）。
# 用法：scripts/corpus-snapshot-diff.sh [BASE]；额外 gradle 参数经 GRADLE_ARGS 传入；
# EXPECT_SOURCES=N 时还校验 BASE 源数。退出码：0 无差异，1 有差异，2 快照为空/缺源。
set -euo pipefail
BASE="${1:-origin/main}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'git -C "$ROOT" worktree remove --force "$WORK/base" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

git -C "$ROOT" worktree add --detach -q "$WORK/base" "$BASE"
# BASE 尚无快照测试与属性转发时，从当前工作区带过去（只加测试，不动被测源码）
cp "$ROOT/src/test/java/aster/core/canonicalizer/CorpusSnapshotTest.java" "$WORK/base/src/test/java/aster/core/canonicalizer/"
cp "$ROOT/build.gradle.kts" "$WORK/base/build.gradle.kts"

run() { (cd "$1" && ./gradlew test --tests 'aster.core.canonicalizer.CorpusSnapshotTest' -Dcorpus.snapshot.out="$2" ${GRADLE_ARGS:-} -q); }
run "$WORK/base" "$WORK/base.txt"
run "$ROOT" "$WORK/head.txt"

# 校验：两侧快照非空，且 BASE 的每个源在 HEAD 中出现次数一致（HEAD 允许多出新增夹具）
count() { grep -c '^=== ' "$1" || true; }
nb=$(count "$WORK/base.txt"); nh=$(count "$WORK/head.txt")
echo "sources: base=$nb head=$nh"
if [ "$nb" -eq 0 ] || [ "$nh" -eq 0 ]; then echo "快照为空" >&2; exit 2; fi
grep '^=== ' "$WORK/base.txt" | sort | uniq -c | sort > "$WORK/base-names.txt"
grep '^=== ' "$WORK/head.txt" | sort | uniq -c | sort > "$WORK/head-names.txt"
missing=$(comm -23 "$WORK/base-names.txt" "$WORK/head-names.txt")
if [ -n "$missing" ]; then echo "HEAD 缺少 / 数量不一致的源：" >&2; echo "$missing" >&2; exit 2; fi
if [ -n "${EXPECT_SOURCES:-}" ] && [ "$nb" -ne "$EXPECT_SOURCES" ]; then echo "BASE 源数 $nb ≠ 期望 $EXPECT_SOURCES" >&2; exit 2; fi

# 只比较 BASE 已有的源（新增夹具等不计入）
keep() { awk -v names="$1" 'BEGIN{while((getline l < names)>0) ok[l]=1} /^=== /{skip=!($0 in ok)} !skip' "$2"; }
grep '^=== ' "$WORK/base.txt" | sort -u > "$WORK/names.txt"
if diff <(keep "$WORK/names.txt" "$WORK/base.txt") <(keep "$WORK/names.txt" "$WORK/head.txt") > "$WORK/diff.txt"; then
  echo "ZERO DIFF"
else
  head -40 "$WORK/diff.txt" >&2
  echo "DIFF FOUND ($(grep -c '^[<>]' "$WORK/diff.txt") 行)" >&2; exit 1
fi
