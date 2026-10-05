#!/usr/bin/env python3
"""解决 diff3 格式的冲突块：按给定的选择序列，把每处冲突替换成「我们」或「上游」。

冲突格式（git merge-file --diff3）：
    <<<<<<< /tmp/mrg/ours.txt
    OURS
    ||||||| /tmp/mrg/base.txt
    BASE
    =======
    THEIRS
    >>>>>>> /tmp/mrg/theirs.txt

用法: resolve_conflicts.py <文件> <选择序列>
      选择序列用 o/t 表示每处冲突取 ours 还是 theirs，例如 "tto"
      特别地 "all-o" / "all-t" 表示全部取一边
"""
import re
import sys

# base 段可能整段缺失 —— 双方各自新增的文件（基线里没有）就是这样，
# 此时 git merge-file 不会输出 ||||||| 段。两种形态都要认。
PAT = re.compile(
    r'<<<<<<< /tmp/mrg/ours\.txt\n(.*?)\n'
    r'(?:\|\|\|\|\|\|\| /tmp/mrg/base\.txt\n.*?\n)?'
    r'={7}\n(.*?)\n'
    r'>>>>>>> /tmp/mrg/theirs\.txt\n', re.S)


def main() -> None:
    path, spec = sys.argv[1], sys.argv[2]
    s = open(path, encoding='utf-8').read()
    matches = list(PAT.finditer(s))
    if not matches:
        print(f'{path}: 没有冲突块')
        return
    if spec == 'all-o':
        picks = ['o'] * len(matches)
    elif spec == 'all-t':
        picks = ['t'] * len(matches)
    else:
        picks = list(spec)
    if len(picks) != len(matches):
        sys.exit(f'{path}: 选择数 {len(picks)} 与冲突数 {len(matches)} 不匹配')

    out = []
    last = 0
    for m, pick in zip(matches, picks):
        out.append(s[last:m.start()])
        out.append(m.group(1) if pick == 'o' else m.group(2))
        last = m.end()
    out.append(s[last:])
    open(path, 'w', encoding='utf-8', newline='').write(''.join(out))
    print(f'{path}: 解决 {len(matches)} 处（{"".join(picks)}）')


if __name__ == '__main__':
    main()
