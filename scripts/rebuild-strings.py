#!/usr/bin/env python3
"""重建三套语言资源：取上游最新版，注入本 fork 新增的字符串。

为什么不用逐块解决冲突：字符串文件里我们的改动与上游的改动交错，逐块判
"取哪边"很容易漏（上一轮就把一条多行字符串截断、弄坏过 XML）。
按**字符串名**做集合运算更可靠：
    our_added = names(ours) − names(base)
    result    = theirs + {n: ours[n] for n in our_added if n not in theirs}
其中 base 是合并基线（上游在本 fork 分叉前的那份），所以 our_added 就是
本 fork 自己加的字符串（LAN 访问、Root 验证、思考强度、签名等）。

上游若已经用同名键提供了同名功能，则以**上游**为准（保留 theirs）。
"""
import re
import subprocess
import sys

BASE = subprocess.check_output(['git', 'merge-base', 'HEAD', 'origin/main'],
                               text=True).strip()
ELEM = re.compile(r'[ \t]*<string\b[^>]*>.*?</string>', re.S)

FILES = [
    'app/src/main/res/values/strings.xml',
    'app/src/main/res/values-en/strings.xml',
    'app/src/main/res/values-zh-rCN/strings.xml',
]


def show(rev, path):
    out = subprocess.run(['git', 'show', f'{rev}:{path}'],
                         capture_output=True, text=True)
    return out.stdout.replace('\r\n', '\n') if out.returncode == 0 else None


def elements(text):
    out = {}
    for m in ELEM.finditer(text or ''):
        blk = m.group(0)
        name = re.search(r'name="([^"]+)"', blk).group(1)
        out[name] = blk.rstrip('\n')
    return out


def flush(path, text):
    open(path, 'w', encoding='utf-8', newline='').write(text)


total = 0
for path in FILES:
    ours = show('HEAD', path)
    theirs = show('origin/main', path)
    if theirs is None:
        print(f'{path}: 上游没有这个文件，保留我们的')
        continue
    # 基线用「本 fork 第一次改这个文件」的前一版，而不是整个合并的基线 ——
    # 后者对上游新加的文件（如 values-zh-rCN）根本不存在，会把我们的改动漏掉。
    first = subprocess.run(['git', 'log', '--reverse', '--format=%H', 'HEAD', '--', path],
                           capture_output=True, text=True).stdout.split()
    if not first:
        print(f'{path}: 我们从未改过 → 取上游新版')
        flush(path, theirs)
        continue
    parent = subprocess.run(['git', 'rev-parse', first[0] + '^'],
                            capture_output=True, text=True).stdout.strip()
    base = show(parent, path)
    if base is None:
        print(f'{path}: 改之前不存在（我们新增的文件）→ 取上游新版 + 注入我们的')
        base = ''

    o, t, b = elements(ours), elements(theirs), elements(base)
    our_added = {n: o[n] for n in o if n not in b}
    to_inject = {n: v for n, v in our_added.items() if n not in t}
    dropped = [n for n in our_added if n in t]

    result = re.sub(r'\s*$', '\n', theirs)
    if to_inject:
        block = ('\n    <!-- 局域网访问 / Root / 思考强度（本 fork 新增） -->\n'
                 + '\n'.join(to_inject[n] for n in to_inject) + '\n')
        result = result.replace('</resources>', block + '</resources>')
    flush(path, result)
    total += len(to_inject)
    print(f'{path}: 注入 {len(to_inject)} 条'
          + (f'（{len(dropped)} 条与上游同名，以上游为准）' if dropped else ''))

print(f'完成，共注入 {total} 条')
