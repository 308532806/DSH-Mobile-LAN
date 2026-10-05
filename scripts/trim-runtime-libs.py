#!/usr/bin/env python3
"""裁剪运行时的库别名：每组收敛成「被需要的名字以实体文件存在」。

## 问题是什么

Termux 的 .deb 里，同一个库常有多个名字（一个真身 + 若干符号链接）：

    libicudata.so.78.3    真身（31.6MB）
    libicudata.so.78      -> .so.78.3     符号链接
    libicudata.so         -> .so.78.3     符号链接

`dpkg-deb -x` 会保留链接，但打包用的 `zip` **默认把符号链接解引用**（除非 -y），
于是 zip 里变成三份 31.6MB 实体 —— 一个库白占 63MB。
（Android 侧解包是 java.util.zip，它也不认符号链接条目，所以不能靠 -y 解决。）

## 做法（不依赖顺序，按"保证"写）

Android linker 按 NEEDED 里记录的**精确文件名**查找，所以只要保证
「被需要的每个名字」都以实体文件存在即可，其余形态全删：

  1. 扫描全部 ELF 的 DT_NEEDED → needed；再加一份白名单（闭包校验要求的名字 +
     可能被 dlopen 的裸名）→ required
  2. 按「去版本后缀的基名」把 lib/ 分组
  3. 每组：
       · 找出一份"真内容"（组内任一真实文件；全是链接就解析到实际目标）
       · 保留名 keep 优先取「真正被 NEEDED 的名字」，白名单名只作兜底
       · 把真内容**落地到 keep**（必要时先删掉同名链接再复制，避免写穿链接）
       · 组内其余 required 名字 → 各复制一份实体
       · 其余名字（链接、多余实体）→ 删除
  4. 断言：required 里每个名字都必须是**实体文件**（不是悬空链接）

## 踩过的坑（都有实测依据）

  · 往已存在的符号链接写文件会**写穿**到链接目标（悬空链接 exists() 为假，
    直接 open(p,'wb') 会去创建它的目标）→ 必须先 lexists 删除再写
  · 保留名不能按字母序取：libz 组里 libz.so 会抢在 libz.so.1 前面
  · 也不能用 want[1:] 取"其余"：keep 未必是 want[0]，会漏掉 want[0]
  · readelf 不可用时 needed 为空 → 会把几乎所有库删光，必须硬性中止

用法: trim-runtime-libs.py <runtime 根目录>
"""
import os
import re
import shutil
import subprocess
import sys

# 裸名可能被 dlopen（NEEDED 扫不出来）；外加项目「运行时命令闭包」校验名单
# （上游作者在真机 CANNOT LINK 事故后加的）。整体保留：体积很小，安全网价值更高。
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
            if os.path.isfile(p) and is_elf(p):
                elfs.append(p)
    print(f'扫描 {len(elfs)} 个 ELF 文件…', flush=True)
    needed = needed_names(elfs)
    if not needed:
        sys.exit('致命：一个被需要的库名都没扫到（readelf 不可用？）—— 中止，绝不继续删')
    required = needed | WHITELIST
    print(f'被 NEEDED 的名字 {len(needed)} 个，加上白名单共 {len(required)} 个', flush=True)

    groups = {}
    for f in sorted(os.listdir(libdir)):
        k = base_key(f)
        if k:
            groups.setdefault(k, []).append(f)

    deleted, copied = [], []
    for k, forms in sorted(groups.items()):
        paths = {f: os.path.join(libdir, f) for f in forms}
        # 1) 找一份真实内容
        real = next((f for f in forms
                     if not os.path.islink(paths[f]) and os.path.isfile(paths[f])), None)
        if real is None:                      # 组内全是链接 → 解析到实际目标
            for f in forms:
                t = os.path.realpath(paths[f])
                if os.path.isfile(t):
                    real = t
                    break
        if real is None:
            print(f'  跳过 {k}：组内没有可用的真实内容', flush=True)
            continue
        real_path = real if os.path.isabs(real) else paths[real]

        want = [f for f in forms if f in required]
        if not want:
            for f in forms:
                os.remove(paths[f])
                deleted.append(f)
            continue

        # 每个被需要的名字都落成**实体文件**。
        # 关键：即使它是符号链接、且指向的内容就是要留的那份，也要复制成实体 ——
        # 因为打包用的 zip 会解引用链接，留着链接等于没省（这一点我第一版搞反了，
        # 写了"realpath 相同就跳过"，结果所有别名都原样留着，省了 0 字节）。
        for f in want:
            p = paths[f]
            if not os.path.islink(p) and os.path.isfile(p):
                continue
            tmp = p + '.trimtmp'
            shutil.copy2(real_path, tmp)
            if os.path.lexists(p):
                os.remove(p)
            os.rename(tmp, p)
            copied.append(f)

        # 组内其余形态（含真身本身，如果它的名字不被需要）一律删除
        for f in forms:
            if f in want:
                continue
            p = paths[f]
            if os.path.lexists(p):
                os.remove(p)
                deleted.append(f)

    print(f'收敛：落地 {len(copied)}、删除 {len(deleted)}', flush=True)
    if deleted:
        print('  删除：' + ' '.join(deleted), flush=True)

    # 断言：required 里每个名字都必须是实体文件。
    # 系统库（Android 自带）本来就不打包，不算问题。
    SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
              'libandroid-spawn.so', 'libstdc++.so', 'libcutils.so'}
    bad = []
    for n in sorted(required):
        if n in SYSTEM:
            continue
        p = os.path.join(libdir, n)
        if os.path.islink(p) or not os.path.isfile(p):
            bad.append(n)
    if bad:
        print('--- lib/ 现状 ---', flush=True)
        print('  ' + ' '.join(sorted(os.listdir(libdir))), flush=True)
        sys.exit('致命：以下被需要的库不是实体文件（构建中止）：\n  ' + '\n  '.join(bad))
    print('校验通过：所有被需要的库名都是实体文件 ✓', flush=True)


if __name__ == '__main__':
    main()
