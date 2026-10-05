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
    needed = needed_names(elfs)
    if not needed:
        sys.exit('致命：一个被需要的库名都没扫到（readelf 不可用？）—— 中止，绝不继续删')
    required = needed | WHITELIST
    print(f'被 NEEDED 的名字 {len(needed)} 个，加上白名单共 {len(required)} 个')

    # 分组：基名 -> [各形态文件名]
    groups = {}
    for f in sorted(os.listdir(libdir)):
        k = base_key(f)
        if k:
            groups.setdefault(k, []).append(f)

    freed = renamed = copied = removed = 0
    trace = {}                      # 基名 -> 决策记录（失败时打印，便于定位）
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
        # 保留名优先取「真正被 NEEDED 的」：白名单里的名字（如裸名 libz.so）
        # 只是保险，不该抢走真身 —— 按字母序取第一个会让 libz.so 抢在
        # libz.so.1 前面，把真身改名成裸名、而真正被需要的 libz.so.1 变成悬空链接。
        keep = next((f for f in want if f in needed), want[0])
        trace[k] = f'forms={forms} want={want} real={real} keep={keep}'
        if real != keep:
            os.rename(os.path.join(libdir, real), os.path.join(libdir, keep))
            renamed += 1
        # 其余被需要的名字 → 实体复制。注意要遍历**全部** want 而不是 want[1:] ——
        # keep 未必是 want[0]（它优先取真正被 NEEDED 的名字），用切片会漏掉 want[0]，
        # 那个名字既不会被复制也不会被删，最后留一个悬空链接（实测踩过）。
        for extra in want:
            if extra == keep:
                continue
            p = os.path.join(libdir, extra)
            # 必须用 lexists：悬空符号链接 os.path.exists() 为假，而直接 open(p,'wb')
            # 会**写穿链接**去创建它的目标，链接本身仍是悬空（实测踩过）
            if os.path.lexists(p):
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
        print('--- 各分组决策 ---')
        for k, v in sorted(trace.items()):
            print(f'  {k}: {v}')
        print('--- lib/ 现有文件 ---')
        print('  ' + ' '.join(sorted(os.listdir(libdir))))
        sys.exit('致命：以下被需要的库在裁剪后不存在（构建中止）：\n  ' + '\n  '.join(missing))
    print('校验通过：所有被需要的库名都以实体文件存在 ✓')


if __name__ == '__main__':
    main()
