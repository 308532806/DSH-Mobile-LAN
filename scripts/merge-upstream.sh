#!/bin/sh
# 重做三方合并：把「合并基线 / 我们 / 上游」三方都归一化成 LF 后再 merge-file。
# CRLF vs LF 的整文件假冲突会消失，只剩真正需要人工判断的内容冲突。
#
# 注意：双方**各自新增**的文件（不在 BASE 里）在 git show 时会失败 ——
# 那种情况基线要当空文件处理，否则临时文件会残留上一个文件的内容，
# 合出乱七八糟的结果（第一版脚本就踩了这个）。
set -u
cd "$(dirname "$0")/.." || exit 1

BASE=$(git merge-base HEAD origin/main)
echo "合并基线: $BASE"
mkdir -p /tmp/mrg

show_or_empty() {
  git show "$1:$2" 2>/dev/null | sed 's/\r$//' || : > /tmp/mrg/empty
}

for f in $(git diff --name-only --diff-filter=U); do
  git show "$BASE:$f" 2>/dev/null | sed 's/\r$//' > /tmp/mrg/base.txt || : > /tmp/mrg/base.txt
  git show "HEAD:$f" 2>/dev/null | sed 's/\r$//' > /tmp/mrg/ours.txt || : > /tmp/mrg/ours.txt
  git show "origin/main:$f" 2>/dev/null | sed 's/\r$//' > /tmp/mrg/theirs.txt || : > /tmp/mrg/theirs.txt
  [ -s /tmp/mrg/base.txt ] || : > /tmp/mrg/base.txt
  [ -s /tmp/mrg/ours.txt ] || : > /tmp/mrg/ours.txt
  [ -s /tmp/mrg/theirs.txt ] || : > /tmp/mrg/theirs.txt

  if git merge-file -p --diff3 \
       /tmp/mrg/ours.txt /tmp/mrg/base.txt /tmp/mrg/theirs.txt > /tmp/mrg/out.txt; then
    echo "  干净合并: $f"
  else
    n=$(grep -c '^<<<<<<<' /tmp/mrg/out.txt)
    echo "  仍有冲突: $f （$n 处）"
  fi
  cp /tmp/mrg/out.txt "$f"
  git add "$f"
done
echo "完成。剩余冲突块总数: $(git diff --cached | grep -c '^+<<<<<<<' || true)"
