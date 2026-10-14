#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""状态槽覆盖报告 + 棘轮门禁：适配完成度的分母是**状态槽**，不是屏数。

为什么不能拿"屏适配 10/10"当完成度
--------------------------------
屏数会把"深色没做、大字号没做、横屏没做、平板三断点没做、IME 弹出没做"全部藏起来：
一屏只要渲染过一次就算完成。所以这里把分母换成 (屏 × 声明状态) 与跨屏一致性维度：

  · scope=screen 的槽 = 规范 screens[].states 里声明过的状态，证据 = **该屏自己**的预览函数；
  · scope=cross-cutting 的槽 = 深色/大字号/横竖屏/平板三断点/IME/对话框/搜索，
    证据 = 全局预览函数（桌面端 Desk*/Window* 命名归不到屏，故不按屏计分）。

证据来源是 `scaffold/androidApp/src/screenshotTest/kotlin` 下的 @Preview 函数名 ——
**它是可判假的**：删掉一个预览函数，对应的槽立刻掉回未覆盖。

用法
----
    python3 tools/design-state-slot-report.py            # 出报告
    python3 tools/design-state-slot-report.py --check    # 棘轮门禁（覆盖数不许缩）
    python3 tools/design-state-slot-report.py --baseline # 有意变更后重记基线
    python3 tools/design-state-slot-report.py --self-test
    python3 tools/design-state-slot-report.py --markdown docs/plans/xxx.md
"""
import argparse
import copy
import datetime
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import design_contract as dc  # noqa: E402

SPEC_REL = "design/design-spec.json"
BASELINE_REL = "tools/design-state-slot-baseline.json"


def load_spec(root):
    with open(os.path.join(root, SPEC_REL), encoding="utf-8") as fh:
        return json.load(fh)


def _prefix_len(preview, pref):
    """该屏的前缀里能匹配这个预览名的最长者长度；一个都不匹配返回 0。"""
    best = 0
    for x in pref:
        if x and preview.startswith(x) and len(x) > best:
            best = len(x)
    return best


def attribute(previews, prefixes, unattributable=(), render_targets=None):
    """把一个预览判给**唯一**一屏：先看它渲染了哪个屏，判不出才看名字前缀。返回 {预览名: {屏, ...}}。

    第一层：渲染目标（render_targets，由 `design_contract.scan_render_targets` 扫函数体得出）
    ------------------------------------------------------------------------------------
    预览函数名**会撒谎**，实测 4 个帧的名字认领的屏 ≠ 实际渲染的屏，全部是 `files` 抢
    `chat` 的帧（`ChatFilesPhonePreview`/`ChatFolderPhonePreview`/`ChatTransferFailedPhonePreview`/
    `ChatTransferResumePhonePreview`，体内都是 `ChatScreen`）。名字前缀分不出"作者故意写的
    别名"和"意外嵌套"，渲染目标可以。所以渲染目标赢。

    但**作者声明的别名不能被误伤**：`files` 声明 `ChatFiles`/`ChatFolder`/`ChatTransfer`
    是有意的（这些帧渲染的聊天界面里带文件卡）。规则是 —— 渲染目标 T 赢，同时
    **不比 T 的前缀匹配更短**的声明前缀可以并列（同长或更长才算作者显式声明）。
    实测这一条是决定性的：无条件让渲染目标独占，`files/normal` 与 `files/error` 两条
    故意别名会一起掉（屏内 42→41）；加上"更长前缀可并列"后只剩 `chat/transfer_failed`
    一处修正（42→43），`files` 的两条保住。

    第二层：名字前缀最长者（判不出渲染目标的帧走这条）
    ------------------------------------------------------------------------------------
    `screenPrefixes` 实测存在**嵌套**：`settings` 的 `Settings` 是 `settings-more` 的
    `SettingsMore` 的前缀；`chat` 的 `Chat` 是 `files` 的 `ChatFiles`/`ChatFolder`/
    `ChatTransfer` 的前缀。纯 `startswith` 会让一个预览同时算多屏的证据，于是：

      · 覆盖数**虚高**（37 条预览被 >1 个 (屏,槽) 认领）；
      · 更糟的是**删掉本屏真帧，覆盖数一个不掉** —— 实测删掉 `SettingsPhonePreview`/
        `ChatPhonePreview`/`CabinetMinePhonePreview` 后，`settings/normal`、`chat/normal`、
        `conversations/normal`、`cabinet/normal` **全部仍是已覆盖**，因为它们被邻屏帧养着。
        这与"拿别的屏的证据充数"是同一个错，只是更隐蔽：报告上看不出哪一屏其实没有自己的帧。

    裁决规则：一个预览归前缀**最长**的那一屏；长度相同才并列 —— 并列是 spec 显式声明的
    别名（如 `conversations` 与 `cabinet` 共用前缀 `Cabinet`），不是意外。

    `unattributablePrefixes`（`Desk`/`Window`/`Dialog`/`Sheet`/`Adaptive`）也参加同一场
    竞赛，但它代表"归不到任何屏"：只有**严格长于**所有屏前缀匹配时才算数。这样
    `DeskAgentsCompactPreview` 谁也归不到（正确），而 `DeskFilesXxx`/`DeskCabinetXxx`
    仍归 `files`/`cabinet`（`DeskFiles` 比 `Desk` 长）—— 否则一句 `Desk` 就会把
    spec 里显式声明的桌面端屏前缀全部误伤。
    """
    rt = render_targets or {}
    un = tuple(unattributable or ())
    best_len = {}
    owners = {}
    for screen, pref in prefixes.items():
        for p in previews:
            if p in rt:
                continue  # 渲染目标赢，名字前缀不参与（下面单独裁决）
            n = _prefix_len(p, tuple(pref or ()))
            if not n:
                continue
            if n > best_len.get(p, 0):
                best_len[p] = n
                owners[p] = {screen}
            elif n == best_len[p]:
                owners[p].add(screen)
    for p in previews:
        if p in rt:
            continue
        n = _prefix_len(p, un)
        if n and n > best_len.get(p, 0):
            owners.pop(p, None)
            best_len[p] = n
    for p, target in rt.items():
        if p not in previews or target not in prefixes:
            continue
        best = _prefix_len(p, tuple(prefixes.get(target) or ()))
        own = {target}
        for screen, pref in prefixes.items():
            if screen == target:
                continue
            n = _prefix_len(p, tuple(pref or ()))
            if n and n >= best:
                own.add(screen)
        n_un = _prefix_len(p, un)
        if n_un and n_un > best:
            own = set()
        best_len[p] = best
        if own:
            owners[p] = own
        else:
            owners.pop(p, None)
    return owners


def evaluate(spec, previews, render_targets=None):
    """返回 {units: [...], unclassified: [...], cross: {...}}。纯函数，自检直接喂它。

    render_targets = {预览名: 屏 id}（`design_contract.scan_render_targets` 扫函数体得出）；
    自检直接喂，主流程由 main() 扫真实文件。
    """
    slots = spec.get("stateSlots") or {}
    vocab = {v["id"]: v for v in slots.get("vocabulary") or []}
    prefixes = slots.get("screenPrefixes") or {}
    unattributable = tuple(slots.get("unattributablePrefixes") or ())
    owners = attribute(previews, prefixes, unattributable, render_targets)
    evidence = {sid: sorted(p for p in previews if re.search(v["match"], p))
                for sid, v in vocab.items()}
    # 词表只读**函数名**，而作者对"这一帧钉的是哪个槽"的声明常常写在别处（夹具 KDoc、
    # 变量名、渲染目标）。名字对不上时，工具会把已有的证据报成缺口——见 `previewAliases`
    # 的说明。别名是**逐 (屏, 槽)** 的显式声明，不放松任何全局正则，因此不会像前缀
    # 嵌套那样把证据漏给邻屏。
    aliases = {}
    for a in ((slots.get("previewAliases") or {}).get("entries") or []):
        bucket = aliases.setdefault((a.get("screen"), a.get("slot")), [])
        for p in (a.get("previews") or []):
            if p not in bucket:
                bucket.append(p)
    units = []
    # 跨屏维度只计一次：桌面/平板预览名归不到屏，按屏重复计会让分母虚高 6 倍
    for sid, v in vocab.items():
        if v.get("scope") != "cross-cutting":
            continue
        units.append({"screen": "(跨屏)", "slot": sid, "scope": "cross-cutting",
                      "evidence": evidence[sid], "covered": bool(evidence[sid]),
                      "attributable": False})
    for screen, entries in (slots.get("perScreen") or {}).items():
        for entry in entries:
            sid = entry["slot"]
            if sid not in vocab or vocab[sid].get("scope") == "cross-cutting":
                continue
            hits = evidence[sid]
            # 只有"判给本屏"的预览才算本屏证据（见 attribute()：最长前缀唯一裁决）
            own = [p for p in hits if screen in (owners.get(p) or ())]
            elsewhere = [p for p in hits if p not in own]
            ambiguous = [p for p in own if len(owners.get(p) or ()) > 1]
            # 显式别名：逐 (屏, 槽) 声明"这一帧钉的就是本槽"。它**绕开**词表与归属
            # 两层启发式，因此必须写 why，且由 design-spec-project.py 校验"别名真的
            # 改变了什么"（不改任何东西的别名 = 掩盖了它本来不需要）。
            aliased = [p for p in aliases.get((screen, sid), []) if p in previews and p not in own]
            own = sorted(own + aliased)
            ambiguous = [p for p in own if len(owners.get(p) or ()) > 1]
            # 「借来的证据」：这一帧**不是本屏的**。
            #
            # 判据是"谁渲染的它"，不是"谁的名字像它"：帧的渲染目标（`render_targets`）
            # 指向另一块屏 ⇒ 对本屏来说是借来的；判不出渲染目标时，退回名字归属，
            # 只有**独占**归属（`owners[p] == {本屏}`）才算自己的，并列归属也算借的。
            #
            # 为什么必须单独判：`ambiguous` 早就被算出来了，却只写进报告当注脚
            # （「同时在养多屏的帧」一节），**没有任何门禁读它**。于是
            # `conversations/error` 长期由 `CabinetErrorPhonePreview`（渲染的是文件柜）
            # 顶着，而真正钉这一槽的 `ConversationsBannerPhonePreview` 被写成"别名"——
            # 量具反过来指责真证据冗余。
            #
            # 一个槽的覆盖**全靠借来的帧**时，那句话是假的："本屏已覆盖"其实是
            # "邻屏的帧名字像本屏"。这一条判据把方向摆正：
            # **借来的帧可以补强，但不许独立支撑一个槽。**
            def _own_frame(p):
                target = (render_targets or {}).get(p)
                if target:
                    return target == screen
                return (owners.get(p) or set()) == {screen}

            borrowed = [p for p in own if not _own_frame(p)]
            propped = bool(own) and len(borrowed) == len(own)
            units.append({"screen": screen, "slot": sid, "scope": "screen",
                          "evidence": own, "covered": bool(own),
                          "attributable": True,
                          "ambiguous": ambiguous,
                          "borrowed": borrowed,
                          "propped": propped,
                          "aliasEvidence": aliased,
                          "evidenceElsewhere": elsewhere if not own else []})
    # 「不适用」：模块边界/平台边界决定的、预览证据**在原理上不可得**的槽。
    # 不计入分母（skill Step 7）。判据是证据来源的可见性，不是"懒得做"——
    # 所以每条规则都要写 reason，且 reason 必须指向具体的模块/平台事实。
    na_rules = slots.get("notApplicable") or {}
    na_pairs = set()
    for rule in (na_rules.get("rules") or []):
        for slot in (rule.get("slots") or []):
            na_pairs.add((rule.get("screen"), slot))
    na_units = []
    kept = []
    for u in units:
        if (u["screen"], u["slot"]) in na_pairs:
            u["applicable"] = False
            u["covered"] = False
            na_units.append(u)
        else:
            u["applicable"] = True
            kept.append(u)
    units = kept
    classified = set()
    for sid in evidence:
        classified |= set(evidence[sid])
    for bucket in aliases.values():
        classified |= {p for p in bucket if p in previews}
    unclassified = [p for p in previews if p not in classified]
    return {"units": units, "unclassified": unclassified, "evidence": evidence,
            "notApplicable": na_units, "notApplicableNote": na_rules.get("note"),
            "previewAliases": (slots.get("previewAliases") or {}).get("entries") or [],
            "unattributablePrefixes": list(unattributable)}


def classify(result, spec, root=None, hit_reader=None):
    """把"没有预览证据"的缺口再分两档：已实现未验证 / 未实现。

    为什么必须由工具分，而不是报告里手写
    ------------------------------------
    "这个槽代码里已经实现了，只差一个 @Preview" 这句话**是可以判假的**：只要声明一个
    锚点（文件 + 正则），工具就能数出命中数。手写这一档的代价是——报告说"cabinet 有
    error 分支"，实际 `CabinetUiState.errorMessage` 只被控制器写入、**没有任何 UI 读它**；
    报告说"remote-view 有错误态"，实际 `enum class ScreenPhase` 里**根本没有 Error 相位**。
    这两种情况下补预览只会渲染空气，而手写表格不会因此报红。

    判据（三态，缺一不可）：
      · 声明了锚点且命中 > 0 → implemented-unverified（补预览可转绿）
      · 声明了锚点但命中 = 0 → unimplemented（锚点被删/从未实现，必须写代码）
      · **没声明锚点**          → unimplemented 且标注 no-anchor（不许默认当"已实现"）

    锚点从 spec.stateSlots.implementationAnchors 读，形状 {screen, slot, file, match, why}。
    """
    anchors = {}
    for a in ((spec.get("stateSlots") or {}).get("implementationAnchors") or {}).get("entries") or []:
        anchors[(a.get("screen"), a.get("slot"))] = a

    def _hits(anchor):
        if hit_reader is not None:
            return hit_reader(anchor)
        rel = dc.scan_base(root, anchor["file"])
        if not os.path.exists(rel):
            return -1  # 文件不存在与"零命中"要分开报，否则锚点路径写错会被读成"未实现"
        rx = re.compile(anchor["match"])
        n = 0
        with open(rel, encoding="utf-8") as fh:
            for line in fh:
                if rx.search(dc._strip(line)):
                    n += 1
        return n

    buckets = {"implemented": [], "unimplemented": [], "noAnchor": []}
    for unit in result["units"]:
        if unit["covered"]:
            continue
        key = (unit["screen"], unit["slot"])
        anchor = anchors.get(key)
        if not anchor:
            unit["implTier"] = "unimplemented"
            unit["implHits"] = 0
            buckets["noAnchor"].append(unit)
            buckets["unimplemented"].append(unit)
            continue
        n = _hits(anchor)
        unit["implAnchor"] = anchor
        unit["implHits"] = n
        if n > 0:
            unit["implTier"] = "implemented"
            buckets["implemented"].append(unit)
        else:
            unit["implTier"] = "unimplemented"
            buckets["unimplemented"].append(unit)
    result["tiers"] = buckets
    return result


def summarize(result):
    per_screen = {}
    for unit in result["units"]:
        key = unit["screen"] if unit["scope"] == "screen" else "(跨屏)"
        bucket = per_screen.setdefault(key, {"total": 0, "covered": 0, "gaps": []})
        bucket["total"] += 1
        if unit["covered"]:
            bucket["covered"] += 1
        else:
            bucket["gaps"].append(unit["slot"])
    return per_screen


def render(result, spec):
    per_screen = summarize(result)
    screen_total = sum(v["total"] for k, v in per_screen.items() if k != "(跨屏)")
    screen_covered = sum(v["covered"] for k, v in per_screen.items() if k != "(跨屏)")
    lines = ["# 状态槽适配完成度", ""]
    lines.append(f"分子=有预览证据的状态槽，分母=规范声明的状态槽与跨屏维度。"
                 f"当前 **{screen_covered}/{screen_total} 屏内状态槽** "
                 f"（不含跨屏维度；跨屏维度见下节）。")
    lines += ["", "| 屏 | 覆盖 | 缺口 |", "|---|---|---|"]
    for key in sorted(per_screen):
        v = per_screen[key]
        gaps = "、".join(v["gaps"]) if v["gaps"] else "—"
        lines.append(f"| {key} | {v['covered']}/{v['total']} | {gaps} |")
    cross_slots = [v["id"] for v in (spec.get("stateSlots") or {}).get("vocabulary") or []
                   if v.get("scope") == "cross-cutting"]
    lines += ["", "## 跨屏一致性维度（全局证据，不按屏计分）", "",
              "| 维度 | 证据数 | 代表预览 |", "|---|---|---|"]
    for slot in cross_slots:
        ev = result["evidence"].get(slot, [])
        lines.append(f"| {slot} | {len(ev)} | {ev[0] if ev else '**无**'} |")
    na = result.get("notApplicable") or []
    if na:
        lines += ["", f"## 不适用（不计入分母，{len(na)} 个）", "",
                  result.get("notApplicableNote") or "", "",
                  "| 屏 | 槽 | 为什么证据在原理上不可得 |", "|---|---|---|"]
        for u in na:
            lines.append(f"| {u['screen']} | {u['slot']} | 见 spec.stateSlots.notApplicable |")
    tiers = result.get("tiers")
    if tiers:
        lines += ["", "## 缺口分档：已实现未验证 / 未实现", "",
                  "缺口不是一种东西：有的槽代码里已经写好、只差一个 `@Preview`（补预览即可转绿），"
                  "有的槽**代码里根本没有这个分支**（补预览只会渲染空气）。这一档由工具按锚点判，"
                  "不手写——锚点声明在 `spec.stateSlots.implementationAnchors`，判据见 `classify()`。", ""]
        impl = tiers["implemented"]
        unim = tiers["unimplemented"]
        no_anchor = tiers["noAnchor"]
        lines += [f"**已实现未验证（{len(impl)} 个）**——锚点命中 > 0，补预览可转绿：", ""]
        if impl:
            lines += ["| 屏 | 槽 | 锚点命中 | 锚点文件 |", "|---|---|---|---|"]
            for u in impl:
                a = u.get("implAnchor") or {}
                lines.append(f"| {u['screen']} | {u['slot']} | {u['implHits']} | `{a.get('file')}` |")
        else:
            lines.append("（无）")
        lines += ["", f"**未实现（{len(unim)} 个）**——锚点零命中，或**没有声明锚点**"
                      f"（其中 {len(no_anchor)} 个没声明锚点，按未实现计，不许默认当已实现）：", ""]
        if unim:
            lines += ["| 屏 | 槽 | 锚点命中 | 判定依据 |", "|---|---|---|---|"]
            for u in unim:
                a = u.get("implAnchor")
                if a:
                    basis = f"`{a.get('match')}` 在 `{a.get('file')}` 零命中"
                    hits = "0"
                else:
                    basis = "**spec 未声明锚点**"
                    hits = "—"
                lines.append(f"| {u['screen']} | {u['slot']} | {hits} | {basis} |")
        else:
            lines.append("（无）")
    if result["unclassified"]:
        lines += ["", f"## 没被任何状态槽解释的预览（{len(result['unclassified'])} 个）", "",
                  "命名没落进词表，等于这些渲染结果无法计入任何状态槽的完成度：", ""]
        lines.append("、".join(result["unclassified"]))
    alias_entries = result.get("previewAliases") or []
    if alias_entries:
        lines += ["", f"## 显式别名（{len(alias_entries)} 条）", "",
                  "词表按**函数名**找证据，可作者对「这一帧钉的是哪个槽」的声明常常写在别处"
                  "（夹具 KDoc、变量名、渲染目标）。名字对不上时，工具会把**已有的证据**报成缺口："
                  "`settings/loading` 的分支在代码里叫 `restarting`，帧叫 `SettingsRestartingPreview`，"
                  "词表正则 `(?i)loading` 一辈子看不见它。", "",
                  "别名是**逐 (屏, 槽)** 的显式声明，不放松任何全局正则——所以不会像前缀嵌套那样"
                  "把证据漏给邻屏。每条都必须写 `why`，且**必须真的改变覆盖**：本工具的 `--check`"
                  "会把「没改变任何东西」的别名判红（冗余的会误导下一个人去写更松的正则，"
                  "指错的既不纠错也不报红）；声明形状（屏/槽在分母里、帧真实存在）"
                  "由 `design-spec-project.py --check` 校验。", "",
                  "| 屏 | 槽 | 别名帧 | 改变了什么 | 为什么这一帧钉的就是本槽 |", "|---|---|---|---|---|"]
        effects = {i: eff for i, (_, eff) in enumerate(result.get("aliasEffect") or [])}
        for i, a in enumerate(alias_entries):
            eff = effects.get(i)
            mark = "**冗余（判红）**" if eff is False else "未覆盖 → 覆盖"
            lines.append(f"| {a.get('screen')} | {a.get('slot')} | "
                         f"{', '.join(a.get('previews') or [])} | {mark} | {a.get('why') or ''} |")
    weak = [u for u in result["units"] if u.get("evidenceElsewhere")]
    if weak:
        lines += ["", f"## 有全局证据但归不到屏的槽（{len(weak)} 个）", "",
                  "这些槽在别的命名下有预览，但按屏名前缀归不到本屏，因此**不计入覆盖**"
                  "（宁可少算，也不拿别的屏的证据充数）：", ""]
        for u in weak[:20]:
            lines.append(f"- {u['screen']} / {u['slot']}：{u['evidenceElsewhere'][:3]}")
    amb = sorted({p for u in result["units"] for p in (u.get("ambiguous") or [])})
    if amb:
        lines += ["", f"## 同时在养多屏的帧（{len(amb)} 个）", "",
                  "这些帧被**同长度**的多个屏前缀并列认领，所以同时计入多屏覆盖。"
                  "并列是 `screenPrefixes` 里显式声明的别名（如 `Cabinet` 同时属于会话列表与文件柜），"
                  "不是前缀嵌套事故——嵌套已由最长前缀裁决排除（见 `attribute()`）。"
                  "但**并列帧不能替代本屏自己的帧**：一屏若只有并列帧、没有独占帧，"
                  "说明它的覆盖是靠共用命名撑起来的。", ""]
        for p in amb[:20]:
            lines.append(f"- {p}")
    propped = [u for u in result["units"] if u.get("propped")]
    lines += ["", f"## 靠并列帧撑着的槽（{len(propped)} 个）", "",
              "判据是**谁渲染的它**，不是谁的名字像它：帧的渲染目标指向另一块屏 ⇒ 借来的；"
              "判不出渲染目标时，只有**独占**归属才算自己的，并列归属也算借的。"
              "**借来的帧可以补强，但不许独立支撑一个槽** —— 一屏若某槽的证据全是借来的，"
              "「本屏已覆盖」就是一句靠邻屏命名撑起来的话。"
              "这一条由 `--check` 判红（见 `main()`），不是注脚。", ""]
    if propped:
        lines += ["| 屏 | 槽 | 借来的证据 |", "|---|---|---|"]
        for u in propped[:20]:
            lines.append(f"| {u['screen']} | {u['slot']} | {', '.join(u.get('borrowed') or [])} |")
    else:
        lines.append("（无——每个已覆盖的槽都至少有一张本屏独占的帧，或有显式别名点名）")
    return "\n".join(lines) + "\n"


def units_signature(result):
    return sorted(f"{u['screen']}|{u['slot']}|{u['scope']}|{1 if u['covered'] else 0}"
                  for u in result["units"])


def tier_signature(result):
    """只记"缺口分档"这一层，用来卡档位降级。

    为什么必须单独棘轮：覆盖棘轮只记 0/1，而分档是**在 0 里面再分**。
    锚点正则一旦不再命中（函数改名、分支被删），`implemented` 会静默变成
    `unimplemented`——覆盖数一个都没掉，棘轮全绿，但报告会告诉下一个人
    "去写代码"，而代码其实存在（只是锚点过期了）。这与图标棘轮"只卡缺、
    漏掉整类降级"是同一族洞。
    """
    return sorted(f"{u['screen']}|{u['slot']}|{u.get('implTier') or ''}"
                  for u in result["units"] if not u["covered"])


def alias_effect(spec, previews, render_targets=None):
    """每条显式别名**真的改变了什么**：返回 [(entry, 生效了吗)]。

    为什么必须检查这一条：别名是一句人写的声明，而声明可以什么都不做。一条"没改变
    任何东西"的别名有两种来源，两种都是洞：
      · 它本来就冗余（词表已经找到了这一帧）—— 留着它会让下一个人以为"这个词表看不见"，
        从而在别处再写一条更松的正则；
      · 它指错了（帧名写错、屏名写错）—— 那它既没纠错，也没报红，只是安静地待着。
    判据是可判假的：把别名整块摘掉再算一遍覆盖，**没变**的就是冗余。
    """
    bare = copy.deepcopy(spec)
    (bare.get("stateSlots") or {}).pop("previewAliases", None)
    base = {(u["screen"], u["slot"]): u["covered"]
            for u in evaluate(bare, previews, render_targets)["units"]}
    out = []
    for a in ((spec.get("stateSlots") or {}).get("previewAliases") or {}).get("entries") or []:
        key = (a.get("screen"), a.get("slot"))
        # 必须"本来在分母里、本来未覆盖、现在覆盖了"才算生效；指向不存在的单元
        # （屏名/槽名写错）既不是生效也不是冗余，由 design-spec-project.py 判红。
        out.append((a, key in base and not base[key]))
    return out


def self_test(root="."):
    """量具自检：已知答案必须报对（含"桌子那边的预览不能算这一屏的证据"）。"""
    spec = {"screens": [{"id": "chat"}],
            "stateSlots": {
                "vocabulary": [{"id": "empty", "scope": "screen", "match": "(?i)empty"},
                               {"id": "dark", "scope": "cross-cutting", "match": "(?i)dark"}],
                "perScreen": {"chat": [{"slot": "empty", "applicableSource": "declared"},
                                       {"slot": "dark", "applicableSource": "derived-keyword"}]},
                "screenPrefixes": {"chat": ["Chat"]},
                "unattributablePrefixes": ["Desk"]}}
    # 不适用：证据在原理上不可得 ⇒ 不进分母、也不算缺口
    spec_na = {"screens": [{"id": "capture"}],
               "stateSlots": {
                   "vocabulary": [{"id": "normal", "scope": "screen", "match": "(?i)normal"}],
                   "perScreen": {"capture": [{"slot": "normal", "applicableSource": "declared"}]},
                   "screenPrefixes": {"capture": ["Capture"]},
                   "unattributablePrefixes": [],
                   "notApplicable": {"rules": [{"screen": "capture", "slots": ["normal"],
                                                "reason": "在 desktopApp 模块，截图源集看不见"}]}}}
    res_na = evaluate(spec_na, [])
    assert res_na["units"] == [], "不适用槽必须被排除出分母（否则永远算缺口）"
    assert len(res_na["notApplicable"]) == 1, "不适用槽必须单独列出，不能静默丢掉"
    ok = evaluate(spec, ["ChatEmptyPreview", "DeskDarkPreview"])
    units = {u["slot"]: u for u in ok["units"]}
    assert units["empty"]["covered"] is True, "本屏有 ChatEmptyPreview，empty 必须算覆盖"
    assert units["dark"]["covered"] is True, "跨屏维度看全局证据，DeskDarkPreview 应算覆盖"
    assert ok["unclassified"] == [], f"两个预览都该被解释掉，实际 {ok['unclassified']}"
    bad = evaluate(spec, ["DeskEmptyPreview"])
    units = {u["slot"]: u for u in bad["units"]}
    assert units["empty"]["covered"] is False, "只有 Desk 前缀的预览不能算本屏证据"
    assert units["empty"]["evidenceElsewhere"] == ["DeskEmptyPreview"], "必须记下归不到屏的证据"
    assert bad["unclassified"] == [], "DeskEmptyPreview 虽归不到屏，但仍被 empty 槽的解释覆盖"
    odd = evaluate(spec, ["ChatEmptyPreview", "SomethingWeirdPreview"])
    assert odd["unclassified"] == ["SomethingWeirdPreview"], \
        "没有任何状态槽能解释的预览必须单独列出（否则它渲染了什么永远不进完成度）"

    # ── 前缀嵌套：一个预览只算一屏的证据 ────────────────────────────────────
    # 已知答案（真实 spec 的形状）：`settings` 的前缀 `Settings` 是 `settings-more` 的
    # `SettingsMore` 的前缀。`SettingsMorePhonePreview` 必须**只**算 settings-more，
    # 否则 settings 会被邻屏的帧养着——删掉本屏真帧覆盖数一个不掉。
    spec_nest = {"screens": [{"id": "settings"}, {"id": "settings-more"}],
                 "stateSlots": {
                     "vocabulary": [{"id": "normal", "scope": "screen", "match": "(?i)preview"}],
                     "perScreen": {"settings": [{"slot": "normal", "applicableSource": "declared"}],
                                   "settings-more": [{"slot": "normal", "applicableSource": "declared"}]},
                     "screenPrefixes": {"settings": ["Settings"], "settings-more": ["SettingsMore"]},
                     "unattributablePrefixes": []}}
    nest = evaluate(spec_nest, ["SettingsMorePhonePreview"])
    u = {(x["screen"], x["slot"]): x for x in nest["units"]}
    assert u[("settings-more", "normal")]["covered"] is True, \
        "SettingsMorePhonePreview 必须算 settings-more 的证据"
    assert u[("settings", "normal")]["covered"] is False, \
        "前缀嵌套时，邻屏更长的前缀必须赢——否则本屏真帧全删、覆盖数也不掉"
    # 决定性判假：本屏真帧删掉后覆盖必须掉（旧实现下这里会假绿）
    nest2 = evaluate(spec_nest, ["SettingsPhonePreview"])
    u2 = {(x["screen"], x["slot"]): x for x in nest2["units"]}
    assert u2[("settings", "normal")]["covered"] is True, "本屏帧在时本屏必须算覆盖"
    assert u2[("settings-more", "normal")]["covered"] is False, \
        "短前缀的帧不能反哺长前缀的屏（settings 的帧不是 settings-more 的证据）"
    # 同长并列是 spec 声明的别名，必须两边都算
    spec_alias = {"screens": [{"id": "conversations"}, {"id": "cabinet"}],
                  "stateSlots": {
                      "vocabulary": [{"id": "normal", "scope": "screen", "match": "(?i)preview"}],
                      "perScreen": {"conversations": [{"slot": "normal", "applicableSource": "declared"}],
                                    "cabinet": [{"slot": "normal", "applicableSource": "declared"}]},
                      "screenPrefixes": {"conversations": ["Conversations", "Cabinet"],
                                         "cabinet": ["Cabinet"]},
                      "unattributablePrefixes": []}}
    alias = evaluate(spec_alias, ["CabinetMinePreview"])
    ua = {(x["screen"], x["slot"]): x for x in alias["units"]}
    assert ua[("conversations", "normal")]["covered"] and ua[("cabinet", "normal")]["covered"], \
        "同长并列是 spec 显式声明的别名（如 Cabinet 同时属于会话列表与文件柜），必须两边都算"
    assert ua[("cabinet", "normal")]["ambiguous"] == ["CabinetMinePreview"], \
        "并列认领必须记进 ambiguous，好让报告能列出哪些帧同时在养两屏"
    # 决定性判假（第二十九个洞）：并列帧**不许独立支撑一个槽**。
    # 上面那个 spec 里两块屏的唯一证据都是 `CabinetMinePreview`（并列帧），
    # 所以两边的覆盖都是"靠共用命名撑起来的"——必须被标出来。
    assert ua[("conversations", "normal")]["propped"] is True and \
        ua[("cabinet", "normal")]["propped"] is True, \
        "全部证据都是并列帧时，覆盖必须标成 propped（否则邻屏的帧会被当成自己的证据）"
    # 反向先验：把 `Cabinet` 从 conversations 的前缀里拿掉，`cabinet` 的帧就变成独占帧，
    # propped 必须消失；而 conversations 必须**掉成未覆盖**（它本来就没有自己的帧）。
    spec_alias_fixed = copy.deepcopy(spec_alias)
    spec_alias_fixed["stateSlots"]["screenPrefixes"]["conversations"] = ["Conversations"]
    fixed = evaluate(spec_alias_fixed, ["CabinetMinePreview"])
    uf = {(x["screen"], x["slot"]): x for x in fixed["units"]}
    assert uf[("cabinet", "normal")]["covered"] is True and \
        uf[("cabinet", "normal")]["propped"] is False, \
        "前缀只挂在 cabinet 下时，Cabinet 帧是独占证据，不许再标 propped"
    assert uf[("conversations", "normal")]["covered"] is False, \
        "拿掉错挂的前缀后，conversations 必须诚实掉成未覆盖——它本来就没有自己的帧"
    # 补一张本屏独占帧 ⇒ 覆盖回来，且不再是 propped（并列帧只能补强，不能独撑）
    fixed2 = evaluate(spec_alias_fixed, ["CabinetMinePreview", "ConversationsMinePreview"])
    uf2 = {(x["screen"], x["slot"]): x for x in fixed2["units"]}
    assert uf2[("conversations", "normal")]["covered"] is True and \
        uf2[("conversations", "normal")]["propped"] is False, \
        "本屏独占帧补上后必须算覆盖，且不许因为还混着并列帧就被标 propped"
    # 归不到屏的前缀与屏前缀同场竞赛：显式屏前缀更长时必须仍归那屏
    spec_desk = {"screens": [{"id": "files"}],
                 "stateSlots": {
                     "vocabulary": [{"id": "normal", "scope": "screen", "match": "(?i)preview"}],
                     "perScreen": {"files": [{"slot": "normal", "applicableSource": "declared"}]},
                     "screenPrefixes": {"files": ["DeskFiles"]},
                     "unattributablePrefixes": ["Desk"]}}
    desk = evaluate(spec_desk, ["DeskFilesXxxPreview", "DeskOtherXxxPreview"])
    ud = {x["slot"]: x for x in desk["units"]}
    assert ud["normal"]["covered"] is True and ud["normal"]["evidence"] == ["DeskFilesXxxPreview"], \
        "`Desk` 不能把 spec 显式声明的 `DeskFiles` 前缀误伤——长前缀必须赢"

    # ── 渲染目标赢过名字前缀：名字会撒谎，函数体不会 ──────────────────────
    # 已知答案（真实 spec 的形状）：`files` 声明了 `ChatFiles`/`ChatFolder`/`ChatTransfer`
    # 前缀，所以 `ChatFilesPhonePreview` 按名字归 files；但它体内是 `ChatScreen(...)`。
    # 分两种情形，规则必须能把它们分开：
    #   (a) **作者故意的别名** —— files 的前缀比 chat 的长，是显式声明，两边都算；
    #   (b) **意外嵌套** —— settings 的前缀比 settings-more 的短，不许蹭。
    # 单看名字前缀这两者一模一样，只有渲染目标能分开。
    spec_rt = {"screens": [{"id": "chat"}, {"id": "files"}],
               "stateSlots": {
                   "vocabulary": [{"id": "normal", "scope": "screen", "match": "(?i)preview"}],
                   "perScreen": {"chat": [{"slot": "normal", "applicableSource": "declared"}],
                                 "files": [{"slot": "normal", "applicableSource": "declared"}]},
                   "screenPrefixes": {"chat": ["Chat"], "files": ["ChatFiles"]},
                   "unattributablePrefixes": []}}
    rt_alias = evaluate(spec_rt, ["ChatFilesPhonePreview"],
                        {"ChatFilesPhonePreview": "chat"})
    ur = {(x["screen"], x["slot"]): x for x in rt_alias["units"]}
    assert ur[("chat", "normal")]["covered"] is True, \
        "渲染目标是 chat 就必须算 chat 的证据——名字写着 ChatFiles 也不算数"
    assert ur[("files", "normal")]["covered"] is True, \
        "`ChatFiles` 比 `Chat` 长，是作者显式声明的别名，必须并列保留"
    assert ur[("chat", "normal")]["ambiguous"] == ["ChatFilesPhonePreview"], \
        "别名并列必须记进 ambiguous"
    spec_rt2 = {"screens": [{"id": "settings"}, {"id": "settings-more"}],
                "stateSlots": {
                    "vocabulary": [{"id": "loading", "scope": "screen", "match": "(?i)loading"}],
                    "perScreen": {"settings": [{"slot": "loading", "applicableSource": "declared"}],
                                  "settings-more": [{"slot": "loading", "applicableSource": "declared"}]},
                    "screenPrefixes": {"settings": ["Settings"], "settings-more": ["SettingsMore"]},
                    "unattributablePrefixes": []}}
    rt_nest = evaluate(spec_rt2, ["SettingsMoreLoadingPhonePreview"],
                       {"SettingsMoreLoadingPhonePreview": "settings-more"})
    un = {(x["screen"], x["slot"]): x for x in rt_nest["units"]}
    assert un[("settings-more", "loading")]["covered"] is True, \
        "渲染目标是 settings-more，就该算 settings-more 的证据"
    assert un[("settings", "loading")]["covered"] is False, \
        "`Settings` 比 `SettingsMore` 短，是意外嵌套，不许蹭邻屏的帧"
    # 判不出渲染目标的帧必须回落到名字前缀（不许因为"判不出"就丢掉）
    rt_fallback = evaluate(spec_rt2, ["SettingsMoreLoadingPhonePreview"], {})
    uf = {(x["screen"], x["slot"]): x for x in rt_fallback["units"]}
    assert uf[("settings-more", "loading")]["covered"] is True, \
        "判不出渲染目标时必须回落名字前缀（最长前缀裁决），不许把帧丢掉"

    # 渲染目标扫描器本身也要有已知答案：真实仓库里那 4 个「名字撒谎」的帧必须扫出来
    #
    # settings-more 的目标名**从真 spec 读**，不写死。第 24 轮把合并的
    # `SettingsMoreScreen` 拆成六个子页后，写死的那份让自检报红（报得对 ——
    # 它证明"体里是什么"这条判据真的在跑）。但写死会把自检变成"跟着仓库改名一起烂"
    # 的东西：改名的人得同时想起这里。改成读 spec 之后，这一条顺带钉住一条真不变量：
    # **spec 声明的渲染目标必须真的出现在至少一帧的体内** ——
    # 声明一个没人渲染的名字，归属就会静默掉回名字前缀，而这正是前缀洞的形状。
    real_target = load_spec(root)["stateSlots"]["renderTargets"]["mapping"]["settings-more"]
    rt_real = dc.scan_render_targets(root, {
        "conversations": "ConversationsScreen", "chat": "ChatScreen", "nodes": "NodesScreen",
        "agents": "AgentConsoleScreen", "settings": "SettingsScreen",
        "settings-more": real_target, "cabinet": "CabinetScreen",
        "remote-view": "RemoteViewScreen", "capture": "CaptureOverlayView"})
    for fn in ("ChatFilesPhonePreview", "ChatFolderPhonePreview",
               "ChatTransferFailedPhonePreview", "ChatTransferResumePhonePreview"):
        assert rt_real.get(fn) == "chat", \
            f"{fn} 体内是 ChatScreen，渲染目标必须扫出 chat（实际 {rt_real.get(fn)!r}）——" \
            f"否则 files 会继续抢 chat 的帧"
    attributed_more = sorted(k for k, v in rt_real.items() if v == "settings-more")
    assert attributed_more, \
        f"spec 声明 settings-more 的渲染目标是 {real_target!r}，但没有任何预览体渲染它 —— " \
        f"归属会静默掉回名字前缀（前缀洞的形状）"
    assert not any(k.startswith("Desk") for k in rt_real), \
        "含 DesktopShell( 的桌面帧一壳多视图，必须一律跳过（不许按渲染目标归属）"

    # ── 显式别名：词表读的是函数名，作者声明的是语义 ──────────────────────
    # 已知答案（真实 spec 的形状）：设置屏的 loading 分支在代码里叫 `state.restarting`，
    # 钉它的帧叫 `SettingsRestartingPreview`，@Preview name 是 `settings-restarting` ——
    # 词表正则 `(?i)loading` **一辈子看不见它**，于是"已经有图有分支"的槽被报成"未实现"。
    # 别名必须能把这一条纠正过来，同时不许纠正过头。
    spec_al = {"screens": [{"id": "settings"}, {"id": "settings-more"}],
               "stateSlots": {
                   "vocabulary": [{"id": "loading", "scope": "screen", "match": "(?i)loading"}],
                   "perScreen": {"settings": [{"slot": "loading", "applicableSource": "declared"}],
                                 "settings-more": [{"slot": "loading", "applicableSource": "declared"}]},
                   "screenPrefixes": {"settings": ["Settings"], "settings-more": ["SettingsMore"]},
                   "unattributablePrefixes": [],
                   "previewAliases": {"entries": [
                       {"screen": "settings", "slot": "loading",
                        "previews": ["SettingsRestartingPreview"], "why": "唯一 loading 分支"}]}}}
    al = evaluate(spec_al, ["SettingsRestartingPreview"])
    ual = {(x["screen"], x["slot"]): x for x in al["units"]}
    assert ual[("settings", "loading")]["covered"] is True, \
        "别名必须让「帧存在但名字对不上词表」的槽转绿——否则已有证据被报成未实现"
    assert ual[("settings", "loading")]["aliasEvidence"] == ["SettingsRestartingPreview"], \
        "别名来的证据必须单独记下来，好让报告能区分「词表找到的」与「人声明的」"
    assert ual[("settings-more", "loading")]["covered"] is False, \
        "别名是逐 (屏, 槽) 的，不许漏给邻屏（这正是前缀嵌套那个洞的形状）"
    assert al["unclassified"] == [], "被别名解释掉的帧不该再出现在「没被解释」清单里"
    # 决定性判假：别名指向不存在的帧时必须什么都不算（否则写错名字会静默充数）
    al_ghost = evaluate(spec_al, [])
    ual2 = {(x["screen"], x["slot"]): x for x in al_ghost["units"]}
    assert ual2[("settings", "loading")]["covered"] is False, \
        "别名指向的帧不存在时必须什么都不算——写错名字不许静默充数"

    # ── 缺口分档：这一档过去是手写的，所以必须用已知答案钉死 ──────────────
    # 已知答案：agents/error 锚点命中（已实现）；cabinet/error 零命中（未实现）；
    # 没有声明锚点的槽必须判未实现，不许默认当已实现。
    spec_t = {"screens": [{"id": "agents"}, {"id": "cabinet"}, {"id": "nodes"}],
              "stateSlots": {
                  "vocabulary": [{"id": "error", "scope": "screen", "match": "(?i)error"}],
                  "perScreen": {"agents": [{"slot": "error", "applicableSource": "declared"}],
                                "cabinet": [{"slot": "error", "applicableSource": "declared"}],
                                "nodes": [{"slot": "error", "applicableSource": "declared"}]},
                  "screenPrefixes": {"agents": ["Agents"], "cabinet": ["Cabinet"], "nodes": ["Nodes"]},
                  "unattributablePrefixes": [],
                  "implementationAnchors": {"entries": [
                      {"screen": "agents", "slot": "error", "file": "Agents.kt",
                       "match": "session_state_error"},
                      {"screen": "cabinet", "slot": "error", "file": "Cabinet.kt",
                       "match": "errorMessage"}]}}}
    no_ev = evaluate(spec_t, [])
    # 锚点命中数由 hit_reader 注入，自检不碰真实文件系统
    hits = {("agents", "error"): 2, ("cabinet", "error"): 0}
    classify(no_ev, spec_t, hit_reader=lambda a: hits[(a["screen"], a["slot"])])
    got = {(u["screen"], u["slot"]): u["implTier"] for u in no_ev["units"]}
    assert got[("agents", "error")] == "implemented", \
        "锚点命中 > 0 必须判「已实现未验证」（补预览可转绿）"
    assert got[("cabinet", "error")] == "unimplemented", \
        "锚点零命中必须判「未实现」——补预览只会渲染空气"
    assert got[("nodes", "error")] == "unimplemented", \
        "没声明锚点的缺口必须按未实现计，不许默认当已实现"
    assert len(no_ev["tiers"]["noAnchor"]) == 1, "没声明锚点的槽必须单独计数"
    # 文件不存在（-1）与零命中必须分开：锚点路径写错不能被读成「未实现」
    no_ev2 = evaluate(spec_t, [])
    classify(no_ev2, spec_t, hit_reader=lambda a: -1)
    assert all(u["implTier"] == "unimplemented" for u in no_ev2["units"]), \
        "锚点文件缺失按未实现计，但必须能与「零命中」区分（implHits = -1）"
    anchored = [u for u in no_ev2["units"] if u.get("implAnchor")]
    assert anchored and all(u["implHits"] == -1 for u in anchored), \
        "锚点文件缺失必须报 -1，否则锚点路径写错会被静默读成「未实现」"
    print("[design-state-slot-report] 自检通过：本屏/跨屏/归不到屏/不适用/显式别名/缺口分档 "
          "六种情形判定全部正确")
    return 0


def main():
    ap = argparse.ArgumentParser(description="状态槽覆盖报告与棘轮门禁")
    ap.add_argument("--root", default=".")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--baseline", action="store_true")
    ap.add_argument("--reason", help="重记基线时覆盖缩小/档位降级的理由（缩小必填，写进基线备查）")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--markdown", help="把报告写到该路径")
    args = ap.parse_args()
    if args.self_test:
        return self_test(args.root)
    spec = load_spec(args.root)
    previews = dc.scan_preview_functions(args.root)
    rt_map = ((spec.get("stateSlots") or {}).get("renderTargets") or {}).get("mapping") or {}
    render_targets = dc.scan_render_targets(args.root, rt_map) if rt_map else {}
    result = evaluate(spec, previews, render_targets)
    classify(result, spec, root=args.root)
    result["aliasEffect"] = alias_effect(spec, previews, render_targets)
    report = render(result, spec)
    if args.markdown:
        with open(args.markdown, "w", encoding="utf-8") as fh:
            fh.write(report)
        print(f"[design-state-slot-report] 报告已写入 {args.markdown}")
    per_screen = summarize(result)
    total = sum(v["total"] for v in per_screen.values())
    covered = sum(v["covered"] for v in per_screen.values())
    if args.baseline:
        path = os.path.join(args.root, BASELINE_REL)
        # 重记基线不许静默缩小：`--baseline` 是"有意变更"的出口，如果它能无声地
        # 把缩水记进去，那棘轮就只剩"宣布"作用——删掉一批预览、跑一次 --baseline，
        # 门禁照样绿。所以缩小必须显式给出理由，理由与差值一起写进基线，可审计。
        prev_units = set()
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                prev_units = {u for u in (json.load(fh).get("units") or []) if u.endswith("|1")}
        now_units = {u for u in units_signature(result) if u.endswith("|1")}
        shrunk = sorted(prev_units - now_units)
        if shrunk and not args.reason:
            print(f"[design-state-slot-report] FAIL — 重记基线会缩小覆盖 {len(shrunk)} 个单元：")
            for u in shrunk:
                print(f"  · {u[:-2]}")
            print("\n`--baseline` 不许静默缩小：缩小必须带 `--reason \"...\"`，"
                  "理由会写进基线里备查。若缩小的原因是**量具判据变严**（例如归属从"
                  "名字前缀改成渲染目标），理由里要写明是哪一条判据、影响哪些单元。")
            return 1
        blob = {"note": "状态槽覆盖棘轮基线：覆盖集合只许扩大；缺口分档只许升级"
                        "（unimplemented → implemented，反向必须显式重记基线）。"
                        "缩小时必须带 --reason，理由记在 shrunkReason。",
                "generatedAt": datetime.date.today().isoformat(),
                "units": units_signature(result),
                "tiers": tier_signature(result),
                "previewCount": len(previews)}
        if shrunk:
            blob["shrunkReason"] = args.reason
            blob["shrunkUnits"] = [u[:-2] for u in shrunk]
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(blob, indent=2, ensure_ascii=False) + "\n")
        note = f"（缩小 {len(shrunk)} 个，理由已记）" if shrunk else ""
        print(f"[design-state-slot-report] 基线已写入 {path}：{covered}/{total} 单元覆盖，"
              f"{len(previews)} 个预览函数{note}")
        return 0
    if args.check:
        path = os.path.join(args.root, BASELINE_REL)
        if not os.path.exists(path):
            print(f"[design-state-slot-report] 基线不存在：{path}（先跑 --baseline）")
            return 1
        with open(path, encoding="utf-8") as fh:
            baseline = json.load(fh)
        now = {u for u in units_signature(result) if u.endswith("|1")}
        before = {u for u in baseline.get("units") or [] if u.endswith("|1")}
        lost = sorted(before - now)
        if lost:
            print(f"[design-state-slot-report] FAIL — {len(lost)} 个状态槽从覆盖掉回未覆盖：")
            for u in lost:
                print(f"  · {u[:-2]}")
            print("\n修法：补回对应屏的 @Preview（在 androidApp/src/screenshotTest/kotlin/），"
                  "确属有意收缩时才重记基线。")
            return 1
        # 档位降级：覆盖数没掉，但"已实现未验证"掉成"未实现"⇒ 报告会把人引去写已存在的代码
        now_t = set(tier_signature(result))
        before_t = set(baseline.get("tiers") or [])
        if not before_t:
            print("[design-state-slot-report] FAIL — 基线里没有 tiers 段（旧基线），"
                  "缺口分档不受棘轮管；先 `--baseline` 重记。")
            return 1
        downgraded = sorted(k for k in (before_t - now_t) if k.endswith("|implemented"))
        if downgraded:
            print(f"[design-state-slot-report] FAIL — {len(downgraded)} 个缺口从"
                  f"「已实现未验证」掉回「未实现」：")
            for k in downgraded:
                print(f"  · {k.rsplit('|', 1)[0]}")
            print("\n这**不是覆盖退步**（覆盖数没掉），是锚点不再命中："
                  "要么代码里那个分支被删了，要么函数/枚举改名导致锚点正则过期。"
                  "先确认是哪一种；锚点过期就改 `spec.stateSlots.implementationAnchors` 的 match，"
                  "确属代码被删才重记基线。")
            return 1
        # 冗余别名：别名是"词表看不见这一帧"的声明，如果它其实什么都没改变，
        # 那这句声明是假的——留着会让人以为词表有洞，从而去写一条更松的全局正则。
        redundant = [a for a, effective in (result.get("aliasEffect") or []) if not effective]
        if redundant:
            print(f"[design-state-slot-report] FAIL — {len(redundant)} 条显式别名没有改变任何东西：")
            for a in redundant:
                print(f"  · {a.get('screen')}/{a.get('slot')} ← {a.get('previews')}")
            print("\n别名是逐 (屏,槽) 的显式声明，只在「帧确实存在、且词表按名字找不到它」时才有意义。"
                  "一条不改变任何东西的别名要么本来就冗余（删掉它，或者说明词表为什么看不见），"
                  "要么指错了（帧名/屏名写错）。两种情况都必须处理，不许留着。")
            return 1
        # 靠共用命名撑起来的覆盖：本槽的**全部**证据都是被多块屏并列认领的帧
        # （`screenPrefixes` 里同一条前缀挂在两块屏下）。这种覆盖不是本屏的证据，
        # 是邻屏的证据借了名字。它必须在门禁里报红，而不是像 `ambiguous` 那样
        # 只当报告注脚——否则真证据会被写成"冗余别名"，方向整个反过来。
        propped = [(u["screen"], u["slot"], u["evidence"])
                   for u in result["units"]
                   if u.get("scope") == "screen" and u.get("propped")]
        if propped:
            print(f"[design-state-slot-report] FAIL — {len(propped)} 个槽的覆盖全靠并列帧撑着：")
            for screen, slot, ev in propped:
                print(f"  · {screen}/{slot} ← {ev}")
            print("\n这些帧被 `screenPrefixes` 里同一条前缀并列认领（同时算给多块屏），"
                  "所以它们**不是本屏的证据**。两种修法：\n"
                  "  ① 前缀挂错了屏 —— 从不需要它的那块屏的 `screenPrefixes` 里删掉；\n"
                  "  ② 本屏真的缺帧 —— 补一张本屏独占命名的 @Preview。\n"
                  "不许留着：留着会让「本屏已覆盖」变成一句靠邻屏命名撑起来的话。")
            return 1
        print(f"[design-state-slot-report] OK — {covered}/{total} 覆盖"
              f"（基线 {len(before)} 个已覆盖单元；{len(now_t)} 个缺口档位无降级）")
        return 0
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
