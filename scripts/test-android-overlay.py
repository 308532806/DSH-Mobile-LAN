#!/usr/bin/env python3
"""校验 kotlin 源码里那份 android-overlay.yml 的内容能解析成合法 YAML。

为什么需要：overlay 是 `EngineConfig.ensureAndroidOverlay()` 用 Kotlin 原始字符串
（`\"\"\" ... \"\"\".trimMargin()`）拼出来的，运行时才写到磁盘上给引擎读。
**一个丢掉的换行就会让整份 YAML 失效，引擎直接起不来** —— 而且编译期完全看不出来
（原始字符串里少个换行仍是合法 Kotlin）。

实测事故（v1.3.7）：合并上游时吃掉了一个换行，`|- id: sandbox-policy` 被接到
上一行注释末尾，引擎报
    failed to parse overlay .../android-overlay.yml: YAMLException:
    end of the stream or a document separator is expected (14:1)
然后无限重启。本脚本把这类问题拦在构建期。

做法：把源码里的原始字符串抠出来 → 模拟 Kotlin 的 trimMargin()（去掉每行前导空白
与第一个 '|'）→ 用 YAML 解析。`!!js` 是引擎自定义标签，注册成允许后再解析。
"""
import re
import sys

SRC = 'app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt'

# !!js 是 cordis 配置方言里的自定义标签（值为 JS 表达式），这里只做占位，
# 目的是验证 YAML 结构（缩进/文档分隔/键值），不执行任何表达式。
JS_TAG = 'tag:yaml.org,2002:js'


def main() -> None:
    try:
        import yaml
    except ImportError:
        print('SKIP: 缺 pyyaml，无法校验 overlay')
        return

    src = open(SRC, encoding='utf-8').read()
    m = re.search(r'val body = """\n(.*?)\n\s*\|"""\.trimMargin\(\)', src, re.S)
    if not m:
        sys.exit(f'FATAL: 在 {SRC} 里找不到 overlay 原始字符串（上游改了生成方式？）')

    lines = []
    for line in m.group(1).split('\n'):
        s = line.lstrip()
        lines.append(s[1:] if s.startswith('|') else line.strip())
    text = '\n'.join(lines)

    class JsTag(yaml.SafeLoader):
        pass

    JsTag.add_constructor(JS_TAG, lambda loader, node: f'<js:{node.value}>')
    schema = yaml.SafeLoader

    try:
        docs = [d for d in yaml.load_all(text, Loader=JsTag) if d is not None]
    except Exception as e:
        print('生成的 overlay 内容：')
        print('\n'.join(f'  {i:>3} | {l}' for i, l in enumerate(text.split('\n'), 1)))
        sys.exit(f'FATAL: overlay YAML 解析失败 —— 引擎会起不来：{e}')

    if not docs:
        sys.exit('FATAL: overlay 解析出 0 个文档')

    ids = []
    for d in docs:
        if isinstance(d, dict) and 'insert' in d:
            ids += [x.get('id') for x in d['insert']]
        elif isinstance(d, list):
            ids += [r.get('id') for r in d if isinstance(r, dict) and r.get('id')]
        elif isinstance(d, dict) and d.get('id'):
            ids.append(d['id'])

    print(f'overlay YAML 合法：{len(docs)} 个文档，patch 行 id = {ids}')
    # 关键行必须仍在（上游若改名，这里会先于用户发现）
    for need in ('sandbox-policy',):
        if need not in ids:
            sys.exit(f'FATAL: overlay 里缺少必需的 patch 行：{need}')
    print('必需行齐全 ✓')


if __name__ == '__main__':
    main()
