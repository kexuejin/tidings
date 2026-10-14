#!/usr/bin/env python3
"""把 relay.html 的每一屏渲染成 PNG，供逐张人眼验收。

用法：先起静态服务器，再 python3 shoot-relay.py [关键词...]
    python3 -m http.server 4173 --bind 127.0.0.1
    python3 shoot-relay.py            # 全部
    python3 shoot-relay.py group chat # 只跑名字里含关键词的

为什么不用 file:// 直开：本机的 --headless 在 file:// + 查询串下会挂住不返回，
必须走 HTTP。为什么加 --no-proxy-server：本机配了 HTTP_PROXY（FlClash/Clash），
不显式绕开的话 Chrome 会把 127.0.0.1:4173 也丢给代理，拿到 502 后静默截一张空白页。

窗口尺寸 = 画布 + 外壳留白，这样截出来的正好是设备/窗口本体，没有多余边。
"""
import os
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
BASE = "http://127.0.0.1:4173/relay.html?bare=1"
OUT = os.path.join(HERE, "screenshots", "relay")
DRAFT = os.path.join(HERE, "relay.html")
# 帧↔稿绑定（2026-10-15，审查 P0-3 的防复发门禁）：
# 截图目录本身被 .gitignore 排除（设计期素材不进仓），但 **manifest 进仓** ——
# 它记着「上一次全量重拍时 relay.html 的 sha256」。CI 全新检出没有 PNG，
# 照样能判红「稿子改了但 manifest 没更新」，也就是改了稿没重拍还打算提交。
# 每帧另记自己的 png sha：本地重拍后字节变了会红（Chrome 升级换渲染也算，逼你显式重录）。
MANIFEST = os.path.join(HERE, "shots-manifest.json")

# (名字, 形态, 屏, 主题, 窗口宽, 窗口高, 追加查询)
# 窗口 = 画布 + 左右各 26px 留白，移动端再加 52px 高度给外壳。
SHOTS = [
    # ---- 移动端主线：消息（提案是默认落点）→ 私聊 → 群聊 ----
    ("m01-home",             "phone",   "m-home-recent",    "light",  445, 904, ""),
    ("m01b-home-more",       "phone",   "m-home-more",      "light",  445, 904, ""),
    ("m01c-home-unread",     "phone",   "m-home-unread",    "light",  445, 904, ""),
    # 全局搜索：顶栏放大镜的落点，三段（联系人 / 聊天记录 / 文件）+ 空态。
    # 它是移动端**唯一**的搜索入口 —— 列表上方那条筛选框（原 m01d-home-search，
    # 带值的搜索态）已随提案删掉，见 relay.html 里 screenHomeRecent 的注释。
    ("m01e-gsearch",         "phone",   "m-gsearch",        "light",  445, 904, ""),
    ("m01f-gsearch-empty",   "phone",   "m-gsearch-empty",  "light",  445, 904, ""),
    ("m03-chat",             "phone",   "m-chat",           "light",  445, 904, ""),
    ("m03b-chat-solo",       "phone",   "m-chat-solo",      "light",  445, 904, ""),
    # 头部动作收纳（`.hdmore`）：把直出上限压到 1，逼出「⋯」并把菜单展开 —— 见 relay.html 的 ?actmore=N。
    ("m03e-chat-actmore",    "phone",   "m-chat",           "light",  445, 904, "&actmore=1"),
    ("m04-chat-group",       "phone",   "m-chat-group",     "light",  445, 904, ""),
    ("m05-groupcreate",      "phone",   "m-groupcreate",    "light",  445, 904, ""),
    ("m06-msgmenu",          "phone",   "m-msgmenu",        "light",  445, 904, ""),
    # 2026-10-15 审查补拍：这两屏在 SCREENS 里挂了 id、也有渲染函数，却从没被任何帧拍过。
    # m-chat-more 是**输入区「更多功能」面板展开**态（`screenChat(solo, moreOpen)` 的
    # moreOpen → mobileComposer），与上面 `&actmore=1` 那个**头部动作收纳**态是两回事，
    # 不能拿 m03e 顶替；m-session-detail 是工作区→会话详情（事件流那一屏）。
    ("m06b-chat-more",       "phone",   "m-chat-more",      "light",  445, 904, ""),
    # 群聊的 ➕ 展开态：与私聊同高 170，但**少一格「窗口震动」**（5 条目排 4+1）。
    # 「震动在群里不成立 → 整项不画」是 screenChatGroup 批注里写明的设计决定，
    # 之前没有任何帧拍过它；两态面板同高也是 §21.4 的结论，这张是它的证据。
    ("m06d-chat-group-more", "phone",   "m-chat-group-more", "light",  445, 904, ""),
    ("m06c-session-detail",  "phone",   "m-session-detail", "light",  445, 904, ""),
    ("m07-groupinfo",        "phone",   "m-groupinfo",      "light",  445, 904, ""),
    # ---- 移动端节点 / Agent / 设置 ----
    # 节点页现在是**一个**列表（在线 → 离线 → 没有回应），不再是「局域网发现 / 已保存」两节；
    # 「我的二维码」那张 276px 的卡搬去了设置·连接，这里只剩一行入口。
    ("m08-nodes",            "phone",   "m-nodes",          "light",  445, 904, ""),
    # m08c-nodemenu 已撤；点节点改为进 m-nodedetail 节点详情（2026-10-12，见 README §20）。
    ("m08a-nodedetail",      "phone",   "m-nodedetail",     "light",  445, 904, ""),
    # 「Agent」区（§3.1）只在该节点声明了 `ag1` 时才画 —— 默认选中的「老周」不是 agent，
    # 所以另拍一张：落到**一个节点上挂两个 agent** 的那台（协议 D6 的多 agent 档）。
    ("m08a2-nodedetail-agent","phone",  "m-nodedetail",     "light",  445, 904, "&node=7c3e91b5"),
    ("m08b-myqr",            "phone",   "m-myqr",           "light",  445, 904, ""),
    ("m09-addnode",          "phone",   "m-addnode",        "light",  445, 904, ""),
    ("m09b-addnode-fill",    "phone",   "m-addnode-fill",   "light",  445, 904, ""),
    ("m10-nodes-empty",      "phone",   "m-nodes-empty",    "light",  445, 904, ""),
    ("m11-agents",           "phone",   "m-agents",         "light",  445, 904, ""),
    # 第二个工作区 `ci-runner` 是**离线**的：队列下面是 `agent_offline_*` 那块
    # （「消息会排队，等它回来自动补发」）。这一档是这轮补的源码分支，得有一张能看。
    ("m11h-agents-offline",  "phone",   "m-agents",         "light",  445, 904, "&ws=1"),
    # 看板外层现在按**节点（= 设备）**分组（§3.1 + 协议 D6）：只有一个节点时不画分组头 ——
    # `?solo=<nodeId>` 把看板收成一个，否则那条分支在 8 个节点的默认夹具下永远跑不到。
    ("m11i-agents-solo",     "phone",   "m-agents",         "light",  445, 904, "&solo=7c3e91b5"),
    ("m11j-agents-empty",    "phone",   "m-agents-empty",   "light",  445, 904, ""),
    # ---- Agent 详情 / 工作区详情（2026-10-13 新增两屏 + 一处空态）----
    # 默认这台（teahouse-mobile）上的第一个 agent：有 agentId（`dsh-main`）所以主名就是它，
    # `agentKind`（DSH）退成一枚类型徽标。它同时带两条**未归属会话**（`agent-session` 省略
    # `wsId`，§2.5），所以那一行也在这一张里。
    ("m12-agentdetail",      "phone",   "m-agentdetail",    "light",  445, 904, ""),
    # **同一个节点上的第二个 agent**（`oc-main` / opencode）：协议 D6 起一个节点可以挂多个
    # agent，身份键 = `agentId ?? agentKind`。这一档只有把 akey 指到第二个才看得到。
    ("m12b-agentdetail-multi","phone",  "m-agentdetail",    "light",  445, 904, "&agent=7c3e91b5&akey=oc-main"),
    # 老端（声明了 `ag1`、`agentId` 与 `agentKind` 都没有、从没宣告工作区）：主名是
    # 「未上报类型」（不猜、不印 wire 键），「工作区」区换成说明条，另有那条降级条。
    ("m12c-agentdetail-nows", "phone",  "m-agentdetail",    "light",  445, 904, "&agent=a4c07d19"),
    # 离线 agent：`agent_offline_*` 文案 + 最后在线（回答「我不在时你发的东西去哪了」）。
    ("m12d-agentdetail-offline","phone","m-agentdetail",    "light",  445, 904, "&agent=9d40e5b7"),
    # 工作区详情：正常 / 未命名（有 `wsId`、缺 `wsTitle`，R1 不许拿 id 冒充名字）/ 离线。
    ("m13-workspacedetail",  "phone",   "m-workspacedetail","light",  445, 904, ""),
    ("m13b-wsdetail-unnamed","phone",   "m-workspacedetail","light",  445, 904, "&wsd=3"),
    ("m13c-wsdetail-offline","phone",   "m-workspacedetail","light",  445, 904, "&wsd=1"),
    # ---- 文件柜 / 文件夹传输 / 检索 / 屏幕协助（对齐桌面版新增能力）----
    ("m11b-cabinet",         "phone",   "m-cabinet",        "light",  445, 904, ""),
    ("m11c-cabinet-peer",    "phone",   "m-cabinet-peer",   "light",  445, 904, ""),
    ("m11d-histsearch",      "phone",   "m-histsearch",     "light",  445, 904, ""),
    ("m11d-sendfolder",      "phone",   "m-sendfolder",     "light",  445, 904, ""),
    ("m11e-folderin",        "phone",   "m-folderin",       "light",  445, 904, ""),
    ("m11f-histsearch",      "phone",   "m-histsearch",     "light",  445, 904, ""),
    ("m11f2-histdate",       "phone",   "m-histdate",       "light",  445, 904, ""),
    ("m11g-screencard",      "phone",   "m-screencard",     "light",  445, 904, ""),
    ("m12-settings",         "phone",   "m-settings",       "light",  445, 904, ""),
    ("m13a-set-profile",     "phone",   "m-set-profile",    "light",  445, 904, ""),
    # 资料页的另外两档头像。三档的内容是**互斥**的（图标档给两组选择器、首字档给自解释条、
    # 上传档给上传区），一张 `?av=initial` 一张 `?av=custom` 才验得全 ——
    # 只看默认那张的话，另两档是空白还是画错了根本分不出来。
    ("m13a1-profile-initial","phone",   "m-set-profile",    "light",  445, 904, "&av=initial"),
    ("m13a3-profile-custom", "phone",   "m-set-profile",    "light",  445, 904, "&av=custom"),
    ("m13a2-set-avatar",     "phone",   "m-set-avatar",     "light",  445, 904, ""),
    ("m13b-set-conn",        "phone",   "m-set-conn",       "light",  445, 904, ""),
    ("m13c-set-notify",      "phone",   "m-set-notify",     "light",  445, 904, ""),
    ("m13d-set-appear",      "phone",   "m-set-appear",     "light",  445, 904, ""),
    ("m13e-set-caps",        "phone",   "m-set-caps",       "light",  445, 904, ""),
    ("m13f-set-data",        "phone",   "m-set-data",       "light",  445, 904, ""),
    ("m13g-set-diag",        "phone",   "m-set-diag",       "light",  445, 904, ""),
    ("m14-insets",           "phone",   "m-insets",         "light",  445, 904, ""),
    # ---- 移动端深色 + 大字号 ----
    ("m15b-home-dark",       "phone",   "m-home-recent",    "dark",   445, 904, ""),
    # 历史搜索那层 + 日期对话框都改过壳，两张都得有深色版：这两层是叠在会话上的
    # 浮层，深色下 scrim 的 42% 与 sheet 的贴底位置都要能看出来。
    ("m15c-histsearch-dark", "phone",   "m-histsearch",     "dark",   445, 904, ""),
    ("m15d-histdate-dark",   "phone",   "m-histdate",       "dark",   445, 904, ""),
    # 上传头像这条链路上有大量「小字约束」和一枚深底浅图：深色下渐变块与虚线框混在一起
    # 容易糊成一片，得有一张深色版能看到。
    ("m15e-set-avatar-dark", "phone",   "m-set-avatar",     "dark",   445, 904, ""),
    ("m16-chat-group-dark",  "phone",   "m-chat-group",     "dark",   445, 904, ""),
    ("m17-settings-dark",    "phone",   "m-settings",       "dark",   445, 904, ""),
    # 资料页那张身份卡是唯一一张 **surfaceVariant 底上压小标签** 的地方
    # （`.tag.plain` 就是为它加的），深浅两个主题下都得看一眼。
    ("m17b-set-profile-dark","phone",   "m-set-profile",    "dark",   445, 904, ""),
    ("m18-chat-fontlg",      "phone",   "m-chat",           "light",  445, 904, "&font=large"),
    ("m19-chat-group-fontlg","phone",   "m-chat-group",     "light",  445, 904, "&font=large"),
    # ---- 平板 / 全宽 rail ----
    ("m20-tablet-home",      "tablet",  "m-home-recent",    "light",  852, 1332, ""),
        ("m21-tabland-chat-group","tabland","m-chat-group",     "light",  1332, 852, ""),
    ("m22-tabland-nodes",    "tabland", "m-nodes",          "light",  1332, 852, ""),
    ("m23-tabland-settings", "tabland", "m-settings",       "light",  1332, 852, ""),
    ("m24-full-agents",      "full",    "m-agents",         "light",  1452, 952, ""),
    ("m26-sessionpick",      "phone",   "m-agents",         "light",  445, 904, "&sessionpick=1"),
    ("m27-chat-sessionpick", "phone",   "m-chat",           "light",  445, 904, "&sessionpick=1"),
    ("m25-tabland-insets-bad","tabland","m-home-recent",    "light",  1332, 852, "&insets=bad"),
    # ---- 桌面端：三视图 + 群聊 ----
    ("d01-chat",             "expanded","d-chat",           "light",  1152, 800, ""),
    # 桌面端也有「会话」一行切换条（与手机聊天屏同款的 .swbar），所以它也得有一张展开态：
    # 桌面这张是 `?sessionpick=1` 唯一能验「弹层定位对不对」的地方（浮层是 absolute，
    # 桌面没有 .app 那个定位祖先，只能截图看它落在哪）。
    ("d01e-chat-sessionpick","expanded","d-chat",           "light",  1152, 800, "&sessionpick=1"),
    ("d01b-tabhost-more",    "tabhost", "d-home-more",      "light",  1152, 800, ""),
    ("d01c-tabhost-chat",    "tabhost", "d-chat",           "light",  1152, 800, ""),
    ("d02-chat-group",       "expanded","d-chat-group",     "light",  1152, 800, ""),
    ("d03-groupcreate",      "expanded","d-groupcreate",    "light",  1152, 800, ""),
    ("d04-files",            "expanded","d-files",          "light",  1152, 800, ""),
    ("d05-nodes",            "expanded","d-nodes",          "light",  1152, 800, ""),
    # 桌面节点视图右栏的「Agent」区同手机；左栏是一行一个节点（见 d05 那张的节点行）。
    ("d05b-nodes-agent",     "expanded","d-nodes",          "light",  1152, 800, "&node=7c3e91b5"),
    ("d06-agents",           "expanded","d-agents",         "light",  1152, 800, ""),
    ("d06b-agents-sessionpick","expanded","d-agents",        "light",  1152, 800, "&sessionpick=1"),
    # 桌面档的离线工作区（同 m11h，两端同一份渲染件，所以两端都要有一张）。
    ("d06c-agents-offline",  "expanded","d-agents",         "light",  1152, 800, "&ws=1"),
    # 桌面新增两屏（§3.2）：Agent 详情 / 工作区详情。它们挂在「工作区」那一格里，
    # 不新增顶层导航格；左栏仍是那棵「节点 → 工作区 → 会话」树。
    ("d06d-agentdetail",     "expanded","d-agentdetail",    "light",  1152, 800, ""),
    ("d06e-agentdetail-nows","expanded","d-agentdetail",    "light",  1152, 800, "&agent=a4c07d19"),
    ("d06f-workspacedetail", "expanded","d-workspacedetail","light",  1152, 800, ""),
    ("d06g-wsdetail-unnamed","expanded","d-workspacedetail","light",  1152, 800, "&wsd=3"),
    # ---- 共享文件柜 / 文件夹传输（桌面版左栏第 3 格，与「文件视图」不是一回事）----
    ("d23-cabinet",          "expanded","d-cabinet",        "light",  1152, 800, ""),
    ("d24-histsearch",       "expanded","d-histsearch",      "light",  1152, 800, ""),
    ("d24-folder",           "expanded","d-folder",         "light",  1152, 800, ""),
    ("d25-resume",           "expanded","d-resume",         "light",  1152, 800, ""),
    # ---- 本地历史与全文检索：全局搜索替换左栏 / 会话内历史搜索是另一套 ----
    ("d26-search",           "expanded","d-search",         "light",  1152, 800, ""),
    ("d27-search-empty",     "expanded","d-search-empty",   "light",  1152, 800, ""),
    # ---- 独立小窗：截图 + 屏幕协助的双角色，都是独立 BrowserWindow ----
    ("d28-capture",          "expanded","d-capture",        "light",  1152, 800, ""),
    ("d29-capture-tools",    "expanded","d-capture-tools",  "light",  1152, 800, ""),
    ("d30-reqscreen",        "expanded","d-reqscreen",      "light",  1152, 800, ""),
    ("d31-sharescreen",      "expanded","d-sharescreen",    "light",  1152, 800, ""),
    ("d32-viewscreen",       "expanded","d-viewscreen",     "light",  1152, 800, ""),
    # ---- 桌面端四个空态 ----
    ("d07-empty",            "expanded","d-empty",          "light",  1152, 800, ""),
    ("d08-nosel",            "expanded","d-nosel",          "light",  1152, 800, ""),
    ("d09-filesempty",       "expanded","d-filesempty",     "light",  1152, 800, ""),
    ("d10-nomatch",          "expanded","d-nomatch",        "light",  1152, 800, ""),
    # ---- 桌面端设置窗三节 ----
    ("d11-settings",         "settings","d-settings",       "light",  772,  640, ""),
    ("d11b-settings-data",   "settings","d-settings-data",  "light",  772,  640, ""),
    ("d12-settings-desk",    "settings","d-settings-desk",  "light",  772,  640, ""),
    ("d13-settings-ime",     "settings","d-settings-ime",   "light",  772,  640, ""),
    ("d14-settings-dark",    "settings","d-settings",       "dark",   772,  640, ""),
    ("d14b-transferlog",     "settings","d-transferlog",    "light",  772,  640, ""),
    # ---- 桌面端托盘 / 拖放 / 表情 ----
    ("d15-tray",             "expanded","d-tray",           "light",  1152, 800, ""),
    ("d16-drop",             "expanded","d-drop",           "light",  1152, 800, ""),
    ("d17-emoji",            "expanded","d-emoji",          "light",  1152, 800, ""),
    # ---- 桌面端三档宽度：紧凑藏左栏 / 中档抽屉 / 宽双栏 ----
    ("d18-chat-compact",     "compact", "d-chat",           "light",  612,  800, ""),
    ("d19-chat-medium",      "medium",  "d-chat",           "light",  812,  800, ""),
    # 中档本来就只剩 1 颗直出，这里再把菜单展开 —— 标题不再被按钮挤成 `teahouse-…`。
    ("d19b-chat-medium-more","medium",  "d-chat",           "light",  812,  800, "&actmore=1"),
    ("d20-chat-dark",        "expanded","d-chat",           "dark",   1152, 800, ""),
    ("d21-chat-group-dark",  "expanded","d-chat-group",     "dark",   1152, 800, ""),
    ("d22-nodes-wide",       "wide",    "d-nodes",          "light",  1492, 980, ""),
]


def draft_sha():
    with open(DRAFT, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()


def load_manifest():
    try:
        with open(MANIFEST, encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        print("manifest 读不了（%s）—— 删掉它重跑全量" % e)
        raise SystemExit(2)


def verify(manifest, cur_sha, shots, out_dir):
    """纯函数：返回问题列表（空 = 绿）。self-test 与 --verify-only 共用这一份判据。"""
    if manifest is None:
        return ["没有 %s —— 跑一次 python3 shoot-relay.py（全量）生成" % os.path.basename(MANIFEST)]
    frames = manifest.get("frames", {})
    problems = []
    for row in shots:
        name = row[0]
        ent = frames.get(name)
        if ent is None:
            problems.append("%s：SHOTS 表里有这一帧，manifest 没有（新增帧后没重拍）" % name)
            continue
        if ent.get("source_sha") != cur_sha:
            problems.append("%s：拍这帧时稿子是 %s…，现在稿子是 %s…（改稿未重拍）"
                            % (name, str(ent.get("source_sha"))[:8], cur_sha[:8]))
            continue
        png = os.path.join(out_dir, name + ".png")
        if not os.path.exists(png):
            continue  # CI 全新检出没有 PNG：manifest 与稿子的绑定已足够判过期
        with open(png, "rb") as fh:
            if hashlib.sha256(fh.read()).hexdigest() != ent.get("png_sha"):
                problems.append("%s：盘上 PNG 字节与 manifest 记的不一致（被动过/换过渲染器）" % name)
    orphans = sorted(set(frames) - {r[0] for r in shots})
    for name in orphans:
        problems.append("%s：manifest 里有但 SHOTS 表已撤（孤儿帧）" % name)
    return problems


def verify_only():
    cur = draft_sha()
    problems = verify(load_manifest(), cur, SHOTS, OUT)
    if problems:
        print("图集过期检查 FAIL — %d 条：" % len(problems))
        for x in problems[:20]:
            print("  · " + x)
        if len(problems) > 20:
            print("  …另 %d 条" % (len(problems) - 20))
        print("\n修法：python3 shoot-relay.py（全量重拍，约 2 分钟）")
        return 1
    print("图集过期检查 OK — %d 帧全部拍自当前稿子 %s…" % (len(SHOTS), cur[:12]))
    return 0


def self_test():
    """喂已知缺陷，确认每一条都会被抓到；良性输入不误报。"""
    import tempfile as _tf
    fails = []
    shots = [("a", "phone", "s1", "light", 10, 10, ""), ("b", "phone", "s2", "light", 10, 10, "")]
    with _tf.TemporaryDirectory() as td:
        open(os.path.join(td, "a.png"), "wb").write(b"AAA")
        open(os.path.join(td, "b.png"), "wb").write(b"BBB")
        sha_a = hashlib.sha256(b"AAA").hexdigest()
        sha_b = hashlib.sha256(b"BBB").hexdigest()
        good = {"frames": {"a": {"source_sha": "X", "png_sha": sha_a},
                           "b": {"source_sha": "X", "png_sha": sha_b}}}
        cases = [
            ("缺 manifest", verify(None, "X", shots, td), True),
            ("良性：全帧同稿同字节", verify(good, "X", shots, td), False),
            ("改稿未重拍", verify(good, "Y", shots, td), True),
            ("新增帧没拍", verify({"frames": {"a": good["frames"]["a"]}}, "X", shots, td), True),
            ("PNG 字节被换", verify({"frames": {"a": {"source_sha": "X", "png_sha": "0"*64},
                                                "b": good["frames"]["b"]}}, "X", shots, td), True),
            ("孤儿帧", verify({"frames": dict(good["frames"], z={"source_sha": "X", "png_sha": "0"*64})}, "X", shots, td), True),
        ]
        for label, got, want_problem in cases:
            hit = bool(got)
            if hit != want_problem:
                fails.append("%s：期望 %s，实际 %s（%s）" % (label, "报问题" if want_problem else "不报", "报" if hit else "不报", got[:1]))
    if fails:
        print("self-test FAIL:")
        for f in fails:
            print("  · " + f)
        return 1
    print("self-test OK（6 例：缺 manifest/良性/改稿未拍/新帧没拍/字节被换/孤儿帧）")
    return 0


def shoot(name, size, screen, theme, w, h, extra="", scale=2):
    os.makedirs(OUT, exist_ok=True)
    dest = os.path.join(OUT, name + ".png")
    # **先把它删干净**，因为下面的完成判据是「文件存在且非空」——目标文件本来就在时，
    # 这个判据分不清「这轮刚写出来的」和「上一轮留下的」。实测踩过：Chrome 的
    # `--screenshot=` 会先 unlink 旧图再写，一旦删除被环境拦掉（WorkBuddy 的批量删除
    # 守卫、或只读挂载），Chrome 写不进去，脚本却对着**上一轮的旧图**报 ok —— 一整批
    # 「重拍成功」里混着没换的图，人眼验收链就废了。
    if os.path.exists(dest):
        try:
            os.remove(dest)
        except OSError as e:
            return "FAIL  %s（旧图删不掉，无法保证重拍：%s）" % (name, e), None
        if os.path.exists(dest):
            return "FAIL  %s（旧图仍在，删除被环境拦掉了）" % name, None
    profile = tempfile.mkdtemp(prefix="shot-")
    t0 = time.time()
    cmd = [
        CHROME,
        "--headless",
        "--no-sandbox",
        "--disable-gpu",
        "--disable-dev-shm-usage",
        "--hide-scrollbars",
        "--no-proxy-server",
        "--user-data-dir=" + profile,
        "--force-device-scale-factor=%d" % scale,
        "--window-size=%d,%d" % (w, h),
        "--screenshot=" + dest,
        "%s&size=%s&screen=%s&theme=%s%s" % (BASE, size, screen, theme, extra),
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.time() + 45
    while time.time() < deadline:
        if os.path.exists(dest) and os.path.getsize(dest) > 0:
            break
        if proc.poll() is not None:
            break
        time.sleep(0.25)
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
    shutil.rmtree(profile, ignore_errors=True)
    # 完成判据里带上 mtime：这一轮启动之后写出来的才算数（旧图已在上面删掉，
    # mtime 这一条是第二道锁，防止删除被静默 no-op 的情况混过去）。
    fresh = (os.path.exists(dest) and os.path.getsize(dest) > 0
             and os.path.getmtime(dest) >= t0 - 1)
    if not fresh:
        return "FAIL  %s（没写出新图）" % name, None
    with open(dest, "rb") as fh:
        png_sha = hashlib.sha256(fh.read()).hexdigest()
    return "ok    %-24s %8d bytes" % (name + ".png", os.path.getsize(dest)), png_sha


def main():
    args = [a for a in sys.argv[1:]]
    if "--self-test" in args:
        return self_test()
    if "--verify-only" in args:
        return verify_only()
    keys = [a for a in args if not a.startswith("--")]
    shots = [s for s in SHOTS if not keys or any(k in s[0] for k in keys)]
    cur = draft_sha()
    bad = 0
    done = {}
    for shot in shots:
        line, png_sha = shoot(*shot)
        print(line, flush=True)
        if line.startswith("FAIL"):
            bad += 1
        elif png_sha:
            done[shot[0]] = {"source_sha": cur, "png_sha": png_sha,
                             "screen": shot[2], "theme": shot[3], "size": shot[1]}
    # manifest 更新策略：**部分重拍（带关键词）不覆盖全量 manifest**，
    # 只把重拍的那几帧合并进去，其余帧保持自己拍时的稿子 sha ——
    # 于是拍完几帧后 --verify-only 照样报其余帧过期。这是特性不是缺陷：
    # 只有全量重拍（不带关键词）才允许把整个 manifest 刷成同一个 sha。
    old = load_manifest() or {"frames": {}}
    frames = old.get("frames", {})
    if keys:
        frames.update(done)
    else:
        frames = done
    manifest = {"source": {"path": "prototype/relay.html", "sha256": cur},
                "frame_count": len(frames), "frames": frames}
    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write("\n")
    os.replace(tmp, MANIFEST)
    print("\n共 %d 张，失败 %d 张 -> %s" % (len(shots), bad, OUT))
    print("manifest -> %s（稿子 %s…，%d 帧）" % (os.path.basename(MANIFEST), cur[:12], len(frames)))
    if not keys and not bad:
        problems = verify(manifest, cur, SHOTS, OUT)
        if problems:
            print("注意：全量重拍后自检仍报 %d 条：" % len(problems))
            for x in problems[:10]:
                print("  · " + x)
            bad += 1
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
