#!/usr/bin/env python3
"""给本 fork 升版本号（app/build.gradle.kts 里的 versionCode / versionName）。

版本号规则：我们用自己的序列，不跟上游的 versionName 走 ——
  versionCode 逐次 +1（Android 靠它判断"是不是新版本"）
  versionName X.Y.Z-lan → X.Y.(Z+1)-lan

为什么需要这个脚本：上游每次发版都会改 app/build.gradle.kts 的版本号，
合并时必然与我们的版本号冲突。上游同步流程用它把版本号"盖"回我们的序列，
从而把这类冲突变成可自动解决的。

用法: bump-lan-version.py            # 升一位并打印新版本号
      bump-lan-version.py --print    # 只打印当前版本号
"""
import re
import sys

PATH = 'app/build.gradle.kts'


def main() -> None:
    src = open(PATH, encoding='utf-8').read()

    code_m = re.search(r'versionCode = (\d+)', src)
    name_m = re.search(r'versionName = "([^"]+)"', src)
    if not code_m or not name_m:
        sys.exit('bump-lan-version: 在 app/build.gradle.kts 里找不到 versionCode/versionName')

    if '--print' in sys.argv:
        print(name_m.group(1))
        return

    code = int(code_m.group(1)) + 1
    name = name_m.group(1)
    m = re.match(r'^(\d+)\.(\d+)\.(\d+)', name)
    if not m:
        sys.exit(f'bump-lan-version: 版本号格式不认识: {name}')
    new_name = f'{m.group(1)}.{m.group(2)}.{int(m.group(3)) + 1}-lan'

    src = src.replace(f'versionCode = {code_m.group(1)}', f'versionCode = {code}', 1)
    src = src.replace(f'versionName = "{name}"', f'versionName = "{new_name}"', 1)
    open(PATH, 'w', encoding='utf-8', newline='').write(src)
    print(new_name)


if __name__ == '__main__':
    main()
