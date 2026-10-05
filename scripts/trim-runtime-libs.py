#!/usr/bin/env python3
"""裁剪运行时的库别名：每组收敛成一个实体文件，命名成「真正被需要的那个名字」。

## 问题是什么

Termux 的 .deb 里，ICU 这类库是「一个真身 + 若干符号链接」：
    libicudata.so.78.3    真身（31.6MB）
    libicudata.so.78      -> .so.78.3    符号链接
    libicudata.so         -> .so.78.3    符号链接

`dpkg-deb -x` 会保留这些链接 ✓，但打包用的 `zip` **默认会把符号链接解引用**
（除非给 -y）。于是 zip 里变成三份 31.6MB 实体 —— 一个库就白占 63MB。

（Android 侧的解包是 java.util.zip，它也不认符号链接条目，所以不能靠 -y 解决；
 必须在构建期就把链接收敛掉。）

## 做法

Android linker 按 NEEDED 里记录的**精确文件名**查找，所以每组只要保证
「被需要的那个名字」以实体文件存在即可：

  · 扫描全部 ELF 的 DT_NEEDED，得到"被需要的名字"集合（外加一份白名单）
  · 把每个库文件按「去版本后缀的基名」分组
  · 组内挑出被需要的名字 → 把真身**改名**成它（若真身本就叫这个名字则不动）
  · 若组内有两个名字都被需要 → 再复制一份（罕见，且都很小）
  · 组内其余名字（符号链接与多余的实体）一律删除

## 护栏

  · 一个被需要的名字都没扫到 → 立即中止（说明 readelf 挂了，绝不能继续删）
  · 收尾时断言：所有被需要的名字都必须以实体文件存在，否则构建失败
  · 白名单里的名字永不删除

用法: trim-runtime-libs.py <runtime 根目录>
"""
import os
import re
import subprocess
import sys

# 项目自己的「运行时命令闭包」校验（CI）要求的名单 —— 上游作者在真机
# CANNOT LINK 事故后加的。整体纳入保留：它们很小，而这条安全网价值更高。
# 裸名那几个则是「可能被 dlopen」，NEEDED 扫不到。
WHITELIST = {
    'libreadline.so.8', 'libhistory.so.8', 'libncursesw.so.6',
    'libncurses.so.6', 'libiconv.so', 'libpcre2-8.so',
    'libcrypto.so', 'libssl.so', 'libz.so', 'libsqlite3.so',
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


def base_key(name: str):
    """去版本后缀的基名：libfoo.so.1.2.3 -> libfoo"""
    m = re.match(r'^(lib.+?)\.so(\.\d+)*$', name)
    return m.group(1) if m else None


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
            if os.path.isfile(p) and is_elf(p):     # isfile 跟随链接，链接也计入
                elfs.append(p)
    print(f'扫描 {len(elfs)} 个 ELF 文件…')
    required = needed_names(elfs) | WHITELIST
    if not any(needed_names(elfs)):
        sys.exit('致命：一个被需要的库名都没扫到（readelf 不可用？）—— 中止，绝不继续删')

    # 分组：基名 -> [各形态文件名]
    groups = {}
    for f in sorted(os.listdir(libdir)):
        k = base_key(f)
        if k:
            groups.setdefault(k, []).append(f)

    freed = renamed = copied = removed = 0
    for k, forms in sorted(groups.items()):
        if len(forms) < 2:
            continue
        # 组内被需要的名字（按 NEEDED 优先级：先精确名，再白名单）
        want = [f for f in forms if f in required]
        if not want:
            # 整组都没人要 → 全删
            for f in forms:
                p = os.path.join(libdir, f)
                if not os.path.lexists(p):
                    continue
                if os.path.isfile(p) and not os.path.islink(p):
                    freed += os.path.getsize(p)
                os.remove(p)
                removed += 1
            continue
        # 挑一个"真身"（优先非链接的文件）
        real = next((f for f in forms if not os.path.islink(os.path.join(libdir, f))), None)
        if real is None:
            continue                      # 全是链接（异常形态），不动
        keep = want[0]
        if real != keep:
            os.rename(os.path.join(libdir, real), os.path.join(libdir, keep))
            renamed += 1
        # 其余被需要的名字 → 复制（罕见）
        for extra in want[1:]:
            if extra == keep:
                continue
            p = os.path.join(libdir, extra)
            if os.path.exists(p):
                os.remove(p)
            with open(os.path.join(libdir, keep), 'rb') as src, open(p, 'wb') as dst:
                while True:
                    chunk = src.read(1 << 20)
                    if not chunk:
                        break
                    dst.write(chunk)
            os.chmod(p, 0o755)
            copied += 1
        # 删掉组里其余所有形态（链接与多余实体）—— 改名过的原名已不存在，跳过
        for f in forms:
            if f in want or f == keep:
                continue
            p = os.path.join(libdir, f)
            if not os.path.lexists(p):
                continue
            if os.path.isfile(p) and not os.path.islink(p):
                freed += os.path.getsize(p)
            os.remove(p)
            removed += 1

    print(f'收敛完成：改名 {renamed}、复制 {copied}、删除 {removed}，释放 {freed/1048576:.1f} MB（未压缩）')

    # 收尾断言：所有被需要的名字都必须以实体文件存在（不是悬空链接）
    missing = []
    for n in sorted(required):
        p = os.path.join(libdir, n)
        if not os.path.exists(p) or not os.path.isfile(p):
            missing.append(n)
    # 系统库本来就不打包，不算问题
    SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
              'libandroid-spawn.so', 'libstdc++.so'}
    missing = [m for m in missing if m not in SYSTEM]
    if missing:
        sys.exit('致命：以下被需要的库在裁剪后不存在（构建中止）：\n  ' + '\n  '.join(missing))
    print('校验通过：所有被需要的库名都以实体文件存在 ✓')


if __name__ == '__main__':
    main()
