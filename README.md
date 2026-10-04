# DSH Mobile · 局域网版

**在手机上跑 DeepSeek Harness，同一 Wi-Fi 下的任何设备用浏览器直接打开使用。**

[![CI](https://github.com/308532806/DSH-Mobile-LAN/actions/workflows/android-build.yml/badge.svg)](https://github.com/308532806/DSH-Mobile-LAN/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/release-v1.3.2--lan-blue)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

---

## 这个版本做了什么

原项目 [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)
把完整的 dsh 引擎搬到了 Android 上，但和桌面版一样**只监听 `127.0.0.1`** ——
只有这台手机自己能访问。想在平板上看 Agent 的结果，得把手机屏幕怼到脸上，
或者截图发过去。

这个 fork 加了一件事：**一个开关，打开后局域网内所有设备都能用浏览器直接访问**。

```
手机（跑 Agent）                同一 Wi-Fi 下的其它设备
┌──────────────┐                ┌──────────────┐
│  DSH Mobile  │  192.168.1.42  │   平板 / 电脑  │
│  引擎 :3080  │◄──────────────►│   浏览器打开   │
└──────────────┘                └──────────────┘
```

打开方式：**设置 → 权限中心 → 局域网访问 → 开启**。
开关一变，App 自动重启引擎让监听面生效，状态栏与通知栏随后直接显示访问地址。

> 首次访问的地址里带一个一次性 token（`http://192.168.1.42:3080/?token=…`），
> 点开即自动换取长期会话 cookie，**不需要在任何设备上登录**。
> 地址可以直接复制（点工具栏的「局域网」按钮）。

**嫌 token 麻烦？** 局域网开启后，下面会多出一个「免 token 访问」开关。
打开它之后地址就是干净的 `http://192.168.1.42:3080/`，在平板/电脑上直接敲即可，
不用再带那一长串 token。

> ⚠️ 这个开关等于撤掉唯一的访问凭据：同一网络里**任何**能访问到该端口的设备
> 都能直接指挥这个 Agent（读写文件、执行命令、消耗模型额度）。所以它默认关闭、
> 开启要过风险确认，只建议在家里自己的 Wi-Fi 或自己的热点下用。
> 防 DNS rebinding 的 Host/Origin 栅栏仍然保留 —— 去掉的只是"口令"这一层。

---

## 它是怎么实现的

改动很克制，尽量不碰上游的引擎代码。

### 1. 让引擎的监听面受开关控制

上游把 `dsh web --host 0.0.0.0` 做成了 CLI 层的硬错误，理由写在源码里：

> `--host 0.0.0.0 is intentionally not supported yet for safety: it would expose remote code execution to the network`

这个判断对"随便开个服务器"是对的，但本项目要的是**单用户、自己家局域网、自己手机上的 Agent**。
所以做法不是把闸门拆了，而是改**配置树的默认值**：

```yaml
# scripts/patch-lan-access.py 注入的形态
host: !!js "process.env.DSH_LAN_ACCESS === '1' ? '0.0.0.0' : (ctx.webStartup.host ?? '127.0.0.1')"
```

- **开关关（默认）**：引擎仍然只监听 `127.0.0.1`，与上游行为完全一致；
- **开关开**：App 启动引擎时注入 `DSH_LAN_ACCESS=1`，引擎监听所有网卡；
- CLI 那层的 `program.error` **原样保留** —— 手动敲 `--host 0.0.0.0` 依旧被拒。

构建期有断言卡住这个补丁（`unzip -p runtime.zip | grep`），
补丁没生效 CI 直接红掉，不会发出一个"构建成功但连不上"的假版本。

### 2. 把局域网地址打进引擎日志，App 捞出展示

就绪日志里会多一行（仅在开关打开时）：

```
dsh web: lan url: http://192.168.1.42:3080/?token=y7J8-jKAAv68FFnnzat1PXpxgcvSBphrCIUbBxHDsyQ
```

App 从 `engine.log` 里正则捞取，展示在设置页 / 状态栏 / 通知栏，并支持一键复制。
地址选择优先级：接口名（`wlan/eth/ap/rndis/usb` 视为真网卡，`tun/tap/ppp/wg/rmnet`
视为隧道或蜂窝）+ 网段（`192.168/16` → `10/8` → `172.16/12`）。

> ⚠️ **为什么不能"取第一个私有地址"**：手机上开着代理应用（Clash / sing-box /
> NekoBox 等）时会多出一个 `tun0`，地址形如 `172.19.0.1` —— 它也落在 RFC1918
> 私有段里。按枚举顺序取第一个私有地址，就会把 **VPN 的地址**当成局域网地址显示，
> 别的设备照这个地址连必然失败。v1.3.0 就是这个 bug，v1.3.1 修掉了，
> 并加了回归测试（`scripts/test-lan-pick.js`，CI 每次构建都会跑）。

### 3. 换网络就重启引擎

引擎在**启动那一刻**干两件和网卡绑死的事：把当时的局域网 IP 拍进「浏览器信任名单」
（上游防 DNS rebinding 的 Host 栅栏），以及打印上面那行地址。切 Wi-Fi、开热点之后
IP 会变，如果只把新 IP 显示给用户，设备打开会**页面能加载但所有 `/api` 请求被打回** ——
比直接打不开更难排查。

所以 App 监听网络变化，发现 IP 和引擎启动时用的那个不一致就重启一次引擎，
让监听面、信任名单、日志地址三者一起重新快照。**不去绕上游的安全校验**，
而是让引擎重新观察世界。

### 4. 默认关闭，开启需过风险确认

开启时弹确认框，明确写清楚：拿到地址的人就能操作这个 Agent（读写文件、执行命令、
消耗模型 API），只应在可信网络里开启，随时可以关掉。

`network-security-config` 也只对回环 + RFC1918 私有网段放行明文 HTTP，
没有开 `cleartextTrafficPermitted="true"` 的全局口子。

### 5. 免 token 访问（可选）

上游的浏览器认证有两道闸门，都在 `dsh-client-connection`：

- `authorizeIndex()` —— 首屏 HTML：`?token=` 换持久 cookie，否则 401
- `requestRejection()` —— 所有 `/api` 请求：先过 Host/Origin 栅栏，再查 cookie，否则 401

「免 token 访问」把这两处的**拒绝分支** gated 掉（`DSH_LAN_NO_AUTH=1`），
但**保留** `isTrustedApiRequest` 的 Host/Origin 栅栏 —— 它挡的是 DNS rebinding
（恶意网页把域名解析到本机地址来打这个 API），局域网设备正常访问时 Host 本来就在
信任名单里，保留它对使用者零成本。去掉的只是"口令"，不是把门整个拆了。

不设该环境变量时行为与上游逐字一致（401 / 303 都不变）。

### 6. Root 检测修正

原项目的 root 检测只 stat 一组写死的 su 路径。真机实测（OPPO / Android 12 / Magisk alpha）
上这些路径**全都不存在**：su 由 magic mount 挂到 `/product/bin/su` 与 `/debug_ramdisk/su`
（都是指向 `./magisk` 的符号链接），`/system/bin/su` 根本没建立
—— 于是"手机明明有 root，软件却检测不到"。

修正三点：

1. **补全路径**：加入 `/product/bin/su`（新 Magisk 常见落点）、`/debug_ramdisk/su`
   （Magisk 24+ 真实二进制）、APatch 的 `/data/adb/ap/bin/su` 等；
2. **删掉两个根本不是 su 的条目**：`/system/bin/busybox`（装了 busybox ≠ 有 root，
   更糟的是它会被 `findSu()` 当成 su 交给 Root 模式启动引擎）和 `/system/bin/su.d`
   （那是脚本目录，不是可执行文件）；
3. **真探测用绝对路径逐个试**，而不是裸 `su` —— Android 应用的 PATH 由 zygote 设定，
   未必包含 `/product/bin`，走 PATH 会直接 FileNotFoundException 导致误判。

另外设置页的「Root 能力状态」行变成**可点击**：点一下会真跑一次 `su -c id`
（会弹 Root 管理器的授权框），结果是权威的 —— 无论被动检测怎么说，用户都有办法自证。
引导页在"未检测到"时，提示行同样可点重试。

---

## 构建

**本机跑不了 Android 构建（aarch64 环境没有可用的 `aapt2`），全部走 GitHub Actions：**

```bash
# 触发方式：推送 tag
git tag v1.3.2-lan && git push origin v1.3.2-lan
```

CI 流水线（`.github/workflows/android-build.yml`）会：

1. `collect-runtime`：从 Termux 仓库收集 aarch64/x86_64 的 node 运行时，
   `npm install @deepseek-ai/dsh`（锁 `0.2.0-rc.2`）拉齐依赖闭包，
   应用全部 Android 适配补丁（含本项目的局域网补丁）；
2. `build-apk`：把 runtime 注入 assets → `gradle assembleDebug` → **校验局域网补丁
   确实进了 `runtime.zip`**（host 已改成受控表达式 + 日志行存在 + JS 语法通过）；
3. `release`：tag 触发时双架构 APK 一并发布。

---

## 已验证 / 未验证

**已在本机（aarch64 Alpine + Node 22）实测：**

- 补丁在 **npm 原始包**上锚点命中（不是只在某份产物上能打）
- 完整引擎进程拉起，探针确认 `DSH_LAN_ACCESS=1` 时 webserver 解出 `host=0.0.0.0`
- 模拟 Wi-Fi 网卡（`192.168.1.42`）后，日志确实输出
  `dsh web: lan url: http://192.168.1.42:3080/?token=…`
- 开关关闭时**不打印**任何局域网地址
- **免 token 双向验证**：同一份产物，`DSH_LAN_NO_AUTH=1` 时 `GET /` 返回 **200 + 首页**
  （`/api` 返回 404 而非 401，说明认证层已放行），不设该变量时恢复 **401 / 带 token 303**
- `cordis.patch.yml` 的 `!!js` 表达式用引擎同款解析器（js-yaml + 同款 Tag 定义）
  与同款求值语义（`with (ctx) { eval(expr) }`）验证：开 → `0.0.0.0`，关 → `127.0.0.1`
- App 侧全部正则对着真实日志行跑通（含 token 提取、上游 `(LAN: …)` 段落、关闭态不误报）
- 全部 Kotlin 源文件编译通过，新增文件零警告
- 资源一致性：双语字符串键集对齐、`R.id`/`R.string` 引用全部存在、XML 良构

**在真机上核对过的事实（用户设备 OPPO / Android 12 / Magisk alpha）：**

- `su` 实际挂载点：`/product/bin/su` 与 `/debug_ramdisk/su`（均 → `./magisk`），
  **`/system/bin/su` 不存在** —— 这正是 root 检测失败的根因
- `/product/bin` 权限 `drwxr-x--x`，普通应用可穿透；以应用 uid 验证两个路径均可见
- Magisk DenyList 为空、`mnt_ns=0`，即应用**未**被 root 隐藏，排除隐藏导致的误判

**未验证（需要真机）：**

- APK 在真实 Android 设备上的安装与运行
- 真机 Wi-Fi 环境下的局域网互访（本机沙箱只有 `lo`，看不到真实网卡）
- Root 模式端到端拉起引擎（需要真机授权后才能验证）

---

## 与上游的关系

这是 [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)
的 fork，保留全部上游功能（扩展中心、Shizuku/Root 权限模式、无障碍读屏点击、
自愈回滚等），只叠加局域网访问这一层改动。上游的 LICENSE 与所有版权声明原样保留。

引擎本身是 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
官方代码（`@deepseek-ai/dsh@0.2.0-rc.2`），本项目不修改 Agent 逻辑。

## 许可

MIT，与上游一致。
