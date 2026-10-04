#!/usr/bin/env node
/**
 * 验证「默认思考档位」补丁的真实行为。
 *
 * 做法：从补丁产物里把 THINKING_LEVELS / invalid / resolveModelReasoning 三个片段
 * 抠出来，拼成一个可执行模块，然后对着两种开关状态断言输出 ——
 * 被执行的正是会随 runtime.zip 发到手机上的那份代码。
 */
const fs = require('fs');

const SRC = process.argv[2];
if (!SRC) {
  console.error('用法: node scripts/test-reasoning-default.js <已打补丁的 dsh-llm-pi-ai/lib/index.js>');
  process.exit(2);
}
const s = fs.readFileSync(SRC, 'utf8');

function slice(from, mode) {
  const i = s.indexOf(from);
  if (i < 0) throw new Error('找不到片段: ' + from.slice(0, 40));
  let depth = 0, started = false;
  for (let k = i; k < s.length; k++) {
    if (s[k] === '{') { depth++; started = true; }
    else if (s[k] === '}') {
      depth--;
      if (started && depth === 0) {
        // 可能是 Object.keys({...}) 这类，继续吃到分号，保证括号闭合
        let j = k + 1;
        while (j < s.length && s[j] !== ';' && s[j] !== '\n') j++;
        if (j < s.length && s[j] === ';') return s.slice(i, j + 1);
        return s.slice(i, k + 1);
      }
    }
  }
  throw new Error('片段不完整: ' + from.slice(0, 40));
}

const parts = [
  'class LlmError extends Error { constructor(m, code) { super(m); this.code = code; } }',
  slice('const THINKING_LEVELS = Object.keys({'),
  slice('function invalid(provider, detail) {'),
  slice('function resolveModelReasoning(provider, entry, base) {'),
];

const mod = new Function(parts.join('\n') + '\nreturn resolveModelReasoning;')();
const resolve = mod;

function run(label, env, entry, base) {
  if (env === '1') process.env.DSH_DEFAULT_REASONING = '1';
  else delete process.env.DSH_DEFAULT_REASONING;
  const out = resolve('test-provider', entry, base);
  console.log(`${label}\n   ->`, JSON.stringify(out));
  return out;
}

let fail = 0;
const check = (cond, msg) => { if (!cond) { fail++; console.log('   ✗ ' + msg); } else console.log('   ✓ ' + msg); };

// 场景：手工声明的模型（没有 reasoningEfforts，也没有 catalog 里的基条目）
console.log('=== 场景 1：开关打开（应为所有模型补上思考档位）===');
{
  const r = run('entry={id:"my-model"} base=undefined', '1', { id: 'my-model' }, undefined);
  check(r.reasoning === true, '模型被报告为支持思考');
  check(r.thinkingLevelMap && r.thinkingLevelMap.low === 'low'
        && r.thinkingLevelMap.medium === 'medium'
        && r.thinkingLevelMap.high === 'high', 'low/medium/high 有 wire 值');
  check(!('off' in (r.thinkingLevelMap || {})), 'off 有意缺席（=支持，发送时不带该参数）');
  check(!('xhigh' in (r.thinkingLevelMap || {})) && !('max' in (r.thinkingLevelMap || {})),
        'xhigh/max 不声明');
}

console.log('=== 场景 2：开关关闭（必须与上游逐字一致）===');
{
  const r1 = run('entry 未声明 base=undefined', '0', { id: 'my-model' }, undefined);
  check(r1.reasoning === false, '回到上游行为：reasoning=false');
  const r2 = run('entry 未声明 base={reasoning:false}', '0', { id: 'm' }, { reasoning: false });
  check(r2.reasoning === false, '跟随 catalog 的 false');
  const r3 = run('entry 未声明 base={reasoning:true}', '0', { id: 'm' }, { reasoning: true });
  check(r3.reasoning === true, '跟随 catalog 的 true');
}

console.log('=== 场景 3：用户自己声明了 reasoningEfforts（补丁不得干预）===');
{
  const r = run('entry 已声明 {off:null,high:"high"}', '1', { id: 'm', reasoningEfforts: { off: null, high: 'high' } }, undefined);
  check(r.reasoning === true && r.thinkingLevelMap.high === 'high', '保留用户的声明');
  check(r.thinkingLevelMap.low === null, '未声明的档位被钉为 null（不支持）');
  const r2 = run('entry 显式 false', '1', { id: 'm', reasoningEfforts: false }, undefined);
  check(r2.reasoning === false, '显式 false 仍然生效');
}

console.log(fail === 0 ? '\n全部通过' : `\n${fail} 项失败`);
process.exit(fail ? 1 : 0);
