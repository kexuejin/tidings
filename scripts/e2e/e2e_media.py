#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""媒体与互动 e2e 真机夹具：图片 / 语音 / 表格文字 + 撤回 / 戳一碰 / PK。

覆盖 docs/plans/2026-10-09-relay-e2e-coverage-matrix.md §2 E 段「媒体预览」（R49–R57）
与 F 段中**媒体相邻**的那几条（R58–R66）：

  R49  图片内联预览：`purpose:"image"` 的 file-ctl offer ≤20MB **自动接收**
       （TransferManager.kt:662 `autoAccept = purpose == IMAGE && totalSize <= 20MB`），
       TCP 拉取 + sha256 校验完成后卡片走 Completed，`mediaKindOf` 判 `MediaKind.Image`，
       `InlineImagePreview` 画出位图（ChatScreen.kt:1298）。dump 里这张图**没有
       text、没有 contentDescription**，唯一痕迹是一个 clickable、无文字、边长 ≤240dp
       的节点 —— 所以这条行用「结构痕迹 + 点击能开出查看图片弹层」两条一起证。
  R50  语音播放：`purpose:"share"`（**协议外自造值**）的 `.m4a`。`FilePurpose.ALL`
       不含 `"share"`，所以 `mediaKindOf` 走扩展名分支判 `MediaKind.Audio`
       （MediaKind.kt 的 KDoc 记着 2026-10-07 那条真机缺陷），卡片上有「播放」。
  R51  解不出的格式安静退回：`.m4a` 装了非音频字节 → `MediaPlayer.prepare()` 抛异常 →
       `platformPlayAudioFile` 返回 false → 卡片原地多一行
       `file_audio_unsupported`，**不崩、不换卡片**。
  R52  图片点击放大全屏：点 R49 那个匿名节点 → `ImageViewerDialog`，里面有
       contentDescription =`chat_image_preview_title`（查看图片）。弹层里的
       「保存 / 分享」是平台出口（ACTION_VIEW / ACTION_SEND），本夹具只报它在不在。
  R53  表格图「看表格文字」：offer 带 `tableText`（≤4096 B）时卡片上出现
       `file_table_show_text`（ChatScreen.kt:1065），点开显示文字、按钮变成
       `file_table_hide_text`。
  R54  表格文字截断说明行：`tableTextTruncated=true` 时展开后再出一行
       `file_table_truncated`。
  R55  表格区横向滚动本身：**不可覆盖** —— 滚动是 Compose 的
       `horizontalScroll` 手势，dump 里没有任何可判定的状态位。
  R56  其它 `purpose` 带 `tableText` 的未定义组合：`tableText` 的渲染在
       ChatScreen.kt:1065 **与 purpose 无关**，所以用 `purpose:"share-get"` 再发一次，
       按钮照样出现 —— 这就是 R56 要找的「未定义组合」。
  R57  `sticker` （和 `update`）的内联上限：`autoAccept` 只认 `purpose == "image"`，
       `sticker` **不在内** → 卡片上必须出现「接收」按钮，UDP 上**看不到** accept。
  R58  文本撤回：`msg {kind:recall, targetId}` 打到一条我们发出的文本的 envelope id 上，
       气泡换成 `chat_recall_peer`（对方撤回了一条消息）。
  R59  图片撤回：offer 带 `msgId`，图先收完（Completed），再发
       `msg {kind:recall, targetId: msgId}`。`acceptsMediaRecall(purpose, Completed)`
       对 `purpose=="image"` 放行 → 卡片被换成撤回提示。
  R60  撤回时对端正在拉取：`recallLocally` 走 `TransferManager.cancelIncoming`
       （TransferManager.kt:1011）—— 它会 `cancelRef.socket?.close()`，夹具这一侧
       直接表现为**写流中途对端断开**。同一条行还顺手钉住一个真实缺陷：
       `file-ctl {op:cancel}` 走的是 `onPeerKilled`（TransferManager.kt:693），
       它对**已在拉取中**的入向传输是空操作 —— 帧被 ACK 了，拉取照跑完。
  R61  超窗（>5 分钟）没有撤回入口：`RECALL_WINDOW_MS` 是**发送侧** `canRecall` 的约束
       （ChatInteractions.kt），协议里没有任何「超窗」帧，夹具扮演的是对端 ——
       **不可覆盖**，需要人工。
  R62  戳一碰发送：`msg {kind:nudge}` → 收端 `onNudged`（RelayControllerMessages.kt:342）
       追加 `chat_nudge_peer`（对方戳了你一下）。
  R63  戳一碰限流：`NudgeThrottle(maxPerWindow=2, windowMs=60s, minGapMs=15s)` ——
       15 秒内的第二条、以及窗口内第三条都不该落成第二条提示。
  R64  戳一碰收端定位会话：`onNudged` 里 `requestFocus(envelope.from)` ——
       先退到会话列表，再发一条 nudge，app 应该自己把那个会话打开（composer 出现）。
  R65  PK 猜拳 / 骰子：`msg {kind:pk}` → `pkEmojiPreview` 写进气泡正文，
       rps/rock 是 ✊、dice/4 是 ⚃。dump 里可见的就是这两个 emoji。
  R66  `pk` 校验失败关闭：`PK_KEYS` 是**严格白名单**（Codec.kt:95/400），多一个键整帧
       被 `Codec.decode` 判 `Rejected` → `Messenger.onDatagram` 第 99 行直接 return
       → **连 ACK 都没有**。非法 game/value 同理。

夹具自己装成"那台电脑"：UDP 握手 / 收 ACK 用共享库的 `UdpPeer`；TCP 侧另起一个
**拉取服务端**（共享库的 `TcpFrameServer` 只认 `{"type":"msg"}`，没有 pull/pull-ok/done
与裸流能力），逐帧对齐 `core/src/commonMain/kotlin/com/kapp/tidings/core/transport/TransferServer.kt`。
`e2e_files.py` 是同一个形状的兄弟夹具；两个文件不互相 import（README §约定）。

    # 不插手机也能确认自己没写崩（纯本地断言，不碰网络、不碰设备）：
    python3 scripts/e2e/e2e_media.py --dry-run

    # 真机：
    python3 scripts/e2e/e2e_media.py --phone 192.168.2.22 --host-ip 192.168.2.82

    # 只测协议面（不 dump UI、不注入按键；播放/表格/撤回/戳一碰/PK 的 UI 面无从触发）：
    python3 scripts/e2e/e2e_media.py --skip-ui

退出码 0=PASS / 1=FAIL / 2=用法或环境错误；结尾恰好一行 `result ...`。

覆盖不了的（矩阵 §2 E 段里显然不归这个夹具的）：
  R55  表格区横向滚动：Compose `horizontalScroll` 的手势，dump 无状态位 —— 需要人工。
  R61  超窗撤回入口：发送侧规则，协议无帧 —— 需要人工（且 5 分钟窗口本身不适合夹具）。
  R52  弹层里的「保存 / 分享」：平台出口（ACTION_VIEW / ACTION_SEND + FileProvider），
       本夹具只报按钮在不在，真正落地**必须人工**。
"""

import hashlib
import io
import json
import math
import os
import shutil
import socket
import struct
import sys
import tempfile
import threading
import time
import wave
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay_e2e as R  # noqa: E402


# ---------------------------------------------------------------------------
# 协议常量：逐条对齐
#   core/src/commonMain/kotlin/com/kapp/tidings/core/protocol/FileTransfer.kt
#   core/src/commonMain/kotlin/com/kapp/tidings/core/protocol/Codec.kt
#   core/src/commonMain/kotlin/com/kapp/tidings/core/protocol/MessageTypes.kt
# ---------------------------------------------------------------------------

FILE_CTL = "file-ctl"
MSG = "msg"

OP_OFFER = "offer"
OP_ACCEPT = "accept"
OP_DECLINE = "decline"
OP_CANCEL = "cancel"

PURPOSE_IMAGE = "image"
PURPOSE_STICKER = "sticker"
PURPOSE_UPDATE = "update"
PURPOSE_SHARE = "share"            # **协议外自造值**：不在 FilePurpose.ALL 里，
PURPOSE_SHARE_GET = "share-get"    #   MediaKind 只能靠扩展名兜。别把它当合法用途。
PURPOSE_SHARE_PUT = "share-put"

OFFER_FILES_PER_PACKET = 6
MAX_FILES_PER_TRANSFER = 2000
IMG_AUTO_ACCEPT = 20 * 1024 * 1024
GROUP_IMG_AUTO_ACCEPT = 10 * 1024 * 1024
MAX_FRAME = 64 * 1024
FILE_OFFER_TTL_MS = 24 * 60 * 60 * 1000
TABLE_TEXT_LIMIT_BYTES = 4096

# FileCtlOffer 的字段**声明顺序**就是线上 key 顺序；必需键 = 下面这 8 个。
OFFER_REQUIRED = ("op", "transferId", "seq", "total", "files", "totalSize", "fileCount", "rootName")
OFFER_OPTIONAL = ("msgId", "purpose", "expiresAt", "tableText", "tableTextTruncated",
                  "groupId", "groupRev", "isFolder")

# TCP 控制帧：TcpFrames.hasOnlyKeys 是**严格键集合**比对，多一个键整帧拒。
TCP_KEYS = {
    "pull": ("type", "from", "transferId", "fileId", "offset"),
    "pull-ok": ("type", "fileId", "len"),
    "done": ("type", "fileId", "sha256"),
    "finish": ("type", "transferId"),
    "err": ("type", "reason"),
    "wait": ("type",),
    "msg": ("type", "envelope"),
    "msg-ack": ("type", "ackFor"),
}

# msg 的 kind（MsgKinds）与它们的载荷白名单严格程度 —— 三档完全不同，别混。
K_TEXT = "text"
K_RECALL = "recall"
K_NUDGE = "nudge"
K_PK = "pk"

# Codec.kt:95 —— `pk` 是**严格**白名单；`recall` / `nudge` **没有**白名单。
PK_KEYS = ("kind", "game", "value", "groupId", "groupRev", "resend")

PK_GAMES = ("rps", "dice")
RPS_VALUES = ("rock", "paper", "scissors")

# RelayControllerPk.pkEmojiPreview 的映射（气泡正文就是这些字符）。
RPS_FACES = {"rock": "✊", "scissors": "✌\ufe0f", "paper": "🖐\ufe0f"}
DICE_FACES = {"1": "⚀", "2": "⚁", "3": "⚂", "4": "⚃", "5": "⚄", "6": "⚅"}

# 均分/展开都在 Kotlin 侧；篇幅限制，这里只做长度与字符集检查。

# UI 文案（composeApp/src/commonMain/composeResources/values/strings.xml）。
# 全部通过 uiautomator dump 出来的 bounds 点击，**绝不目测像素**。
UI_ACCEPT = "接收"                        # file_accept
UI_DECLINE = "拒绝"                       # file_decline
UI_PLAY = "播放"                          # file_play_audio
UI_AUDIO_UNSUPPORTED = "这台设备放不了这个格式"    # file_audio_unsupported
UI_TABLE_SHOW = "看表格文字"               # file_table_show_text
UI_TABLE_HIDE = "收起文字"                 # file_table_hide_text
UI_TABLE_TRUNCATED = "文字已被发送端截断，图片是完整的。"  # file_table_truncated
UI_PREVIEW_TITLE = "查看图片"              # chat_image_preview_title
UI_SHARE = "分享"                         # file_share
UI_SAVED_PREFIX = "已存到"                 # file_saved_to
UI_RECALL_PEER = "对方撤回了一条消息"         # chat_recall_peer
UI_DECLINED = "对方已拒绝"                 # file_state_declined（strings.xml:404）
UI_NUDGE_PEER = "对方戳了你一下"             # chat_nudge_peer
UI_NAV_CABINET = "文件柜"                  # screen_cabinet（本夹具只用来确认能退出去）

# 内联图预览最长边 240dp（ChatScreen.MAX_PREVIEW_SIDE）；480 = 2x 密度下的宽容上界。
INLINE_PREVIEW_MAX_PX = 480

IMG_HEADER = bytes([0x89]) + b"PNG\r\n\x1a\n"


# ---------------------------------------------------------------------------
# 纯本地协议层：造帧 + 校验（`--dry-run` 全靠这一层）
# ---------------------------------------------------------------------------

def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def _nonempty_str(x, maxlen):
    return isinstance(x, str) and x != "" and len(x) <= maxlen


def _nonneg_safe_int(x):
    # nonNegativeSafeInteger：1.5 与字符串都被拒，上界 2^53-1。
    if isinstance(x, bool):
        return False
    if isinstance(x, int):
        return 0 <= x <= 9007199254740991
    return False


def file_meta(file_id, path, size, is_dir=None):
    meta = {"fileId": file_id, "path": path, "size": int(size)}
    if is_dir is not None:
        meta["isDir"] = bool(is_dir)
    return meta


def offer_payload(transfer_id, files, total_size=None, root_name=None, seq=1, total=1,
                  file_count=None, purpose=None, group_id=None, group_rev=None,
                  msg_id=None, is_folder=None, expires_at=None, table_text=None,
                  table_text_truncated=None):
    """造一条 `file-ctl offer` 载荷（键顺序按 FileCtlOffer 的声明顺序）。

    多一个键、少一个键都会被收端 `wireJson.decodeFromJsonElement` 整条丢掉
    （TransferManager.kt:614 的 `runCatching{}.getOrNull() ?: return`），
    而且**现象是静默的**：没有 err、没有拒绝，卡片就是不出现。
    """
    payload = {"op": OP_OFFER, "transferId": transfer_id}
    if msg_id is not None:
        payload["msgId"] = msg_id
    payload["seq"] = int(seq)
    payload["total"] = int(total)
    payload["files"] = list(files)
    payload["totalSize"] = int(total_size if total_size is not None
                               else sum(int(f["size"]) for f in files))
    payload["fileCount"] = int(file_count if file_count is not None else len(files))
    payload["rootName"] = root_name if root_name is not None else (
        files[0]["path"] if files else transfer_id)
    if purpose is not None:
        payload["purpose"] = purpose
    if expires_at is not None:
        payload["expiresAt"] = int(expires_at)
    if table_text is not None:
        payload["tableText"] = table_text
    if table_text_truncated is not None:
        payload["tableTextTruncated"] = bool(table_text_truncated)
    if group_id is not None:
        payload["groupId"] = group_id
    if group_rev is not None:
        payload["groupRev"] = int(group_rev)
    if is_folder is not None:
        payload["isFolder"] = bool(is_folder)
    return payload


def simple_payload(op, transfer_id):
    """`accept` / `decline` / `cancel` / `direct`：只有 op 与 transferId。"""
    return {"op": op, "transferId": transfer_id}


def chunk_offers(metas, transfer_id, total_size=None, root_name=None, purpose=None,
                 group_id=None, group_rev=None, is_folder=None, expires_at=None,
                 msg_id=None, table_text=None, table_text_truncated=None):
    """按 OFFER_FILES_PER_PACKET=6 分片。收端凑齐 `fileCount` 条才算完整。"""
    chunks = [metas[i:i + OFFER_FILES_PER_PACKET]
              for i in range(0, len(metas), OFFER_FILES_PER_PACKET)] or [[]]
    total = len(chunks)
    out = []
    for idx, chunk in enumerate(chunks):
        out.append(offer_payload(
            transfer_id, chunk, total_size=total_size, root_name=root_name,
            seq=idx + 1, total=total, file_count=len(metas), purpose=purpose,
            group_id=group_id, group_rev=group_rev, is_folder=is_folder,
            expires_at=expires_at, msg_id=msg_id, table_text=table_text,
            table_text_truncated=table_text_truncated))
    return out


def tcp_frame_bytes(obj, body=b""):
    """4 字节大端长度前缀 + JSON 体（+ 裸流）。与共享库 tcp_frame 同一形状。"""
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return struct.pack(">I", len(raw)) + raw + body


def frame_keys_ok(obj, expected):
    """**严格相等**的键集合比对 —— 这是 TCP 帧侧的 `TcpFrames.hasOnlyKeys`。"""
    return isinstance(obj, dict) and len(obj) == len(expected) and all(k in expected for k in obj)


def has_only_keys(obj, allowed):
    """Codec.kt:144 的 `hasOnlyKeys`：**只**要求 `o` 的键都在 allowed 里，
    并不要求 allowed 里的键都出现（那才是 frame_keys_ok 的语义）。"""
    return isinstance(obj, dict) and all(k in allowed for k in obj)


def validate_offer(payload):
    """本地校验一条 offer 载荷够不够收端 `decodeFromJsonElement` 这一关。

    返回 None 表示通过，否则返回一个短原因串。
    """
    if not isinstance(payload, dict):
        return "not-object"
    for key in OFFER_REQUIRED:
        if key not in payload:
            return "missing:" + key
    for key in payload:
        if key not in OFFER_REQUIRED and key not in OFFER_OPTIONAL:
            return "unknown-key:" + key
    if not _nonempty_str(payload.get("op"), 16):
        return "bad-op"
    if not _nonempty_str(payload.get("transferId"), 128):
        return "bad-transferId"
    if not _nonneg_safe_int(payload.get("seq")) or payload["seq"] < 1:
        return "bad-seq"
    if not _nonneg_safe_int(payload.get("total")) or payload["total"] < 1:
        return "bad-total"
    if not _nonneg_safe_int(payload.get("totalSize")):
        return "bad-totalSize"
    if not _nonneg_safe_int(payload.get("fileCount")) or payload["fileCount"] < 1:
        return "bad-fileCount"
    if not _nonempty_str(payload.get("rootName"), 255):
        return "bad-rootName"
    files = payload.get("files")
    if not isinstance(files, list) or not files:
        return "bad-files"
    if len(files) > OFFER_FILES_PER_PACKET:
        return "too-many-files-in-packet"
    if payload["fileCount"] > MAX_FILES_PER_TRANSFER:
        return "over-max-files"
    for meta in files:
        if not isinstance(meta, dict):
            return "bad-meta"
        if not _nonempty_str(meta.get("fileId"), 128):
            return "bad-fileId"
        if not isinstance(meta.get("path"), str) or meta["path"] == "":
            return "bad-path"
        if not _nonneg_safe_int(meta.get("size")):
            return "bad-size"
    if payload.get("purpose") is not None and not _nonempty_str(payload["purpose"], 32):
        return "bad-purpose"
    if payload.get("tableText") is not None:
        tt = payload["tableText"]
        if not isinstance(tt, str) or R.utf8_len(tt) > TABLE_TEXT_LIMIT_BYTES:
            return "bad-tableText"
    return None


def validate_control_frame(obj):
    """逐条对齐 `TcpFrames.decodeBody`：键集合严格相等 + 逐字段有界。"""
    if not isinstance(obj, dict):
        return "bad-frame-shape"
    ftype = obj.get("type")
    if not isinstance(ftype, str) or ftype not in TCP_KEYS:
        return "bad-frame-shape"
    if not frame_keys_ok(obj, TCP_KEYS[ftype]):
        return "bad-frame-shape"
    if ftype == "pull":
        if not _nonempty_str(obj.get("from"), 128):
            return "bad-from"
        if not _nonempty_str(obj.get("transferId"), 128):
            return "bad-transferId"
        if not _nonempty_str(obj.get("fileId"), 128):
            return "bad-fileId"
        if not _nonneg_safe_int(obj.get("offset")):
            return "bad-offset"
        return None
    if ftype == "pull-ok":
        if not _nonempty_str(obj.get("fileId"), 128):
            return "bad-fileId"
        if not _nonneg_safe_int(obj.get("len")) or obj["len"] < 1:
            return "bad-len"
        return None
    if ftype == "done":
        if not _nonempty_str(obj.get("fileId"), 128):
            return "bad-fileId"
        digest = obj.get("sha256")
        if not isinstance(digest, str) or len(digest) != 64 \
                or any(c not in "0123456789abcdefABCDEF" for c in digest):
            return "bad-sha256"
        return None
    if ftype == "finish":
        return None if _nonempty_str(obj.get("transferId"), 128) else "bad-transferId"
    if ftype == "err":
        return None if _nonempty_str(obj.get("reason"), 128) else "bad-reason"
    return None


# --- msg 载荷：三档白名单严格程度不同，本地各造一条、各验一条 ----------------

def text_msg_payload(text, resend=None):
    payload = {"kind": K_TEXT, "text": text}
    if resend is not None:
        payload["resend"] = bool(resend)
    return payload


def recall_payload(target_id, group_id=None, group_rev=None):
    """Codec.recallPayload：**没有** hasOnlyKeys（Codec.kt:317-320）。"""
    payload = {"kind": K_RECALL, "targetId": target_id}
    if group_id is not None:
        payload["groupId"] = group_id
        payload["groupRev"] = int(group_rev or 0)
    return payload


def nudge_payload():
    """Codec.nudgePayload：payload 就是 `{"kind":"nudge"}`；除 resend 外无白名单。"""
    return {"kind": K_NUDGE}


def pk_payload(game, value, group_id=None, group_rev=None):
    """Codec.pkPayload（Codec.kt:707）：dice 的 `value` 写成**数字**，rps 写成字符串。"""
    payload = {"kind": K_PK, "game": game}
    if game == "dice":
        n = int(value)
        payload["value"] = n
    else:
        payload["value"] = str(value)
    if group_id is not None:
        payload["groupId"] = group_id
        payload["groupRev"] = int(group_rev or 0)
    return payload


def validate_pk_local(payload):
    """照抄 `Codec.validatePk`（Codec.kt:400-417）。返回 None 或原因串。

    用的是 `hasOnlyKeys`（**子集**判定），不是 TCP 帧那种等长比对 ——
    合法 pk 只带 kind/game/value 三个键，正是靠这条才能通过。
    """
    if not isinstance(payload, dict):
        return "not-object"
    if not has_only_keys(payload, PK_KEYS):
        return "not-only-pk-keys"
    game = payload.get("game")
    if not isinstance(game, str):
        return "bad-game"
    value = payload.get("value")
    if game == "rps":
        if not isinstance(value, str) or value not in RPS_VALUES:
            return "bad-rps-value"
    elif game == "dice":
        n = value if isinstance(value, int) and not isinstance(value, bool) else None
        if n is None and isinstance(value, str):
            n = int(value) if value.isdigit() else None
        if n is None or not (1 <= n <= 6):
            return "bad-dice-value"
    else:
        return "unknown-game"
    if ("groupId" in payload) != ("groupRev" in payload):
        return "group-ref-not-paired"
    return None


def local_frame_checks():
    """纯本地断言：造帧与白名单这一层自己不能错。`--dry-run` 主要靠它。"""
    # --- offer：键集合与必需键
    metas = [file_meta("f-1", "a.png", 4096)]
    ok_offer = offer_payload("t-x", metas, purpose=PURPOSE_IMAGE, msg_id="m-1",
                             table_text="姓名,分数")
    R.check("local-offer-shape-ok", validate_offer(ok_offer) is None,
            "自造 offer 过本地校验：%s" % validate_offer(ok_offer))
    R.check("local-offer-has-no-unknown-keys",
            all(k in OFFER_REQUIRED or k in OFFER_OPTIONAL for k in ok_offer),
            "所有键都在 FileCtlOffer 的声明集里（多一个键收端整条静默丢弃）")

    bad_missing = dict(ok_offer)
    bad_missing.pop("fileCount")
    R.check("local-offer-missing-required-detected",
            validate_offer(bad_missing) == "missing:fileCount",
            "少 fileCount 被本地校验拦下：%s" % validate_offer(bad_missing))

    bad_extra = dict(ok_offer)
    bad_extra["purposeX"] = "image"
    R.check("local-offer-extra-key-detected",
            validate_offer(bad_extra) == "unknown-key:purposeX",
            "多一个键被本地校验拦下（这正是收端静默丢帧的那条）")

    # --- tableText 的 4096 字节界限
    tt = "a" * TABLE_TEXT_LIMIT_BYTES
    R.check("local-tabletext-at-limit-ok",
            validate_offer(offer_payload("t-x", metas, table_text=tt)) is None,
            "tableText 正好 %d 字节通过" % TABLE_TEXT_LIMIT_BYTES)
    tt_over = "a" * (TABLE_TEXT_LIMIT_BYTES + 1)
    R.check("local-tabletext-over-limit-detected",
            validate_offer(offer_payload("t-x", metas, table_text=tt_over)) == "bad-tableText",
            "超出 1 字节被本地校验拦下")
    # 多字节字符按 utf8 字节算，不按 Python 的字符数算。
    tt_cjk = "表" * (TABLE_TEXT_LIMIT_BYTES // 3)
    R.check("local-tabletext-counts-utf8-bytes",
            validate_offer(offer_payload("t-x", metas, table_text=tt_cjk)) is None
            and R.utf8_len(tt_cjk) <= TABLE_TEXT_LIMIT_BYTES,
            "%d 个汉字 = %d 字节（按 UTF-8 字节，不是字符数）"
            % (len(tt_cjk), R.utf8_len(tt_cjk)))

    # --- msg 的三档白名单
    R.check("local-pk-keys-exact",
            validate_pk_local(pk_payload("rps", "rock")) is None,
            "合法 pk 通过：%s" % validate_pk_local(pk_payload("rps", "rock")))
    pk_extra = pk_payload("rps", "rock")
    pk_extra["extra"] = 1
    R.check("local-pk-extra-key-rejected",
            validate_pk_local(pk_extra) == "not-only-pk-keys",
            "pk 多一个键 → %s（严格白名单，整帧 Rejected，连 ACK 都不回）"
            % validate_pk_local(pk_extra))
    R.check("local-pk-bad-rps-value-rejected",
            validate_pk_local(pk_payload("rps", "lizard")) == "bad-rps-value",
            "rps 的 value 不在 rock/paper/scissors 里 → 拒")
    R.check("local-pk-dice-numeric-accepted",
            validate_pk_local(pk_payload("dice", 4)) is None
            and pk_payload("dice", 4)["value"] == 4,
            "dice 的 value 是数字 4（JsonPrimitive(num)，不是字符串）")
    R.check("local-pk-dice-out-of-range-rejected",
            validate_pk_local(pk_payload("dice", 7)) == "bad-dice-value",
            "dice 7 点 → 拒")
    R.check("local-pk-unknown-game-rejected",
            validate_pk_local({"kind": K_PK, "game": "coin", "value": "head"}) == "unknown-game",
            "未知 game → 拒（Codec 的 else 分支）")
    # recall / nudge **没有**白名单：多一个键照样过。夹具只用最小键集合，
    # 绝不会拿「多一个键被拒」去证这两类 —— 那会是一条为错理由通过的断言。
    R.check("local-recall-nudge-use-minimal-keys",
            set(recall_payload("m-1")) == {"kind", "targetId"}
            and set(nudge_payload()) == {"kind"},
            "recall/nudge 的载荷只有最小键集合；Codec 里它们**没有** hasOnlyKeys"
            "（Codec.kt:313 / 317-320），多键不会被拒，所以夹具不造这种帧")

    # --- TCP 帧
    frame = tcp_frame_bytes({"type": "pull", "from": R.FIXTURE_NODE, "transferId": "t-x",
                             "fileId": "f-1", "offset": 0})
    R.check("local-tcp-frame-roundtrip", _roundtrip_frame(frame) is not None,
            "4 字节大端长度前缀 + JSON 可原样解回（%d 字节）" % len(frame))
    pull_frame = {"type": "pull", "from": R.FIXTURE_NODE, "transferId": "t-x",
                  "fileId": "f-1", "offset": 0}
    R.check("local-tcp-pull-keys-exact", validate_control_frame(pull_frame) is None,
            "pull 的键集合 = %s" % (TCP_KEYS["pull"],))
    bad_frame = dict(pull_frame)
    bad_frame["extra"] = 1
    R.check("local-tcp-extra-key-rejected",
            validate_control_frame(bad_frame) == "bad-frame-shape",
            "TCP 帧多一个键 → hasOnlyKeys 判 bad-frame-shape")

    # --- 素材
    png = png_bytes(8, 8, seed=3)
    R.check("local-png-header", png.startswith(IMG_HEADER) and len(png) > 100,
            "自造 PNG 头正确、%d 字节" % len(png))
    wav = wav_bytes(50)
    R.check("local-wav-header", wav[:4] == b"RIFF" and wav[8:12] == b"WAVE",
            "自造 WAV 头正确、%d 字节" % len(wav))
    R.check("local-media-kind-expectations",
            media_kind_expectation(PURPOSE_IMAGE, "shot.png") == "image"
            and media_kind_expectation(PURPOSE_STICKER, "s.png") == "image"
            and media_kind_expectation(PURPOSE_SHARE, "voice.m4a") == "audio"
            and media_kind_expectation(PURPOSE_SHARE_GET, "t.png") == "other"
            and media_kind_expectation(None, "voice.m4a") == "audio"
            and media_kind_expectation(None, "notes.txt") == "other",
            "镜像 mediaKindOf：image/sticker→图；协议外 'share'+.m4a→按扩展名判 audio；"
            "share-get 等合法用途→other")
    R.check("local-emoji-mapping",
            RPS_FACES["rock"] == "✊" and DICE_FACES["4"] == "⚃",
            "PK 气泡正文：rock→✊、dice 4→⚃")

    R.check("local-reliable-covers-file-ctl", FILE_CTL in R.RELIABLE_TYPES,
            "file-ctl 在 RELIABLE_TYPES 里 → 每条 offer 都要等 ACK 才算送到")
    R.check("local-table-text-limit-matches-core", TABLE_TEXT_LIMIT_BYTES == R.TEXT_TCP_LIMIT,
            "TABLE_TEXT_LIMIT_BYTES == Protocol.TEXT_TCP_LIMIT == %d" % R.TEXT_TCP_LIMIT)


def _roundtrip_frame(frame):
    (length,) = struct.unpack(">I", frame[:4])
    if length <= 0 or length > MAX_FRAME or len(frame) < 4 + length:
        return None
    try:
        return json.loads(frame[4:4 + length].decode("utf-8"))
    except ValueError:
        return None


def media_kind_expectation(purpose, name):
    """镜像 `app/MediaKind.kt:mediaKindOf` —— 只用来在本地断言夹具的预期没写反。

    第一版是「非空且不是媒体用途就按普通文件算」，于是协议外自造值 `purpose:"share"`
    的 `.m4a` 再也没有「播放」按钮（2026-10-07 真机缺陷），现在 `"share"` 走扩展名。
    """
    if purpose in (PURPOSE_IMAGE, PURPOSE_STICKER):
        return "image"
    if purpose in (PURPOSE_UPDATE, PURPOSE_SHARE_GET, PURPOSE_SHARE_PUT):
        return "other"
    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
    if ext in ("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif"):
        return "image"
    if ext in ("m4a", "aac", "mp3", "ogg", "oga", "opus", "wav", "amr", "flac", "caf"):
        return "audio"
    return "other"


# ---------------------------------------------------------------------------
# 素材：真 PNG / 真 WAV / 装成音频的垃圾字节
# ---------------------------------------------------------------------------

def png_bytes(width=96, height=96, seed=0):
    """一张真 PNG（RGB8）。Android 的 BitmapFactory 必须解得开，
    R49 的内联预览才有得画 —— 解不开时 `InlineImagePreview` 直接 `return`，什么都不画。"""
    raw = bytearray()
    x = (seed + 104729) & 0xFFFFFFFF
    for _y in range(height):
        raw.append(0)                       # filter type 0
        for _x3 in range(width * 3):
            x = (1103515245 * x + 12345) & 0x7FFFFFFF
            raw.append((x >> 16) & 0xFF)
    return IMG_HEADER + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) \
        + _png_chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + _png_chunk(b"IEND", b"")


def _png_chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def wav_bytes(ms=400, rate=8000):
    """一段真 WAV（16-bit PCM 单声道）。MediaPlayer 认 RIFF/WAVE，认扩展名以外的内容。"""
    frames = bytearray()
    total = max(1, int(rate * ms / 1000.0))
    for i in range(total):
        frames += struct.pack("<h", int(3000 * math.sin(2 * math.pi * 440 * i / rate)))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))
    return buf.getvalue()


def garbage_bytes(size=4096, seed=0):
    """看不出格式的字节 —— 用来让 `file_audio_unsupported` 出现。"""
    out = bytearray()
    x = (seed + 2654435761) & 0xFFFFFFFF
    while len(out) < size:
        x = (1103515245 * x + 12345) & 0x7FFFFFFF
        out += struct.pack("<I", x)
    return bytes(out[:size])


class LocalFile(object):
    """夹具侧的一个待发文件：磁盘 bytes + 线上 FileMeta 三件套。"""

    __slots__ = ("path", "name", "size", "file_id", "sha256", "data")

    def __init__(self, path, name, file_id):
        self.path = path
        self.name = name
        self.file_id = file_id
        with open(path, "rb") as fh:
            self.data = fh.read()
        self.size = len(self.data)
        self.sha256 = sha256_hex(self.data)

    def meta(self):
        return file_meta(self.file_id, self.name, self.size)

    def __repr__(self):
        return "LocalFile(%r, %dB)" % (self.name, self.size)


def write_material(root, name, data):
    path = os.path.join(root, name.replace("/", "_"))
    os.makedirs(os.path.dirname(path) or root, exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)
    return path


def new_transfer_id(tag):
    return "t-%s-%s" % (tag, R.uniq_marker("", 6))


def register(server, *files):
    for f in files:
        server.files[f.file_id] = (f.size, f.data, f.sha256)
    return server


# ---------------------------------------------------------------------------
# TCP 拉取服务端（TransferServer.kt 的最小镜像）
# ---------------------------------------------------------------------------

class _Interrupted(Exception):
    """夹具**故意**在把 len 字节发完之前断流。"""


class PullServer(object):
    """监听夹具自己的 `profile.tcpPort`，服务手机发来的 `pull`。

    与共享库 `TcpFrameServer` 的区别：那个只认 `{"type":"msg","envelope":…}`。
    这里做的是 `TransferServer.serve()` 的那半边 —— 收 `pull`，回
    `pull-ok{len}` + len 字节裸流 + `done{sha256}`；`finish` 只记一笔。

    三个容易写错的地方，逐条对齐 Kotlin：
      * 首帧等 15s 握手、之后按 60s 空闲；`pull` 里 `offset > file.size` 回
        `err reason:"bad-offset"`，`resolve` 不到回 `err reason:"not-found"`。
      * **裸流发完才发 `done`**；数据面里插帧会被收端当文件内容吃掉。
      * 收端先发的 `pull-ok`/`done`/`err`/`wait` 一律**静默忽略、不回 err** ——
        回了就会把续传时对方先发的 `wait` 判成 `peer:busy` 而整条失败。

    `delay_ms` 是 R60 的关键旋钮：把一条流拖慢，才有一个能塞进 `recall` 的窗口。
    """

    def __init__(self, port=R.TCP_PORT_DEFAULT, bind="0.0.0.0", name="pull"):
        self.port = int(port)
        self.bind = bind
        self.name = name
        self._srv = None
        self._thread = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self.files = {}          # fileId -> (size, data, sha256)
        self.pulls = []          # [(ts, addr, frame)]
        self.finishes = []       # [(ts, addr, frame)]
        self.ignored = []        # [(ts, addr, frame)] 收端帧（wait/pull-ok/done/err）
        self.errors = []         # [(ts, addr, reason)]
        self.incomplete = []     # [(ts, addr, frame)] 夹具故意断流的 pull
        self.disconnects = []    # [(ts, addr)] 发流中途对端断了
        self.send_budget = None  # 每连接最多发多少字节数据（None = 不限）
        self.delay_ms = 0        # 每 8KiB 之间的等待（模拟慢链路）
        self.corrupt = False     # done 里故意给错摘要

    def start(self):
        if self._srv:
            return self
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.bind, self.port))
        srv.listen(64)
        srv.settimeout(0.5)
        self._srv = srv
        if self.port == 0:
            self.port = srv.getsockname()[1]
        self._thread = threading.Thread(target=self._loop, name="pull-srv", daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self._stop.set()
        if self._srv:
            try:
                self._srv.close()
            except OSError:
                pass
        if self._thread:
            self._thread.join(timeout=2.0)
        self._srv = None
        self._thread = None

    def __enter__(self):
        return self.start()

    def __exit__(self, *exc):
        self.stop()

    def _loop(self):
        while not self._stop.is_set():
            try:
                conn, addr = self._srv.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            threading.Thread(target=self._serve, args=(conn, addr),
                             name="pull-conn", daemon=True).start()

    def _read_frame(self, conn, timeout):
        conn.settimeout(timeout)
        head = R.recv_exact(conn, 4, time.monotonic() + timeout)
        if not head:
            return None
        (length,) = struct.unpack(">I", head)
        if length <= 0 or length > MAX_FRAME:
            return None
        body = R.recv_exact(conn, length, time.monotonic() + timeout)
        if not body:
            return None
        try:
            return json.loads(body.decode("utf-8"))
        except ValueError:
            return None

    def _write(self, conn, obj, body=b""):
        conn.sendall(tcp_frame_bytes(obj, body))

    def _serve(self, conn, addr):
        ts = time.time()
        first = True
        try:
            while not self._stop.is_set():
                frame = self._read_frame(conn, 15.0 if first else 60.0)
                if frame is None:
                    return
                first = False
                reason = validate_control_frame(frame)
                if reason is not None:
                    with self._lock:
                        self.errors.append((time.time(), addr, reason))
                    return
                ftype = frame["type"]
                if ftype == "finish":
                    with self._lock:
                        self.finishes.append((time.time(), addr, frame))
                    continue
                if ftype != "pull":
                    with self._lock:
                        self.ignored.append((time.time(), addr, frame))
                    continue
                with self._lock:
                    self.pulls.append((ts, addr, frame))
                self._serve_pull(conn, addr, frame)
        except (OSError, _Interrupted):
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _serve_pull(self, conn, addr, frame):
        meta = self.files.get(frame["fileId"])
        if meta is None:
            self._write(conn, {"type": "err", "reason": "not-found"})
            return
        size, data, digest = meta
        offset = int(frame["offset"])
        if offset > size:
            self._write(conn, {"type": "err", "reason": "bad-offset"})
            return
        remaining = size - offset
        if remaining <= 0:
            self._write(conn, {"type": "err", "reason": "bad-offset"})
            return
        self._write(conn, {"type": "pull-ok", "fileId": frame["fileId"], "len": remaining})
        sent = 0
        step = 8192
        try:
            while sent < remaining:
                take = min(step, remaining - sent)
                if self.send_budget is not None and sent + take > self.send_budget:
                    take = max(0, self.send_budget - sent)
                    if take > 0:
                        conn.sendall(data[offset + sent:offset + sent + take])
                        sent += take
                    conn.close()
                    with self._lock:
                        self.incomplete.append((time.time(), addr, frame))
                    return
                conn.sendall(data[offset + sent:offset + sent + take])
                sent += take
                if self.delay_ms:
                    time.sleep(self.delay_ms / 1000.0)
        except OSError:
            # 对端在流中途把连接关了 —— R60 要的正是这一刻（cancelIncoming 关 socket）。
            with self._lock:
                self.disconnects.append((time.time(), addr))
            return
        if self.corrupt:
            digest = ("0" if digest[0] != "0" else "1") + digest[1:]
        self._write(conn, {"type": "done", "fileId": frame["fileId"], "sha256": digest})


# ---------------------------------------------------------------------------
# 夹具主体
# ---------------------------------------------------------------------------

def _budget(args, cap):
    return max(1.0, min(float(cap), float(args.secs)))


def phase(title):
    print("\n== %s ==" % title, flush=True)


def settle(secs=0.8):
    time.sleep(max(0.0, secs))


def send_reliable(peer, mtype, payload, timeout=6.0, expect_ack=True):
    """发一条 RELIABLE 帧并等它的 ACK。返回 (env, ack)。"""
    env = peer.env(mtype, payload)
    peer.send(env)
    return env, (peer.wait_ack(env["id"], timeout) if expect_ack else None)


def send_offer(peer, files, transfer_id, root_name=None, purpose=None, group_id=None,
               group_rev=None, is_folder=None, total_size=None, tag="", msg_id=None,
               table_text=None, table_text_truncated=None, expires_at=None):
    """把一个（或多个）`file-ctl offer` 分片按 seq 顺序发出去，返回 [(env, payload, ack)]。"""
    metas = [f.meta() for f in files]
    if expires_at is None and purpose not in (PURPOSE_IMAGE, PURPOSE_STICKER, PURPOSE_UPDATE):
        expires_at = R.now_ms() + FILE_OFFER_TTL_MS
    packets = chunk_offers(
        metas, transfer_id, root_name=root_name, purpose=purpose, group_id=group_id,
        group_rev=group_rev, is_folder=is_folder, total_size=total_size,
        expires_at=expires_at, msg_id=msg_id, table_text=table_text,
        table_text_truncated=table_text_truncated)
    out = []
    for payload in packets:
        bad = validate_offer(payload)
        if bad is not None:
            R.check("offer-local-shape-%s-%s-seq%d" % (tag, transfer_id[-6:], payload["seq"]),
                    False, bad)
            return out
        env = peer.env(FILE_CTL, payload)
        peer.send(env)
        ack = peer.wait_ack(env["id"], 8.0)
        out.append((env, payload, ack))
    return out


def wait_pull(server, transfer_id, file_id=None, since=0.0, timeout=10.0, min_offset=None):
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        with server._lock:
            hits = [(ts, a, f) for (ts, a, f) in server.pulls
                    if ts >= since and f["transferId"] == transfer_id
                    and (file_id is None or f["fileId"] == file_id)
                    and (min_offset is None or int(f["offset"]) >= min_offset)]
        if hits:
            return hits[-1][2]
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.05)


def wait_finish(server, transfer_id, since=0.0, timeout=10.0):
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        with server._lock:
            hits = [f for (ts, a, f) in server.finishes if ts >= since and f["transferId"] == transfer_id]
        if hits:
            return hits[-1]
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.05)


def wait_disconnect(server, since=0.0, timeout=10.0):
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        with server._lock:
            hits = [(ts, a) for (ts, a) in server.disconnects if ts >= since]
        if hits:
            return hits[-1]
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.05)


def _op(env):
    return (env.get("payload") or {}).get("op")


def _tid(env):
    return (env.get("payload") or {}).get("transferId")


def wait_op(peer, op, transfer_id, timeout):
    return peer.wait(lambda e: _op(e) == op and _tid(e) == transfer_id, timeout)


# --- UI 取证：全部坐标来自 uiautomator dump 的 bounds ------------------------

def _tap_ui(args, label, check_name, timeout=6.0, exact=True):
    """用 dump 出来的 bounds 点一个文字节点（**绝不目测坐标**）。"""
    node = R.find_ui(text=label, exact=exact, timeout=timeout)
    ok = bool(node) and R.tap_node(node)
    R.check(check_name, ok, "在 dump 里找到 %r，点它的 bounds 中心 %s"
            % (label, node.center if node else None))
    return ok


def _text_present(substr, timeout=6.0, exact=False, interval=0.5, desc=False):
    """等一段文字出现在 dump 里；返回 nodes 或 None。

    `desc=True` 比的是 **contentDescription**（`n.desc`）。有些「标题」只在描述上：
    查看图片弹层的标题就是 `contentDescription = chat_image_preview_title`
    （`composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/ImageViewerDialog.kt:84`），
    用 text 找永远是 None（2026-10-09 真机：r52 因此恒红）。
    """
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        nodes = R.ui_all()
        if desc:
            hit = any(((n.desc or "") == substr) if exact
                      else (substr in (n.desc or "")) for n in nodes)
        else:
            hit = R.ui_has(substr, nodes) if not exact else any(n.text == substr for n in nodes)
        if hit:
            return nodes
        if time.monotonic() >= deadline:
            return None
        time.sleep(interval)


def _count_text(substr, nodes=None, exact=True):
    nodes = R.ui_all() if nodes is None else nodes
    return sum(1 for n in nodes if (n.text == substr if exact else substr in (n.text or "")))


def _contains(outer, inner):
    """outer 的 bounds 是否包住 inner（同一个节点不算）。"""
    if outer is inner or outer.bounds is None or inner.bounds is None:
        return False
    ox1, oy1, ox2, oy2 = outer.bounds
    ix1, iy1, ix2, iy2 = inner.bounds
    return ox1 <= ix1 and oy1 <= iy1 and ox2 >= ix2 and oy2 >= iy2


def _area(n):
    x1, y1, x2, y2 = n.bounds
    return (x2 - x1) * (y2 - y1)


def _find_inline_preview(nodes):
    """内联图预览在 dump 里**没有 text、没有 contentDescription**
    （ChatScreen.kt:1314 `Image(bitmap, contentDescription = null, …)`）。

    2026-10-09 真机取证的收端已完成图片卡（Pixel 6，1080×2400）：

        卡片（气泡，(42,331,859,898)，combinedClickable）
        ├ 标题 probe-inline.png (84,363,…)
        ├ 内联图 (69,441,195,567) clickable 无文字   ← 点它才开弹层
        ├ 「已接收」「已存到 …」(84,592,817,674)
        └ 「打开」(84,696,236,822) / 「分享」(268,696,420,822) clickable 无文字

    两个坑都踩过：
    1. **不能挑最大**：卡片气泡自己就是 clickable 无文字（`combinedClickable(onClick = {},
       onLongClick = …)`，ChatScreen.kt:804-806），点它什么也不发生。
    2. **「打开」也是 clickable 无文字，而且比图更大**（152×126 vs 126×126）⇒
       「内层最大的那个」会挑中它；真机点下去是把 **Google Photos** 拉到前台
       （后续 dump 只剩 16 个节点、应用退到后台）。
    所以参照物换成卡片结构：**图在「已存到 …」那一行之上，两个按钮在它下面**。

    兜底：上下文里没有「已存到」行（比如发端卡片）时，退回旧启发式
    ——先丢掉「包着别的候选」的节点，再在剩下的里挑最大的。
    """
    saved = None
    for n in nodes:
        if (n.text or "").startswith(UI_SAVED_PREFIX):
            saved = n
            break
    if saved is not None:
        cards = [n for n in nodes
                 if getattr(n, "clickable", False) and n.bounds and _contains(n, saved)]
        if cards:
            card = min(cards, key=_area)
            inner = [n for n in nodes
                     if getattr(n, "clickable", False) and n.bounds
                     and not (n.text or "").strip() and not (n.desc or "").strip()
                     and n.bounds != card.bounds and _contains(card, n)
                     and not _contains(n, saved)]
            above = [n for n in inner if n.bounds[3] <= saved.bounds[1]]
            pool = above or inner
            if pool:
                return max(pool, key=_area)

    cands = []
    for n in nodes:
        if not getattr(n, "clickable", False) or n.bounds is None:
            continue
        if (n.text or "").strip() or (n.desc or "").strip():
            continue
        x1, y1, x2, y2 = n.bounds
        w, h = x2 - x1, y2 - y1
        if w < 32 or h < 32 or w > INLINE_PREVIEW_MAX_PX or h > INLINE_PREVIEW_MAX_PX:
            continue
        cands.append(n)
    inner2 = [n for n in cands if not any(_contains(n, m) for m in cands)]
    pool2 = inner2 or cands
    best = None
    for n in pool2:
        if best is None or (_area(n) > _area(best)):
            best = n
    return best


# 卡片按钮离卡名（rootName）的纵向最大距离。真机实测（Pixel 6）：同一张卡上的按钮在卡
# 名下方约 180px，相邻卡片间距约 394px ⇒ 420 既容忍两行长文件名，又不会串到下一张卡。
_CARD_BUTTON_MAX_DY = 420


def _scroll_chat_bottom(times=2, pixels=900):
    """把消息列表拉到底，露出最新的卡片。

    会话页是 LazyColumn，只在**消息集合变化**时 `animateScrollToItem(lastIndex)`
    （`composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/chat/ChatScreen.kt:356`）——
    一张已经收到的卡自己长高（出现「接收」按钮、进度、完成态）**不会**触发滚动，
    于是新卡片经常落在视口下方；而 uiautomator 只报**可见**节点，看不见的卡等于不存在。
    2026-10-09 真机实证：音频相位找 `e2e-broken.m4a` 的「接收」12 秒找不到，那一刻
    屏上是表格卡 —— 不是收到与否的问题，是没滚到底。
    """
    for _ in range(max(1, int(times))):
        R.adb("shell", "input", "swipe", "540", "1600", "540", str(1600 - int(pixels)), "300")
        time.sleep(0.4)
    settle(0.5)


def _card_button(args, root_name, label, check_name, timeout=8.0, desc=False, tap=True):
    """按 rootName 定位卡片，再点**这张卡**上纵向最近的同名按钮。

    不能用 `R.find_ui(text=…)`：它按 dump 顺序取第一个，而同屏常有别的相位留下的旧
    卡片（同一个会话里越积越多），那一击会被别人抢走（e2e_files.py 里 2026-10-09 的
    真机实测：两张卡时点错另一张）。`desc=True` 按 contentDescription 找（收端的
    「取消」只有描述没有文字）。`tap=False` 只看不点。`check_name=None` 不记 check。
    第一轮没找到就先 `_scroll_chat_bottom()` 拉到底再找（卡可能落在视口下方）。
    同名卡有好几张时挑**最靠下**的那张（最新的）。
    """
    deadline = time.monotonic() + max(0.0, timeout)
    seen_cands = 0
    attempt = 0
    while True:
        attempt += 1
        nodes = R.ui_all() or []
        # 同名卡可能有好几张（同一个会话跨轮次留着重名的卡，真机跑第二遍就有），
        # **最新的那张在最下面** ⇒ 按 y 从大到小排，优先挑最靠下的。
        heads = sorted([n for n in nodes if (n.text or "").strip() == root_name],
                       key=lambda n: n.center[1] if n.center else -1, reverse=True)
        cands = [n for n in nodes
                 if (((n.desc or "") if desc else (n.text or "")).strip() == label)]
        seen_cands = len(cands)
        for head in heads:
            if head.bounds is None:
                continue
            band = [b for b in cands
                    if b.bounds is not None
                    and 0 <= b.center[1] - head.center[1] <= _CARD_BUTTON_MAX_DY]
            if band:
                band.sort(key=lambda b: b.center[1] - head.center[1])
                target = band[0]
                if not tap:
                    if check_name:
                        R.check(check_name, True,
                                "卡 %r 上有 %r at %s" % (root_name, label, target.center))
                    return target
                ok = bool(R.tap_node(target))
                if check_name:
                    R.check(check_name, ok,
                            "卡 %r 上的 %r at %s（按卡名下方的纵向距离挑最近的）"
                            % (root_name, label, target.center))
                return target if ok else None
        if time.monotonic() >= deadline:
            break
        if attempt == 1:
            # 第一轮空手 ⇒ 先把列表拉到底再接着轮询（新卡常落在视口下方）
            _scroll_chat_bottom(2)
        time.sleep(0.6)
    if check_name:
        R.check(check_name, False,
                "卡 %r 上没找到 %r（%s 内；dump 里共 %d 个 %r。当前可见：%s）"
                % (root_name, label, timeout, seen_cands, label,
                   " | ".join((R.ui_texts() or [])[:16])))
    return None


def _ensure_chat(args, peer):
    """先把对端塞进会话列表（握手本身不建会话，得有一条消息），再点开它。"""
    R.ensure_app_foreground()
    env, ack = send_reliable(peer, MSG, text_msg_payload("e2e-media-%s" % R.uniq_marker("", 4)))
    R.check("ui-greeting-acked", ack is not None, "问候文本被 ACK（会话因此存在）")
    settle(1.2)
    opened = R.open_chat(args.nick)
    R.check("ui-open-chat-with-fixture", opened, "会话列表里点开 %r" % args.nick)
    return opened


# ---------------------------------------------------------------------------
# run / _body
# ---------------------------------------------------------------------------

def run(args):
    material_dir = None
    server = None
    peer = None
    try:
        # ------------------------------------------------------------ 本地自检
        phase("本地自检（不碰网络、不碰设备）")
        local_frame_checks()
        R.log("本地断言 %d 条" % len(R._CHECKS))

        if args.dry_run:
            print("\n[dry-run] 不碰网络、不碰设备：跳过 adb 与握手/拉取。", flush=True)
            print("[dry-run] 真机将执行：握手(tbl1/shr1/fd1 caps) → 图片自动接收"
                  "(TCP 拉取+sha256) → 内联预览结构痕迹 → 点图开查看图片弹层 → "
                  "sticker 不自动接收(卡片上有「接收」+ 无 accept 帧) → "
                  "看表格文字/截断说明/非 image 用途的 tableText → "
                  "语音播放(真 WAV 装 .m4a) → 解不出的格式出提示 → "
                  "文本撤回/图片撤回/拉取中撤回(对端断开)/cancel 不停在途拉取 → "
                  "戳一碰(提示+限流+自动定位会话) → PK 猜拳/骰子/非法帧不回 ACK。", flush=True)
            return 0

        # ------------------------------------------------------------ 环境
        phase("设备与环境")
        R.require_device()
        material_dir = tempfile.mkdtemp(prefix="relay-e2e-media-")

        # ------------------------------------------------------------ 起服务
        phase("起对端（UDP 握手 + TCP 拉取服务端）")
        server = PullServer(port=args.tcp_port, name="media")
        server.start()
        R.log("TCP 拉取服务端 :%d（写进夹具 profile.tcpPort）" % server.port)
        peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=args.nick,
                         tcp_port=server.port, host=args.host_ip).start()
        R.log("UDP 绑定 :%d；夹具 nodeId=%s nick=%s"
              % (peer.port, R.FIXTURE_NODE[:8], args.nick))
        return _body(args, peer, server, material_dir)
    finally:
        if peer is not None:
            try:
                peer.send_exit()
                time.sleep(0.3)
            except Exception:
                pass
            peer.stop()
        if server is not None:
            server.stop()
        if material_dir:
            shutil.rmtree(material_dir, ignore_errors=True)


def _body(args, peer, server, material_dir):
    # ---------------------------------------------------------------- 握手
    phase("握手与对端能力")
    prof = peer.handshake(secs=_budget(args, 20), probe=True)
    if not R.check("handshake-alive", prof is not None,
                   "手机 alive 里的 profile" if prof
                   else "%ds 内没握上手" % min(args.secs, 20)):
        return None
    caps = prof.get("caps") or []
    R.log("手机 profile nick=%r platform=%s tcpPort=%s caps=%s"
          % (prof.get("nick"), prof.get("platform"), prof.get("tcpPort"), ",".join(caps)))
    R.check("caps-has-tbl1", R.CAP_TABLE_TEXT in caps,
            "手机声明 %s（表格文字视图的前提）" % R.CAP_TABLE_TEXT)
    # `fd1` 是**有意不声明**，不是缺口：`TransferManager.onDirectRequest` 的 KDoc 明写
    # 「本客户端不声明 `fd1`（见 MsgTypes.CAP_FILE_DIRECT），按协议对端就不该给我们发
    # 这一条」——免确认落盘的决定留在人手里。手机 caps 由 `RelaySettings.caps()` 组装，
    # 只有 rv1/rvs1/shr1 是条件追加的 ⇒ fd1 永远缺席（2026-10-09：原断言写成 in caps，恒红）。
    R.check("caps-omits-fd1-on-purpose", R.CAP_FILE_DIRECT not in caps,
            "手机有意不声明 %s（对端因此不该发 direct）" % R.CAP_FILE_DIRECT)
    # shr1 是**条件**声明：shareRoot 为空就不加（RelaySettings.kt:213）。
    R.log("caps shr1=%s —— 共享文件柜的能力位是条件声明（设了共享目录才有），"
          "R88 在 e2e_share.py 里单独验" % (R.CAP_SHARE in caps))

    # ---------------------------------------------------------------- UI 前置
    if args.skip_ui:
        R.log("--skip-ui：不 dump UI、不注入按键；所有 UI 面断言按「跳过」记，不伪造 PASS")
    else:
        phase("会话前置")
        _ensure_chat(args, peer)

    _phase_image(args, peer, server, material_dir)
    _phase_table_text(args, peer, server, material_dir)
    _phase_sticker(args, peer, server, material_dir)
    _phase_audio(args, peer, server, material_dir)
    _phase_recall(args, peer, server, material_dir)
    _phase_nudge(args, peer, server, material_dir)
    _phase_pk(args, peer)
    return None


# ---------------------------------------------------------------------------
# R49 / R52 —— 图片内联预览与放大
# ---------------------------------------------------------------------------

def _phase_image(args, peer, server, material_dir):
    phase("R49 图片内联预览（purpose=image 自动接收 + TCP 拉取 + sha256）")
    tid = new_transfer_id("img")
    shot = LocalFile(write_material(material_dir, "e2e-shot.png", png_bytes(96, 96, seed=11)),
                     "e2e-shot.png", "f-img")
    root = "e2e-shot.png"
    register(server, shot)
    since = time.time()
    send_offer(peer, [shot], tid, root_name=root, purpose=PURPOSE_IMAGE, tag="-img")

    accepted = wait_op(peer, OP_ACCEPT, tid, _budget(args, 12))
    R.check("r49-image-auto-accepted", accepted is not None,
            "purpose=image 且 ≤20MB：手机自己回 accept（不需要人点「接收」）"
            if accepted is not None else "12s 内没有 accept —— 自动接收没生效")

    pull = wait_pull(server, tid, file_id=shot.file_id, since=since, timeout=_budget(args, 12))
    R.check("r49-image-pull-over-tcp", pull is not None,
            "手机连 %d 端口发 pull{offset:%s}（注意不是 UDP 端口）"
            % (server.port, pull["offset"] if pull else "?"))
    if pull is not None:
        R.check("r49-image-pull-from-first-byte", int(pull["offset"]) == 0,
                "首次拉取 offset=0")
    fin = wait_finish(server, tid, since=since, timeout=_budget(args, 12))
    R.check("r49-image-transfer-finished", fin is not None,
            "收到 finish{transferId}（sha256 校验通过才会发）")
    with server._lock:
        got_sha = [f for (_ts, _a, f) in server.pulls]
    R.check("r49-image-digest-is-real-sha256",
            validate_control_frame({"type": "done", "fileId": shot.file_id,
                                    "sha256": shot.sha256}) is None,
            "夹具这一侧提供的摘要形状合法（sha256=%s…）" % shot.sha256[:12])

    if args.skip_ui:
        R.log("跳过 R49 的 UI 面（--skip-ui）：已存到 / 内联位图 / 查看图片弹层")
        return

    nodes = _text_present(UI_SAVED_PREFIX, timeout=_budget(args, 10))
    R.check("r49-image-card-shows-saved-path", nodes is not None,
            "卡片走到 Completed，dump 里有「%s …」（file_saved_to）" % UI_SAVED_PREFIX)

    node = _find_inline_preview(nodes or R.ui_all())
    R.check("r49-inline-preview-node-present", node is not None,
            "内联图预览无 text/desc，结构痕迹 = clickable 无文字节点 %s（bounds=%s）"
            % ("命中" if node else "没找到", node.bounds if node else None))

    # R52：点它必须开出查看图片弹层 —— 这才是「真的有图」的语义证据。
    if node is None:
        R.log("跳过 R52（--skip-ui 之外：R49 没有定位到内联图节点）")
        return
    R.tap_node(node)
    settle(1.2)
    dlg = _text_present(UI_PREVIEW_TITLE, timeout=_budget(args, 8), exact=True, desc=True)
    R.check("r52-image-viewer-dialog-opens", dlg is not None,
            "点内联图后 dump 里有 contentDescription=%r（chat_image_preview_title）"
            % UI_PREVIEW_TITLE)
    if dlg is not None:
        R.log("弹层里还有「%s」= %s" % (UI_SHARE, UI_SHARE in (R.ui_texts(dlg) or [])))
        R.press_back()
        settle(0.8)
    else:
        # 弹层没开就**不能**按返回：这一记返回会把聊天页弹回会话列表，之后每个相位
        # 的 dump 都是列表页，一次误导航能解释掉十几条假红（2026-10-09 真机实测）。
        R.log("弹层没开 ⇒ 不按返回（按了会把聊天页弹回会话列表）")
    if R.chat_composer(timeout=2.0) is None:
        R.log("聊天页不在了，重新打开 %r" % args.nick)
        R.open_chat(args.nick)
        settle(1.0)
    R.check("r52-still-in-chat-after-viewer", R.chat_composer(timeout=3.0) is not None,
            "R52 之后仍停在聊天页（后面 R53/R57/R50/R58… 都依赖这一点）")


# ---------------------------------------------------------------------------
# R53 / R54 / R56 —— 表格文字
# ---------------------------------------------------------------------------

def _phase_table_text(args, peer, server, material_dir):
    phase("R53/R54/R56 表格文字（tableText / tableTextTruncated / 非 image 用途）")
    marker = R.uniq_marker("tbl-", 6)
    table = "列A,列B\n%s,42\n合计,42" % marker

    # ---- R53：purpose=image + tableText
    tid = new_transfer_id("tbl")
    tbl = LocalFile(write_material(material_dir, "e2e-table.png", png_bytes(64, 48, seed=21)),
                    "e2e-table.png", "f-tbl")
    register(server, tbl)
    send_offer(peer, [tbl], tid, root_name="e2e-table.png", purpose=PURPOSE_IMAGE,
               tag="-tbl", table_text=table)
    wait_pull(server, tid, timeout=_budget(args, 10))

    if args.skip_ui:
        R.log("跳过 R53/R54/R56 的 UI 面（--skip-ui）：看表格文字按钮/展开/截断行")
        return
    btn = _card_button(args, "e2e-table.png", UI_TABLE_SHOW, None,
                       timeout=_budget(args, 10), tap=False)
    R.check("r53-table-text-button-visible", btn is not None,
            "offer 带 tableText 的卡片上有「%s」（file_table_show_text）" % UI_TABLE_SHOW)
    if btn is None:
        return
    R.tap_node(btn)
    settle(1.0)
    shown = _text_present(marker, timeout=_budget(args, 8))
    R.check("r53-table-text-expands", shown is not None,
            "点开后表格文字进了 dump（找得到夹具的标记 %r）" % marker)
    # 也要卡定向：`find_ui` 会命中**上一轮遗留**的那张已展开的表格卡（它的按钮正好是
    # 「收起文字」），于是这一条会假绿。
    R.check("r53-button-flips-to-hide",
            _card_button(args, "e2e-table.png", UI_TABLE_HIDE, None,
                         timeout=3.0, tap=False) is not None,
            "按钮文案翻成「%s」（file_table_hide_text）" % UI_TABLE_HIDE)

    # ---- R54：截断说明行
    tid2 = new_transfer_id("tbl2")
    tbl2 = LocalFile(write_material(material_dir, "e2e-table2.png", png_bytes(64, 48, seed=22)),
                     "e2e-table2.png", "f-tbl2")
    register(server, tbl2)
    send_offer(peer, [tbl2], tid2, root_name="e2e-table2.png", purpose=PURPOSE_IMAGE,
               tag="-tbl2", table_text=table, table_text_truncated=True)
    wait_pull(server, tid2, timeout=_budget(args, 10))
    settle(1.0)
    # 卡定向：`find_ui(text=UI_TABLE_SHOW)` 会命中**上一轮遗留**的同名卡（它默认是收起态，
    # 于是「第一个看表格文字」不是这一张）。真机 2026-10-09 run3 就是在这里红的。
    btn2 = _card_button(args, "e2e-table2.png", UI_TABLE_SHOW, None,
                        timeout=_budget(args, 10), tap=False)
    if btn2 is not None:
        R.tap_node(btn2)
        settle(1.0)
        # 展开后卡片变高，截断行可能落在视口下方 —— LazyColumn 不会自己滚。
        _scroll_chat_bottom(1)
    R.check("r54-truncated-line-visible",
            _text_present(UI_TABLE_TRUNCATED, timeout=_budget(args, 8)) is not None,
            "tableTextTruncated=true 时展开后有「%s」" % UI_TABLE_TRUNCATED)

    # ---- R56：合法但非 media 的 purpose 也照样渲染 tableText
    #      ChatScreen.kt:1065 的渲染条件是 `tableText != null`，与 purpose 无关 ——
    #      这一条就是在钉那个「未定义组合」。
    tid3 = new_transfer_id("tbl3")
    tbl3 = LocalFile(write_material(material_dir, "e2e-table3.png", png_bytes(64, 48, seed=23)),
                     "e2e-table3.png", "f-tbl3")
    marker3 = R.uniq_marker("tbl3-", 6)
    register(server, tbl3)
    send_offer(peer, [tbl3], tid3, root_name="e2e-table3.png", purpose=PURPOSE_SHARE_GET,
               tag="-tbl3", table_text="非图片用途,%s" % marker3)
    settle(1.5)
    # share-get 不在 autoAccept 里（只有 image 是），所以卡片停在待接收 ——
    # 但 tableText 按钮**照样**应该出现：这正是 R56 要钉的「未定义组合」。
    btn3 = _card_button(args, "e2e-table3.png", UI_TABLE_SHOW, None,
                        timeout=_budget(args, 12), tap=False)
    R.check("r56-table-text-renders-for-non-image-purpose", btn3 is not None,
            "purpose=%s（合法、非 media）带 tableText 时按钮照样出现 —— "
            "渲染与 purpose 无关（ChatScreen.kt:1065）" % PURPOSE_SHARE_GET)
    if btn3 is not None:
        R.tap_node(btn3)
        settle(1.0)
        _scroll_chat_bottom(1)
        R.check("r56-table-text-content-visible",
                _text_present(marker3, timeout=_budget(args, 8)) is not None,
                "非 image 用途的 tableText 内容也进 dump")
    R.check("r56-non-image-purpose-not-auto-accepted",
            wait_op(peer, OP_ACCEPT, tid3, 3.0) is None,
            "purpose=%s 不在 autoAccept 白名单里（只有 image 是），没有 accept 帧"
            % PURPOSE_SHARE_GET)
    # 收尾：拒掉它，免得它一直挂在卡片列表里影响后面的 dump。
    peer.send(peer.env(FILE_CTL, simple_payload(OP_DECLINE, tid3)))
    settle(0.6)

    # R55 横向滚动本身在 dump 里没有可判定的状态位（滚动手势改不动 uiautomator 的
    # 文字节点集合）—— 这一条**不可覆盖**，只记一条日志，**不写成一个恒真的 check**
    # （恒真的 check 会把 PASS 计数撑大，属于「为错的原因通过」）。
    R.log("R55 横向滚动本身不可覆盖：dump 里没有可判定的状态位，见文件头清单")


# ---------------------------------------------------------------------------
# R57 —— sticker 不自动接收
# ---------------------------------------------------------------------------

def _phase_sticker(args, peer, server, material_dir):
    phase("R57 sticker 的内联上限（autoAccept 只认 image，sticker 不在内）")
    tid = new_transfer_id("stk")
    stk = LocalFile(write_material(material_dir, "e2e-sticker.png", png_bytes(48, 48, seed=31)),
                    "e2e-sticker.png", "f-stk")
    register(server, stk)
    send_offer(peer, [stk], tid, root_name="e2e-sticker.png", purpose=PURPOSE_STICKER,
               tag="-stk", expires_at=R.now_ms() + FILE_OFFER_TTL_MS)
    # TransferManager.kt:662 `autoAccept = purpose == FilePurpose.IMAGE && …`
    # ⇒ sticker **不该**自动接收（FilePurpose.MEDIA 里有它，但自动接收判据不是 MEDIA）。
    R.check("r57-sticker-not-auto-accepted",
            wait_op(peer, OP_ACCEPT, tid, _budget(args, 10)) is None,
            "purpose=sticker 没有 accept 帧 —— 自动接收判据是 `purpose == \"image\"`，"
            "不是 FilePurpose.MEDIA")
    if args.skip_ui:
        R.log("跳过 R57 的 UI 面（--skip-ui）：卡片上的「接收 / 拒绝」")
        return
    node = _card_button(args, "e2e-sticker.png", UI_ACCEPT,
                        "r57-sticker-card-needs-manual-accept",
                        timeout=_budget(args, 10), tap=False)
    if node is not None:
        R.tap_node(node)
        settle(1.0)
        acc = wait_op(peer, OP_ACCEPT, tid, _budget(args, 10))
        R.check("r57-manual-accept-emits-accept-frame", acc is not None,
                "点「%s」后 UDP 上出现 accept{transferId=%s}" % (UI_ACCEPT, tid))
        wait_pull(server, tid, timeout=_budget(args, 8))
    else:
        peer.send(peer.env(FILE_CTL, simple_payload(OP_DECLINE, tid)))
        settle(0.6)


# ---------------------------------------------------------------------------
# R50 / R51 —— 语音播放与解不出的格式
# ---------------------------------------------------------------------------

def _phase_audio(args, peer, server, material_dir):
    phase("R50/R51 语音：真音频能播、解不出的格式安静退回")
    # ---- R50：purpose="share"（**协议外自造值**）+ .m4a → mediaKindOf 走扩展名判 Audio
    tid = new_transfer_id("aud")
    voice = LocalFile(write_material(material_dir, "e2e-voice.m4a", wav_bytes(400)),
                      "e2e-voice.m4a", "f-aud")
    register(server, voice)
    send_offer(peer, [voice], tid, root_name="e2e-voice.m4a", purpose=PURPOSE_SHARE,
               tag="-aud", expires_at=R.now_ms() + FILE_OFFER_TTL_MS)
    R.check("r50-foreign-purpose-not-auto-accepted",
            wait_op(peer, OP_ACCEPT, tid, 5.0) is None,
            "purpose=%s 是协议外自造值，不在 autoAccept 白名单里" % PURPOSE_SHARE)

    # ---- R51：同一条路，但装的是解不出的字节
    tid2 = new_transfer_id("bad")
    bad = LocalFile(write_material(material_dir, "e2e-broken.m4a", garbage_bytes(4096, seed=41)),
                    "e2e-broken.m4a", "f-bad")
    register(server, bad)
    send_offer(peer, [bad], tid2, root_name="e2e-broken.m4a", purpose=PURPOSE_SHARE,
               tag="-bad", expires_at=R.now_ms() + FILE_OFFER_TTL_MS)

    if args.skip_ui:
        R.log("跳过 R50/R51 的 UI 面（--skip-ui）：播放按钮与不支持提示")
        for t in (tid, tid2):
            peer.send(peer.env(FILE_CTL, simple_payload(OP_DECLINE, t)))
        settle(0.5)
        return

    # 两条都人手接收：一条放真音频、一条放垃圾字节。必须按卡名定向点，否则同屏前几个
    # 相位留下的旧卡片会把这一击抢走（R57 的 sticker 卡此刻也还挂着「接收」）。
    for tag, t, root in (("voice", tid, "e2e-voice.m4a"),
                         ("broken", tid2, "e2e-broken.m4a")):
        node = _card_button(args, root, UI_ACCEPT, "r50-%s-accept-button" % tag,
                            timeout=_budget(args, 12), tap=False)
        if node is None:
            continue
        R.tap_node(node)
        settle(0.8)
        wait_pull(server, t, timeout=_budget(args, 10))
        settle(1.2)

    play = _card_button(args, "e2e-voice.m4a", UI_PLAY, None,
                        timeout=_budget(args, 12), tap=False)
    R.check("r50-audio-play-button-visible", play is not None,
            "purpose=%s 的 .m4a 卡片上有「%s」——FilePurpose.ALL 不含 'share'，"
            "mediaKindOf 是靠扩展名判出 Audio 的（MediaKind.kt）" % (PURPOSE_SHARE, UI_PLAY))
    if play is not None:
        R.tap_node(play)
        settle(1.5)
        R.check("r50-real-audio-plays-without-unsupported-hint",
                _text_present(UI_AUDIO_UNSUPPORTED, timeout=3.0) is None,
                "点「%s」后没有出现「%s」——MediaPlayer 认 RIFF/WAVE 内容，返回 true"
                % (UI_PLAY, UI_AUDIO_UNSUPPORTED))
    else:
        R.log("真音频那条没有「%s」按钮 —— R51 的后半段仍会照跑" % UI_PLAY)

    # R51：垃圾字节那条，播放必须失败并**原地**退回（卡片不换、不崩）
    bad_play = _card_button(args, "e2e-broken.m4a", UI_PLAY, None, timeout=4.0, tap=False)
    if bad_play is not None:
        R.tap_node(bad_play)
    else:
        plays = [n for n in (R.ui_all() or []) if n.text == UI_PLAY]
        R.log("按卡名 %r 没定位到播放按钮，退回 dump 顺序启发式：dump 里有 %d 个「%s」"
              % ("e2e-broken.m4a", len(plays), UI_PLAY))
        if len(plays) >= 2:
            R.tap_node(plays[-1])
        elif plays:
            R.tap_node(plays[0])
            R.log("只找到 1 个播放按钮（--skip-ui 之外：前面那次点过之后 unsupported 状态"
                  "是按 transferId remember 的），这次点击可能落在已经失败过的那条上")
        else:
            R.check("r51-unsupported-hint-shown", False, "dump 里一个「%s」都没有" % UI_PLAY)
            return
    settle(1.5)
    hint = _text_present(UI_AUDIO_UNSUPPORTED, timeout=_budget(args, 8))
    R.check("r51-unsupported-hint-shown", hint is not None,
            "解不出的 .m4a 点播放后出现「%s」（RelayAudioPlayback.play 返回 false）"
            % UI_AUDIO_UNSUPPORTED)
    R.check("r51-card-stays-a-card",
            _text_present(UI_SAVED_PREFIX, timeout=3.0) is not None,
            "提示是**加在卡片上的一行**，卡片本身还在（没换成别的状态、没崩）")


# ---------------------------------------------------------------------------
# R58 / R59 / R60 —— 撤回
# ---------------------------------------------------------------------------

def _phase_recall(args, peer, server, material_dir):
    phase("R58 文本撤回（msg {kind:recall, targetId}）")
    marker = R.uniq_marker("rc-", 6)
    env, ack = send_reliable(peer, MSG, text_msg_payload(marker))
    R.check("r58-text-message-acked", ack is not None, "文本帧被 ACK")
    if not args.skip_ui:
        R.check("r58-text-bubble-visible-before-recall",
                _text_present(marker, timeout=_budget(args, 8)) is not None,
                "撤回前 dump 里看得到正文 %r" % marker)
    peer.send(peer.env(MSG, recall_payload(env["id"])))
    settle(1.5)
    if not args.skip_ui:
        R.check("r58-recall-notice-shown",
                _text_present(UI_RECALL_PEER, timeout=_budget(args, 10)) is not None,
                "气泡换成「%s」（NoticeKind.RecalledByPeer）" % UI_RECALL_PEER)
        R.check("r58-text-bubble-gone-after-recall",
                _text_present(marker, timeout=3.0) is None,
                "原正文 %r 从 dump 里消失（replaceMessage 把 text 清成空串）" % marker)

    # ---- R59：图片撤回。媒体撤回靠 offer 的 `msgId` 匹配（ChatInteractions.kt:52）。
    phase("R59 图片撤回（先 cancel 再 recall：靠 offer 的 msgId 对上）")
    tid = new_transfer_id("rimg")
    msg_id = "m-img-%s" % R.uniq_marker("", 6)
    pic = LocalFile(write_material(material_dir, "e2e-recall.png", png_bytes(64, 64, seed=51)),
                    "e2e-recall.png", "f-rimg")
    register(server, pic)
    send_offer(peer, [pic], tid, root_name="e2e-recall.png", purpose=PURPOSE_IMAGE,
               tag="-rimg", msg_id=msg_id)
    accepted = wait_op(peer, OP_ACCEPT, tid, _budget(args, 12))
    R.check("r59-image-accepted-before-recall", accepted is not None,
            "图片先按 purpose=image 自动接收（撤回只对**已完成**的图片放行）")
    wait_pull(server, tid, file_id=pic.file_id, timeout=_budget(args, 12))
    wait_finish(server, tid, timeout=_budget(args, 12))
    settle(1.2)
    if not args.skip_ui:
        R.check("r59-image-card-completed-before-recall",
                _text_present(UI_SAVED_PREFIX, timeout=_budget(args, 8)) is not None,
                "撤回前卡片是 Completed")
    peer.send(peer.env(MSG, recall_payload(msg_id)))
    settle(1.5)
    if not args.skip_ui:
        R.check("r59-image-recall-replaced-card",
                _text_present(UI_RECALL_PEER, timeout=_budget(args, 10)) is not None,
                "targetId=%s（offer 的 msgId）打中这张图 → 卡片换成「%s」"
                % (msg_id[:14], UI_RECALL_PEER))
    else:
        R.log("--skip-ui：R59 只验了协议侧（accept/pull/finish 三条）")

    # ---- R60a：拉取中途撤回 → cancelIncoming 关 socket，夹具这侧看到中途断开
    phase("R60a 撤回时正在拉取：cancelIncoming 关掉 socket（对端中途 EOF）")
    if args.skip_ui:
        R.log("R60a 需要 UI（撤回没有协议入口，只能由界面发起）—— 跳过")
    else:
        tid = new_transfer_id("mid")
        mid_msg_id = "m-mid-%s" % R.uniq_marker("", 6)
        big = LocalFile(write_material(material_dir, "e2e-midpull.png",
                                       png_bytes(160, 160, seed=61)),
                        "e2e-midpull.png", "f-mid")
        register(server, big)
        server.delay_ms = args.slow_ms
        server.send_budget = None
        send_offer(peer, [big], tid, root_name="e2e-midpull.png", purpose=PURPOSE_IMAGE,
                   tag="-mid", msg_id=mid_msg_id)
        hooked = wait_pull(server, tid, file_id=big.file_id, timeout=_budget(args, 12))
        R.check("r60-midpull-pull-started", hooked is not None,
                "拉取已开始，且被 delay_ms=%d 拖住（%d 字节 ≈ %.1fs 的窗口）"
                % (server.delay_ms, big.size, (big.size / 8192.0) * server.delay_ms / 1000.0))
        d_since = time.time()
        # 对端（我们）在窗口内发起撤回。targetId 用的是 offer 的 msgId ——
        # ChatInteractions.kt:52 的 recallTargetIdOf 就是 `file.msgId`，offer 不带
        # msgId 时媒体撤回**没有**可报的 id（那条路根本走不到）。
        peer.send(peer.env(MSG, recall_payload(mid_msg_id)))
        R.log("已发出 recall{targetId=%s}（与 offer 的 msgId 相同）" % mid_msg_id[:14])
        disc = wait_disconnect(server, since=d_since, timeout=_budget(args, 15))
        R.check("r60-recall-closes-inflight-socket", disc is not None,
                "写流中途对端断开（%.2fs 后）——正是 cancelIncoming 里 "
                "`cancelRef.socket?.close()` 的后果" % ((disc[0] - d_since) if disc else 0.0))
        if not args.skip_ui:
            R.check("r60-midpull-card-becomes-recall-notice",
                    _text_present(UI_RECALL_PEER, timeout=_budget(args, 10)) is not None,
                    "Active 卡片被 replaceMessage 换成「%s」（recallLocally 在 "
                    "cancelIncoming 之后无条件替换）" % UI_RECALL_PEER)
        server.delay_ms = 0

    # ---- R60b：`file-ctl {op:cancel}` 走 onPeerKilled，对**已在拉取中**的入向传输是空操作
    phase("R60b file-ctl{cancel} 不停在途拉取（onPeerKilled 只认 offers/outgoing）")
    tid = new_transfer_id("cno")
    mid = LocalFile(write_material(material_dir, "e2e-cancel.png", png_bytes(160, 160, seed=62)),
                    "e2e-cancel.png", "f-cno")
    register(server, mid)
    server.delay_ms = args.slow_ms
    send_offer(peer, [mid], tid, root_name="e2e-cancel.png", purpose=PURPOSE_IMAGE, tag="-cno")
    hooked = wait_pull(server, tid, file_id=mid.file_id, timeout=_budget(args, 12))
    if hooked is None:
        server.delay_ms = 0
        R.check("r60b-cancel-op-mid-pull-doc", False, "拉取没起来，后面两条读数无意义")
    else:
        c_since = time.time()
        env = peer.env(FILE_CTL, simple_payload(OP_CANCEL, tid))
        peer.send(env)
        acked = peer.wait_ack(env["id"], 6.0)
        R.check("r60b-cancel-op-is-acked", acked is not None,
                "`file-ctl{op:cancel}` 被 ACK —— 帧确实到了手机并被处理（RELIABLE 无条件先 ACK）")
        disc = wait_disconnect(server, since=c_since, timeout=_budget(args, 10))
        R.check("r60b-cancel-op-stops-inflight-pull", disc is not None,
                "`file-ctl{op:cancel}` 关掉了在途拉取（%s）—— onPeerKilled 现在会把 "
                "acceptedOffers/pulling 里的那条也收掉：cancelRef.canceled=true + "
                "socket.close() + 删 .part（TransferManager.kt:709-736，2026-10-09 修）。"
                "修之前它只动 outgoing/offers，`pulling` 表里的传输看不见，"
                "矩阵 R60 以为「发一帧 cancel 就能停了」而实际停不下来 —— 那是缺陷"
                % ("%.2fs 后断开" % (disc[0] - c_since) if disc else "10s 内没断"))
        fin = wait_finish(server, tid, since=c_since, timeout=min(_budget(args, 12), 12.0))
        R.check("r60b-inflight-pull-does-not-finish", fin is None,
                "被撤回的拉取不再跑完、也不回 finish（pull 的 catch 见 cancelRef.canceled "
                "就报 Declined，不再报 Failed）")
        if not args.skip_ui:
            R.check("r60b-cancel-op-card-shows-declined",
                    _text_present(UI_DECLINED, timeout=_budget(args, 8)) is not None,
                    "卡片变成「%s」（onPeerKilled 对「对方撤回」与「对方拒绝」用同一套收尾）"
                    % UI_DECLINED)
        server.delay_ms = 0


# ---------------------------------------------------------------------------
# R62 / R63 / R64 —— 戳一碰
# ---------------------------------------------------------------------------

def _phase_nudge(args, peer, server, material_dir):
    phase("R64/R62 戳一碰：收端提示 + 自动定位到那个会话")
    if args.skip_ui:
        R.log("跳过 R62/R63/R64（--skip-ui）：三条都只有 UI 面可观测")
        return

    # 先退到会话列表，才看得见 requestFocus 有没有把会话拉起来。
    # 不能盲目连按两次返回：从聊天页连按两下的第二下会把应用退到桌面（run1 的
    # `r64-back-to-conversation-list` 就是这么红的），而 goto_messages_list 是幂等的
    # ——已在列表就立刻返回，不在才点底栏「消息」/按返回。
    R.ensure_app_foreground(timeout=10)
    listed = R.goto_messages_list()
    R.check("r64-back-to-conversation-list", listed is not False,
            "退到会话列表（find_ui 找不到聊天输入框时 requestFocus 才有可观测效果）")

    peer.send(peer.env(MSG, nudge_payload()))
    nodes = _text_present(UI_NUDGE_PEER, timeout=_budget(args, 10))
    R.check("r62-nudge-notice-shown", nodes is not None,
            "收端追加一条「%s」（NoticeKind.NudgedByPeer）" % UI_NUDGE_PEER)
    R.check("r64-nudge-focuses-conversation", R.chat_composer(timeout=_budget(args, 8)) is not None,
            "onNudged 里的 requestFocus(from) 把那个会话打开了（输入框出现）")

    # ---- R63：限流。NudgeThrottle(maxPerWindow=2, windowMs=60s, minGapMs=15s)
    #
    # 关键读源事实（`composeApp/src/commonMain/kotlin/com/kapp/tidings/app/ChatInteractions.kt:81-90`）：
    #   `allow()` 被 minGapMs 挡下时 **不记账**（`if (last != null && now - last < minGapMs) return false`
    #   在 `times += now` 之前）⇒ 挨着发的第二条**不占**配额。
    # 所以「间隔够了但窗口已满」这一条必须先把配额**用满**：要让 60s 窗口里真的攒下 2 条**放行**的，
    # 再发第三条。只发三条（第 2 条被间隔挡掉、不记账）时第 3 条本来就该放行 ——
    # 2026-10-09 run2 就是在这里红的（`r63-nudge-window-cap-suppressed` 提示节点数 1 → 2），
    # 那是夹具把「被间隔挡下」当成了「占了配额」，不是产品缺陷。
    before = _count_text(UI_NUDGE_PEER)
    R.log("第一条 nudge 落成 %d 个提示节点" % before)
    settle(2.0)
    peer.send(peer.env(MSG, nudge_payload()))          # 与上一条间隔 < 15s
    settle(2.5)
    mid = _count_text(UI_NUDGE_PEER)
    R.check("r63-nudge-within-min-gap-suppressed", mid == before,
            "15 秒内的第二条被 minGapMs 挡下（提示节点数 %d → %d）" % (before, mid))

    R.log("等 %ss 让 minGapMs 过去，发第三条（这一条配额还只剩 1，应当放行）"
          % args.nudge_window_wait)
    time.sleep(args.nudge_window_wait)
    peer.send(peer.env(MSG, nudge_payload()))          # 间隔够了，且窗口里只放行过 1 条 ⇒ 放行
    settle(2.5)
    mid2 = _count_text(UI_NUDGE_PEER)
    R.check("r63-nudge-second-in-window-allowed", mid2 == mid + 1,
            "间隔够了的这条被放行，窗口里凑满 maxPerWindow=2（提示节点数 %d → %d）；"
            "也顺带证明被 minGapMs 挡下的那次**不占配额**" % (mid, mid2))

    R.log("再等 %ss：这次窗口里已有 2 条放行的，间隔也够，该被 maxPerWindow 挡下"
          % args.nudge_window_wait)
    time.sleep(args.nudge_window_wait)
    peer.send(peer.env(MSG, nudge_payload()))          # 间隔够了，但窗口已满
    settle(2.5)
    after = _count_text(UI_NUDGE_PEER)
    R.check("r63-nudge-window-cap-suppressed", after == mid2,
            "间隔够了仍被 maxPerWindow=2 挡下（提示节点数 %d → %d）—— 两条规则各证一次"
            % (mid2, after))


# ---------------------------------------------------------------------------
# R65 / R66 —— PK
# ---------------------------------------------------------------------------

def _phase_pk(args, peer):
    phase("R65 PK 猜拳 / 骰子（气泡正文就是 pkEmojiPreview 的 emoji）")
    rock_env, rock_ack = send_reliable(peer, MSG, pk_payload("rps", "rock"))
    R.check("r65-pk-rps-acked", rock_ack is not None, "合法 pk 帧被 ACK（RELIABLE 类型）")
    dice_env, dice_ack = send_reliable(peer, MSG, pk_payload("dice", 4))
    R.check("r65-pk-dice-acked", dice_ack is not None, "dice pk 帧被 ACK")

    if args.skip_ui:
        R.log("跳过 R65/R66 的 UI 面（--skip-ui）：气泡 emoji")
        return
    R.check("r65-pk-rps-bubble-shows-rock",
            _text_present(RPS_FACES["rock"], timeout=_budget(args, 10), exact=True) is not None,
            "猜拳/石头的气泡正文是 %r（pkEmojiPreview）" % RPS_FACES["rock"])
    R.check("r65-pk-dice-bubble-shows-face",
            _text_present(DICE_FACES["4"], timeout=_budget(args, 8), exact=True) is not None,
            "骰子 4 点的气泡正文是 %r（pkEmojiPreview 的 DICE 映射）" % DICE_FACES["4"])

    phase("R66 pk 校验失败关闭（严格白名单 → Codec Rejected → 连 ACK 都没有）")
    # `Codec.decode` 在 `decodeEnvelopeObject` 里就做 validatePayload，载荷非法 →
    # `DecodeResult.Rejected` → `Messenger.onDatagram` 第 99 行 `return`，
    # **在回 ACK 之前**。所以「没有 ACK」正是这条行的可观测判据。
    extra = pk_payload("rps", "rock")
    extra["extra"] = 1
    env_extra = peer.env(MSG, extra)
    peer.send(env_extra)
    R.check("r66-pk-extra-key-no-ack", peer.wait_ack(env_extra["id"], 6.0) is None,
            "pk 多一个键（PK_KEYS 严格白名单，Codec.kt:400 hasOnlyKeys）→ 整帧 Rejected，"
            "**没有 ACK**")

    bad = {"kind": K_PK, "game": "rps", "value": "lizard"}
    env_bad = peer.env(MSG, bad)
    peer.send(env_bad)
    R.check("r66-pk-bad-value-no-ack", peer.wait_ack(env_bad["id"], 6.0) is None,
            "rps 的 value 不在 rock/paper/scissors 里 → 整帧 Rejected，没有 ACK")

    bad_game = {"kind": K_PK, "game": "coin", "value": "head"}
    env_game = peer.env(MSG, bad_game)
    peer.send(env_game)
    R.check("r66-pk-unknown-game-no-ack", peer.wait_ack(env_game["id"], 6.0) is None,
            "未知 game → Codec 的 else 分支直接拒，没有 ACK")

    settle(1.5)
    R.check("r66-invalid-pk-draws-no-bubble",
            _count_text(RPS_FACES["rock"], exact=True) == 1,
            "三次非法 pk **一个气泡都没画出来**（合法那条仍然是唯一的一个 %r）"
            % RPS_FACES["rock"])
    # 对照：同一条机动车道上，recall/nudge 是**没有**白名单的 —— 见本地断言。
    R.log("对照：recall / nudge 没有 hasOnlyKeys（Codec.kt:313/317），"
          "给它们加键不会被拒 —— 所以夹具不能用「多键被拒」去证这两类")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def extra_args(ap):
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="夹具 TCP 拉取服务端端口（写进 profile.tcpPort），默认 %(default)s；"
                         "17879 被其他进程占用时换一个")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只测协议面：不 dump UI、不注入按键（播放/表格/撤回/戳一碰/PK 的 "
                         "UI 面无从触发，相关断言按「跳过」记，不伪造 PASS）")
    ap.add_argument("--nick", default=R.DEFAULT_NICK,
                    help="夹具在手机上的昵称，默认 %(default)s")
    ap.add_argument("--slow-ms", type=int, default=90,
                    help="R60 慢链路模式每 8KiB 的等待毫秒（留着塞进撤回的窗口），"
                         "默认 %(default)s")
    ap.add_argument("--nudge-window-wait", type=float, default=16.0,
                    help="R63 每条 nudge 之间的等待秒数（要 > minGapMs=15s；两次等待"
                         "合计 < windowMs=60s，默认 %(default)s 时约 39s 跑完）")
    ap.set_defaults(secs=420)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
