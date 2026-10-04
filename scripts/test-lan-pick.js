#!/usr/bin/env node
/**
 * 回归测试：局域网地址挑选逻辑。
 *
 * 真机事故（v1.3.0-lan）：手机上开着代理应用时存在 `tun0`（地址 172.19.0.1），
 * 它也落在 RFC1918 私有段，旧逻辑「按枚举顺序取第一个私有段地址」把 VPN 地址
 * 当成了局域网地址显示给用户 —— 别的设备照这个地址连必然失败。
 *
 * 本测试**不重复实现**挑选逻辑，而是：
 *   1. 把补丁脚本应用到一份最小夹具上（占位文件 + 真实锚点）；
 *   2. 从打好的补丁产物里把注入的那段 JS 抠出来；
 *   3. 用假的 networkInterfaces / connectionCtx / port / config 执行它；
 *   4. 断言打印出来的局域网地址。
 * 也就是说，被测的是真正会被打进 runtime.zip 的那段代码。
 *
 * 用法：node scripts/test-lan-pick.js
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFileSync } = require('child_process');

const REPO = path.resolve(__dirname, '..');
// 允许指定另一份补丁脚本：用于「对着旧版/错误实现跑，必须失败」的负向验证
const PATCH = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(REPO, 'scripts', 'patch-lan-access.py');

// —— 夹具：只需要让补丁脚本的锚点命中 ——
const YML_ANCHOR = "        host: !!js ctx.webStartup.host ?? '127.0.0.1'\n";
const JS_ANCHOR =
  '\t\t\tif (config.printUrl) console.log(`dsh web: ${authenticatedUrl}' +
  '${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);\n';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'lan-pick-'));
const ymlDir = path.join(tmp, 'lib/node_modules/@deepseek-ai/dsh-web-app');
fs.mkdirSync(ymlDir, { recursive: true });
fs.writeFileSync(path.join(ymlDir, 'cordis.patch.yml'), YML_ANCHOR + '        port: 3080\n');
fs.mkdirSync(path.join(ymlDir, 'lib'), { recursive: true });
fs.writeFileSync(path.join(ymlDir, 'lib/index.js'), 'function x() {\n' + JS_ANCHOR + '}\n');

execFileSync('python3', [PATCH], { env: { ...process.env, DSH_PATCH_TARGET: tmp } });

const patched = fs.readFileSync(path.join(ymlDir, 'lib/index.js'), 'utf8');
const patchedYml = fs.readFileSync(path.join(ymlDir, 'cordis.patch.yml'), 'utf8');

// —— 抠出注入的代码块 ——
const start = patched.indexOf('if (config.printUrl && process.env.DSH_LAN_ACCESS === "1") {');
if (start < 0) {
  console.error('FAIL: 补丁产物里找不到局域网地址代码块');
  process.exit(1);
}
let depth = 0;
let end = -1;
for (let i = patched.indexOf('{', start); i < patched.length; i++) {
  if (patched[i] === '{') depth++;
  else if (patched[i] === '}') {
    depth--;
    if (depth === 0) { end = i + 1; break; }
  }
}
const block = patched.slice(start, end);

/** 用假的运行环境执行注入的代码块，返回它打印出的局域网地址（没有则 undefined） */
function runBlock(ni) {
  const printed = [];
  const sandbox = {
    config: { printUrl: true },
    networkInterfaces: () => ni,
    connectionCtx: { connection: { authenticatedUrl: (u) => u + '/?token=FAKE' } },
    port: 3080,
    console: { log: (s) => printed.push(s) },
    process: { env: { DSH_LAN_ACCESS: '1' } },
  };
  const fn = new Function(...Object.keys(sandbox), block);
  fn(...Object.values(sandbox));
  const hit = printed.find((s) => s.startsWith('dsh web: lan url: '));
  return hit === undefined ? undefined : hit.replace('dsh web: lan url: ', '').split('://')[1].split(':')[0];
}

const cases = [
  {
    name: '真机事故场景：wlan0(192.168.0.107) + VPN tun0(172.19.0.1)',
    ni: {
      lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
      wlan0: [{ address: '192.168.0.107', family: 'IPv4', internal: false }],
      tun0: [{ address: '172.19.0.1', family: 'IPv4', internal: false }],
    },
    expect: '192.168.0.107',
  },
  {
    name: '枚举顺序把 VPN 排在真网卡前面（旧逻辑必错）',
    ni: {
      lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
      tun0: [{ address: '172.19.0.1', family: 'IPv4', internal: false }],
      wlan0: [{ address: '192.168.0.107', family: 'IPv4', internal: false }],
    },
    expect: '192.168.0.107',
  },
  {
    name: '只有 VPN、没有真网卡 → 退回隧道地址好过没有',
    ni: {
      lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
      tun0: [{ address: '172.19.0.1', family: 'IPv4', internal: false }],
    },
    expect: '172.19.0.1',
  },
  {
    name: '手机热点 ap0(192.168.43.1) 优先于蜂窝 rmnet(10.55.1.7)',
    ni: {
      lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
      rmnet_data0: [{ address: '10.55.1.7', family: 'IPv4', internal: false }],
      ap0: [{ address: '192.168.43.1', family: 'IPv4', internal: false }],
    },
    expect: '192.168.43.1',
  },
  {
    name: 'USB 网络共享 rndis0',
    ni: {
      lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
      rndis0: [{ address: '192.168.42.129', family: 'IPv4', internal: false }],
    },
    expect: '192.168.42.129',
  },
  {
    name: '企业 10.x 真网卡 vs 隧道上的 192.168 → 真网卡优先',
    ni: {
      lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
      wlan0: [{ address: '10.1.2.3', family: 'IPv4', internal: false }],
      tun0: [{ address: '192.168.9.9', family: 'IPv4', internal: false }],
    },
    expect: '10.1.2.3',
  },
  {
    name: '链路本地 169.254 被跳过',
    ni: {
      lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }],
      wlan0: [{ address: '169.254.3.4', family: 'IPv4', internal: false }],
      eth0: [{ address: '192.168.1.5', family: 'IPv4', internal: false }],
    },
    expect: '192.168.1.5',
  },
  {
    name: 'IPv6 被忽略',
    ni: {
      wlan0: [
        { address: 'fe80::1', family: 'IPv6', internal: false },
        { address: '192.168.1.9', family: 'IPv4', internal: false },
      ],
    },
    expect: '192.168.1.9',
  },
];

let fail = 0;
for (const c of cases) {
  const got = runBlock(c.ni);
  const ok = got === c.expect;
  if (!ok) fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${c.name}`);
  if (!ok) console.log(`        期望=${c.expect} 实际=${got}`);
}

// 完全没有可用网卡：不应打印局域网地址行
const silent = runBlock({ lo: [{ address: '127.0.0.1', family: 'IPv4', internal: true }] });
if (silent !== undefined) {
  fail++;
  console.log(`FAIL  无可用网卡时不应打印地址（实际=${silent}）`);
} else {
  console.log('PASS  无可用网卡时不打印地址');
}

// 关闭态（环境变量非 1）不应打印地址
{
  const printed = [];
  const fn = new Function(
    'config', 'networkInterfaces', 'connectionCtx', 'port', 'console', 'process',
    block,
  );
  fn(
    { printUrl: true },
    () => ({ wlan0: [{ address: '192.168.1.5', family: 'IPv4', internal: false }] }),
    { connection: { authenticatedUrl: (u) => u } },
    3080,
    { log: (s) => printed.push(s) },
    { env: {} },
  );
  if (printed.some((s) => s.startsWith('dsh web: lan url: '))) {
    fail++;
    console.log('FAIL  开关关闭时仍打印了局域网地址');
  } else {
    console.log('PASS  开关关闭时不打印局域网地址');
  }
}

// 顺带确认 YAML 侧的开关表达式确实被改写
if (!/host: !!js "process\.env\.DSH_LAN_ACCESS === '1'/.test(patchedYml)
    || !/ctx\.webStartup\.host \?\? '127\.0\.0\.1'/.test(patchedYml)) {
  fail++;
  console.log('FAIL  cordis.patch.yml 的 host 表达式不符合预期');
} else {
  console.log('PASS  cordis.patch.yml host 受开关控制且关闭态回落回环');
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(fail === 0 ? '\n全部通过' : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
