#!/usr/bin/env python3
"""D5: 让 dsh WebUI 可由局域网访问（默认仍是只监听本机）。

上游默认只监听 127.0.0.1，并且刻意把 `dsh --profile web --host 0.0.0.0` 做成
CLI 层的硬错误（web-app/startup.js 里 program.error，理由写在源码：
"it would expose remote code execution to the network"）。这个判断对桌面场景是对的，
但本项目要的是「单用户、自己家的局域网、自己手机上的 Agent」，所以提供显式开关。

【关键】开关必须能真正决定监听面，而不是「构建时焊死 0.0.0.0」。做法：
webserver 行的 host 改成读环境变量 —— App 在启动引擎时按用户开关注入
`DSH_LAN_ACCESS=1`，不注入就是上游原行为（仅回环）。这样：

  · 默认（开关关）= 与上游逐字节等价的只监听本机；
  · 开关开       = 监听所有网卡，且 App 会重启引擎让新值生效。

CLI 那层闸门**原样保留**：用户手动敲 `--host 0.0.0.0` 依旧被拒。
我们改的是"部署默认值"，不是"把安全闸门拆了"。

第二个问题：局域网设备首次访问必须带 `?token=`（上游的浏览器会话认证）。
上游日志行末尾本来就有 `(LAN: ...)` 段，但它是**引擎启动那一刻**的网卡快照 ——
之后切 Wi-Fi / 开热点，IP 就变了，那段地址失效（且连接层的信任名单也是那一刻
快照的，换 IP 后 /api 会被 Host 栅栏拒绝 → 页面能开但用不了）。
所以 App 侧的策略是「网卡变化就重启引擎」（LanNetworkWatcher），
让监听面、信任名单、日志地址三者一起刷新，而不是在引擎里绕开上游的校验。

本补丁只负责：
  1. webserver 绑定 host 受 DSH_LAN_ACCESS 控制；
  2. 开关打开时就绪日志里补打一行带 token 的局域网地址
     （`dsh web: lan url: http://<ip>:<port>/?token=...`），App 直接正则捞取；
     开关关闭时该行不打印，日志里不会出现局域网地址。

幂等：以 MARK 做哨兵；上游结构变了会 loud 报错退出，绝不静默跳过 ——
静默跳过会让用户拿到一个"构建成功但连不上"的假版本。
"""
import os
import sys

MARK = "[dsh-android-lan]"

# --- 1) web-app 的 cordis.patch.yml：host 受环境变量控制 ----------------------
PATCHYML_REL = ('lib', 'node_modules', '@deepseek-ai', 'dsh-web-app', 'cordis.patch.yml')
YML_OLD = "host: !!js ctx.webStartup.host ?? '127.0.0.1'"
# 用双引号包住表达式：里面有三元运算符的 " : "，裸标量会被 YAML 误判成映射。
# !!js 是 scalar 标签（resolve 只要求 typeof data === "string"），引号形式一样生效。
YML_NEW = (
    'host: !!js "process.env.DSH_LAN_ACCESS === \'1\' '
    "? '0.0.0.0' : (ctx.webStartup.host ?? '127.0.0.1')\""
)

# --- 2) 就绪日志补打局域网地址（仅在开关打开时） -------------------------------
APP_REL = ('lib', 'node_modules', '@deepseek-ai', 'dsh-web-app', 'lib', 'index.js')
APP_OLD = ('\t\t\tif (config.printUrl) console.log(`dsh web: ${authenticatedUrl}'
           '${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);')
APP_NEW = ('\t\t\tif (config.printUrl) console.log(`dsh web: ${authenticatedUrl}'
           '${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);\n'
           '\t\t\t/* ' + MARK + ' 局域网访问：只有开关打开时才把带 token 的局域网地址\n'
           '\t\t\t   打进日志（App 侧正则捞取后展示 / 复制给其它设备）。\n'
           '\t\t\t   networkInterfaces 是本模块已从 node:os 导入的绑定，直接用即可\n'
           '\t\t\t   （注意不是 node:net —— net 没有这个函数）。 */\n'
           '\t\t\tif (config.printUrl && process.env.DSH_LAN_ACCESS === "1") {\n'
           '\t\t\t\ttry {\n'
           '\t\t\t\t\tconst cands = Object.values(networkInterfaces() ?? {}).flat()\n'
           '\t\t\t\t\t\t.filter((i) => i !== void 0 && i.family === "IPv4" && !i.internal)\n'
           '\t\t\t\t\t\t.map((i) => i.address)\n'
           '\t\t\t\t\t\t.filter((a) => !a.startsWith("169.254."));\n'
           '\t\t\t\t\tconst pick = cands.find((a) => a.startsWith("192.168.") || a.startsWith("10.")\n'
           '\t\t\t\t\t\t|| /^172\\.(1[6-9]|2\\d|3[01])\\./.test(a)) ?? cands[0];\n'
           '\t\t\t\t\tif (pick !== void 0) {\n'
           '\t\t\t\t\t\tconst lanFull = connectionCtx.connection.authenticatedUrl(\n'
           '\t\t\t\t\t\t\t`http://${pick}:${String(port)}`);\n'
           '\t\t\t\t\t\tconsole.log(`dsh web: lan url: ${lanFull}`);\n'
           '\t\t\t\t\t}\n'
           '\t\t\t\t} catch (_e) { /* 取不到网卡就静默：日志少一行，UI 仍走环回地址 */ }\n'
           '\t\t\t}')


def read(path):
    with open(path, 'r', encoding='utf-8') as f:
        return f.read()


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


def patch_yml(root, changed):
    p = os.path.join(root, *PATCHYML_REL)
    if not os.path.isfile(p):
        sys.exit(f'{MARK} fatal: {os.path.join(*PATCHYML_REL)} not found')
    s = read(p)
    if 'DSH_LAN_ACCESS' in s:
        print(f'{MARK} webserver host already gated on DSH_LAN_ACCESS ({p})')
        return
    if YML_OLD not in s:
        sys.exit(f'{MARK} fatal: web-app host default anchor not found in {p}; '
                 f'upstream changed the webserver config shape')
    write(p, s.replace(YML_OLD, YML_NEW, 1))
    changed.append(p)
    print(f'{MARK} webserver bind host -> gated on DSH_LAN_ACCESS ({p})')


def patch_log_line(root, changed):
    p = os.path.join(root, *APP_REL)
    if not os.path.isfile(p):
        sys.exit(f'{MARK} fatal: {os.path.join(*APP_REL)} not found')
    s = read(p)
    if 'dsh web: lan url:' in s:
        print(f'{MARK} lan url log line already injected ({p})')
        return
    if APP_OLD not in s:
        sys.exit(f'{MARK} fatal: "dsh web:" announce anchor not found in {p}; '
                 f'upstream changed the readiness print')
    write(p, s.replace(APP_OLD, APP_NEW, 1))
    changed.append(p)
    print(f'{MARK} lan url log line injected ({p})')


def main():
    raw = os.environ.get('DSH_PATCH_TARGET', '')
    if not raw:
        sys.exit('rejected: DSH_PATCH_TARGET not set by caller')
    root = os.path.realpath(raw)
    changed = []
    patch_yml(root, changed)
    patch_log_line(root, changed)
    print(f'{MARK} done: {len(changed)} file(s) rewritten')


if __name__ == '__main__':
    main()
