/* relay.html 静态体检（不依赖浏览器）：
   把 <script> 块在 Node 里跑起来（DOM 桩），逐屏调渲染函数，检查
   1) 抛异常  2) 标签不闭合  3) undefined/NaN/[object Object] 漏进文本
   4) ic('x') 引用的 symbol 是否存在  5) class 是否都在 CSS 里定义过 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import vm from 'vm';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const HTML = path.join(__dirname, 'relay.html');
const src = fs.readFileSync(HTML, 'utf8');
const scripts = [...src.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
if (!scripts.length) { console.error('没抽到 script 块'); process.exit(2); }
const code = scripts.join('\n;\n');

/* ---- DOM 桩 ---- */
const VOID = new Set(['br','img','input','hr','meta','link','source','area','col',
  'embed','track','wbr','path','use','circle','rect','line','polyline','polygon','ellipse','stop']);
const mk = () => {
  const el = {
    innerHTML: '', textContent: '', children: [], dataset: {}, style: { cssText: '' },
    classList: { toggle(){}, add(){}, remove(){}, contains: () => false },
    setAttribute(){}, getAttribute: () => null, addEventListener(){}, remove(){},
    appendChild(){}, closest: () => null, querySelector: () => mk(), querySelectorAll: () => [],
    click(){}, removeAttribute(){}, insertAdjacentHTML(){}, focus(){},
  };
  return el;
};
const ctx = {
  console, URLSearchParams, URL, Blob: class {}, FileReader: class {},
  MutationObserver: class { observe(){} disconnect(){} },
  requestAnimationFrame: () => 0,
  getComputedStyle: () => ({ getPropertyValue: () => '' }),
  devicePixelRatio: 2,
  document: {
    documentElement: mk(), body: mk(), head: mk(),
    createElement: mk, createDocumentFragment: mk,
    querySelector: mk, querySelectorAll: () => [],
    addEventListener(){}, removeEventListener(){},
  },
  localStorage: { getItem: () => null, setItem(){}, removeItem(){} },
  location: { href: '', hash: '' },
  navigator: { userAgent: 'node' },
  fetch: async () => ({ ok: true, text: async () => '' }),
};
ctx.window = ctx;
ctx.globalThis = ctx;
vm.createContext(ctx);

const EXPORT = '\n;globalThis.__X = { SCREENS, SCREENFN, SCREENFN_D, DVIEWS, NAV, FORMS };\n';
try { vm.runInContext(code + EXPORT, ctx, { filename: 'relay-inline.js' }); }
catch (e) { console.error('脚本运行期抛错：', e.message); process.exit(2); }

const SC = [];
const X = ctx.__X || {};
SC.push(...(X.SCREENS || []));
const FN = { ...(X.SCREENFN || {}), ...(X.SCREENFN_D || {}) };
if (!SC.length) { console.error('没拿到 SCREENS'); process.exit(2); }

/* symbol 清单 */
const syms = new Set([...src.matchAll(/<symbol id="([^"]+)"/g)].map(m => m[1]));
void syms;
const css = (src.match(/<style>([\s\S]*?)<\/style>/g) || []).join('\n');
const cssClasses = new Set([...css.matchAll(/\.([a-zA-Z][\w-]*)/g)].map(m => m[1]));

const problems = [];
function checkTags(id, html) {
  const stack = [];
  const re = /<(\/?)([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>/g;
  let m, line = 0;
  const at = () => '偏移 ' + m.index;
  while ((m = re.exec(html))) {
    line++;
    const [, close, tag, attrs, self] = m;
    if (close) {
      const top = stack.pop();
      if (top !== tag) problems.push(`[${id}] 标签不匹配：第${line}处 </${tag}> 遇到栈顶 <${top}> (${at()})`);
    } else if (!self && !VOID.has(tag.toLowerCase())) {
      stack.push(tag);
    }
    void attrs;
  }
  if (stack.length) problems.push(`[${id}] 未闭合：<${stack.join('> <')} (${at()})`);
}
function checkText(id, html) {
  for (const [pat, name] of [[/\bundefined\b/g, 'undefined'], [/\bNaN\b/g, 'NaN'],
                             [/\[object Object\]/g, '[object Object]'], [/\bfunction\b/g, '裸 function']]) {
    if (pat.test(html)) problems.push(`[${id}] 输出里有 ${name}`);
  }
}
function checkIcons(id, html) {
  for (const m of html.matchAll(/<svg>[\s\S]*?<\/svg>/g)) chk(m[0]);
  function chk(svgStr) {
    const mm = svgStr.match(/<use[^>]*href="#([^"]+)"/g) || [];
    for (const one of mm) {
      const h = one.match(/#([^"]+)"/)[1];
      if (!syms.has(h)) problems.push(`[${id}] 引用了不存在的 symbol：${h}`);
    }
  }
}
function checkClasses(id, html) {
  const used = new Set([...html.matchAll(/class="([^"$]*)"/g)]
    .flatMap(m => m[1].split(/\s+/)).filter(Boolean));
  const miss = [...used].filter(c => !cssClasses.has(c));
  if (miss.length) problems.push(`[${id}] 用了 CSS 里没有的类：${miss.join(' ')}`);
}

let ok = 0;
for (const s of SC) {
  const fn = FN[s.id];
  if (typeof fn !== 'function') { problems.push(`[${s.id}] 没有注册渲染函数`); continue; }
  let html;
  try { html = fn(); } catch (e) { problems.push(`[${s.id}] 渲染抛错：${e.message}`); continue; }
  if (typeof html !== 'string' || !html.length) { problems.push(`[${s.id}] 渲染结果为空`); continue; }
  checkTags(s.id, html); checkText(s.id, html); checkIcons(s.id, html); checkClasses(s.id, html);
  ok++;
}

console.log(`屏数 ${SC.length}，渲染通 ${ok}`);
if (problems.length) {
  console.log(`发现 ${problems.length} 处问题：`);
  problems.slice(0, 60).forEach(p => console.log('  ' + p));
  if (problems.length > 60) console.log(`  …（还有 ${problems.length - 60} 条）`);
  process.exit(1);
}
console.log('体检通过：无未闭合、无 undefined/NaN、symbol 齐全、class 都有定义');
