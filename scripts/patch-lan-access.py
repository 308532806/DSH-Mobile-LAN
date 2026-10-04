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

# --- 2) 免 token 访问（可选，默认关）-----------------------------------------
# 上游的浏览器认证是两段：
#   a) authorizeIndex() —— 首屏 HTML：?token= 换持久 cookie，否则 401
#   b) requestRejection() —— /api：先过 Host/Origin 栅栏，再查 cookie，否则 401
# 用户希望同一网络内直接 http://ip:3080/ 打开，所以 gated 掉这两处的拒绝分支。
#
# 刻意**保留** Host/Origin 栅栏（isTrustedApiRequest）：它挡的是 DNS rebinding
# ——恶意网页把域名解析到本机地址来打这个 API。局域网设备正常访问时 Host 本来
# 就在信任名单里，保留它对使用者零成本，去掉它才是真的把门拆了。
CONN_REL = ('lib', 'node_modules', '@deepseek-ai', 'dsh-client-connection', 'lib', 'index.js')
CONN_INDEX_ANCHOR = '\tauthorizeIndex(req, res) {\n'
CONN_REJECT_ANCHOR = (
    '\trequestRejection(request) {\n'
    '\t\tif (!isTrustedApiRequest(request, this.trustedHosts)) return 403;\n'
)

# --- 2b) 浏览器端：让局域网地址也能读写设置 ---------------------------------
# 上游在客户端按 location.hostname 判定 isLoopback，并据此决定设置的持久化方式：
#     const persistence = ctx.remote.$host.isLoopback ? "host" : "memory";
# 非环回页面拿到 "memory" —— 设置**连读都不读**（mirror 的 load/ensure 直接 return），
# 于是模型供应商页显示 "加载提供商目录失败: settings are unavailable in this browser"，
# 也就无法修改供应商、拿不到模型目录里的思考强度。
#
# 对本项目来说这是致命的：局域网设备访问的全部意义就是用网页端干活，却连模型都配不了。
# 所以把「私有网段 IPv4 字面量」也算作 host-local：
#   · 只有引擎自己监听该地址时，浏览器才可能从它加载页面（局域网开关关闭时不会）；
#   · 真正挡住跨站攻击的是服务端的 Host/Origin 栅栏（Origin.host 必须等于 Host），
#     那层原样保留 —— 别的局域网主机上托管的页面依然打不到这个引擎。
# 注意这是**浏览器端**判定，改的是 dist 里的客户端包，与 DSH_LAN_ACCESS 无关。
CLIENT_REL = ('lib', 'node_modules', '@deepseek-ai', 'dsh-client-connection', 'lib', 'client.js')
CLIENT_OLD = '''\t\tfunction isLoopbackHostname(hostname) {
\t\t\tif (hostname === "localhost" || hostname === "[::1]") return true;
\t\t\tconst parts = hostname.split(".");
\t\t\treturn parts.length === 4 && parts[0] === "127" && parts.every((part) => /^\\d{1,3}$/.test(part) && Number(part) <= 255);
\t\t}'''
CLIENT_NEW = '''\t\tfunction isLoopbackHostname(hostname) {
\t\t\tif (hostname === "localhost" || hostname === "[::1]") return true;
\t\t\tconst parts = hostname.split(".");
\t\t\tif (!(parts.length === 4 && parts.every((part) => /^\\d{1,3}$/.test(part) && Number(part) <= 255))) return false;
\t\t\tconst [a, b] = [Number(parts[0]), Number(parts[1])];
\t\t\tif (a === 127) return true;
\t\t\t/* ''' + MARK + ''' 局域网访问：私有网段字面量同样视为 host-local，
\t\t\t   否则局域网设备拿不到可写的设置（供应商/模型都改不了）。
\t\t\t   跨站请求仍由服务端 Host/Origin 栅栏拦截。 */
\t\t\treturn a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31);
\t\t}'''

# --- 3) 就绪日志补打局域网地址（仅在开关打开时） -------------------------------
APP_REL = ('lib', 'node_modules', '@deepseek-ai', 'dsh-web-app', 'lib', 'index.js')
APP_OLD = ('\t\t\tif (config.printUrl) console.log(`dsh web: ${authenticatedUrl}'
           '${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);')
APP_NEW = ('\t\t\tif (config.printUrl) console.log(`dsh web: ${authenticatedUrl}'
           '${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);\n'
           '\t\t\t/* ' + MARK + ' 局域网访问：只有开关打开时才把带 token 的局域网地址\n'
           '\t\t\t   打进日志（App 侧正则捞取后展示 / 复制给其它设备）。\n'
           '\t\t\t   networkInterfaces 是本模块已从 node:os 导入的绑定，直接用即可\n'
           '\t\t\t   （注意不是 node:net —— net 没有这个函数）。\n'
           '\t\t\t   地址选择必须按「接口名 + 网段」打分，不能按枚举顺序取第一个：\n'
           '\t\t\t   真机上开着代理时会有 tun0（如 172.19.0.1），它同样落在私有段，\n'
           '\t\t\t   按顺序取会把 VPN 地址当成局域网地址显示，别的设备根本连不上。 */\n'
           '\t\t\tif (config.printUrl && process.env.DSH_LAN_ACCESS === "1") {\n'
           '\t\t\t\ttry {\n'
           '\t\t\t\t\tconst VIRTUAL_RE = /^(tun|tap|ppp|wg|ipsec|utun|rmnet|dummy|sit|gre|ip6tnl|clat|hwsim)/i;\n'
           '\t\t\t\t\tconst LAN_RE = /^(wlan|eth|ap|softap|rndis|usb|swlan)/i;\n'
           '\t\t\t\t\tconst cands = [];\n'
           '\t\t\t\t\tfor (const [nicName, addrs] of Object.entries(networkInterfaces() ?? {})) {\n'
           '\t\t\t\t\t\tfor (const a of addrs ?? []) {\n'
           '\t\t\t\t\t\t\tif (!a || a.family !== "IPv4" || a.internal) continue;\n'
           '\t\t\t\t\t\t\tif (a.address.startsWith("169.254.")) continue;\n'
           '\t\t\t\t\t\t\tcands.push({ nicName, ip: a.address });\n'
           '\t\t\t\t\t\t}\n'
           '\t\t\t\t\t}\n'
           '\t\t\t\t\tconst score = (c) => {\n'
           '\t\t\t\t\t\tlet s = 0;\n'
           '\t\t\t\t\t\tif (LAN_RE.test(c.nicName)) s += 100;\n'
           '\t\t\t\t\t\telse if (VIRTUAL_RE.test(c.nicName)) s -= 100;\n'
           '\t\t\t\t\t\tif (c.ip.startsWith("192.168.")) s += 30;\n'
           '\t\t\t\t\t\telse if (c.ip.startsWith("10.")) s += 20;\n'
           '\t\t\t\t\t\telse if (/^172\\.(1[6-9]|2\\d|3[01])\\./.test(c.ip)) s += 10;\n'
           '\t\t\t\t\t\treturn s;\n'
           '\t\t\t\t\t};\n'
           '\t\t\t\t\tconst pick = cands.length === 0 ? void 0\n'
           '\t\t\t\t\t\t: cands.slice().sort((x, y) => score(y) - score(x))[0].ip;\n'
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


def patch_no_auth(root, changed):
    """免 token 访问：gated 掉首屏与 /api 的 cookie 校验（Host/Origin 栅栏保留）。"""
    p = os.path.join(root, *CONN_REL)
    if not os.path.isfile(p):
        sys.exit(f'{MARK} fatal: {os.path.join(*CONN_REL)} not found')
    s = read(p)
    if 'DSH_LAN_NO_AUTH' in s:
        print(f'{MARK} no-auth guards already present ({p})')
        return
    if CONN_INDEX_ANCHOR not in s or CONN_REJECT_ANCHOR not in s:
        sys.exit(f'{MARK} fatal: client-connection auth anchors not found in {p}; '
                 f'upstream changed the browser auth shape')

    guard_index = (
        CONN_INDEX_ANCHOR
        + '\t\t/* ' + MARK + ' 免 token 访问（默认关）：打开后同一网络内直接用\n'
        + '\t\t   http://<ip>:<port>/ 打开即可，不需要 URL 里带 ?token=。\n'
        + '\t\t   注意这里只绕过"浏览器会话认证"，/api 的 Host/Origin 栅栏照旧生效。 */\n'
        + '\t\tif (process.env.DSH_LAN_NO_AUTH === "1") return true;\n'
    )
    s = s.replace(CONN_INDEX_ANCHOR, guard_index, 1)

    guard_reject = (
        '\trequestRejection(request) {\n'
        '\t\tif (!isTrustedApiRequest(request, this.trustedHosts)) return 403;\n'
        '\t\t/* ' + MARK + ' 免 token 访问：跳过 cookie 校验（栅栏已在上一行过掉） */\n'
        '\t\tif (process.env.DSH_LAN_NO_AUTH === "1") return void 0;\n'
    )
    s = s.replace(CONN_REJECT_ANCHOR, guard_reject, 1)

    write(p, s)
    changed.append(p)
    print(f'{MARK} no-auth guards injected ({p})')


def patch_client_settings(root, changed):
    """让局域网地址打开时也能读写设置（上游按 isLoopback 把非环回降级为内存模式）。"""
    p = os.path.join(root, *CLIENT_REL)
    if not os.path.isfile(p):
        sys.exit(f'{MARK} fatal: {os.path.join(*CLIENT_REL)} not found')
    s = read(p)
    if '[dsh-android-lan]' in s and 'a === 192 && b === 168' in s:
        print(f'{MARK} client settings gate already relaxed ({p})')
        return
    if CLIENT_OLD not in s:
        sys.exit(f'{MARK} fatal: client isLoopbackHostname anchor not found in {p}; '
                 f'upstream changed the browser-side loopback detection')
    write(p, s.replace(CLIENT_OLD, CLIENT_NEW, 1))
    changed.append(p)
    print(f'{MARK} client settings gate relaxed for private LAN literals ({p})')


def main():
    raw = os.environ.get('DSH_PATCH_TARGET', '')
    if not raw:
        sys.exit('rejected: DSH_PATCH_TARGET not set by caller')
    root = os.path.realpath(raw)
    changed = []
    patch_yml(root, changed)
    patch_log_line(root, changed)
    patch_no_auth(root, changed)
    patch_client_settings(root, changed)
    print(f'{MARK} done: {len(changed)} file(s) rewritten')


if __name__ == '__main__':
    main()
