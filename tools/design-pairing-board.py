#!/usr/bin/env python3
"""并排配对板：把「屏配对台账」的每一行渲染成一张左原型 / 右代码的对比图。

## 为什么需要它

goal ① 要求"台账每一行都达到图+Preview 双有、并排签收"（当时是 65 行，
现为 71 行——**行数从台账算，不在这里写死**）。但**签收栏只能人填**，
而人没法对着两个文件路径签字——台账里的 `原型截图` 列是 `m01-home,m15b-home-dark`
这样的**文件名**，`现有 Preview` 列是 `ConversationsBannerPhonePreview` 这样的
**函数名**，两者都不是"能看的图"。人在这种输入下的真实行为是：不签，或者凭印象签。

这个工具把两列解析成真实文件、等比缩放、贴标签、拼成一张图。
人看图 → 在台账里签 → 台账才是可复核的。

## 关键判据（不是"图生成了就算数"）

- **两侧都必须真的解析到文件**。解析不到就画一块显眼的红底占位，并在报告里
  计入 `missing`——**不许静默跳过这一行**（跳过 = 那一屏在签收视野里消失，
  是"用分母换好看"的同一族错误）。
- **左侧只认台账声明的 shot 名**，不猜。台账写错 shot 名时应当暴露，而不是被
  模糊匹配悄悄修好。
- 宿主轨的 4 行（`d-capture`/`d-capture-tools`/`d-tray`/`d-drop`）**不生成并排图**：
  它们的验收面是真实窗口，没有代码侧渲染图可比。单独列进报告，指向
  `host-surface-checklist.md`。给它们造一张"占位对比图"会让人以为可以看图签字。

## 用法

    python3 scaffold/tools/design-pairing-board.py                 # 生成全部板子
    python3 scaffold/tools/design-pairing-board.py --screen m-chat # 只生成一屏
    python3 scaffold/tools/design-pairing-board.py --out DIR
    python3 scaffold/tools/design-pairing-board.py --self-test     # 量具先验

输出：`<out>/<屏 id>.png` 每屏一张，`<out>/INDEX.md` 索引（含签收栏）。
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER = "docs/plans/2026-10-10-relay-screen-pairing-ledger.md"
PROTO_DIR = "prototype/screenshots/relay"
REF_GLOB = "androidApp/src/screenshotTestDebug/reference/**/*.png"
HOST_TRACK = "走宿主面"
# 台账**末列**写着「已撤」的行 = 这一屏已从原型整屏删除（诚实保留一行记录，
# 不删——删了就没人知道它曾经存在过、为什么没了）。与 `design-screen-diff.py`
# 的 `withdrawn()` 用**同一条判据**：只认末列，中间某格出现「已撤」是在讲别的事。
# 这类行两侧都无可比之物 ⇒ 既不出并排图，也**不进「待人工裁决」**（否则会拿着
# 一张不存在的屏去问人「挑哪张帧」，正好造出一张看起来对、其实不对的并排图）。
WITHDRAWN = "已撤"
# 并排图的统一高度。手机原型 890x1808、桌面 2304x1600、代码渲染 1032x2237，
# 不统一高度的话"并排"没有意义（一侧被缩成条）。
BOARD_H = 1000
LABEL_H = 96
PAD = 24
# magick 默认字体画不了中文，标签会变成方块——这会让整张板子失去可读性。
CJK_FONT = "/System/Library/Fonts/Hiragino Sans GB.ttc"


class BoardError(Exception):
    pass


# 屏 id 前缀 → 该屏的代码域词。用来判"这一行的 Preview 是不是**本屏**的预览。
#
# 为什么需要：台账的 `现有 Preview` 列是**按函数名匹配自动填的**（表头原话
# "域约束+跨域补配"），所以 `m-home-recent`（消息）会被填上
# `CabinetMinePhonePreview`（文件柜）。这类"跨域补配"在并排图上一眼可见
# ——左边是消息列表，右边是文件柜——但如果只在台账里看两个名字，看不出来。
# 并排板的第二个作用就是**把这件事变成可见的**。
DOMAIN_OF = {
    "m-home": "Conversations", "m-gsearch": "Conversations", "m-sendfolder": "Chat",
    "m-folderin": "Chat", "m-hist": "Chat", "m-screencard": "RemoteView",
    "m-chat": "Chat", "m-group": "Group", "m-msgmenu": "Chat", "m-nodes": "Nodes",
    "m-addnode": "AddNode", "m-myqr": "Nodes", "m-nodemenu": "Nodes",
    "m-cabinet": "Cabinet", "m-agents": "Agents", "m-settings": "Settings",
    "m-set-": "Settings", "m-insets": "Window",
    "d-chat": "Chat", "d-home": "Conversations", "d-files": "Files",
    "d-cabinet": "Cabinet", "d-nodes": "Nodes", "d-agents": "Agents",
    "d-empty": "Empty", "d-nosel": "NoSelection", "d-nomatch": "NoMatch",
    "d-folder": "Folder", "d-resume": "Resume", "d-transferlog": "TransferLog",
    "d-search": "Search", "d-capture": "Capture", "d-reqscreen": "RemoteView",
    "d-sharescreen": "RemoteView", "d-viewscreen": "RemoteView",
    "d-settings": "Settings", "d-emoji": "Emoji", "d-hist": "History",
    "d-tray": "", "d-drop": "",
}


def domain_of(screen: str) -> str | None:
    for k in sorted(DOMAIN_OF, key=len, reverse=True):
        if screen.startswith(k):
            return DOMAIN_OF[k]
    return None


def pairing_kind(screen: str, preview: str | None, verdict: str | None = None) -> str:
    """`同域` = Preview 就是本屏的；`跨域` = 借了别的域的预览；`缺` = 没有。

    跨域**不等于错**：`d-settings-ime` 借 `DeskImeSmokeDarkPreview` 是合理的
    （IME 冒烟本来就是设置窗的形态）。但跨域行必须**在签收时被看见**——
    否则"这一屏有预览"是假信息。

    **判定源（2026-10-12 修，独立验证者发现）**：优先用 `design-preview-pairing.py`
    的**函数体**判定（`verdict` 参数）。旧版只看名字里有没有域词，于是
    `m-home-recent`（域词 `Conversations`）配上它的本屏帧 `ChatHomeRecentPhonePreview`
    （渲染的确实是 `ConversationsScreen`）被判「跨域」，与配对工具的「本屏」互相矛盾 ——
    两个工件对同一件事给出相反结论，人工签收时只能二选一。名字域启发式只在
    工具判不了（这个帧名不在函数体扫描结果里）时兜底。
    """
    if preview is None:
        return "缺"
    if verdict == "本屏":
        return "同域"
    if verdict == "无法判定":
        return "无法判定"
    if verdict in ("跨域", "函数不存在"):
        return "跨域"
    want = domain_of(screen)
    if want and want.lower() in preview.lower():
        return "同域"
    return "跨域"


def body_verdicts(ledger_path: str) -> dict:
    """{屏 id: {Preview 名: 函数体判定}}，取自 `design-preview-pairing.py`（唯一权威）。"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "design-preview-pairing.py")
    if not os.path.exists(path):
        return {}
    import importlib.util
    spec = importlib.util.spec_from_file_location("_dpp", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    try:
        previews = mod.scan_previews(REPO)
        rows = mod.read_ledger(ledger_path)
    except Exception:
        return {}
    out = {}
    for row in rows:
        _want, verdicts = mod.judge(row, previews)
        out[row["id"]] = {p: v for p, v, _r in verdicts}
    return out


def _magick() -> str:
    for c in ("magick", "convert"):
        p = shutil.which(c)
        if p:
            return p
    raise BoardError("找不到 magick/convert（ImageMagick）。brew install imagemagick")


def parse_ledger(path: str) -> list[dict]:
    """读台账表格。只认 7 列的屏行；列数不对就抛，不猜。"""
    if not os.path.exists(path):
        raise BoardError(f"台账不存在：{path}")
    rows = []
    for ln, line in enumerate(open(path, encoding="utf-8"), 1):
        if not line.startswith("| "):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 7:
            continue
        if cells[0] in ("屏", "---") or set(cells[0]) <= set("-: "):
            continue
        rows.append({"screen": cells[0], "label": cells[1], "shots": cells[2],
                     "code": cells[3], "previews": cells[4], "grade": cells[5],
                     "conflict": cells[6],
                     # 末列（第 8 格）必须留着：`已撤` 的判据就在这一格。
                     # 之前没存它，`m-nodemenu` 便以「缺帧」身份混进待裁决表。
                     "trail": cells[7] if len(cells) > 7 else "",
                     "line": ln})
    if not rows:
        raise BoardError(f"台账里没解析出任何屏行：{path}")
    return rows


def shot_names(cell: str) -> list[str]:
    return [s.strip() for s in cell.split(",") if s.strip()]


def is_withdrawn(row: dict) -> bool:
    """台账这一行说的是「这屏已经撤了」吗。

    与 `design-screen-diff.py:withdrawn()` 同判据：只认**末列**以「已撤」开头。
    中间某格里出现「已撤」是在讲别的事（比如 `m-screencard` 行里那句
    「本行原先填『无本屏预览』…现已补帧」根本不涉及撤屏，而
    「`NodesRowMenuPhonePreview` 亦已删」说的是一个帧被删、屏还在）。
    """
    return row.get("trail", "").startswith(WITHDRAWN)


def preview_names(cell: str) -> list[str]:
    """从 `现有 Preview` 列取函数名。

    列里的写法是人写的，有三种形态要认：
      `FooPreview, BarPreview`            裸函数名
      `FooPreview（说明文字…）`            函数名 + 中文括注
      `FooPreview/BarPreview（…）`        斜杠分隔
    **不认** `走宿主面 …` 这类无函数名的说明。
    """
    if HOST_TRACK in cell:
        return []
    out = []
    for part in re.split(r"[,/]", cell):
        part = part.strip()
        m = re.match(r"([A-Za-z][A-Za-z0-9]*Preview)", part)
        if m:
            out.append(m.group(1))
    return out


# 台账里「这一屏没有代码侧帧」的**声明式写法**。它比一个空格有信息量得多：
# 空格只说"没填"，这个写法说"没填，而且我知道为什么"。
# 注意只取核心词、**不带星号**：作者真会写成两种（`**无本屏预览**（原因）` 与
# `**无本屏预览（原因）**`），带星号做存在性判断会把后一种整类漏掉——
# 这个漏掉是自检抓出来的（第一版就是这样，`d-settings` 明明写了却没被认出）。
NO_PREVIEW = "无本屏预览"


def declared_cause(cell: str) -> str | None:
    """台账在「现有 Preview」列里**自己写下的**缺帧原因，没有则 None。

    为什么要有这个函数：缺帧的行分两种，**人要做的事完全不同** ——
    一种是"没人写过帧"（该去挑一张对的，或补一帧），
    另一种是"结构上就没有对应物"（台账已声明 V7/V9/模块边界之类）。
    工具如果对两种都摆出同一张同域候选表，就是在**诱导人从一列全都不对的帧里挑一个**，
    正好造出这个文件开头警告的那种"看起来对、其实不对"的并排图。
    所以这里要把台账自己写下的原因**取出来显示给人看**，而不是丢掉它只留"缺"字。
    """
    if NO_PREVIEW not in cell:
        return None
    # 三种写法都要认（作者真会这么写），顺序有讲究：
    #   A. `**无本屏预览（原因）**：补充说明…`  —— 原因块用 `）**` 收口，这个定界符最强
    #   B. `**无本屏预览**（原因…原因）`        —— 原因块一直走到单元格末尾
    #   C. `**无本屏预览**（短原因）`           —— 原因块里没有嵌套括号
    # **A 必须排在 B/C 前面**：原因文字里常常自带括号（`（ui/components/AvatarPalette.kt:100）`），
    # 用非贪婪匹配第一个 `）` 会把原因**截断在一半**——自检抓出来的第二个真 bug。
    for pat in (r"无本屏预览\*{0,2}\s*（(.+?)）\*{2}",
                r"无本屏预览\*{2}\s*（(.+)）\s*$",
                r"无本屏预览\*{0,2}\s*（(.+?)）"):
        m = re.search(pat, cell, re.S)
        if m:
            return m.group(1).strip()
    return cell.strip()


# reference 文件名：`<FuncName>_<slug>_<hash8>_<n>.png`。
# **函数名本身不含下划线**（实测 139 个文件名，0 个含下划线）——所以必须按
# **第一个**下划线切。用贪婪 `(.+)_[0-9a-f]{8}_\d+` 会把 slug 一起吃进去
# （`DeskTransferLogFilledPreview_desk-transferlog-filled`），
# 于是所有函数名都匹配不上台账。这个坑是本文件自检抓出来的。
REF_RE = re.compile(r"^([A-Za-z][A-Za-z0-9]*)_[^_]*_[0-9a-f]{8}_\d+\.png$")


def index_references(root: str) -> dict[str, list[str]]:
    """函数名 → reference 文件列表。"""
    idx: dict[str, list[str]] = {}
    unmatched = []
    for p in glob.glob(os.path.join(root, REF_GLOB), recursive=True):
        m = REF_RE.match(os.path.basename(p))
        if m:
            idx.setdefault(m.group(1), []).append(p)
        else:
            unmatched.append(p)
    # 解析不出来的文件不能静默丢——那会让"这个 Preview 存在"变成假信息。
    if unmatched:
        raise BoardError(f"{len(unmatched)} 个 reference 文件名不符合 "
                         f"`<Func>_<slug>_<hash8>_<n>.png`，例如 "
                         f"{os.path.basename(unmatched[0])}")
    return idx


def resolve(row: dict, proto: set[str], refs: dict[str, list[str]], root: str):
    """解析一行 → 左侧原型图 / 右侧代码图。解析不到的**留空并记录**，不猜。"""
    left = None
    for s in shot_names(row["shots"]):
        cand = os.path.join(root, PROTO_DIR, s + ".png")
        if os.path.exists(cand):
            left = (s, cand)
            break
    right = None
    for n in preview_names(row["previews"]):
        if n in refs:
            right = (n, sorted(refs[n])[0])
            break
    return left, right


def _placeholder(out: str, magick: str, text: str) -> None:
    """解析不到时的红底占位。**故意做得刺眼**——一块安静的白图会被当成"已配好"。"""
    subprocess.run([magick, "-size", f"{BOARD_H}x{BOARD_H}", "xc:#B3261E",
                    "-gravity", "center", "-fill", "white",
                    "-font", CJK_FONT, "-pointsize", "40",
                    "-annotate", "+0+0", text, out], check=True)


def _fit(src: str, out: str, magick: str) -> None:
    """等比缩放到统一高度。`>` 表示只缩不放（小图放大只会糊，不增加信息）。"""
    subprocess.run([magick, src, "-resize", f"x{BOARD_H}>", out], check=True)


def _label(text: str, out: str, magick: str, w: int, color: str) -> None:
    subprocess.run([magick, "-size", f"{w}x{LABEL_H}", f"xc:{color}",
                    "-gravity", "center", "-fill", "white",
                    "-font", CJK_FONT, "-pointsize", "30",
                    "-annotate", "+0+0", text, out], check=True)


def build_board(row: dict, left, right, magick: str, outdir: str) -> str:
    """拼一张板子：左侧原型（标题条写 shot 名）/ 右侧代码（写函数名）。"""
    tmp = os.path.join(outdir, ".tmp")
    os.makedirs(tmp, exist_ok=True)
    tag = row["screen"]

    def side(one, prefix: str, color: str):
        p = os.path.join(tmp, f"{tag}-{prefix}.png")
        if one is None:
            _placeholder(p, magick, "缺" if prefix == "L" else "缺 Preview")
            return p, BOARD_H, None
        name, path = one
        fit = os.path.join(tmp, f"{tag}-{prefix}-fit.png")
        _fit(path, fit, magick)
        w = int(subprocess.run([magick, "identify", "-format", "%w", fit],
                               capture_output=True, text=True, check=True).stdout)
        lab = os.path.join(tmp, f"{tag}-{prefix}-lab.png")
        _label(f"{'原型' if prefix == 'L' else '代码'}  {name}", lab, magick, w, color)
        return fit, w, lab

    lfit, lw, llab = side(left, "L", "#1F4E4A")
    rfit, rw, rlab = side(right, "R", "#4A3F1F")

    parts = []
    if llab:
        parts.append(llab)
    parts.append(lfit)
    if rlab:
        parts.append(rlab)
    parts.append(rfit)

    # 标题条横跨两栏，写屏 id + 标签 + 两侧来源，签收时不用回台账对行
    title = os.path.join(tmp, f"{tag}-title.png")
    total = lw + rw + PAD
    lt = shot_names(row["shots"])[0] if shot_names(row["shots"]) else "?"
    rt = preview_names(row["previews"])[0] if preview_names(row["previews"]) else "?"
    _label(f"{row['screen']}  {row['label']}    原型[{lt}]  代码[{rt}]  验收档[{row['grade']}]",
           title, magick, total, "#111111")

    out = os.path.join(outdir, f"{tag}.png")
    subprocess.run([magick, "montage", title,
                    *[p for p in parts if p],
                    "-tile", "1x", "-geometry", f"+{PAD // 2}+0",
                    "-background", "#F2F2F2", out], check=True)
    shutil.rmtree(tmp, ignore_errors=True)
    return out


def render_index(rows, resolved, outdir: str, magick: str, refs=None) -> str:
    """索引：每屏一行，**签收栏留空给人工填**。"""
    verdicts = body_verdicts(os.path.join(REPO, LEDGER))
    # 行数**从台账算**，不写死：台账会随屏矩阵增删而变（65 → 71），
    # 写死的数字只会让标题先于台账变陈旧，而陈旧的分母正是「用分母换好看」的入口。
    lines = [f"# 并排配对板索引（{len(rows)} 屏）", "",
             "> 本文件由 `scaffold/tools/design-pairing-board.py` 生成。",
             "> **签收栏只能人工填**：看图后在「签收」列写 `✅ 名字 日期`，",
             "> 或写 `❌ 原因`。agent 不得代签。",
             "",
             "| 屏 | 标签 | 板子 | 原型 | 代码 | 配对 | 验收档 | 签收 |",
             "|---|---|---|---|---|---|---|---|"]
    host = []
    withdrawn = []
    for r in rows:
        if is_withdrawn(r):
            withdrawn.append(r)
            lines.append(f"| {r['screen']} | {r['label']} | 已撤（无并排图） | "
                         f"{r['shots']} | — | — | {r['grade']} | |")
            continue
        if HOST_TRACK in r["previews"]:
            host.append(r)
            lines.append(f"| {r['screen']} | {r['label']} | 宿主轨（无并排图） | "
                         f"{r['shots']} | — | — | {r['grade']} | |")
            continue
        left, right = resolved[r["screen"]]
        lp = f"`{left[0]}`" if left else "**缺**"
        rp = f"`{right[0]}`" if right else "**缺**"
        kind = pairing_kind(r["screen"], right[0] if right else None,
                          verdicts.get(r["screen"], {}).get(right[0]) if right else None)
        mark = {"同域": "同域", "跨域": "**跨域**", "无法判定": "**无法判定**", "缺": "**缺**"}[kind]
        link = f"[{r['screen']}.png]({r['screen']}.png)"
        lines.append(f"| {r['screen']} | {r['label']} | {link} | {lp} | {rp} | "
                     f"{mark} | {r['grade']} | |")
    kinds = {}
    for r in rows:
        if is_withdrawn(r):
            kinds["已撤"] = kinds.get("已撤", 0) + 1
            continue
        if HOST_TRACK in r["previews"]:
            kinds["宿主轨"] = kinds.get("宿主轨", 0) + 1
            continue
        left, right = resolved[r["screen"]]
        k = pairing_kind(r["screen"], right[0] if right else None,
                          verdicts.get(r["screen"], {}).get(right[0]) if right else None)
        kinds[k] = kinds.get(k, 0) + 1
    lines += ["", f"合计 {len(rows)} 屏：并排 {len(rows) - len(host) - len(withdrawn)} · "
                  f"宿主轨 {len(host)} · 已撤 {len(withdrawn)}"
                  f"（宿主轨见 `scaffold/design/host-surface-checklist.md`）", "",
              "## 配对可信度", "",
              "判定源 = `design-preview-pairing.py` 的**函数体**扫描（不是名字像不像）："
              "`同域` = 该 Preview 渲染的就是本屏；`跨域` = 渲染的是别的屏"
              "（**不等于错，但签收时必须看见**——否则「这一屏有预览」是假信息）；"
              "`无法判定` = 帧的函数体里没有屏级 Composable（走同文件 helper 间接渲染）"
              "⇒ **必须人看图**；`缺` = 没有。", ""]
    for k in ("同域", "跨域", "无法判定", "缺", "宿主轨"):
        if k in kinds:
            lines.append(f"- {k}：{kinds[k]}")
    # 需要人工裁决的行：跨域 + 缺。**这两类都要给出同域候选**，否则人只能自己
    # 去 139 个 reference 里翻——那等于没给工具，签收会退化成"看一眼就签"。
    #
    # 但「缺」还要再分一次：台账可能已经**声明了结构性原因**（写的是
    # `**无本屏预览**（结构缺口 V9：…）`）。这种情况下**不能把候选表摆出来当选项** ——
    # 那会诱导人从一列全都不对的帧里挑一个，正好造出一张"看起来对、其实不对"的并排图。
    # 人的动作因此分成两种，工具必须替人分好：
    #   - 缺 + 台账**没写**原因  ⇒ 从候选里挑，或判定本屏确实没有预览（原逻辑）
    #   - 缺 + 台账**已写**原因  ⇒ 判「该原因成立 / 不成立」，不是挑帧
    need = []
    for r in rows:
        # 已撤的屏**不进待裁决表**：它两侧都没有东西可比，问人「挑哪张帧」
        # 是把一张不存在的屏当成待签收项，等于给签收栏送假候选。
        if HOST_TRACK in r["previews"] or is_withdrawn(r):
            continue
        right = resolved[r["screen"]][1]
        k = pairing_kind(r["screen"], right[0] if right else None,
                          verdicts.get(r["screen"], {}).get(right[0]) if right else None)
        if k in ("跨域", "无法判定", "缺"):
            need.append((r["screen"], right[0] if right else None, k, declared_cause(r["previews"])))
    if need:
        n_declared = len([1 for _, _, k, c in need if k == "缺" and c])
        lines += ["", "## 待人工裁决（跨域 + 缺）", "",
                  "两类要人做的事**不一样**，不要混着签：", "",
                  "1. **跨域**：这一屏借了别的域的帧。人的动作 = 从同域候选里挑对的那个"
                  "填回台账，或判定「本屏确实没有预览」。",
                  "2. **缺**：没有代码侧帧。再分两种 ——",
                  "   - 台账**没写**原因：人的动作 = 从候选里挑，或判定本屏确实没有预览；",
                  f"   - 台账**已写**结构性原因（本轮 {n_declared} 行）："
                  "**候选表不给**（给了就是诱导挑错帧）。人的动作 = 判「该原因成立 / 不成立」；"
                  "不成立就把帧补上，成立就把它记成结构缺口、别再从候选里挑。",
                  "",
                  "**agent 不代挑**——挑错比不挑更坏（会造出一张看起来对、其实不对的并排图）。", ""]
        for s_, cur, k, cause in need:
            want = domain_of(s_)
            cand = sorted(n for n in refs
                          if want and want.lower() in n.lower()) if want else []
            lines.append(f"### `{s_}`（{k}）")
            if cur:
                lines.append(f"- 当前填的是：`{cur}`")
            if k == "缺" and cause:
                lines.append(f"- **台账已声明原因**（待判成立与否）：{cause}")
                lines.append("- 同域候选：**故意不列** —— 台账已判定本屏结构上不可配对，"
                             "列出来只会让人挑一张错的帧把这一行「签」过去。")
            elif cand:
                lines.append(f"- 同域候选（{len(cand)}）：" +
                             "、".join(f"`{c}`" for c in cand[:12]) +
                             ("…" if len(cand) > 12 else ""))
            else:
                lines.append(f"- 同域候选：**无**（域词 `{want}` 在 reference 里没有匹配）"
                             f" ⇒ 这一屏大概率真的缺预览，需要在截图源集里补")
            lines.append("")
    lines.append("")
    p = os.path.join(outdir, "INDEX.md")
    open(p, "w", encoding="utf-8").write("\n".join(lines))
    return p


def self_test() -> int:
    """量具先验：喂已知答案，验解析与判据，不验 magick 输出好不好看。"""
    # 1) 台账解析：列数不对的行不能进结果（否则半行数据会被当成完整屏）
    assert preview_names("FooPreview, BarPreview") == ["FooPreview", "BarPreview"]
    assert preview_names("FooPreview（中文说明）") == ["FooPreview"]
    assert preview_names("FooPreview/BarPreview（x）") == ["FooPreview", "BarPreview"]
    assert preview_names("走宿主面 B7（desktopApp 模块）") == [], \
        "宿主轨行不该被解析出函数名（否则会去找一张不存在的渲染图）"
    assert shot_names("m01-home,m15b-home-dark,") == ["m01-home", "m15b-home-dark"], \
        "台账 shot 列末尾有逗号，不能产生空名"
    # 2) reference 切名：函数名**不含下划线**，必须按**第一个**下划线切。
    #    贪婪匹配会把 slug 一起吃进去（这是本文件自检抓出的真 bug）。
    m = REF_RE.match("DeskTransferLogFilledPreview_desk-transferlog-filled_52f5fd51_0.png")
    assert m and m.group(1) == "DeskTransferLogFilledPreview", \
        f"reference 必须按首个下划线切出函数名，实得 {m.group(1) if m else None}"
    greedy = re.match(r"(.+)_[0-9a-f]{8}_\d+\.png$",
                      "DeskTransferLogFilledPreview_desk-transferlog-filled_52f5fd51_0.png")
    assert greedy.group(1) != "DeskTransferLogFilledPreview", \
        "贪婪写法确实会多吃 slug——这正是不能用它的原因"
    assert REF_RE.match("Foo_foo_52f5fd51_0.png"), "基本形态必须认"
    assert not REF_RE.match("Foo_bar_baz_52f5fd51_0.png"), \
        "slug 含下划线（两个下划线）时不该被当成合法命名"
    # 3) 解析不到时**不能**静默返回 None 当成功——resolve 的调用方必须看返回值
    rows = parse_ledger(os.path.join(REPO, LEDGER))
    # 不钉死行数：台账随屏矩阵增删而变（65 → 71），钉死只会让自检报的是
    # 「仓库变了」而不是「工具错了」，两者处置完全相反。改为钉**不变量**：
    # 必须解析到行，且屏 id 不重复（重复行会让并排板与差集都静默按第一个算）。
    assert rows, "台账一行屏都没解析到（表头格式变了？）"
    _screens = [r["screen"] for r in rows]
    _dupes = sorted({s for s in _screens if _screens.count(s) > 1})
    assert not _dupes, f"台账有重复屏行：{_dupes}"
    bad = [r for r in rows if not r["screen"] or not r["grade"]]
    assert not bad, f"有屏行缺屏 id 或验收档：{bad[:2]}"
    # 4) 宿主轨行必须被识别出来（它们的验收面不是渲染图）
    h = [r for r in rows if HOST_TRACK in r["previews"]]
    assert len(h) == 4, f"宿主轨应恰好 4 行，实得 {len(h)}"
    # 4b) 已撤行必须被识别出来，且**判据只认末列**。这两条都要有牙：
    #     - 造数据：末列「已撤」⇒ 认；中间格提到「已撤」⇒ 不认（否则会误撤活屏）。
    assert is_withdrawn({"trail": "已撤"}), "末列写着「已撤」的行必须被认出来"
    assert not is_withdrawn({"trail": "已改"}), "末列不是「已撤」的行不能认成已撤"
    assert not is_withdrawn({}), "缺末列的行不能认成已撤"
    #     - 真数据：台账里若**存在**已撤行，其末列必须真是「已撤」；且它不能同时
    #       被判成缺帧（那样它会以「缺」身份混进待人工裁决表，等于拿一张不存在的屏
    #       去问人「挑哪张帧」）。用「若存在」而不是「必须存在」：撤屏是可耗尽的，
    #       将来清掉这一行不该让自检变红。
    _wd = [r for r in rows if is_withdrawn(r)]
    assert all("已撤" in r["trail"] for r in _wd), "已撤行末列必须写着「已撤」"
    assert not [r for r in _wd if declared_cause(r["previews"])], \
        "已撤行不该同时被判成缺帧（会以「缺」身份混进待裁决表）"
    # 5) 缺帧行要能分出「台账已声明原因」与「只是没写过帧」——**这两种人做的事不同**，
    #    混成一种就会把一列全都不对的候选摆给人挑（挑错比不挑更坏）。
    assert declared_cause("**无本屏预览**（结构缺口 V9：原型五页被合并）") \
        == "结构缺口 V9：原型五页被合并"
    assert declared_cause("**无本屏预览**（模块边界，物理上不可能）") \
        == "模块边界，物理上不可能"
    assert declared_cause("SettingsProfilePhonePreview") is None, \
        "有帧的行不该被判成缺帧"
    assert declared_cause("") is None
    # 台账里**真的**有这两种行，且都能被认出来（不是只在造出来的字符串上成立）。
    # **不要写死屏 id**：第 24 轮把 V9 那四行拆出独立子页之后 `m-set-notify` 有了本屏帧，
    # 写死 `"m-set-notify" in decl` 会让自检跟着仓库改名一起烂 —— 那时它报的不是"工具错了"，
    # 而是"仓库变了"，而这两种情况的处置完全相反。改成钉**不变量**：
    #   ① 声明了原因的缺帧行**可以**非空（台账历史上确实有这个类别）；但该类别是
    #      可耗尽的——把模块边界/结构缺口补上代码侧帧之后，这一行就不再声明原因。
    #      因此这里只保证「若判定为缺帧，Preview 格必须真写着 `无本屏预览`」，
    #      不再强制台账永远存在已声明原因的缺帧行（否则补完缺口反而让自检变红）。
    #   ② 四种行（有帧 / 已声明原因的缺帧 / 宿主轨 / 已撤）必须**恰好铺满**台账全部行，不重不漏。
    decl = [r["screen"] for r in rows if declared_cause(r["previews"])]
    assert all("无本屏预览" in r["previews"] for r in rows
               if r["screen"] in decl), "被判成缺帧的行 Preview 格里必须写着「无本屏预览」"
    with_frame = [r["screen"] for r in rows
                  if preview_names(r["previews"]) and HOST_TRACK not in r["previews"]
                  and not is_withdrawn(r)]
    host = [r["screen"] for r in rows if HOST_TRACK in r["previews"]]
    wd = [r["screen"] for r in rows if is_withdrawn(r)]
    assert sorted(set(with_frame) | set(decl) | set(host) | set(wd)) \
        == sorted(r["screen"] for r in rows), \
        "有帧 / 已声明原因的缺帧 / 宿主轨 / 已撤 四类必须恰好铺满台账，不重不漏"
    assert "m-set-profile" not in decl, \
        "m-set-profile 本轮已补帧，不该再被判成缺帧"
    # 6) 声明式写法本身要有牙：只写「无本屏预览」不带括号说明时，不能返回 None
    #    （否则那一行会被当成"没人写过帧"，又把候选表摆出来）
    assert declared_cause("**无本屏预览**") == "**无本屏预览**", \
        "只声明没解释的行也要被认成已声明，不能退回成普通缺帧"
    # 7) 原因文字**自带括号**时不能被截断。台账里真有这种行（`m-set-avatar` 的原因里
    #    嵌着 `（ui/components/AvatarPalette.kt:100）`），截断后显示给人看的原因是半句话，
    #    人判"该原因成立与否"就判在半句话上。非贪婪匹配第一个 `）` 正是这个 bug。
    nested = ("**无本屏预览**（结构缺口 V7：`AvatarPicker`（ui/components/AvatarPalette.kt:100）"
              "已实现但只作为资料页里的「头像」字段；台账 gap-plan.md:126）")
    got = declared_cause(nested)
    assert "AvatarPalette.kt:100）" in got and got.endswith("gap-plan.md:126"), \
        f"原因里的嵌套括号不能被截断，实得 {got!r}"
    print(f"[design-pairing-board] 自检通过：台账 {len(rows)} 行、宿主轨 4 行、"
          "列解析、reference 切名、缺帧原因分级与嵌套括号判据全部正确")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="原型↔代码并排配对板生成器")
    ap.add_argument("--root", default=REPO)
    ap.add_argument("--ledger", default=LEDGER)
    ap.add_argument("--out", default="docs/plans/pairing-board")
    ap.add_argument("--screen", help="只生成这一屏")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check", action="store_true",
                    help="只校验两侧是否都能解析到文件，不画图")
    a = ap.parse_args()
    if a.self_test:
        return self_test()

    root = a.root
    ledger = a.ledger if os.path.isabs(a.ledger) else os.path.join(root, a.ledger)
    rows = parse_ledger(ledger)
    if a.screen:
        rows = [r for r in rows if r["screen"] == a.screen]
        if not rows:
            print(f"台账里没有屏 {a.screen}", file=sys.stderr)
            return 2

    proto = set(os.path.basename(p)[:-4]
                for p in glob.glob(os.path.join(root, PROTO_DIR, "*.png")))
    refs = index_references(root)
    resolved, missing = {}, []
    for r in rows:
        # 已撤的屏两侧都无可比之物：不出图、不进缺帧报告。
        if HOST_TRACK in r["previews"] or is_withdrawn(r):
            continue
        left, right = resolve(r, proto, refs, root)
        resolved[r["screen"]] = (left, right)
        if left is None or right is None:
            missing.append((r["screen"], left is not None, right is not None))

    if a.check:
        for s, hl, hr in missing:
            print(f"  {s}: 原型{'有' if hl else '**缺**'} 代码{'有' if hr else '**缺**'}")
        n = len([r for r in rows
                 if HOST_TRACK not in r["previews"] and not is_withdrawn(r)])
        n_host = len([r for r in rows if HOST_TRACK in r["previews"]])
        n_wd = len([r for r in rows if is_withdrawn(r)])
        print(f"并排 {n - len(missing)}/{n} 两侧齐全；宿主轨 {n_host} 行；已撤 {n_wd} 行")
        return 1 if missing else 0

    magick = _magick()
    outdir = a.out if os.path.isabs(a.out) else os.path.join(root, a.out)
    os.makedirs(outdir, exist_ok=True)
    for r in rows:
        # 已撤的屏没有 `resolved` 条目（上面刻意没给它解析），这里必须同步跳过，
        # 否则 KeyError —— 而这正是「已撤行必须和宿主轨走同一条豁免路径」的牙。
        if HOST_TRACK in r["previews"] or is_withdrawn(r):
            continue
        left, right = resolved[r["screen"]]
        build_board(r, left, right, magick, outdir)
        print(f"  {r['screen']}.png  "
              f"原型[{'有' if left else '缺'}] 代码[{'有' if right else '缺'}]")
    idx = render_index(rows, resolved, outdir, magick, refs)
    print(f"\n索引：{os.path.relpath(idx, root)}")
    if missing:
        print(f"⚠ {len(missing)} 行有一侧解析不到（已在板子上画红底占位）：")
        for s, hl, hr in missing:
            print(f"   {s}: 原型{'有' if hl else '缺'} 代码{'有' if hr else '缺'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
