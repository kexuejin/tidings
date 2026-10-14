#!/usr/bin/env node
/* check-contrast.mjs —— 逐屏做对比度审查（不依赖 puppeteer / playwright）。
 *
 * 为什么需要它：稿子里最容易漏、又最影响"精致感"的一类问题，是**字与底色太接近**——
 * 例如「深绿实心按钮 + 灰字」。这种问题几何探针看不见（盒子位置都对），
 * 截图里不放大也看不出（`m11-agents` 那张就是这么漏过去的），但只要把每一屏的
 * 「文字色 / 实际底色 / 字号字重」都算一遍对比度，它就无处可藏。
 *
 * 判据用 WCAG 2.1 AA：正文 ≥ 4.5:1，大字（≥24px，或 ≥18.66px 且 ≥700 字重）≥ 3:1。
 * 底色取「最近的、不透明的祖先背景色」——这正是浏览器实际合成时看到的那一层
 * （半透明卡片本身不构成底，要继续往上找）。元素自身 opacity < 0.6 的整条跳过，
 * 它本来就是"刻意弱化"的。
 *
 * ⚠ 射程边界（**必须连同 0 发现一起读**）：它只对**有文字的节点**取样。
 *   图标、色点、渐变、描边、选中态圆点这些**纯图形不查** —— 它们走 WCAG 1.4.11
 *   （非文字对比度 ≥ 3:1），要另用手算。纯 emoji 也跳过（emoji 是彩色字形，
 *   `color` 不参与合成；⚠ 别用 `\p{Extended_Pictographic}` 判，它含 ASCII 的
 *   `#` `*` `0-9`，会误跳日历里真有问题的「9」）。
 *   所以本脚本报 0 ≠「配色全绿」，只 =「没有文字压在几乎同色的底上」。
 *   例：`relay.html` 的 `.avedit-c[aria-pressed]::after` 选中点曾压在杏黄底上
 *   只有 2.74:1 —— 本脚本一次都没报过，是手算才发现的（见 README §13.6）。
 *
 * 用法：node check-contrast.mjs [--port 4173] [--theme light|dark|both] [--screen m11-agents]
 * 退出码：0 = 无发现，1 = 有发现（可直接接进 CI / 提交前检查）。
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const arg = (k, d) => {
  const i = process.argv.indexOf('--' + k);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : d;
};
const HTTP_PORT = arg('port', '4173');
const THEMES = arg('theme', 'both') === 'both' ? ['light', 'dark'] : [arg('theme', 'light')];
const ONLY = arg('screen', '');
const BASE = `http://127.0.0.1:${HTTP_PORT}/relay.html`;
const DEBUG_PORT = Number(arg('debug-port', '9335'));
const CHROME = process.env.CHROME ||
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const profile = mkdtempSync(join(tmpdir(), 'relay-contrast-'));
const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-sandbox', '--no-first-run',
  '--hide-scrollbars', '--force-device-scale-factor=1',
  `--remote-debugging-port=${DEBUG_PORT}`, `--user-data-dir=${profile}`, 'about:blank',
], { stdio: ['ignore', 'ignore', 'ignore'] });
const bye = () => { try { chrome.kill('SIGKILL'); } catch {} try { rmSync(profile, { recursive: true, force: true }); } catch {} };
process.on('exit', bye);
process.on('SIGINT', () => { bye(); process.exit(130); });

/* ---- 连 CDP ---- */
const sleep = ms => new Promise(r => setTimeout(r, ms));
let ver = null;
for (let i = 0; i < 60 && !ver; i++) {
  try { ver = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`)).json(); }
  catch { await sleep(200); }
}
if (!ver) { console.error('Chrome 没起来（检查 CHROME 路径）'); process.exit(2); }

const targets = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/list`)).json();
const target = targets.find(t => t.type === 'page');
const ws = new WebSocket(target.webSocketDebuggerUrl);
let seq = 0;
const waiting = new Map();
const listeners = new Set();
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); return; }
  for (const fn of listeners) fn(m);
};
await new Promise(r => ws.onopen = r);
const cmd = (method, params = {}) => new Promise(res => {
  const id = ++seq; waiting.set(id, res);
  ws.send(JSON.stringify({ id, method, params }));
});
await cmd('Page.enable');
await cmd('Runtime.enable');

const once = (method, timeout = 8000) => new Promise(res => {
  const fn = m => { if (m.method === method) { listeners.delete(fn); res(true); } };
  listeners.add(fn); setTimeout(() => { listeners.delete(fn); res(false); }, timeout);
});
async function open(url) {
  const loaded = once('Page.loadEventFired');
  await cmd('Page.navigate', { url });
  await loaded; await sleep(120);
}
async function evaluate(expression) {
  const r = await cmd('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 300));
  return r.result?.result?.value;
}

/* ---- 屏清单从页面自己读，避免和 relay.html 里的 SCREENS 漂移 ---- */
await open(`${BASE}?bare=1`);
const screens = JSON.parse(await evaluate(`JSON.stringify(SCREENS.map(s => ({id:s.id, form:s.form})))`));
/* SCREENS 里的 `form` 是 mobile / desktop（两套外壳），而不是形态表里的 size key。
   默认形态取 phone / expanded；`EXTRA` 再把「同屏不同形态」补几格 ——
   那几档的排版差异最大（窄档会把动作收进菜单、设置窗收成一列）。 */
const SIZE_OF = { mobile: 'phone', desktop: 'expanded' };
const EXTRA = [
  { id: 'm-home-recent', form: 'tablet' },
  { id: 'm-chat',        form: 'tabland' },
  { id: 'd-chat',        form: 'compact' },
  { id: 'd-chat',        form: 'medium' },
  { id: 'd-chat',        form: 'tabhost' },
  { id: 'd-settings',    form: 'settings' },
];
const all = [...screens.map(s => ({ id: s.id, form: SIZE_OF[s.form] || 'phone' })), ...EXTRA];
const list = ONLY ? all.filter(s => s.id === ONLY) : all;
console.log(`relay.html 共 ${screens.length} 屏（+${EXTRA.length} 形态格）× ${THEMES.length} 主题`);

/* ---- 审查表达式 ---- */
const AUDIT = `(() => {
  const P = s => { const m = /rgba?\\(([^)]+)\\)/.exec(s||''); if (!m) return null;
    const p = m[1].split(',').map(Number); return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1}; };
  const L = c => { const f = v => { v/=255; return v<=0.03928 ? v/12.92 : Math.pow((v+0.055)/1.055,2.4); };
    return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b); };
  const ratio = (a,b) => { const l1=L(a), l2=L(b);
    return (Math.max(l1,l2)+0.05)/(Math.min(l1,l2)+0.05); };
  const bgOf = el => { let n=el; while (n) { const c=P(getComputedStyle(n).backgroundColor);
    if (c && c.a>=0.9) return c; n=n.parentElement; } return {r:255,g:255,b:255,a:1}; };
  const path = el => { const o=[]; let n=el;
    while (n && n!==document.body && o.length<4) {
      const cn = (typeof n.className==='string' && n.className.trim())
        ? '.'+n.className.trim().split(/\\s+/).join('.') : '';
      o.unshift(n.tagName.toLowerCase()+cn); n=n.parentElement; }
    return o.join('>'); };
  const out=[];
  /* 纯 emoji 的文字**不算**：😀 / 🐶 这类字形由字体按彩色位图渲染，CSS 的 color
     完全不参与合成，算出来的"对比度"没有意义。稿子里 emoji 头像 / emoji 面板
     原来会稳定报出 40 多处假阳性，把真问题埋在噪音里。
     判据：把 emoji 码点、变体选择符、零宽连接符、键帽组合符去掉后，
     若一个字都不剩，就跳过；剩一个字（如「🐶狗」）就照常审。
     注意**不能**用 \p{Extended_Pictographic}：它把 ASCII 的 #、*、0-9 也算进去，
     会把日历里那个「9」一起误跳过。 */
  const EMOJI = /[\u{1F000}-\u{1FAFF}\u{1F1E6}-\u{1F1FF}\u{2190}-\u{21FF}\u{2300}-\u{23FF}\u{2460}-\u{24FF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}\u{200D}\u{20E3}]/gu;
  for (const el of document.querySelectorAll('body *')) {
    let txt=''; for (const n of el.childNodes) if (n.nodeType===3) txt += n.textContent;
    txt = txt.trim(); if (!txt) continue;
    if (!txt.replace(EMOJI, '').replace(/[\s\u00A0]/g, '')) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility==='hidden' || cs.display==='none' || parseFloat(cs.opacity) < 0.6) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    const fg = P(cs.color); if (!fg || fg.a < 0.6) continue;
    const bg = bgOf(el);
    const size = parseFloat(cs.fontSize), wt = parseInt(cs.fontWeight)||400;
    const large = size>=24 || (size>=18.66 && wt>=700);
    const need = large ? 3 : 4.5;
    const cr = ratio(fg,bg);
    if (cr < need - 0.005) out.push({ p: path(el), t: txt.replace(/\\s+/g,' ').slice(0,26),
      r: Math.round(cr*100)/100, need, size: Math.round(size*10)/10,
      fg: [fg.r,fg.g,fg.b].map(Math.round).join(','),
      bg: [bg.r,bg.g,bg.b].map(Math.round).join(',') });
  }
  return JSON.stringify(out);
})()`;

/* ---- 逐屏跑 ---- */

let total = 0;
const rows = [];
for (const th of THEMES) {
  for (const s of list) {
    await open(`${BASE}?bare=1&size=${s.form}&screen=${s.id}&theme=${th}`);
    const raw = await evaluate(AUDIT);
    const hits = JSON.parse(raw || '[]');
    if (hits.length) {
      rows.push({ theme: th, screen: s.id, hits });
      total += hits.length;
    }
  }
}
ws.close();

if (!total) {
  console.log(`\n对比度：${list.length * THEMES.length} 次加载，0 发现（AA：正文 4.5:1 / 大字 3:1）`);
} else {
  console.log(`\n对比度：${total} 处低于 AA\n`);
  for (const r of rows) {
    console.log(`── ${r.theme} · ${r.screen}`);
    for (const h of r.hits) {
      console.log(`   ${String(h.r).padStart(5)}:1 (需 ${h.need})  ${h.size}px  #${h.fg} on #${h.bg}`);
      console.log(`         ${h.t}   @ ${h.p}`);
    }
  }
}
process.exit(total ? 1 : 0);
