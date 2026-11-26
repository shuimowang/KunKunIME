#!/usr/bin/env bash
# 生成「开源许可」页的 Rust 依赖清单：app/src/main/assets/licenses/rust-crates.txt
#
# 只收 Android 产物里真正链进去的（-e normal、aarch64-linux-android 目标），dev / build
# 依赖不分发、不列。清风自己的 wind-* crate 不列——它们属于输入法核心，许可见同页的核心条目。
# 依赖有增减（升级核心、改 Cargo.lock）后重跑本脚本并提交结果。
#
# 用法：scripts/gen-rust-licenses.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/licenses/rust-crates.txt"
cd "$ROOT/rust"
# 先写临时文件、成功再替换：cargo 失败时不能留下一个只有表头的清单还让人以为生成好了
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
{
    echo "# 由 scripts/gen-rust-licenses.sh 生成，勿手改。每行：crate 版本<TAB>许可证"
    cargo tree -e normal --target aarch64-linux-android -p wind-android \
        --prefix none --format '{p}|{l}' \
        | sed 's/ (\*)$//; s/ (proc-macro)$//' \
        | sort -u \
        | awk -F'|' '$1 !~ /^wind-/ { sub(/ \(.*\)$/, "", $1); printf "%s\t%s\n", $1, ($2 == "" ? "见 crate 源码" : $2) }'
} > "$TMP"
mv "$TMP" "$OUT"
echo "已写入 $OUT（$(($(wc -l < "$OUT") - 1)) 个 crate）"
