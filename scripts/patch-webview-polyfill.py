#!/usr/bin/env python3
"""D4: 为 Android 11 等设备的旧 WebView 注入 polyfill。

dsh-web-frontend 前端使用了 Object.hasOwn（Chrome 93+）与 Array/String
.prototype.at（Chrome 92+）。Android 11 出厂 WebView（Chrome 8x，且国产
ROM 常无更新渠道）缺失这些 API → 前端 JS 抛 TypeError → WebUI 永远转圈
（外壳 HTML/CSS 正常渲染）。

在 dist/index.html 的第一个 module script 之前注入同步 polyfill。
幂等：标记存在则跳过。
"""
import os
import sys

MARK = '<!-- [dsh-android] legacy-webview polyfill v3 (Object.hasOwn / .at / replaceChildren / replaceAll / crypto.randomUUID) -->'
POLYFILL = '''<!-- [dsh-android] legacy-webview polyfill v3 (Object.hasOwn / .at / replaceChildren / replaceAll / crypto.randomUUID) -->
<script>
/* [dsh-android] polyfill for Android 11 legacy WebView (Chrome <92) */
if (!Object.hasOwn) { Object.defineProperty(Object, 'hasOwn', { value: function (o, k) { if (o == null) throw new TypeError("Cannot convert undefined or null to object"); return Object.prototype.hasOwnProperty.call(Object(o), k); }, configurable: true, writable: true }); }
if (!Array.prototype.at) { Object.defineProperty(Array.prototype, "at", { value: function (n) { n = Math.trunc(n) || 0; if (n < 0) n += this.length; if (n < 0 || n >= this.length) return undefined; return this[n]; }, writable: true, enumerable: false, configurable: true }); }
if (!String.prototype.at) { Object.defineProperty(String.prototype, "at", { value: function (n) { n = Math.trunc(n) || 0; if (n < 0) n += this.length; if (n < 0 || n >= this.length) return undefined; return this[n]; }, writable: true, enumerable: false, configurable: true }); }
if (!Element.prototype.replaceChildren) { Object.defineProperty(Element.prototype, "replaceChildren", { value: function () { while (this.lastChild) this.removeChild(this.lastChild); if (arguments.length) this.append.apply(this, arguments); }, writable: true, enumerable: false, configurable: true }); }
if (!String.prototype.replaceAll) { Object.defineProperty(String.prototype, "replaceAll", { value: function (s, r) { if (s instanceof RegExp) { if (!s.global) throw new TypeError("replaceAll must use a global RegExp"); return this.replace(s, r); } return this.split(s).join(r === undefined ? "undefined" : String(r)); }, writable: true, enumerable: false, configurable: true }); }
/* [dsh-android] crypto.randomUUID 只在「安全上下文」存在，而局域网访问是
   http://<私有IP> —— 非安全上下文，该方法可能是 undefined（RPC 请求要生成
   请求 id，缺失会导致"页面能开但点了没反应"）。getRandomValues 在非安全上下文
   是可用的，用它拼一个符合 RFC 4122 v4 的 UUID 顶上。 */
if (typeof crypto !== "undefined" && typeof crypto.randomUUID !== "function") {
  try {
    Object.defineProperty(crypto, "randomUUID", { value: function randomUUID() {
      var b = crypto.getRandomValues(new Uint8Array(16));
      b[6] = (b[6] & 0x0f) | 0x40;
      b[8] = (b[8] & 0x3f) | 0x80;
      var h = [];
      for (var i = 0; i < 16; i++) h.push((b[i] + 0x100).toString(16).slice(1));
      return h.slice(0,4).join("") + "-" + h.slice(4,6).join("") + "-" + h.slice(6,8).join("") + "-" + h.slice(8,10).join("") + "-" + h.slice(10).join("");
    }, writable: true, enumerable: false, configurable: true });
  } catch (e) { /* 定义失败时保持原样，不影响其它 polyfill */ }
}
</script>
'''
# v2 的标记（升级到 v3 时要替换掉它，否则旧标记会让补丁以为已注入而跳过）
MARK_V2 = '<!-- [dsh-android] legacy-webview polyfill v2 (Object.hasOwn / .at / replaceChildren / replaceAll) -->'
ANCHOR = '<script type="module" crossorigin'


def patch(root: str) -> None:
    html_path = os.path.join(root, 'lib', 'node_modules', '@deepseek-ai',
                             'dsh-web-frontend', 'dist', 'index.html')
    if not os.path.isfile(html_path):
        print(f'WARN: frontend index.html not found at {html_path}')
        return
    with open(html_path, 'r', encoding='utf-8') as f:
        s = f.read()
    if MARK in s:
        print('polyfill already injected')
        return
    # v2 → v3 升级路径：把旧块整体换掉
    if MARK_V2 in s:
        start = s.index(MARK_V2)
        end = s.index('</script>', start) + len('</script>') + 1
        s = s[:start] + POLYFILL + s[end:]
        with open(html_path, 'w', encoding='utf-8', newline='') as f:
            f.write(s)
        print('polyfill upgraded v2 -> v3:', html_path)
        return
    if ANCHOR not in s:
        print('WARN: module script anchor not found; skipped')
        return
    s = s.replace(ANCHOR, MARK + '\n' + POLYFILL + '\n' + ANCHOR, 1)
    with open(html_path, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print('polyfill injected:', html_path)


if __name__ == '__main__':
    # 目标目录只从 DSH_PATCH_TARGET 环境变量读取（不接受命令行路径）
    raw = os.environ.get('DSH_PATCH_TARGET', '')
    if not raw:
        sys.exit('rejected: DSH_PATCH_TARGET not set by caller')
    patch(os.path.realpath(raw))
