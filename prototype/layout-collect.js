/* 原型渲染真值采集器：在 iframe 的 realm 里被 eval，然后调用 __collect()。
   单独成文件（而不是内联进 wrapper 的字符串字面量）是为了：可 node --check、可 diff、
   可被别的工具复用；内联那份实测会被 json.dumps 的转义与 % 模板撞车，坏法很难读。 */
window.__collect = function () {
  // 只认 px。parseFloat("50%")=50 会把 border-radius:50%（实测 3536 处）当 50px 记进
  // 档位——第一版就这么被灌进「圆角 50 档」。% / calc / auto / normal 一律 null，
  // 与 design-converge 的 special 口径一致：不参与归一化的东西不许冒充档位。
  const W = (s) => {
    if (typeof s !== "string") return null;
    if (s === "0" || s === "0px") return 0;
    const m = /^(-?[\d.]+)px$/.exec(s.trim());
    return m ? parseFloat(m[1]) : null;
  };
  const canvas = document.querySelector('.device,.win,.dlg,.pop') || document.body;
  const cb = canvas.getBoundingClientRect();
  const R = (r) => ({ x: Math.round(r.left - cb.left), y: Math.round(r.top - cb.top),
                      w: Math.round(r.width), h: Math.round(r.height) });
  const CLS = (c) => (typeof c.className === 'string' ? c.className.trim().replace(/\s+/g, '.') : '');

  const nodes = [];
  (function walk(el, path, depth) {
    for (const c of el.children) {
      const r = c.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) continue;
      const cs = getComputedStyle(c);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) continue;
      let icon = null;
      if (c.tagName.toLowerCase() === 'svg') {
        // <use> 本体 rect 常是 0x0，会被可见性过滤吃掉；图标要从 svg 节点上取。
        const u = c.querySelector('use');
        if (u) icon = { ref: u.getAttribute('href') || u.getAttribute('xlink:href') || '',
                        size: [Math.round(r.width), Math.round(r.height)] };
      }
      let t = '';
      for (const n of c.childNodes) if (n.nodeType === 3) t += n.textContent;
      const pathHere = path + '>' + c.tagName.toLowerCase() + (CLS(c) ? '.' + CLS(c) : '');
      nodes.push({ path: pathHere, tag: c.tagName.toLowerCase(),
        rect: R(r), viewport: { x: Math.round(r.x), y: Math.round(r.y) },
        style: { disp: cs.display, pos: cs.position, flexDir: cs.flexDirection,
          pad: [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft].map(W),
          margin: [cs.marginTop, cs.marginRight, cs.marginBottom, cs.marginLeft].map(W),
          gap: [W(cs.rowGap), W(cs.columnGap)],
          radius: [cs.borderTopLeftRadius, cs.borderTopRightRadius,
                   cs.borderBottomRightRadius, cs.borderBottomLeftRadius].map(W),
          font: { size: W(cs.fontSize), weight: cs.fontWeight, lh: W(cs.lineHeight) },
          color: cs.color, bg: cs.backgroundColor,
          border: [W(cs.borderTopWidth), cs.borderTopStyle],
          opacity: parseFloat(cs.opacity) },
        icon: icon && icon.ref ? icon : null,
        text: t.trim().slice(0, 48), kids: c.children.length, depth: depth });
      if (depth < 24) walk(c, pathHere, depth + 1);
    }
  })(canvas, 'root', 0);

  const icons = {};
  for (const n of nodes) if (n.icon) icons[n.icon.ref] = (icons[n.icon.ref] || 0) + 1;

  /* CSS 变量：设计侧最脏的一层。源文本里是 var(--x)，这里给出**解析后的数**。
     枚举靠 styleSheets（同源），取值靠 getComputedStyle —— 两者都要，缺一边就不全。 */
  const vars = {};
  for (const sheet of document.styleSheets) {
    let rules;
    try { rules = sheet.cssRules; } catch (e) { continue; }  // 跨域表读不到，跳过并记账
    if (!rules) continue;
    (function scan(rules) {
      for (const rule of rules) {
        if (rule.style) {
          for (const p of rule.style) {
            if (p.startsWith('--') && !(p in vars)) {
              const v = getComputedStyle(document.documentElement).getPropertyValue(p).trim();
              vars[p] = v;
            }
          }
        }
        if (rule.cssRules) scan(rule.cssRules);
      }
    })(rules);
  }

  function anchors() {
    const A = {};
    // 顶栏 = 手机 .topbar / 桌面 .titlebar / 设置窗头。别把 .bar 算进来：
    // 原型里 .bar{height:3px} 是**进度条**，实测曾被当顶栏高（锚点=3 的假数）。
    const hd = canvas.querySelector('.topbar,.titlebar,.head');
    if (hd) A.topbar = { h: Math.round(hd.getBoundingClientRect().height), sel: CLS(hd) };
    const rows = [];
    (function scan(el, d) {
      if (d > 12) return;
      const kids = Array.from(el.children).filter((c) => c.getBoundingClientRect().width > 1);
      if (kids.length >= 3) {
        const sig = new Map();
        for (const c of kids) {
          const k = c.tagName + '.' + CLS(c);
          if (!sig.has(k)) sig.set(k, []);
          sig.get(k).push(c);
        }
        for (const [k, g] of sig) {
          if (g.length < 3) continue;
          const hs = g.map((c) => Math.round(c.getBoundingClientRect().height)).sort((a, b) => a - b);
          const gaps = [];
          for (let i = 1; i < g.length; i++) {
            const v = Math.round(g[i].getBoundingClientRect().top - g[i - 1].getBoundingClientRect().bottom);
            if (v >= 0 && v < 64) gaps.push(v);
          }
          const med = (a) => (a.length ? a[Math.floor(a.length / 2)] : null);
          rows.push({ sel: k, n: g.length, h: med(hs), hspread: [hs[0], hs[hs.length - 1]], gaps });
        }
      }
      for (const c of el.children) scan(c, d + 1);
    })(canvas, 0);
    A.rows = rows;
    const gapCnt = {};
    (function scan(el) {
      const kids = Array.from(el.children).filter((c) => {
        const r = c.getBoundingClientRect(); return r.width > 1 && r.height > 1; });
      const dir = getComputedStyle(el).flexDirection;
      for (let i = 1; i < kids.length; i++) {
        const a = kids[i - 1].getBoundingClientRect(), b = kids[i].getBoundingClientRect();
        const g = dir === 'column' ? Math.round(b.top - a.bottom) : Math.round(b.left - a.right);
        if (g >= 0 && g <= 64) gapCnt[g] = (gapCnt[g] || 0) + 1;
      }
      for (const c of el.children) scan(c);
    })(canvas);
    A.siblingGaps = gapCnt;
    return A;
  }

  return JSON.stringify({
    canvas: R(cb), canvasViewport: { x: Math.round(cb.x), y: Math.round(cb.y),
      w: Math.round(cb.width), h: Math.round(cb.height) },
    nodes: nodes, icons: icons, vars: vars, anchors: anchors(),
  });
};
