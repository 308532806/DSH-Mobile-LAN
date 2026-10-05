#!/usr/bin/env python3
"""D6: 给「手工声明的模型」补一套默认思考档位（受开关控制，默认关）。

背景：dsh 把「思考强度」当作**每个模型自己声明的能力**：
  · 官方适配器（deepseek）会给出档位，所以模型选择器里有思考强度可选；
  · 自定义/手工声明的模型走 pi-ai，而 pi-ai 的逻辑是
    「模型没声明 reasoningEfforts → 报成只有 off 一档 → 界面干脆不显示这个控件」
    （dsh-llm-pi-ai/lib/index.js 的 resolveModelReasoning()）。
    上游的模型设置界面**没有**声明 reasoningEfforts 的表单，所以自定义模型的
    思考强度选择是永远出不来的 —— 除非手改配置文件。

本补丁给这类"没声明"的模型补一套通行档位（low/medium/high，off 保持"不发参数"
即不思考），从而让所有模型都有思考强度可选。**开关默认关闭**：
  · 对不支持该参数的供应商，选了档位会被对方拒绝（报错可见、可改回 off）；
  · 因此必须由用户显式打开，而不是默认替所有人改变请求形态。

对应 App 侧开关：设置 → 模型 → 默认开启思考强度（注入 DSH_DEFAULT_REASONING=1）。

幂等：以 MARK 做哨兵；上游结构变了会 loud 报错退出，不静默跳过。
"""
import os
import sys

MARK = '[dsh-android-reasoning]'
REL = ('lib', 'node_modules', '@deepseek-ai', 'dsh-llm-pi-ai', 'lib', 'index.js')

ANCHOR = ('function resolveModelReasoning(provider, entry, base) {\n'
          '\tconst efforts = entry.reasoningEfforts;\n'
          '\tif (efforts === void 0) return { reasoning: base?.reasoning ?? false };\n')

REPLACEMENT = (
    'function resolveModelReasoning(provider, entry, base) {\n'
    '\tconst efforts = entry.reasoningEfforts;\n'
    '\t/* ' + MARK + ' 默认思考档位（开关：DSH_DEFAULT_REASONING=1）。\n'
    '\t   没声明 reasoningEfforts 的模型（含全部手工声明的）本来会被报成"只有 off"，\n'
    '\t   界面随即隐藏思考强度选择。打开开关后补一套通行档位：\n'
    '\t   · off 有意不写进 map —— pi-ai 把"缺席的 off"读作"支持，发送时不带该参数"，\n'
    '\t     这正是"不思考"该有的请求形态；\n'
    '\t   · low/medium/high 用档位名本身作为 wire 值（OpenAI 兼容接口的通行拼写）；\n'
    '\t   · xhigh/max 不写 —— 缺席即不支持（pi-ai 对这两档的缺省语义与基础档相反）。\n'
    '\t   注意：**官方目录已声明过能力的模型一概不动**（base?.reasoning 为真就直接透传）——\n'
    '\t   它们有自己的 wire 拼写，用通用档位名覆盖会把能用的模型弄坏。 */\n'
    '\tif (efforts === void 0) {\n'
    '\t\tif (base?.reasoning) return { reasoning: base.reasoning };\n'
    '\t\tif (process.env.DSH_DEFAULT_REASONING === "1") {\n'
    '\t\t\treturn {\n'
    '\t\t\t\treasoning: true,\n'
    '\t\t\t\tthinkingLevelMap: { low: "low", medium: "medium", high: "high" }\n'
    '\t\t\t};\n'
    '\t\t}\n'
    '\t\treturn { reasoning: base?.reasoning ?? false };\n'
    '\t}\n'
)


def main() -> None:
    raw = os.environ.get('DSH_PATCH_TARGET', '')
    if not raw:
        sys.exit('rejected: DSH_PATCH_TARGET not set by caller')
    root = os.path.realpath(raw)
    path = os.path.join(root, *REL)
    if not os.path.isfile(path):
        sys.exit(f'{MARK} fatal: {os.path.join(*REL)} not found')
    s = open(path, encoding='utf-8').read()
    if 'DSH_DEFAULT_REASONING' in s:
        print(f'{MARK} already patched ({path})')
        return
    if ANCHOR not in s:
        sys.exit(f'{MARK} fatal: resolveModelReasoning anchor not found in {path}; '
                 f'upstream changed the reasoning capability shape')
    open(path, 'w', encoding='utf-8', newline='').write(s.replace(ANCHOR, REPLACEMENT, 1))
    print(f'{MARK} default reasoning levels injected ({path})')


if __name__ == '__main__':
    main()
