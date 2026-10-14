#!/usr/bin/env python3
"""原型渲染真值 dump：computed style + 几何 + 图标 + CSS 变量 → prototype/layout/<shot>.json

skill: prototype-to-compose / Step A。**设计侧的唯一读取器是渲染结果，不是 CSS 源文本。**

为什么不再写一个 CSS 正则器：var()/calc/% 在源文本里是符号，只有浏览器算完才是数；
死规则与被覆盖的声明在源文本里和生效值长得一模一样——实测 fidelity-ledger 的
"间距 133 种"就是被这个灌水的（棘轮因此被一行声明顶红）。getComputedStyle 给生效值，
getBoundingClientRect 给位置与大小——**取值集合差集永远答不出来的那一半，这里有**。

与截图同条件：复用 shoot-relay.py 的 SHOTS 表（不在本文件抄第二份屏清单）、
同一支 Chrome、同一套 --window-size/--hide-scrollbars/--force-device-scale-factor
语义，所以几何与像素出自同一次渲染，不会出现"dump 说 12px、图上是另一个数"。

不改设计源：relay.html 一个字都不动（它有棘轮门禁，注入脚本等于让量具改自己的考卷）。
用一个 wrapper 页面把 iframe 装进来，采集脚本 fetch 后在 **iframe 的 realm** 里 eval
——这样脚本里的 document/getComputedStyle 指向原型而不是 wrapper。

用法（先起静态服务器，与 shoot-relay.py 同一个，根目录必须是 prototype/）：
    cd prototype && python3 -m http.server 4173 --bind 127.0.0.1
    python3 prototype/dump-layout.py                  # 全量，已存在的 json 跳过
    python3 prototype/dump-layout.py --force home     # 关键词过滤 + 重拍
    python3 prototype/dump-layout.py --self-test      # 先验量具本身
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))

# 文件名带横杠，不是合法模块名：importlib.import_module("shoot-relay") 会被解释成
# "包 shoot 的子模块 relay" 而 ImportError。按文件路径装载。
_spec = importlib.util.spec_from_file_location("shoot_relay", os.path.join(HERE, "shoot-relay.py"))
shoot_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shoot_mod)

CHROME = shoot_mod.CHROME
BASE = shoot_mod.BASE
OUT_DIR = os.path.join(HERE, "layout")
WRAPPER = os.path.join(HERE, "dump.html")           # 生成物，已进 .gitignore，别手改
COLLECTOR = os.path.join(HERE, "layout-collect.js")
SERVER = "http://127.0.0.1:4173/dump.html"          # 与 relay.html 同源
SCALE = 2.0  # 原型 PNG 像素 / CSS px（实测四条吻合证据）；px ÷ 2.0 = dp

# 采集器里那个 return JSON.stringify(...) 在 <script> 语境下是**语句**不是表达式：
# eval 的完成值是 undefined，所以必须显式包一层调用。这是"渲染成功但拿不到 payload"
# 那一族症状的根因，别再让下一个人在 dump.html 里找。
WRAPPER_TMPL = """<!doctype html><html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:0;background:#fff}</style></head><body>
<iframe id="f" src="__SRC__"></iframe>
<pre id="out">PENDING</pre>
<script>
const f = document.getElementById('f');
const fail = (m) => { document.getElementById('out').textContent = '@@ERR ' + m; };
const done = (p) => { const o = document.getElementById('out'); o.textContent = '';
  o.appendChild(document.createTextNode('@@' + p)); };
f.addEventListener('load', () => setTimeout(run, 300));
async function run() {
  let d;
  try { d = f.contentDocument; } catch (e) { return fail('contentDocument 不可达: ' + e); }
  if (!d || !d.body) return fail('iframe 文档为空');
  if (d.body.dataset.bare !== '1') return fail('未走 bare=1，外壳会污染几何');
  if (!d.body.children.length) return fail('iframe 文档没有内容');
  let js;
  try { js = await fetch('layout-collect.js').then((r) => { if (!r.ok) throw new Error(r.status); return r.text(); }); }
  catch (e) { return fail('采集脚本取不到: ' + e); }
  let payload;
  try { payload = f.contentWindow.eval(js + '\\n__collect()'); }
  catch (e) { return fail('采集脚本异常: ' + e); }
  if (!payload) return fail('采集脚本没有返回');
  done(payload);
}
setTimeout(() => { if (document.getElementById('out').textContent === 'PENDING') run(); }, 4000);
</script></body></html>
"""


def write_wrapper(params):
    """wrapper 写进 prototype/dump.html：与 relay.html 同源，否则 contentWindow 被
    same-origin policy 挡住，采集直接失败。"""
    src = "%s&%s" % (BASE, "&".join("%s=%s" % (k, v) for k, v in params.items() if v != ""))
    with open(WRAPPER, "w", encoding="utf-8") as fh:
        fh.write(WRAPPER_TMPL.replace("__SRC__", src))
    return src


def parse_payload(dom, name):
    """从 --dump-dom 的输出里取 payload。失败必须给出**是哪一种**失败。"""
    m = re.search(r'<pre id="out">@@(.*?)</pre>', dom, re.S)
    if not m:
        if "<pre id=\"out\">PENDING" in dom:
            return None, "wrapper 脚本没跑完（PENDING 未替换）"
        if "@@ERR" in dom:
            e = re.search(r'@@ERR ([^<]*)', dom)
            return None, "采集主动报错：%s" % e.group(1)[:120]
        if "<iframe" not in dom:
            return None, "dump 里没有 iframe——服务器根目录不是 prototype/？"
        return None, "out 节点缺失（页面结构变了，检查 dump.html 是否最新）"
    raw = html_unescape(m.group(1))
    try:
        data = json.loads(raw)
    except ValueError as e:
        return None, "payload 不是合法 JSON：%s（前 80 字：%s）" % (str(e)[:80], raw[:80])
    if not data.get("nodes"):
        return None, "渲染了但一个可见节点都没有（空屏或量具坏，不静默出空表）"
    return data, None


def html_unescape(s):
    return (s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
             .replace("&#39;", "'").replace("&amp;", "&"))


def server_up(url=SERVER.split("?")[0]):
    import urllib.request
    try:
        with urllib.request.urlopen(url, timeout=2) as r:
            return r.status == 200
    except Exception:
        return False


def source_sha(relay=os.path.join(HERE, "relay.html")):
    """钉住渲染所用设计源的版本。批次跑一半时别的会话改了 relay.html（实测
    12:06 改源、12:01-12:14 在渲），不记版本就分不清哪些图出自哪份稿——
    「设计侧唯一读取器是渲染结果」这句话要有审计链才成立。"""
    return hashlib.sha256(open(relay, "rb").read()).hexdigest()[:12]


def dump_one(name, size, screen, theme, w, h, extra):
    out = os.path.join(OUT_DIR, name + ".json")
    tmp = out + ".tmp"
    src_sha = source_sha()
    write_wrapper({"size": size, "screen": screen, "theme": theme,
                   "extra": extra.lstrip("&")})
    qs = "&".join(x for x in ["size=" + size, "screen=" + screen, "theme=" + theme,
                              extra.lstrip("&"), "dump=1"] if x)
    profile = tempfile.mkdtemp(prefix="dump-")
    cmd = [CHROME, "--headless", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
           "--hide-scrollbars", "--no-proxy-server",
           "--user-data-dir=" + profile,
           "--virtual-time-budget=3000", "--window-size=%d,%d" % (w, h),
           "--dump-dom", "%s?%s" % (SERVER, qs)]
    t0 = time.time()
    try:
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        try:
            dom, _ = proc.communicate(timeout=60)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
            return "FAIL  %s（Chrome 60s 未返回）" % name
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    data, err = parse_payload(dom, name)
    if err:
        return "FAIL  %s（%s）" % (name, err)
    data = {"meta": {"shot": name, "size": size, "screen": screen, "theme": theme,
                     "windowCss": [w, h], "scale": SCALE, "extra": extra.lstrip("&"),
                     "sourceSha": src_sha,
                     "collector": os.path.basename(COLLECTOR),
                     "collectorSha": hashlib.sha256(open(COLLECTOR, "rb").read()).hexdigest()[:12],
                     "dumpMs": int((time.time() - t0) * 1000)}, **data}
    for p in (out, tmp):
        if os.path.exists(p):
            try:
                os.remove(p)
            except OSError as e:
                return "FAIL  %s（旧产物删不掉：%s）" % (name, e)
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, out)
    # 完成判据带 mtime：与 shoot-relay.py 同款教训——目标本来就在时，
    # "文件存在"分不清这轮新写的还是上一轮的，删除被静默 no-op 就会混过去。
    if not (os.path.exists(out) and os.path.getmtime(out) >= t0 - 1):
        return "FAIL  %s（写完又不在，被环境拦了）" % name
    return "ok    %-26s nodes=%-4d icons=%-3d vars=%-3d %8d B" % (
        name + ".json", len(data["nodes"]), len(data["icons"]), len(data["vars"]),
        os.path.getsize(out))


def collect_comments():
    """HTML 注释是设计者的意图批注（跨端语义锚点，如 'CaptureApp.vue:550-600'），
    渲染后不在 DOM 里，只能从源码提——这是 dump 拿不到的那一类信息。"""
    raw = open(os.path.join(HERE, "relay.html"), encoding="utf-8").read()
    return [{"line": raw[:m.start()].count("\n") + 1, "text": " ".join(m.group(1).split())[:300]}
            for m in re.finditer(r"<!--(.*?)-->", raw, re.S) if " ".join(m.group(1).split())]


def main():
    global OUT_DIR
    ap = argparse.ArgumentParser(description="原型渲染真值 dump")
    ap.add_argument("keys", nargs="*", help="只跑名字含关键词的 shot")
    ap.add_argument("--force", action="store_true", help="已存在的 json 也重拍")
    ap.add_argument("--self-test", action="store_true", help="先验量具")
    ap.add_argument("--out", default=None, help="输出目录，默认 prototype/layout/")
    args = ap.parse_args()
    if args.out:
        OUT_DIR = os.path.abspath(args.out)
    os.makedirs(OUT_DIR, exist_ok=True)

    if not server_up():
        print("服务器没起。在 prototype/ 目录下：python3 -m http.server 4173 --bind 127.0.0.1\n"
              "（根目录必须是 prototype/，因为 wrapper 与采集脚本都放那儿）", file=sys.stderr)
        return 1

    if args.self_test:
        return self_test()

    shots = shoot_mod.SHOTS
    if args.keys:
        shots = [s for s in shots if any(k in s[0] for k in args.keys)]
    done = bad = skipped = 0
    for shot in shots:
        if not args.force and os.path.exists(os.path.join(OUT_DIR, shot[0] + ".json")):
            skipped += 1
            continue
        line = dump_one(*shot)
        print(line, flush=True)
        if line.startswith("FAIL"):
            bad += 1
        else:
            done += 1
    comments = collect_comments()
    with open(os.path.join(OUT_DIR, "_comments.json"), "w", encoding="utf-8") as fh:
        json.dump(comments, fh, ensure_ascii=False)
    print("\n共 %d：成功 %d / 失败 %d / 跳过已存在 %d；注释 %d 条 -> %s"
          % (len(shots), done, bad, skipped, len(comments), OUT_DIR))
    return 1 if bad else 0


def self_test():
    """量具先验：已知答案必须对得上，坏输入必须 FAIL（skill Step 6/8 的硬规则 6）。"""
    fails = []

    def check(label, ok, detail=""):
        print("  %s %s %s" % ("✓" if ok else "✗", label, detail))
        if not ok:
            fails.append(label)

    print("[self-test] A) m01-home：padding 必须是解析后的 CSS px 数值")
    line = dump_one("m01-home", "phone", "m-home-recent", "light", 445, 904, "")
    print("  " + line)
    if not line.startswith("ok"):
        print("[self-test] FAIL（A 步就没跑通）"); return 1
    data = json.load(open(os.path.join(OUT_DIR, "m01-home.json"), encoding="utf-8"))
    pads = [v for n in data["nodes"] for v in n["style"]["pad"] if v is not None]
    check("有数值 padding", bool(pads), "%d 个" % len(pads))
    check("没有 var() 漏进结果", not any(isinstance(v, str) and "var(" in str(v) for v in pads))
    check("canvas 宽=393 CSS px（手机壳）", data["canvasViewport"]["w"] == 393,
          "实测 %s" % data["canvasViewport"]["w"])
    print("[self-test] B) 锚点必须数得出顶栏、重复行与兄弟间隙")
    a = data["anchors"]
    check("topbar", bool(a.get("topbar")), str(a.get("topbar")))
    check("rows>=1 组", len(a.get("rows", [])) >= 1, "%d 组" % len(a.get("rows", [])))
    check("siblingGaps>=1 档", len(a.get("siblingGaps", {})) >= 1, str(a["siblingGaps"])[:70])
    print("[self-test] C) CSS 变量必须给解析后的值（这是源文本正则拿不到的那一半）")
    check("vars 非空", len(data["vars"]) > 0, "%d 个变量" % len(data["vars"]))
    bad_var = [k for k, v in data["vars"].items() if "var(" in v]
    check("变量值里不再有 var()", not bad_var, str(bad_var[:3]))
    print("[self-test] D) 坏输入必须 FAIL，不许静默出空表")
    cases = [
        ("空 out（脚本没跑完）", '<html><pre id="out">PENDING</pre></html>'),
        ("采集主动报错", '<html><pre id="out">@@ERR 采集脚本异常: boom</pre></html>'),
        ("没有 iframe", "<html><body>nothing</body></html>"),
        ("节点为空", '<html><pre id="out">@@{"nodes":[],"icons":{},"vars":{}}</pre></html>'),
    ]
    for label, dom in cases:
        d, err = parse_payload(dom, "x")
        check("拒绝：" + label, d is None and bool(err), (err or "")[:46])
    print("[self-test] " + ("PASS" if not fails else "FAIL：%s" % fails))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())