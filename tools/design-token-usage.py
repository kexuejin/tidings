#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""token 消费率棘轮门禁：采用面不许缩、字面量欠债不许涨。

为什么是棘轮而不是阈值
----------------------
"间距/字阶/圆角不够精致"的根因不是没有 token，而是**没有门禁**：颜色有
`check-design-palette.py` 逐屏对色，所以它一直精致；间距同样有 JSON、有 8 档，
却全仓 0 处消费也无人报红。一步到位要求"全部改完"不现实（785 处 `.dp` 字面量），
所以这里用棘轮：**已采用的不许丢，欠债不许涨**。新代码绕开 token 写字面量时立刻报红，
存量按基线冻结，可以分批还。

口径（三条，都实测得出，不靠约定）：
  1. 采用面 = 至少 1 处真实消费点的 token 集合（`design_contract.scan_usage`，
     证据行必须真的引用该 token，不是"文件里有这个字符串"）。
  2. 欠债 = 字面量计数（`scan_debt`）：shape=`RoundedCornerShape(N.dp)`、
     typography=`fontSize = N.sp`、color=主题外 `Color(0x`、elevation、spacing=全部 `.dp`。
  3. 档外半径 = 没有任何 M3 槽位能表达的圆角值，集合只许缩小。

用法
----
    python3 tools/design-token-usage.py --check      # 门禁
    python3 tools/design-token-usage.py --baseline   # 有意变更后重记基线
    python3 tools/design-token-usage.py --self-test  # 量具自检：已知答案必须报对
"""
import argparse
import datetime
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import design_contract as dc  # noqa: E402

BASELINE_REL = "tools/design-token-usage-baseline.json"


def measure(root):
    code = dc.read_code_tokens(root)
    usage = dc.scan_usage(root, code)
    debt = dc.scan_debt(root)
    inv = dc.scan_shape_inventory(root)
    scale = set(code["shape"].values())
    off_scale = sorted({r for r in inv["raw"] if float(r) not in scale}, key=float)
    adopted = {group: sorted(n for n, v in entries.items() if v["sites"])
               for group, entries in usage.items()}
    sites = {group: {n: v["sites"] for n, v in sorted(entries.items())}
             for group, entries in usage.items()}
    first = {f"{g}.{n}": v["first"] for g, entries in usage.items()
             for n, v in entries.items() if v.get("first")}
    return {"adopted": adopted, "sites": sites, "first": first,
            "debt": debt, "offScale": off_scale}


SCALE_VALUES = (4, 8, 12, 16, 28)   # M3 圆角槽位值，与 design-spec tokens.shape 同源


def compare(current, baseline):
    """返回问题列表。已知答案自检就是喂两份构造数据给这个函数。"""
    issues = []
    for group, names in (baseline.get("adopted") or {}).items():
        lost = sorted(set(names) - set((current["adopted"] or {}).get(group) or []))
        for name in lost:
            issues.append(f"采用面缩小：{group}.{name} 从「已采用」掉回「0 处消费」"
                          f"（基线里曾有消费点 {baseline.get('first', {}).get(f'{group}.{name}', '?')}）")
    # 棘轮只比对基线里**已有**的组 ⇒ 新加一组字面量模式而基线没记时，整组静默不受管。
    # 这正是"只卡已知项会漏掉整类回归"的同一族洞（图标棘轮也踩过一次）。
    # 判据：current 有、baseline 没有的组，必须报红要求先 --baseline 记账，不许默认放行。
    for group in sorted(set(current["debt"]) - set(baseline.get("debt") or {})):
        now = current["debt"].get(group, 0)
        if now:
            issues.append(f"基线缺欠债组：{group} 当前 {now} 处，基线里没有这一组 ⇒ "
                          f"该组完全不受棘轮管；先 --baseline 记账再判")
    for group, count in (baseline.get("debt") or {}).items():
        now = current["debt"].get(group, 0)
        if now > count:
            issues.append(f"欠债上涨：{group} 字面量 {count} → {now}（多了 {now - count} 处绕开 token 的写法）")
    new_radii = sorted(set(current["offScale"]) - set(baseline.get("offScale") or []), key=float)
    for radius in new_radii:
        issues.append(f"新增档外半径：{radius}dp 不属于任何 M3 槽位"
                      f"（槽位值 {sorted(SCALE_VALUES)}）；要加就先改 tokens.shape 再重投影")
    return issues


def read_baseline(root):
    path = os.path.join(root, BASELINE_REL)
    if not os.path.exists(path):
        return None, path
    with open(path, encoding="utf-8") as fh:
        return json.load(fh), path


def baseline_regressions(current, old):
    """**第二十个洞**：重记基线时，这个函数列出「比旧基线差」的方向。

    本工具的 `--baseline` 原来**没有任何守卫**：`--check` 会把「欠债上涨」报红，
    但只要跑一次 `--baseline`，上涨就被无声无息地记进基线，没有 reason、没有痕迹 ——
    同一个棘轮，正门是锁着的，后门是敞开的。`design-screen-diff.py`（差集变大要先给
    `--reason`）与 `design-state-slot-report.py`（覆盖缩小要先给 `--reason`）都有这道守卫，
    只有这里没有 ⇒ **同族判据在三个工具里不一致，本身就是洞**。
    """
    bad = []
    for group, count in (current.get("debt") or {}).items():
        was = (old.get("debt") or {}).get(group)
        if was is None:
            bad.append(f"新增欠债组：{group} {count} 处（旧基线里没有这个组，等于不受棘轮管）")
        elif count > was:
            bad.append(f"欠债上涨：{group} 字面量 {was} → {count}（多了 {count - was} 处）")
    for group, names in (old.get("adopted") or {}).items():
        have = (current.get("adopted") or {}).get(group, [])
        for name in names:
            if name not in have:
                bad.append(f"采用面缩小：{group}.{name} 从「已采用」掉回 0 处消费")
    for r in sorted(set(current.get("offScale") or []) - set(old.get("offScale") or []), key=float):
        bad.append(f"新增档外半径：{r}dp（不属于任何 M3 槽位）")
    return bad


def write_baseline(root, current, old=None, reason=None):
    path = os.path.join(root, BASELINE_REL)
    blob = {"note": "token 消费率棘轮基线。采用面只许扩大、欠债只许减少。"
                    "有意变更（真的还了一批债或新增 token 消费）后跑 --baseline 重记。"
                    "重记时若欠债上涨/采用面缩小/新增档外半径，必须给 --reason，"
                    "reason 与变差清单会一起写进基线（regressions / regressionReason）。",
            "generatedAt": datetime.date.today().isoformat()}
    blob.update({k: current[k] for k in ("adopted", "sites", "debt", "offScale")})
    blob["adoptedFirst"] = current["first"]
    bad = baseline_regressions(current, old or {})
    if bad:
        blob["regressions"] = bad
        blob["regressionReason"] = reason
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(blob, indent=2, ensure_ascii=False) + "\n")
    return path


def self_test():
    """量具自检：喂一条已知缺陷，必须报红；喂一份相同数据，必须不报。"""
    good = {"adopted": {"colors": ["primary"]}, "sites": {}, "first": {"colors.primary": "A.kt:1"},
            "debt": {"shape": 10}, "offScale": []}
    same = json.loads(json.dumps(good))
    assert compare(same, good) == [], "完全相同的数据不该报问题"
    worse_adoption = json.loads(json.dumps(good))
    worse_adoption["adopted"] = {"colors": []}
    assert any("采用面缩小" in i for i in compare(worse_adoption, good)), "采用的 token 掉回 0 处必须报红"
    worse_debt = json.loads(json.dumps(good))
    worse_debt["debt"] = {"shape": 11}
    assert any("欠债上涨" in i for i in compare(worse_debt, good)), "欠债上涨必须报红"
    # iconSizes 必须与 spacing **分开**成组：图标尺寸被 spacing 吞掉时，
    # "图标尺寸消费率"永远量不出来（goal ③）。注意棘轮只比对基线里已有的组，
    # 所以基线必须自己带 iconSizes（首次 --write-baseline 记进去）。
    with_icon = json.loads(json.dumps(good))
    with_icon["debt"] = {"shape": 10, "iconSizes": 4}
    worse_icon = json.loads(json.dumps(with_icon))
    worse_icon["debt"] = {"shape": 10, "iconSizes": 5}
    assert any("iconSizes" in i for i in compare(worse_icon, with_icon)), \
        "图标尺寸欠债上涨必须单独报红（不能被 spacing 吞掉）"
    assert compare(with_icon, with_icon) == [], "图标尺寸欠债不变不该报红"
    missing_group = json.loads(json.dumps(good))
    missing_group["debt"] = {"shape": 10, "iconSizes": 25}
    assert any("基线缺欠债组" in i for i in compare(missing_group, good)), \
        "基线里没有的欠债组必须报红要求记账（否则新加一组字面量完全不受棘轮管）"
    assert compare({"adopted": {}, "sites": {}, "first": {}, "debt": {}, "offScale": []}, good) != [], \
        "空 debt 也不该静默通过（good 里的组在 current 缺失要按 0 处理，不能崩）"
    worse_radius = json.loads(json.dumps(good))
    worse_radius["offScale"] = ["6"]
    assert any("新增档外半径" in i for i in compare(worse_radius, good)), "新增档外半径必须报红"
    improved = json.loads(json.dumps(good))
    improved["debt"] = {"shape": 3}
    improved["adopted"] = {"colors": ["primary", "surface"]}
    assert compare(improved, good) == [], "只还债/只扩大采用不许报红"
    # 第二十个洞：重记基线必须有守卫
    _old = {"debt": {"shape": 10}, "adopted": {"shape": ["small"]}, "offScale": []}
    _same = json.loads(json.dumps(_old))
    assert baseline_regressions(_same, _old) == [], "基线与现状相同不该报变差"
    _worse = json.loads(json.dumps(_old)); _worse["debt"] = {"shape": 12}
    assert any("欠债上涨" in x for x in baseline_regressions(_worse, _old)), \
        "重记基线时欠债上涨必须被列出（否则 --baseline 就是擦红灯的后门）"
    _drop = json.loads(json.dumps(_old)); _drop["adopted"] = {"shape": []}
    assert any("采用面缩小" in x for x in baseline_regressions(_drop, _old)), \
        "重记基线时采用面缩小必须被列出"
    _rad = json.loads(json.dumps(_old)); _rad["offScale"] = ["5"]
    assert any("新增档外半径" in x for x in baseline_regressions(_rad, _old)), \
        "重记基线时新增档外半径必须被列出"
    _newgrp = json.loads(json.dumps(_old)); _newgrp["debt"] = {"shape": 10, "iconSizes": 3}
    assert any("新增欠债组" in x for x in baseline_regressions(_newgrp, _old)), \
        "重记基线时冒出一个旧基线没有的欠债组必须被列出"
    _debt_known_answers()
    _strip_known_answers()
    print("[design-token-usage] 自检通过：5 个已知缺陷 + 4 条重记基线守卫 + 11 条欠债分类已知答案"
          "全部报对，2 个良性变更不报红")
    return 0


# 欠债分类的已知答案表：每一条都对应一个**真的犯过**的错。
_DEBT_FIXTURE = '''\
/* KDoc：改动前的形态是 Modifier.width(300.dp) 写死 —— 注释里的值不是欠债 */
val url = "https://x.example/a"
fun a() {
    Modifier.padding(
        start = 14.dp,
        top = 12.dp,
    )
    Modifier.size(20.dp)
    RoundedCornerShape(12.dp)
    Icon(p, null, Modifier.size(16.dp))
    Modifier.padding(12.dp) // 行尾注释里的 999.dp 也不算
    Modifier.strokeWidth = 2.dp
}

// 第四十八/四十九个洞的已知答案：两组「量具此前量反了」的形态各钉一条。
fun b() {
    // 正确用法：走 token 的 cardElevation **不算** elevation 欠债（旧模式把函数名本身
    // 记成欠债，于是多采用一处 token 会让欠债上涨 —— 棘轮方向写反）。
    CardDefaults.cardElevation(defaultElevation = RelayElevations.Card)
    // 真正的 elevation 字面量：Surface 上的高度值，旧模式零命中。
    Surface(tonalElevation = 6.dp) { }
    Surface(shadowElevation = 2.dp) { }
    // 无参 cardElevation() 吃 M3 默认值 —— 也是绕开 RelayElevations 的写法。
    CardDefaults.cardElevation()
    // 第五十三个洞的已知答案：**海拔归零不算欠债**。0.dp 不是一个高度值，它是一句指令
    // ——「不要 M3 平台阴影」（relayCardShadow 自绘投影时 KDoc 明文要求把 elevation 归零）。
    // 旧模式把这两行记成 2 处欠债 ⇒ 照 KDoc 写正确代码反而让欠债上涨，棘轮方向又写反了。
    CardDefaults.cardElevation(defaultElevation = 0.dp)
    Surface(shadowElevation = 0.dp) { }
    // 跨行 Icon（Kotlin 格式化的主流形态）：旧模式因 `.` 不匹配换行而一处不数。
    Icon(
        painter = p,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
    )
}
'''


def _debt_known_answers():
    """喂一段**每个值都能手算**的源码，逐组核对。

    为什么单独喂夹具而不是只看全仓数字：全仓数字变了看不出是哪一类变的。
    这段夹具把"该算的"与"不该算的"并排放，任何一边判错都立刻红。
    """
    import shutil
    import tempfile
    tmp = tempfile.mkdtemp(prefix="debt-fixture-")
    try:
        rel = os.path.join(tmp, "composeApp/src/commonMain/kotlin")
        os.makedirs(rel)
        with open(os.path.join(rel, "T.kt"), "w", encoding="utf-8") as fh:
            fh.write(_DEBT_FIXTURE)
        got = dc.scan_debt(tmp)
        want = {"spacing": 3, "dimension": 2, "iconSizes": 2, "shape": 1, "elevation": 3}
        assert got["spacing"] == want["spacing"], \
            f"间距欠债只数真间距调用实参：跨行 padding 的 14/12 与单行 padding 的 12 = 3 处（实得 {got['spacing']}）"
        assert got["dimension"] == want["dimension"], \
            f"尺寸欠债 = size(20.dp) + strokeWidth = 2.dp（实得 {got['dimension']}）；图标尺寸不许重复计入"
        assert got["iconSizes"] == want["iconSizes"], \
            f"图标尺寸 = 单行 Icon 的 size(16.dp) + **跨行** Icon 的 size(14.dp) = 2 处" \
            f"（实得 {got['iconSizes']}）—— 只认单行会让 Kotlin 的主流写法整类隐形（第四十九个洞）"
        assert got["shape"] == want["shape"], \
            f"RoundedCornerShape(12.dp) 是圆角 1 处，且**不许**再被 spacing 记一笔（实得 {got['shape']}）"
        assert got["elevation"] == want["elevation"], \
            f"elevation 欠债 = tonalElevation(6.dp) + shadowElevation(2.dp) + 无参 cardElevation() = 3 处" \
            f"（实得 {got['elevation']}）；走 token 的 cardElevation(defaultElevation = RelayElevations.Card) " \
            f"**不算**欠债 —— 旧模式恰好把它记成欠债、把真字面量全漏掉（第四十八个洞）；" \
            f"**海拔归零（ = 0.dp）也不算欠债** —— 0 不是高度值，是「不要 M3 平台阴影」这句指令，" \
            f"relayCardShadow 的 KDoc 明文要求照着写（第五十三个洞）"
        # 反方向：注释里的值一个都不许进任何组
        assert sum(got.values()) == 11, \
            f"这段夹具一共 11 处真欠债（3+2+2+1+3），注释里的 300.dp / 999.dp 一处都不许算，" \
            f"两行 0.dp 归零也不算（实得 {sum(got.values())}）"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _strip_known_answers():
    """`_strip` / `strip_comments` 的两个方向：多剥会假绿，少剥会虚高。"""
    assert dc._strip('x("https://a.b/c")') == 'x("https://a.b/c")', \
        "字符串里的 // 不是注释起点（多剥 = 少读 = 假绿）"
    assert dc._strip("val p = 1.dp  // 深茶绿").strip() == "val p = 1.dp", "空白后的 // 是行尾注释"
    assert "300" not in dc.strip_comments("a(1.dp)\n/* 300.dp */\nb(2.dp)"), \
        "块注释（含 KDoc）里的字面量必须剥掉 —— 它记的是旧写法，不是新欠债"
    assert dc.strip_comments("a(1.dp)\n/* 300.dp */\nb(2.dp)").count("\n") == 2, \
        "剥块注释必须保住行号，否则证据行号会错位"


def main():
    ap = argparse.ArgumentParser(description="token 消费率棘轮门禁")
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--baseline", action="store_true")
    ap.add_argument("--reason", default=None,
                    help="重记基线时覆盖了变差的方向（欠债上涨/采用面缩小/新增档外半径）必填")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    current = measure(args.root)
    if args.baseline:
        old, _oldpath = read_baseline(args.root)
        bad = baseline_regressions(current, old or {})
        if bad and not args.reason:
            print(f"[design-token-usage] FAIL — 重记基线会把 {len(bad)} 条变差的方向记进基线，"
                  f"必须给 --reason 说明为什么可以接受：")
            for line in bad:
                print(f"  · {line}")
            print("\n重记基线不是把红灯擦掉的手段。确属有意（例如为了保真刻意用原型精确值、"
                  "或某批欠债已排期）就带上 --reason 重跑，变差清单会写进基线备查。")
            return 1
        print(f"[design-token-usage] 基线已写入 {write_baseline(args.root, current, old, args.reason)}")
        if bad:
            print(f"  ⚠ 本次重记记下了 {len(bad)} 条变差的方向（已连同 reason 写进基线）：")
            for line in bad:
                print(f"    · {line}")
        for group, names in current["adopted"].items():
            total = len(current["sites"][group])
            print(f"  {group}: 采用 {len(names)}/{total}")
        print(f"  欠债 {current['debt']}；档外半径 {current['offScale']}")
        return 0
    baseline, path = read_baseline(args.root)
    if baseline is None:
        print(f"[design-token-usage] 基线文件不存在：{path}\n  先跑 --baseline 建立基线（会有意变更才重记）。")
        return 1
    issues = compare(current, baseline)
    shrink = "；".join(f"{g} {sum(1 for v in (current['sites'][g] or {}).values() if v > 0)}"
                      f"/{len(current['sites'][g] or {})}" for g in sorted(current["sites"]))
    if issues:
        print(f"[design-token-usage] FAIL — {len(issues)} 条棘轮违规（采用率 {shrink}）")
        for line in issues:
            print(f"  · {line}")
        print("\n修法：新代码改用 MaterialTheme.colorScheme/typography/shapes/RelayElevations/"
              "LocalAppSpacing.current，而不是字面量；确实是合理新增档外半径时，先加进 "
              "design/design-spec.json 的 tokens.shape 并重投影，再重记基线。")
        return 1
    print(f"[design-token-usage] OK — 采用率 {shrink}；欠债 {current['debt']}；"
          f"档外半径 {current['offScale']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
