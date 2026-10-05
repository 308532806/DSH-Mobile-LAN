# DSH Mobile · LAN Edition

[中文](README.md) · English · [Deutsch](README.de.md)
**Run the DeepSeek Harness agent on your phone — and open it from any device on the same Wi-Fi.**

[![CI](https://github.com/308532806/DSH-Mobile-LAN/actions/workflows/android-build.yml/badge.svg)](https://github.com/308532806/DSH-Mobile-LAN/actions/workflows/android-build.yml)
![Release](https://img.shields.io/badge/release-v1.3.12--lan-blue)![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

[中文](README.md) · English

---

## What this fork adds

The original project [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)
runs the full dsh engine on Android — but like the desktop build it **listens on
`127.0.0.1` only**, so only the phone itself can reach it. Checking the agent's
output from a tablet means holding the phone up to your face or screenshotting it.

This fork adds exactly one thing: **a switch that makes the agent reachable from
every device on your LAN**.

```
phone (runs the agent)          any device on the same Wi-Fi
┌──────────────┐                ┌──────────────┐
│  DSH Mobile  │  192.168.1.42  │ tablet / PC  │
│  engine:3080 │◄──────────────►│  browser     │
└──────────────┘                └──────────────┘
```

**Settings → Permission Center → LAN access → Enable.**
Flipping the switch restarts the engine so the new bind takes effect; the status
bar and the notification then show the address to open.

> The first visit carries a one-time token (`http://192.168.1.42:3080/?token=…`)
> which is exchanged for a persistent session cookie — **you never log in on the
> other device**. The toolbar's "LAN" button copies the address.

---

## How it works

Deliberately small changes; the engine's own logic is left alone.

### 1. The bind address follows the switch

Upstream makes `dsh web --host 0.0.0.0` a hard CLI error, for a stated reason:

> `--host 0.0.0.0 is intentionally not supported yet for safety: it would expose remote code execution to the network`

That call is right for "just start a server". This project's use case is a
**single-user agent on your own phone, on your own home network** — so instead of
tearing the guard out, the default in the *config tree* is changed:

```yaml
# what scripts/patch-lan-access.py injects
host: !!js "process.env.DSH_LAN_ACCESS === '1' ? '0.0.0.0' : (ctx.webStartup.host ?? '127.0.0.1')"
```

- **Switch off (default)** — the engine still binds `127.0.0.1`, byte-for-byte
  upstream behaviour;
- **Switch on** — the App passes `DSH_LAN_ACCESS=1` when spawning the engine, and
  it binds every interface;
- The CLI `program.error` **stays exactly as it is** — typing `--host 0.0.0.0`
  yourself is still refused.

A build-time assertion checks that this patch actually landed in `runtime.zip`,
so CI fails loudly rather than shipping a build that silently cannot do LAN.

### 2. The LAN address is printed into the engine log

With the switch on, the engine logs one extra line:

```
dsh web: lan url: http://192.168.1.42:3080/?token=y7J8-jKAAv68FFnnzat1PXpxgcvSBphrCIUbBxHDsyQ
```

The App scrapes it from `engine.log` and surfaces it in Settings, the status bar
and the notification, with one-tap copy. Address selection: interface name (`wlan/eth/ap/rndis/usb` count as real NICs;
`tun/tap/ppp/wg/rmnet` as tunnels or cellular) combined with address range
(`192.168/16` → `10/8` → `172.16/12`).

> ⚠️ **Why "first private address" is wrong**: a proxy app (Clash / sing-box /
> NekoBox) adds a `tun0` with an address like `172.19.0.1` — which is *also* inside
> the RFC1918 space. Picking the first private address in enumeration order shows
> the **VPN address** as the LAN address, and no other device can reach it.
> That was v1.3.0's bug; v1.3.1 fixes it and adds a regression test
> (`scripts/test-lan-pick.js`, run by CI on every build).

### 3. Changing networks restarts the engine

At **startup** the engine does two things that are pinned to the interfaces of
that moment: it snapshots the LAN addresses into the browser-trust fence
(upstream's DNS-rebinding guard), and it prints the address above. Switch Wi-Fi
or turn on a hotspot and the IP changes — if the App merely displayed the new IP,
the other device would get **a page that loads but whose every `/api` call is
rejected**, which is worse than a clean failure.

So the App watches connectivity and restarts the engine when the current address
differs from the one the engine booted with, re-snapshotting bind, trust list and
logged address together. It does **not** bypass upstream's checks — it lets the
engine look at the world again.

### 4. Off by default, with a risk prompt

Enabling shows a confirmation spelling out that whoever has the address can drive
the agent (file access, command execution, model API spend), that it belongs on
trusted networks only, and that it can be turned off at any time.

`network-security-config` permits cleartext HTTP only for loopback and RFC1918
prefixes — there is no blanket `cleartextTrafficPermitted="true"`.

---

## Building

**Android builds cannot run on this machine (aarch64, no working `aapt2`), so
everything goes through GitHub Actions:**

```bash
git tag v1.3.12-lan && git push origin v1.3.12-lan
```

The pipeline (`.github/workflows/android-build.yml`):

1. `collect-runtime` — collects the aarch64/x86_64 node runtime from the Termux
   repos, `npm install @deepseek-ai/dsh` (pinned to `0.2.0-rc.2`), applies all
   Android adaptation patches including this fork's LAN patch;
2. `build-apk` — injects the runtime into assets → `gradle assembleDebug` →
   **asserts the LAN patch really is inside `runtime.zip`** (host expression
   present, log line present, injected JS parses);
3. `release` — on a tag, publishes both ABIs.

---

## Verified / not verified

**Measured locally (aarch64 Alpine, Node 22):**

- The patch's anchors match the **pristine npm tarball**, not just one built artifact
- A real engine process starts and a probe confirms the webserver resolves
  `host=0.0.0.0` when `DSH_LAN_ACCESS=1`
- With a simulated Wi-Fi NIC (`192.168.1.42`) the log really does emit
  `dsh web: lan url: http://192.168.1.42:3080/?token=…`
- With the switch off, no LAN address is printed at all
- The `!!js` expression in `cordis.patch.yml` was evaluated with the engine's own
  parser (js-yaml + the same Tag definition) and the same evaluation semantics
  (`with (ctx) { eval(expr) }`): on → `0.0.0.0`, off → `127.0.0.1`
- Every App-side regex runs against real log lines (token extraction, upstream
  `(LAN: …)` segment, and no false positive when off)
- All 22 Kotlin sources compile; the new files produce zero warnings
- Resource consistency: zh/en string keys aligned, all `R.id`/`R.string`
  references resolve, 63 XML files well-formed

**Not verified (needs real hardware):**

- Installing and running the APK on an actual Android device
- Real Wi-Fi LAN reachability (this sandbox only exposes `lo`)

---

## Relationship to upstream

A fork of [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android).
All upstream features are kept (extension center, Shizuku/Root privilege modes,
accessibility screen reading and tapping, self-healing rollback); the LAN layer is
the only addition. Upstream's LICENSE and all copyright notices are preserved.

The engine itself is [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
(`@deepseek-ai/dsh@0.2.0-rc.2`); agent logic is untouched.

## License

MIT, same as upstream.
