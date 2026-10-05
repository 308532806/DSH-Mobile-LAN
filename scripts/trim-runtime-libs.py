#!/usr/bin/env python3
"""裁剪运行时里「谁都不需要」的库副本 —— 纯删减，不改任何被需要的文件。

## 为什么会多出来

Termux 的 .deb 里，同一个库通常有三个名字：
    libicudata.so        -> 软链
    libicudata.so.78     -> 软链
    libicudata.so.78.3     真身
而 Android 的 SELinux 不允许普通应用创建软/硬链接，解包器只能**把软链解引用成三份实体**。
于是 libicudata 一个库就占 94 MB（三份 × 31.6 MB）。

## 依据

Android linker 是按 **NEEDED 里记录的精确文件名** 去 lib/ 找文件的。
本脚本扫描运行时里所有 ELF 的 DT_NEEDED，得到"真正被需要的名字"集合；
不在该集合里的 lib/ 文件就是纯占地方，删掉。

## 保险

- 保留一份白名单（裸名 libcrypto.so / libssl.so / libz.so / libsqlite3.so）：
  这几个存在被 dlopen 的可能，NEEDED 扫不到，花几 MB 买个安心。
- 删完之后**再扫一遍**：任何 NEEDED 名字找不到对应文件 → 直接失败并退出非 0。
  构建因此会红，而不是等到手机上 CANNOT LINK。

用法: trim-runtime-libs.py <runtime 根目录>
"""
import os
import re
import subprocess
import sys

# 裸名可能被 dlopen（NEEDED 扫不出来），保留它们
WHITELIST = {
    'libcrypto.so', 'libssl.so', 'libz.so', 'libsqlite3.so',
    'libc.so', 'libm.so', 'libdl.so',
    # 项目自己的「运行时命令闭包」校验（CI）要求这两个存在 —— 那是上游作者在
    # 真机 CANNOT LINK 事故后加的名单。按 NEEDED 扫描其实无人依赖，但体积很小
    # （压缩后合计约 0.6MB），保留以维持既有安全网不变。
    'libhistory.so.8', 'libncurses.so.6',
}


def is_elf(path: str) -> bool:
    try:
        with open(path, 'rb') as f:
            return f.read(4) == b'\x7fELF'
    except Exception:
        return False


def needed_names(paths):
    names = set()
    for p in paths:
        try:
            out = subprocess.run(['readelf', '-d', p], capture_output=True,
                                 text=True, timeout=60).stdout
        except Exception:
            continue
        names |= set(re.findall(r'\(NEEDED\)\s+Shared library: \[([^\]]+)\]', out))
    return names


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit('用法: trim-runtime-libs.py <runtime 根目录>')
    root = os.path.realpath(sys.argv[1])
    libdir = os.path.join(root, 'lib')
    if not os.path.isdir(libdir):
        sys.exit(f'找不到 {libdir}')

    elfs = []
    for d in ('bin', 'lib'):
        base = os.path.join(root, d)
        if not os.path.isdir(base):
            continue
        for f in os.listdir(base):
            p = os.path.join(base, f)
            if os.path.isfile(p) and is_elf(p):
                elfs.append(p)
    print(f'扫描 {len(elfs)} 个 ELF 文件…')
    required = needed_names(elfs)
    print(f'被需要的库名共 {len(required)} 个')

    before = {f for f in os.listdir(libdir) if os.path.isfile(os.path.join(libdir, f))}

    removed = kept = 0
    freed = 0
    for f in sorted(os.listdir(libdir)):
        p = os.path.join(libdir, f)
        if not os.path.isfile(p) or not is_elf(p):
            continue
        if f in required or f in WHITELIST:
            kept += 1
            continue
        freed += os.path.getsize(p)
        os.remove(p)
        removed += 1
    print(f'删除无用库副本 {removed} 个，保留 {kept} 个，释放 {freed/1048576:.1f} MB（解压后）')

    # 断言 1（硬性）：原本就在 lib/ 里、且被需要的文件，删减后必须还在。
    # 这正是"不要删错"的核心保证 —— 违反说明删除规则有 bug，构建必须红。
    after = set(os.listdir(libdir))
    lost = sorted((required & before) - after)
    if lost:
        sys.exit('致命：删掉了被需要的库（删除规则有 bug，构建中止）：\n  ' + '\n  '.join(lost))

    # 断言 2（提示）：被需要但既没打包、也不是已知系统库的名字 —— 只警告不拦截。
    # 例：libuv.so 需要 libandroid-spawn.so，但 libuv.so 本身没有任何东西需要它
    #     （node 静态链接了 libuv），所以缺了也从不生效。
    SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
              'libcutils.so', 'libstdc++.so', 'libz.so'}
    unresolved = sorted(n for n in required if n not in after and n not in SYSTEM)
    if unresolved:
        print('提示：以下名字被 NEEDED 但运行时里没有（历史上也未打包）：')
        for n in unresolved:
            print(f'   {n}')
    print('校验通过：原本被需要的库都还在 ✓')


if __name__ == '__main__':
    main()
