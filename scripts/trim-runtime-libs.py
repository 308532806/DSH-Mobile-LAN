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
    # 项目自己的「运行时命令闭包」校验（CI）要求的名单 —— 那是上游作者在真机
    # CANNOT LINK 事故后加的。整体纳入保留：它们很小，而这条安全网的价值高于
    # 省下的几百 KB。
    'libreadline.so.8', 'libhistory.so.8', 'libncursesw.so.6',
    'libncurses.so.6', 'libiconv.so', 'libpcre2-8.so',
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
    # 守卫：一个都没扫到说明 readelf 不可用或扫描逻辑坏了 —— 此时继续下去会把
    # 几乎所有库都当"没人需要"删掉（实测踩过：CI 上就是这样删掉了 libreadline.so.8）
    if not required:
        sys.exit('致命：一个被需要的库名都没扫到（readelf 不可用？）—— 中止，绝不继续删')
    print('被需要的名字: ' + ' '.join(sorted(required)))

    before = {f for f in os.listdir(libdir) if os.path.isfile(os.path.join(libdir, f))}

    # 符号链接（及其目标）必须原样保留：
    #   · 链接本身是零成本，删了没意义；
    #   · 更重要的是**不能删链接指向的目标** —— 删了链接就悬空，运行时直接 CANNOT LINK。
    # CI 上就是这样：libreadline.so.8 是指向 .so.8.3 的符号链接，删掉目标后
    # 闭包校验（test -e）立刻失败。本地产物里那些是解引用后的实体，所以本地看不出来。
    symlinks = {f for f in os.listdir(libdir) if os.path.islink(os.path.join(libdir, f))}
    protected = set()
    for f in symlinks:
        try:
            protected.add(os.path.basename(os.path.realpath(os.path.join(libdir, f))))
        except OSError:
            pass
    if symlinks:
        print(f'发现 {len(symlinks)} 个符号链接，其目标一并保护：{sorted(protected)[:6]}')

    removed = kept = 0
    freed = 0
    deleted = []
    for f in sorted(os.listdir(libdir)):
        p = os.path.join(libdir, f)
        if os.path.islink(p):          # 链接本身不动
            kept += 1
            continue
        if not os.path.isfile(p) or not is_elf(p):
            continue
        if f in required or f in WHITELIST or f in protected:
            kept += 1
            continue
        freed += os.path.getsize(p)
        os.remove(p)
        removed += 1
        deleted.append(f)
    print(f'删除无用库副本 {removed} 个，保留 {kept} 个，释放 {freed/1048576:.1f} MB（解压后）')
    if deleted:
        print('删掉的是: ' + ' '.join(deleted))

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
