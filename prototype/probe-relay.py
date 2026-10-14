#!/usr/bin/env python3
"""几何探针：把 relay.html 的每一屏都渲染一遍，量真实盒模型，报三件事。

  · clip-x   内容比容器宽、容器又不让滚 —— 会被切掉（省略号是允许的）
  · clip-y   内容比容器高、容器 overflow:hidden，且溢出超过半个行盒 —— 掉了整行才算切
             （只多 1~2px 是行盒取整，见 measure 里的注释）
  · outside-x 元素伸出设备框（滚动容器里的除外）

用法：先起静态服务（`python3 -m http.server 4173`），再
      python3 probe-relay.py [http://127.0.0.1:4173]
退出码非 0 = 有发现。生成物只写 /tmp，不落在仓库里。
"""
import json, os, re, shutil, subprocess, sys, tempfile, html as H

HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

PROBE = r"""
<script>
(function(){
  const out = { screens: [], spacing: {} };
  const host = document.createElement('div');
  host.style.cssText = 'position:absolute;left:-99999px;top:0;';
  document.body.appendChild(host);

  function formFor(s){
    if (s.form === 'mobile') return 'phone';
    return /设置窗/.test(s.label) ? 'settings' : 'expanded';
  }
  function snap(el){
    const cs = getComputedStyle(el), r = el.getBoundingClientRect();
    return {x:+r.left.toFixed(1), y:+r.top.toFixed(1),
            w:+r.width.toFixed(1), h:+r.height.toFixed(1),
            pad:cs.padding, mar:cs.margin, gap:cs.rowGap};
  }
  function measure(sid, sk, label){
    host.innerHTML = device(sid, sk);
    const dev = host.querySelector('.device');
    if (!dev) return {screen: sid, form: sk, label, n: 1, issues: [{t:'fatal'}]};
    const dr = dev.getBoundingClientRect();
    const iss = [];
    host.querySelectorAll('*').forEach(el => {
      const cs = getComputedStyle(el);
      const hx = el.scrollWidth - el.clientWidth;
      if (hx > 1 && el.clientWidth > 0 && cs.textOverflow !== 'ellipsis'
          && cs.overflowX !== 'visible' && cs.overflowX !== 'auto' && cs.overflowX !== 'scroll') {
        iss.push({t:'clip-x', sel:String(el.className||el.tagName), over:hx,
                  txt:(el.textContent||'').trim().slice(0,40)});
      }
      /* 「被切掉」指的是掉了多少，不是有没有溢出。Chrome 对单行 nowrap 的块化行盒，
         scrollHeight 取 ceil、clientHeight 取 round，非整数行高（27.6px）会稳定多报
         1~2px —— 36 处 `.ell` 就是这么报出来的，量出来字形墨迹还比盒子矮 3~10px，
         一个字都没切。所以真切的判据是「溢出超过半个行盒」；比这更细的那一档
         （某个字的降部被压掉一点）由下面的墨迹检查用 canvas 度量兜住。

         `-webkit-line-clamp` 要单独排除：它是**CSS 里显式声明**的截断
         （`.row .t2.two` 就是照 `ConversationsScreen.kt:516` 的 `maxLines = 2`
         写的），浏览器会在第 2 行末尾自己补省略号 —— 这是设计要的切，
         不是"掉了整行"。不排除的话，每一条长摘要都会稳定报一次假阳性
         （实测 2 处）。⚠ 代价写清楚：clamp 数值**写得太小**（比如本该 2 行写成 1 行）
         这类问题，本探针此后不报，只能靠截图看。 */
      const lh = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.2;
      const hy = el.scrollHeight - el.clientHeight;
      const clamped = cs.webkitLineClamp && cs.webkitLineClamp !== 'none';
      if (hy > 1 && el.clientHeight > 0 && cs.overflowY === 'hidden' && hy > lh / 2
          && !clamped) {
        iss.push({t:'clip-y', sel:String(el.className||el.tagName), over:hy,
                  txt:(el.textContent||'').trim().slice(0,40)});
      }
      const r = el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) return;
      if ((r.right > dr.right + 1.5 || r.left < dr.left - 1.5)
          && !el.closest('.scroll') && !el.closest('.menu') && !el.closest('.tabstrip')) {
        iss.push({t:'outside-x', sel:String(el.className||el.tagName),
                  d:+(r.right-dr.right).toFixed(1)});
      }
    });
    return {screen: sid, form: sk, label, n: iss.length, issues: iss.slice(0, 12)};
  }

  /* 单屏抛错不能拖垮整轮：以前任何一个 measure() 抛出，整个 IIFE 就死了，
     <pre id="__probe__"> 一个都不产出，向下游报成「JSON 解析失败」，看不出是哪一屏。
     现在逐屏兜住，把异常当成一条 issue 报出来。 */
  function safe(fn, sid, sk, label){
    try { return fn(); }
    catch (e) {
      return {screen: sid, form: sk, label, n: 1,
              issues: [{t: 'throw', sel: String(e && e.message || e).slice(0, 90)}]};
    }
  }

  SCREENS.forEach(s => out.screens.push(safe(() => measure(s.id, formFor(s), s.label), s.id, formFor(s), s.label)));
  out.screens.push(safe(() => measure('m-home-recent', 'tablet', 'm-home-recent @ 平板'), 'm-home-recent', 'tablet', 'm-home-recent @ 平板'));
  out.screens.push(safe(() => measure('m-chat', 'tabland', 'm-chat @ 平板横屏'), 'm-chat', 'tabland', 'm-chat @ 平板横屏'));

  function row(sid, sk, sels){
    host.innerHTML = device(sid, sk);
    return sels.map(s => { const e = host.querySelector(s); return e ? {sel:s, ...snap(e)} : null; })
               .filter(Boolean);
  }
  /* 消息（提案）：列表上方**一块都不留**之后，盯的是「第一行距顶」。
     2026-10-10 实测 216px —— 删掉那条 36px 搜索框 + 10px 外边距之前是 262px，
     而 `.rows` 自己那 8px 上边距不随谁走（别把回收算成 54px）。 */
  out.spacing['消息(提案)'] = row('m-home-recent','phone',
    ['.topbar','.tbinner','.scroll','.pad','.fchips','.rows','.row','.annot']);
  /* 搜索态顶栏：盯「返回钮中线 vs 输入框中线」。标题态那套偏移（align-self:flex-start
     + margin-top:-3px，为的是去对 24px 大标题的视觉基线）挪到输入框旁边就是错位 ——
     2026-10-10 实测差 7.5px（钮 62 / 框 69.5）。改完两者都该落在 67。 */
  out.spacing['全局搜索(手机)'] = row('m-gsearch','phone',
    ['.topbar','.tbinner','.ibtn.plain','.search.fill','.search.fill input','.spsec','.spitem']);
  /* 建群对话框：盯「框到屏幕」与「框到内容」两层内缩。曾经两层叠成 38px，
     而 340px 的框只剩 264px 放内容（见 `.dlg .dbody` 的注释）。 */
  out.spacing['建群对话框(手机)'] = row('m-groupcreate','phone',
    ['.device','.dialog','.dlg','.dlg h2','.dbody','.field','.inp','.mem','.mem .cb']);
  out.spacing['历史搜索(手机)'] = row('m-histsearch','phone',
    ['.hs-f','.hsbody','.hsrow','.hsdatebtn','.hsres','.annot']);
  out.spacing['历史搜索(日期)'] = row('m-histdate','phone',
    ['.hs-f','.hsbody','.drpfld','.drpq','.hscal','.drpact']);
  out.spacing['上传头像(手机)'] = row('m-set-avatar','phone',
    ['.topbar','.scroll','.pad','.card','.avstep','.asb','.tt','.annot']);
  out.spacing['编辑资料(头像)'] = row('m-set-profile','phone',
    ['.topbar','.scroll','.pad','.avup','.avup-pick','.avup-rule','.seg']);
  /* 会话输入区：盯**那一排图标有没有被挤到换行**，以及输入区的竖向账。
     这一排原先手抄三遍、漏画两遍（m-folderin / m-screencard 根本没有），
     2026-10-10 收成 toolsRow()。手机上 8 颗 28dp 在 373px 里恰好一行，
     输入区总高 110.8px = 排 28 + 其下 7 + 输入框 38 + 底行 15.8(+5) + 上下 16( +1px 上边框)。
     换行的信号是 `.tools` 高从 28 跳到 64 —— 那说明钮数或图标尺寸变了。 */
  out.spacing['会话输入区(手机)'] = row('m-chat','phone',
    ['.topbar','.scroll','.composer','.composer .tools','.composer .box','.composer .hintline']);
  /* 补排那两屏盯的是**代价**：工具排 28 + 下边距 7 = 35px，直接从消息区里扣。
     这两条一旦消失，说明「漏画」又回来了。 */
  out.spacing['会话输入区(收文件夹)'] = row('m-folderin','phone',
    ['.topbar','.scroll','.composer','.composer .tools','.composer .box','.composer .hintline']);
  out.spacing['会话输入区(桌面)'] = row('d-chat','expanded',
    ['.cmp','.cmp .tools','.cmp .ctxt','.cmp .cinbar']);

  /* 墨迹检查：`.ell` 带 overflow:hidden，若行盒比字形还矮就会切掉降部。
     用 canvas 的真实字体度量算，不靠肉眼。 */
  out.ink = [];
  const ctx = document.createElement('canvas').getContext('2d');
  function ink(sid, sk){
    host.innerHTML = device(sid, sk);
    host.querySelectorAll('.ell').forEach(el => {
      const cs = getComputedStyle(el);
      const txt = (el.textContent || '').trim();
      if (!txt) return;
      /* 盒子高度取 rect：行内盒的 clientHeight 按规范就是 0（Chrome 如此），
         而真正没铺开的盒子 rect 也是 0，两种情形都会自然跳过。
         只用 clientHeight 当判据会把「`.ell` 掉回行内、省略号失效」这类真问题藏掉。 */
      const box = el.clientHeight || el.getBoundingClientRect().height;
      if (!box) return;
      ctx.font = cs.fontStyle + ' ' + cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
      const m = ctx.measureText(txt);
      const h = m.actualBoundingBoxAscent + m.actualBoundingBoxDescent;
      if (h > box + 0.5) {
        out.ink.push({screen:sid, form:sk, txt:txt.slice(0,24),
                      font:cs.fontSize + '/' + cs.lineHeight + ' w' + cs.fontWeight,
                      ink:+h.toFixed(1), box:+box.toFixed(1), over:+(h - box).toFixed(1)});
      }
    });
  }
  SCREENS.forEach(s => { try { ink(s.id, formFor(s)); } catch (e) {} });
  host.remove();
  document.body.innerHTML = '<pre id="__probe__">' + JSON.stringify(out) + '</pre>';
})();
</script>
"""


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:4173").rstrip("/")
    src = open(os.path.join(HERE, "relay.html"), encoding="utf-8").read()
    out_path = os.path.join(HERE, "_probe-relay.html")
    open(out_path, "w", encoding="utf-8").write(src.replace("</body>", PROBE + "</body>", 1))
    profile = tempfile.mkdtemp(prefix="probe-")
    try:
        p = subprocess.Popen(
            [CHROME, "--headless", "--no-sandbox", "--disable-gpu",
             "--disable-dev-shm-usage", "--no-proxy-server",
             "--user-data-dir=" + profile, "--virtual-time-budget=8000",
             "--dump-dom", base + "/_probe-relay.html"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            out, err = p.communicate(timeout=240)
        except subprocess.TimeoutExpired:
            p.kill(); out, err = p.communicate()
    finally:
        shutil.rmtree(profile, ignore_errors=True)
        if os.path.exists(out_path):
            os.remove(out_path)

    # 只在**去掉了 script 块之后**的 DOM 里找探针结果。
    # 否则脚本源码里那句 `'<pre id="__probe__">' + JSON.stringify(out)` 会被正则命中，
    # 抓到的是 `' + JSON.stringify(out) + '` 这截源码，json.loads 报
    # 「Expecting value: line 1 column 1」——把「页面脚本抛错、探针根本没跑」
    # 这个真因伪装成一次 JSON 解析失败（2026-10-09 实际踩过）。
    dom = re.sub(r'<script\b[\s\S]*?</script>', '', out or '')
    m = re.search(r'<pre id="__probe__">\s*(\{.*?)\s*</pre>', dom, re.S)
    if not m:
        print("探针没跑出来（页面里某个渲染函数整段抛错）。stderr 尾部：", file=sys.stderr)
        print((err or "")[-800:], file=sys.stderr)
        return 2

    data = json.loads(H.unescape(m.group(1)))
    json.dump(data, open("/tmp/relay-probe.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)

    total = sum(s["n"] for s in data["screens"])
    print(f"渲染 {len(data['screens'])} 屏，发现 {total} 处\n")
    for s in data["screens"]:
        if not s["n"]:
            continue
        print(f"[{s['screen']} @ {s['form']}] {s['n']} 处")
        for i in s["issues"]:
            extra = i.get("txt") or ("+" + str(i.get("d", "")) + "px")
            print(f"    {i['t']:<10} {i['sel'][:28]:<28} {extra}")
        print()
    print("──── 间距快照（页面 px）────")
    for name, rows in data["spacing"].items():
        print(f"\n{name}")
        print(f"  {'选择器':<10} {'x':>7} {'w':>7} {'h':>7}  {'padding':<22} margin")
        for r in rows:
            print(f"  {r['sel']:<10} {r['x']:>7} {r['w']:>7} {r['h']:>7}  "
                  f"{r['pad']:<22} {r['mar']}")

    ink = data.get("ink", [])
    print(f"\n──── 字形墨迹超出盒子（会被 overflow:hidden 切掉）────")
    if not ink:
        print("  无")
    seen = set()
    for r in ink:
        k = (r["form"], r["font"], round(r["over"]))
        if k in seen:
            continue
        seen.add(k)
        print(f"  [{r['form']:<9}] {r['font']:<22} 墨迹{r['ink']:>6} 盒{r['box']:>4} "
              f"超{r['over']:>5}  「{r['txt']}」")
    return 1 if total or ink else 0


if __name__ == "__main__":
    sys.exit(main())
