#!/usr/bin/env python3
"""核对 relay.html 里的 `Foo.kt:NNN` 行号引用，还指不指得准。

## 为什么会存在这个脚本

`relay.html` 的注释里钉了一批 Kotlin 行号（"照 `DesktopShell.kt:379-405` 改"这类），
用来说明"这一版代码就是这样"。Kotlin 侧一旦大改（`git diff` 里动几十个 .kt 是常事），
这些行号就悄悄错到别处去了 —— 稿子看着还是对的，读的人照着翻到错的地方。

因为 `git status` 对未跟踪/未改的引用毫无表示，只看 `git diff --name-only` 也看不出
哪几处真的指错。**这个脚本把两件事做实**：

1. 行号引用的唯一集合（同一处被提到 4 次只算一次）；
2. 每处引用所指的那几行，**现在**是什么内容。

## 判据，以及它的边界（读这个之前先看这段）

自动判据是：稿子那句描述里用反引号包住的符号名（如 `PeerAvatar`、
`memberOnlineCount`），是不是还落在引用行号 ±8 行里。

- 命中 → 标 ok；
- 没命中但有可锚符号 → 标 **漂移**；
- 没命中且稿子里根本没可锚符号 → 标 **未知（无锚点）**，**不计入结论**。

第三类必须留着：早期版本把"抓不到符号"也当成漂移，结果报出 42 处"漂移"，
其中绝大多数是判据自己没吃到东西 —— 满屏假红比全绿更糟。现在不知道的就是不知道，
它不会伪装成结论。（这条教训和 `check-layout` 里那个金丝雀自检是同一类错误。）

所以**标 ok 的 39 处只说明"被锚点验证了的那部分"，不等于剩下 39 处都准**。
要坐实某处，得拿输出里的实际行内容跟稿子那句描述人眼对一遍。

## 用法

    cd /workspace/src/teahouse-mobile
    python3 prototype/check-refs.py            # 全量（稿子 → .kt）
    python3 prototype/check-refs.py --drift   # 只看漂移的
    python3 prototype/check-refs.py --exists  # 稿子钉的东西，定义还在不在
    python3 prototype/check-refs.py --self    # 稿子引**自己**的行号（`:NNN`）
    python3 prototype/check-refs.py --reverse # .kt 引**稿子**的行号（`relay.html:NNN`）

`--exists` 回答的是另一个问题，别跟上面的混：上面的判"行号指得准不准"，
`--exists` 判"稿子描述里的那个东西**还在不在代码里**"。

两者天差地别：行号漂了但定义还在，是**注释过期**，改个数字就行；
定义真的没了，是**代码这轮改了功能**（删了/改名/挪走了），行号改多少都没用，
得先回答"这个功能还要不要"。

## 为什么会有 `--self` 与 `--reverse`

默认模式只覆盖**一个方向**：稿子 → `.kt`。而实际踩的坑里，另外两个方向各占一半：

- **稿子引自己**（`--self`）：`relay.html` 的注释里钉了 `` `:NNN` ``（"见 `:4832`"）。
  这个文件**每轮都在插行**，插点之后的自引用全废。它已经栽过三次
  （README §14.4 / §15.6 / §16.4），每次都是人眼逐条核出来的 —— 现在让机器核。
  判据比 `.kt` 那套**多一条硬规则**：目标是**空行**时无条件判漂移（空行不可能指对）。
- **`.kt` 引稿子**（`--reverse`）：Kotlin 侧把原型当"设计真相源"，写了十几处
  `prototype/relay.html:NNNN`。这个方向**过去从没被检查过** —— 2026-10-10 实查
  17 处，**17 处全错**（最久的一处偏了 3000+ 行）。原因和 `--self` 同一条：
  稿子每轮都在动，而 .kt 里的数字不会跟着动。

两个方向的根因是同一个，所以**修法也一样：数字改完还会再烂**。真要治，
是引用时**引符号名**（"原型 `GALLERY` 里 `['d-nodes','expanded']` 那一格"），
把行号当附注；或者每次动完稿子就跑一遍这两个模式。

只读，不改任何文件。
"""

from pathlib import Path
import re
import subprocess
import sys

TEA = Path(__file__).resolve().parent.parent          # .../teahouse-mobile
COP = Path(__file__).resolve().parent / 'relay.html'  # prototype/relay.html

# 代码侧的检索根。**2026-10-15 修正**：这里原本写死 `TEA/'scaffold'`，而本仓根本没有
# `scaffold/` 目录（代码在 composeApp/core/desktopApp/agentNode 下）—— 索引恒空，于是
# 每一次调用都把全部锚点判成「文件没找到」，输出永远是 `漂移 N / ok 0`。
# 「0 ok」本身就是判据失效的形状（一个真的全错的稿子不可能一处都不 ok）。
# 现在按模块列明，并且**一个根都不存在时报错退出**，不许静默退化成空索引。
KT_ROOTS = ('composeApp', 'core', 'desktopApp', 'agentNode', 'androidApp')


def kt_index() -> dict[str, list[Path]]:
    """按模块根建 `.kt` 文件名索引；一个根都不存在时直接失败，不返回空索引。"""
    found = [r for r in KT_ROOTS if (TEA / r).is_dir()]
    if not found:
        raise SystemExit(
            f'检索根全都不存在（在 {TEA} 下找 {list(KT_ROOTS)}）—— 判据要瞎了。'
            '宁可报错退出，也不要返回空索引把全部锚点判成漂移。')
    index: dict[str, list[Path]] = {}
    for root in found:
        for q in (TEA / root).rglob('*.kt'):
            if '/build/' in str(q):
                continue
            index.setdefault(q.name, []).append(q)
    return index

PAT = re.compile(r'(?P<f>[A-Za-z0-9_]+\.kt):(?P<a>\d+)(?:-(?P<b>\d+))?')
SYM = re.compile(r'`([A-Za-z][A-Za-z0-9_.()\[\]<>, ]{2,60})`')
IDENT = re.compile(r'[A-Za-z_][A-Za-z0-9_]{3,}')

# `--self`：稿子引自己。**只认反引号包住的** `` `:4832` ``，不认裸 `:4832` ——
# 裸行号在这份文件里绝大多数是"承接上一句那个 .kt 文件"，算进来会淹掉真信号
# （2026-10-10 实测：43 处命中里只有 2 处是自引用，其余 41 处都是 `.kt` 的续指）。
SELF = re.compile(r'`:(?P<a>\d{1,4})(?:-(?P<b>\d{1,4}))?`')
# `--self` 的锚点：任意反引号片段（比 SYM 宽 —— 自引用旁边钉的常常不是符号名，
# 而是 CSS 选择器或 `tier !== 'compact'` 这种表达式，SYM 的字符集吃不下来）。
SELF_BT = re.compile(r'`([^`\n]{2,64})`')
# `--reverse`：.kt 引稿子。
REV = re.compile(r'relay\.html:(?P<a>\d+)(?:-(?P<b>\d+))?')

# 这里**刻意没有停用词表**。早期版本列了一张（padding / state / conversations /
# dwin …），以为能滤掉满文件泛滥的泛词，结果把稿子真正锚定的 `dwin` 也滤掉了 ——
# 那处被判成"没法搜"，于是真消失的符号和不存在的结论一起溜走。
# 泛词根本不需要这张表拦：退化后命中 >5 行就够判 `broad` 了（见 locate 注释）。
# 硬塞停用词只会让判据在"该不该搜"上做主观取舍，而这是它可以不做的判断。

WINDOW = 8  # 锚点允许在引用行号上下各偏多少行
SELF_NEAR = 1200  # `--self`：往回多少字符内的 `.kt:` 算"续指"（见 _kind 的说明）


def changed_kt() -> set[str]:
    out = subprocess.run(['git', 'diff', '--name-only'], cwd=TEA,
                         capture_output=True, text=True).stdout
    return {Path(p).name for p in out.splitlines() if p.endswith('.kt')}


NEAR_TIGHT = 120   # 存在性模式：只认紧邻引用左边的反引号词
NEAR_LOOSE = 700   # 行号模式：放宽到同一段注释，宁可多标"漂移"让人眼复核


def anchors_of(html: str, start: int, near: int = NEAR_TIGHT) -> list[str]:
    """稿子那句描述里可锚的符号。

    窗口两种宽度，**两个模式要的是相反方向**，所以不能共用同一个值：

    - 存在性模式（--exists）必须紧（120）：一个注释块里往往连提好几处 `Foo.kt:NNN`
      （`DesktopShell.kt:379-405` 和 `DesktopConversationsPane.kt:497-504` 同在一段），
      700 的窗口会把上一处的 `tabhost` 记给 497-504，而它根本不在这个文件里 ——
      凭空报出一处"定义没了"。**假红（说没了其实在）比漏报危险得多。**
    - 行号模式（默认）可以宽（700）：串锚的后果只是"这一处没被验证成 ok"，
      往"漂移/未知"那边偏，是保守方向；而收紧窗口的代价是一次性把 34 处 ok
      压成 5 处 ok + 39 处未知，信息量断崖式下跌，反而看不出哪里真的错了。

    换句话说：不知道就是不知道（未知），但别**假装知道**。
    """
    toks = [t.strip() for t in SYM.findall(html[max(0, start - near):start])]
    return [t for t in toks if not t.startswith(('conversations', 'http'))]


def locate(lines: list[str], tok: str) -> tuple[list[int], str, str]:
    """tok 在整份文件里还能不能搜到。返回 (行号, 形态, mode)。

    mode 的四种取值，**区分的是"搜过没有"还是"没法搜"**，这决定了能不能下"没了"的结论：

    - `exact`  — 原样命中（tok 本身够具体，命中几行都算证据）
    - `ident`  — 退化为标识符后命中 ≤5 行（够具体，算证据）
    - `miss`   — 标识符可提取、**搜到 0 行** → 这才是真正的"定义没了"
    - `broad`  — 退化后命中 >5 行（`padding` 这类）→ 太泛，等于没锚点
    - `none`   — STOP 把标识符滤空了（`state.conversations`、自然语言描述）→ 没法搜

    只有 `miss` 能支撑"没了"这个结论。早先版本把 `none`/`broad` 也算没了，
    报出 3 处假消失（其中两处稿子写的就是"vertical padding 8dp"这种话）。
    """
    hits = [i + 1 for i, l in enumerate(lines) if tok in l]
    if hits:
        return hits, '原样', 'exact'
    idents = IDENT.findall(tok)
    if not idents:
        return [], '', 'none'
    head = max(idents, key=len)
    hits = [i + 1 for i, l in enumerate(lines) if head in l]
    if len(hits) == 0:
        return [], f'退化:{head}', 'miss'
    if len(hits) <= 5:
        return hits, f'退化:{head}', 'ident'
    return [], f'退化:{head}', 'broad'


def collect(html: str, index: dict[str, list[Path]], near: int = NEAR_TIGHT):
    """唯一行号引用集合 + 每处引用的锚点与文件。"""
    seen, out = set(), []
    for m in PAT.finditer(html):
        fname, a = m.group('f'), int(m.group('a'))
        # 区间末尾也要带出去：判据要按整段 `:A-B` 开窗口（见 main 里的说明）。
        b = int(m.group('b')) if m.group('b') else a
        if f'{fname}:{a}' in seen:
            continue
        seen.add(f'{fname}:{a}')
        toks = anchors_of(html, m.start(), near)
        cands = index.get(fname)
        path = sorted(cands, key=lambda x: len(str(x)))[0] if cands else None
        out.append((fname, a, b, toks, path))
    return out


def exists_mode(html: str, index: dict[str, list[Path]]) -> int:
    """稿子钉的那些东西，现在还躺在代码里吗。

    同名 .kt 可能在好几个源集里（composeApp / desktopApp 各一份），所以这里
    遍历**所有**同名文件，不看 collect 里挑的那一个 —— 定义挪到另一份同名文件
    是常事，只看一个容易把「在」误报成「没了」。
    """
    rows = collect(html, index, NEAR_TIGHT)
    alive, gone, nope = [], [], []
    for fname, a, _b, toks, _ in rows:
        files = index.get(fname) or []
        found: list[str] = []
        missed = False         # 有锚点确实去搜了、且 0 命中 → 定义没了
        for p in files:
            lines = p.read_text(encoding='utf-8', errors='replace').splitlines()
            for tok in toks:
                hits, form, mode = locate(lines, tok)
                if mode == 'miss':
                    missed = True
                if hits:
                    span = hits[0] if hits[-1] - hits[0] < 3 else f'{hits[0]}-{hits[-1]}'
                    rel = p.relative_to(TEA)
                    # 命中几十行时给个区间是误导（那只是"这个词在文件里到处都是"，
                    # 证明不了引用指的地方对）。命中 >10 行就明说它只够证"在"、不够证"对"。
                    weak = f'（符号在，共命中 {len(hits)} 处 — 只够证"在"，不够证"这里"）' \
                        if len(hits) > 10 else ''
                    found.append(f'{tok} → {rel}:{span} [{form}]{weak}')
                    break
        if found:
            alive.append((fname, a, toks, found))
        elif missed:
            gone.append((fname, a, toks, files))
        else:
            nope.append((fname, a, toks, files))

    print(f'唯一行号引用 {len(rows)} 处\n')
    print(f'  定义还在 {len(alive)} / 定义没了 {len(gone)} / '
          f'无法判定（稿子是自然语言描述）{len(nope)}\n')

    for fname, a, _b, toks, found in alive:
        print(f'【{fname}:{a}】 ✓ 在')
        for f in found:
            print(f'   {f}')
        print()
    for fname, a, _b, toks, files in gone:
        print(f'【{fname}:{a}】 ✗ 没了')
        print(f'   稿子锚点: {" / ".join(toks[-2:])}')
        print(f'   {"找不到同名文件" if not files else "对同名文件全搜过一遍，锚点都不在"}')
        print()
    for fname, a, _b, toks, files in nope:
        print(f'【{fname}:{a}】 ? 无法判定')
        print('   稿子那句没有可锚的符号（自然语言描述），只能人眼看')
        print()

    return 1 if gone else 0


def _anchors_around(text: str, pos: int, near: int = 300) -> list[str]:
    """引用**前后**各 ±near 字符里，反引号包住的片段（排除行号引用自己、文件路径、URL）。

    为什么窗口要往两边看：这个项目写引用是"锚点在前、行号在后"（README §14.4），
    但自引用的写法两种都有 —— `` 原型同一处 `:4832` 也是 `tier !== 'compact'` ``（锚点在后）
    与 `` `SyncBar` 写在 `if (showHeader)` 之外（`NodesScreen.kt:210`） ``（锚点在前）。
    只看一边会把它稳定判成"无锚点"，那等于这个检查有一半是瞎的。
    """
    lo, hi = max(0, pos - near), min(len(text), pos + near)
    out = []
    for t in SELF_BT.findall(text[lo:hi]):
        t = t.strip()
        if not t or t.startswith(':'):
            continue
        if '.kt:' in t or 'relay.html' in t or t.startswith('http'):
            continue
        if re.fullmatch(r'[:\d\-]+', t):
            continue
        out.append(t)
    return out


def _hit(tok: str, span: str) -> bool:
    """tok 的某个标识符落在 span 里。取最长的那个（与 locate 同一套退化逻辑）。"""
    ids = [i for i in IDENT.findall(tok) if len(i) >= 4]
    if not ids:
        return False
    return any(i in span for i in ids)


def _kind(text: str, pos: int) -> str:
    """这条 `` `:NNN` `` 是**自引用**，还是承接某个 `.kt` 文件的续指？

    返回 `'self'` / `'kt'`。分不清的代价两边都难吃：全算自引用 → 41/48 是假红
    （实测第一版）；全不算 → 真信号（2 处）一起丢掉。所以按三级判：

    1. **同一行里谁在前面谁说了算** —— `（`Common.kt:197`；动物是 × 0.47，`:190`）`
       里 `.kt:` 在本行更前，判 `kt`；`原型同一处 `:4832` 也是 …` 里"原型"更前，判 `self`。
    2. 同一行没有线索时看**就近**：往回找最近的 `.kt:`，落在
       [SELF_NEAR] 字符内就算它的续指，否则算自引用。
    3. 都没有 → 自引用。

    第 2 条是**启发式**，不是判据：它靠"续指通常紧跟在文件名后面"这个事实。
    说清代价 —— 一段注释隔了几十行再引同一个 `.kt`（这个项目里确实有），会被误判成
    自引用，于是多出一行"疑似"。这类误判**只会多报、不会漏报**，而且报出来的两边
    原文并列摆着，人眼三秒能否掉；反过来（漏报）才是要避免的。
    """
    ls = text.rfind('\n', 0, pos) + 1
    le = text.find('\n', pos)
    line, rel = text[ls:le if le >= 0 else len(text)], pos - ls
    kt = line.rfind('.kt:', 0, rel)
    mk = max(line.rfind(m, 0, rel) for m in ('原型', '本稿', '这稿', '本文件', 'relay.html'))
    if kt >= 0 and kt > mk:
        return 'kt'
    if mk >= 0 and mk > kt:
        return 'self'
    back = text[max(0, pos - SELF_NEAR):pos]
    return 'kt' if back.rfind('.kt:') >= 0 else 'self'


def _scan(ref_pat: re.Pattern, text: str, lines: list[str], where, kind_of=None):
    """一条共用的扫描器：**只对能确定性判定的开火**。

    为什么不做"锚点不命中 ⇒ 漂移"的全量判定（第一版就是这么写的，**假红 30/45**）：
    这份稿子里的反引号 `` `:NNN` `` 有**很大一部分不是自引用**，而是承接上一句那个
    `.kt` 文件的续指（`（`Common.kt:197`；动物是 × 0.47，`:190`）`）—— 加反引号也拦不住，
    因为裸行号在原文里本来就是带反引号写的。

    所以这里只声称三件确定的事：
      1. 行号越界；
      2. 目标是**空行**（空行不可能指对）；
      3. 引用旁边有反引号锚点、而锚点明确不在目标区间里 —— 报"疑似"，并把人眼要的
         两边原文直接并列出来（这是 §16.4 那种人工核对的替代品，不是它的结论）。
    其余一律只计数、不表态。
    """
    rows = []
    for m in ref_pat.finditer(text):
        if kind_of and kind_of(text, m.start()) != 'self':
            rows.append((where(text.count('\n', 0, m.start()) + 1), 0, 0, [], 'skip', ''))
            continue
        a = int(m.group('a'))
        b = int(m.group('b')) if m.group('b') else a
        line_no = text.count('\n', 0, m.start()) + 1
        toks = _anchors_around(text, m.start())
        ref = where(line_no)
        if a > len(lines) or b > len(lines):
            rows.append((ref, a, b, toks, 'hard', '行号越界'))
            continue
        span = '\n'.join(lines[a - 1:b])
        if not span.strip():
            rows.append((ref, a, b, toks, 'hard', '目标是空行'))
            continue
        if toks and not any(_hit(t, span) for t in toks):
            rows.append((ref, a, b, toks, 'suspect', lines[a - 1].strip()[:70]))
            continue
        rows.append((ref, a, b, toks, 'quiet', lines[a - 1].strip()[:70]))
    return rows


def _report(title: str, rows, args: list[str]) -> int:
    live = [r for r in rows if r[4] != 'skip']
    skip = len(rows) - len(live)
    hard = [r for r in live if r[4] == 'hard']
    sus = [r for r in live if r[4] == 'suspect']
    quiet = [r for r in live if r[4] == 'quiet']
    print(f'{title} {len(rows)} 处'
          f'（其中判定为 `.kt` 续指、已跳过 {skip} 处）')
    print(f'  确定有问题 {len(hard)} / 疑似 {len(sus)} / 未表态 {len(quiet)}\n')

    def show(r):
        ref, a, b, toks, _, extra = r
        span = f':{a}' if a == b else f':{a}-{b}'
        print(f'【{ref} → {span}】')
        print(f'   锚点: {" / ".join(toks[-2:]) if toks else "(无)"}')
        print(f'   {extra}\n')

    if hard:
        print('──── 确定有问题 ────')
        for r in hard:
            show(r)
    if sus:
        print('──── 疑似（锚点不在目标里；两边原文并列，人眼判）────')
        for r in sus:
            show(r)
    if '--all' in args:
        print('──── 未表态（并列供核）────')
        for r in quiet:
            show(r)
    return 1 if hard else 0


def self_mode(html: str, args: list[str]) -> int:
    """稿子引自己的行号（`` `:NNN` ``）。判据与边界见 `_scan`。"""
    lines = html.splitlines()
    rows = _scan(SELF, html, lines, lambda n: f'第 {n} 行', kind_of=_kind)
    return _report('自引用（`:NNN`）', rows, args)


def reverse_mode(args: list[str]) -> int:
    """`.kt` 引稿子的行号（`prototype/relay.html:NNNN`）。

    这个方向**默认模式完全不覆盖**（默认只扫 `relay.html` 里的 `.kt:NNN`）。
    2026-10-10 首次实查：17 处，**17 处全错**（最久的一处偏了 3000+ 行）。
    这里不做 `_kind` 过滤 —— `relay.html:NNN` 写在 .kt 里，本身就是明示的跨文件引用。
    """
    lines = COP.read_text(encoding='utf-8', errors='replace').splitlines()
    rows = []
    paths = sorted(q for v in kt_index().values() for q in v)
    for p in paths:
        try:
            src = p.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        rows += _scan(REV, src, lines, lambda n, p=p: f'{p.name}:{n}')
    return _report('反向引用（.kt → relay.html）', rows, args)


def main() -> int:
    if not COP.exists():
        print(f'找不到稿子 {COP}')
        return 2

    html = COP.read_text(encoding='utf-8', errors='replace')

    if '--self' in sys.argv:
        return self_mode(html, sys.argv)
    if '--reverse' in sys.argv:
        return reverse_mode(sys.argv)

    index = kt_index()

    changed = changed_kt()

    if '--exists' in sys.argv:
        return exists_mode(html, index)

    seen, rows = set(), []
    for fname, a, b, toks, path in collect(html, index, NEAR_LOOSE):
        if f'{fname}:{a}' in seen:
            continue
        seen.add(f'{fname}:{a}')
        desc = ' / '.join(toks[-2:]) if toks else '(无符号可锚)'
        cands = index.get(fname)
        if not path:
            rows.append((fname, a, fname in changed, None, desc, '漂移', '文件没找到'))
            continue
        lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
        if a > len(lines):
            rows.append((fname, a, fname in changed, path, desc, '漂移',
                         f'行号越界（文件共 {len(lines)} 行）'))
            continue
        # **区间要整段算窗口**（2026-10-15 修）。稿子里 87 处引用有 59 处写的是
        # `:A-B`（一个函数、一个 when 块），而判据原先只看**起点** ±WINDOW。
        # 实测假漂移两条：`DesktopStatusBar.kt:87-96` 的锚点
        # `desktop_status_connected` 就在 96 行（区间内！），但窗口是 79-95，
        # 够不到末尾 ⇒ 判成漂移。区间引用是主流写法，这个缺陷会稳定制造假红。
        lo, hi = min(a, b), max(a, b)
        window = '\n'.join(lines[max(0, lo - 1 - WINDOW):hi + WINDOW])
        hits = [t for t in toks if t.split('.')[0] in window]
        status = 'ok' if hits else ('未知' if not toks else '漂移')
        shown = lines[a - 1].strip()[:88] if a <= len(lines) else ''
        extra = f'现在这一行: {shown}' if hits else (
            f'锚点未命中（区间 {lo}-{hi} 附近 ±{WINDOW} 行）' if toks else '判据无锚点，未参与结论')
        rows.append((fname, a, fname in changed, path, desc, status, extra))

    drift = [r for r in rows if r[5] == '漂移']
    unknown = [r for r in rows if r[5] == '未知']
    ok = [r for r in rows if r[5] == 'ok']

    print(f'唯一行号引用 {len(rows)} 处；所在文件在这批未提交改动里 '
          f'{sum(1 for r in rows if r[2])} 处')
    print(f'  漂移 {len(drift)} / 无锚点未参与 {len(unknown)} / ok {len(ok)}\n')

    for fname, a, ch, path, desc, status, extra in rows:
        if status == 'ok':
            continue
        mark = '⚠ 漂移' if status == '漂移' else '? 未知'
        print(f'【{fname}:{a}】 {mark}')
        if path:
            print(f'   文件: {path.relative_to(TEA)}')
        print(f'   稿子锚点: {desc}')
        print(f'   {extra}')
        print()

    if '--drift' in sys.argv:
        print('漂移清单：' + '、'.join(f'{f}:{a}' for f, a, *_ in drift))
    if '--ledger' in sys.argv:
        i = sys.argv.index('--ledger')
        if i + 1 >= len(sys.argv):
            print('--ledger 要跟一个输出路径')
            return 2
        return write_ledger(sys.argv[i + 1], html, index, rows, drift, ok, unknown)
    return 1 if drift else 0


def write_ledger(out_path: str, html: str, index, rows, drift, ok, unknown) -> int:
    """把漂移落成**生成的**欠债台账（手写台账会腐，这份每次重跑即刷新）。

    为什么是台账而不是当场修数字：50 处漂移里只有 2 处存在**唯一**候选行，
    而那 2 处逐条核对后都是假修复（`DesktopShell.kt:379` 现在的内容是
    `} else {`，锚点 `dhead` 的唯一候选在一条注释里 —— 把数字改成 471 会让
    引用指向一段注释而不是稿子说的那段逻辑）。**批量改数字 = 把门禁刷绿**，
    是 skill 明令的反模式。每条欠债要人（或下一个会话）对着 claim 读代码再改。
    """
    import datetime
    lines_by_file = {}
    def lines_of(fname):
        if fname not in lines_by_file:
            cands = index.get(fname) or []
            path = sorted(cands, key=lambda x: len(str(x)))[0] if cands else None
            lines_by_file[fname] = (path,
                                    path.read_text(encoding='utf-8', errors='replace').splitlines()
                                    if path else [])
        return lines_by_file[fname]
    changed = changed_kt()
    out = []
    out.append('# 锚点欠债台账（**生成物，勿手改**）\n')
    out.append(f'生成：`python3 prototype/check-refs.py --ledger docs/plans/2026-10-15-relay-anchor-debt-ledger.md`')
    out.append(f'\n- 稿子：`prototype/relay.html`（{len(rows)} 处唯一行号引用：ok {len(ok)} / 漂移 {len(drift)} / 无锚点未参与 {len(unknown)}）')
    out.append(f'- 判据版本：区间 `:A-B` 整段开窗 + 检索根 {list(KT_ROOTS)} + 可判别金丝雀自检')
    out.append('- **条数只许降不许升**；每条要么改稿子里的数字（读代码核对 claim 后），')
    out.append('  要么改稿子那句话（代码真的改了功能时），要么把锚点改成引符号名。\n')
    out.append('| 稿子引用 | 锚点 | 为什么判漂 | 目标文件现在在改吗 | 稿子的说法（截断） |')
    out.append('|---|---|---|---|---|')
    for fname, a, ch, path, desc, status, extra in drift:
        pos = html.find(f'{fname}:{a}')
        ls = html.rfind('\n', 0, pos) + 1
        le = html.find('\n', pos)
        claim = html[ls:le].strip()
        claim = (claim[:88] + '…') if len(claim) > 88 else claim
        inflight = '是（对面在途，先别动数字）' if fname in changed else '否'
        out.append(f'| `{fname}:{a}` | {desc} | {extra} | {inflight} | {claim} |')
    out.append('\n> 台账里「是（对面在途）」的行**等那批提交落地后重跑本命令再看** ——')
    out.append('> 在途文件还在动，现在把数字改准也是白改（这正是本仓 10-14 那轮重构')
    out.append('> 把 scaffold/ 挪成根模块时，check-refs 检索根断掉却无人察觉的同款腐法）。')
    tmp = out_path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(out) + '\n')
    import os as _os
    _os.replace(tmp, out_path)
    print(f'\n欠债台账 -> {out_path}（{len(drift)} 条）')
    return 0


def self_test() -> int:
    """量具先验：**已知正确的锚点必须判 ok**。

    2026-10-15 的教训：检索根写错（`scaffold/` 不存在）时，输出是 `漂移 89 / ok 0` ——
    一个真的全错的稿子不可能一处都不 ok，「0 ok」本身就是判据失效的形状。
    这条自检把那个形状变成机器可判：拿当前稿子里一处**实测 ok** 的锚点喂进去，
    必须还是 ok；再喂一个故意偏 500 行的，必须不 ok。
    """
    html = COP.read_text(encoding='utf-8', errors='replace')
    index = kt_index()
    # 金丝雀要**可判别**：在当前行判 ok，且在偏移 500 行处判不命中。
    # 只要求前者会挑到 `padding` 这类满文件泛滥的锚点 —— 它在哪都"命中"，
    # 证明不了判据有分辨力（2026-10-15 首跑就挑到 GroupCreateDialog.kt:237，
    # 偏 500 行照样命中，于是自检报红：这条红是对的，锚点太泛测不出漂移）。
    seen, ok_case, broad_hits = set(), None, 0
    for fname, a, b, toks, path in collect(html, index, NEAR_LOOSE):
        if f'{fname}:{a}' in seen or not path or not toks:
            continue
        seen.add(f'{fname}:{a}')
        lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
        lo, hi = min(a, b), max(a, b)
        window = '\n'.join(lines[max(0, lo - 1 - WINDOW):hi + WINDOW])
        if not any(t.split('.')[0] in window for t in toks):
            continue
        bad_a = min(hi + 500, max(1, len(lines) - 1))
        bad_window = '\n'.join(lines[max(0, bad_a - 1 - WINDOW):bad_a - 1 + WINDOW + 1])
        if any(t.split('.')[0] in bad_window for t in toks):
            broad_hits += 1
            continue
        ok_case = (fname, a, bad_a)
        break
    if ok_case is None:
        print(f'self-test FAIL：{len(seen)} 处锚点里找不到一个可判别的金丝雀'
              f'（判 ok 但偏移 500 行仍命中的泛锚 {broad_hits} 处）—— '
              f'判据没有分辨力，或索引又空了（检索根？）')
        return 1
    fname, a, bad_a = ok_case
    fails = []
    if not index:
        fails.append('索引为空')
    if fails:
        for f in fails:
            print('self-test FAIL: ' + f)
        return 1
    print(f'self-test OK（金丝雀 {fname}:{a} 判 ok、偏移 500 行不判 ok、索引 {sum(len(v) for v in index.values())} 个 .kt）')
    return 0


if __name__ == '__main__':
    if '--self-test' in sys.argv:
        raise SystemExit(self_test())
    raise SystemExit(main())
