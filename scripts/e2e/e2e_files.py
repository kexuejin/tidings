#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文件传输 e2e 真机夹具：`file-ctl` offer/accept/decline + TCP 拉取/校验/续传/文件夹。

覆盖 docs/plans/2026-10-09-relay-e2e-coverage-matrix.md §2 D 段「文件与传输」：
  R35  入向 offer 的「接收 / 拒绝」两个按钮（`file_accept` / `file_decline`）与
       对应的 `file-ctl {op:accept|decline}` 出口。
  R36  TCP 拉取 + 进度 + SHA-256 校验：对端连 `profile.tcpPort`，发
       `{"type":"pull","from","transferId","fileId","offset"}`，发送方回
       `pull-ok{len}` + len 字节裸流 + `done{sha256}`，接收方校验通过才 `rename .part`。
  R37  断点续传（同一进程内）：夹具中途断流留下 `.part`，U 点「继续」后第二次
       `pull` 必须带 `offset > 0`（`transfer_action_resume`）。
  R38  重启后 `.part` 被清扫：`sweepStaleParts` 在 `TransferManager.start()` 自动跑，
       跨进程续传本来就不存在（认领表是内存的）—— 杀进程重启后盘上不能留 `.part`。
  R39  文件夹传输：`isFolder` / `rootName` / `files[].path` 相对层级，
       UI 上出现 `transfer_folder_badge`（文件夹）与 `transfer_folder_count`（N 个文件）。
  R40  路径穿越净化 `sanitizeRelativePath`：`../evil.txt` / `/abs` / `C:x` / 含 NUL
       的条目被**逐条丢弃**（`mapNotNull`），合法的那条照常收下。
  R41  自动接收上限：私聊图 ≤20MB / 群图 ≤10MB 自动收（UDP 上看得见 accept）；
       超限退化成手动接收（卡片上出现「接收」）。
  R42  取消 / 失败原因 / 对方拒绝：收端「取消」按钮、坏哈希 → `hash-mismatch`、
       发端撤回 `file-ctl cancel` → 收端「对方已拒绝」。
  R43  传输落盘根 = 数据目录 downloads/（`downloadDir()`；Android 是
       `/data/user/0/com.kapp.tidings/files/relay/downloads`）。
  R44  落盘副本与原文件 sha256 一致（`adb shell run-as ... sha256sum`）。
  R45  同名的「一层同名目录」存档：`downloads/<rootName>/<relPath>`；同名再来一次
       走 `dedupeTargetPath` → `name(1).ext`（不覆盖）。

夹具自己装成"那台电脑"：UDP 握手/收 ACK 用共享库的 `UdpPeer`；TCP 侧另起一个
**拉取服务端**（共享库的 `TcpFrameServer` 只认 `{"type":"msg"}`，没有 pull/pull-ok/done
与裸流能力），逐帧对齐 `core/src/commonMain/kotlin/com/kapp/tidings/core/transport/TransferServer.kt`。

    # 不插手机也能确认自己没写崩（纯本地断言，不碰网络、不碰设备）：
    python3 scripts/e2e/e2e_files.py --dry-run

    # 真机：
    python3 scripts/e2e/e2e_files.py --phone 192.168.2.22 --host-ip 192.168.2.82

    # 只测协议面（不 dump UI、不注入按键；「接收/拒绝/继续/取消」四条无从触发）：
    python3 scripts/e2e/e2e_files.py --skip-ui

    # 跳过 adb 落盘取证（`run-as` 在 release 包上不可用）：
    python3 scripts/e2e/e2e_files.py --skip-fs

退出码 0=PASS / 1=FAIL / 2=用法或环境错误；结尾恰好一行 `result ...`。

覆盖不了的（矩阵 §2 D 段里显然不归这个夹具的）：
  R34  发文件的入口在**系统文件选择器**里（`platform.filePicker.pick()`），
       夹具无法在 adb 上确定性地选一个文件 —— 需要人工。
  R46  收到文件的「打开 / 分享」两个按钮（`ReceivedFileActions`）是平台出口
       （Android 是 ACTION_VIEW / ACTION_SEND + FileProvider），本夹具判定**必须人工**。
  R47  「更改归档目录」：`saveDirectoryPicker` 全仓零调用点，是死代码 —— 没有可测的入口。
  R48  传输记录面板是桌面端的（`desktop/DesktopTransferLogPane.kt`），不属于手机夹具。
"""

import hashlib
import json
import os
import re
import shutil
import socket
import struct
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay_e2e as R  # noqa: E402


# ---------------------------------------------------------------------------
# 协议常量：逐条对齐 core/src/commonMain/kotlin/com/kapp/tidings/core/protocol/FileTransfer.kt
# ---------------------------------------------------------------------------

FILE_CTL = "file-ctl"
OP_OFFER = "offer"
OP_ACCEPT = "accept"
OP_DECLINE = "decline"
OP_CANCEL = "cancel"
OP_DIRECT = "direct"
PURPOSE_IMAGE = "image"

OFFER_FILES_PER_PACKET = 6            # FileTransfer.OFFER_FILES_PER_PACKET
MAX_FILES_PER_TRANSFER = 2000         # FileTransfer.MAX_FILES_PER_TRANSFER
IMG_AUTO_ACCEPT = 20 * 1024 * 1024    # FileTransfer.IMG_AUTO_ACCEPT（私聊图）
GROUP_IMG_AUTO_ACCEPT = 10 * 1024 * 1024   # FileTransfer.GROUP_IMG_AUTO_ACCEPT（群图）
MAX_FRAME = 64 * 1024                 # FileTransfer.MAX_FRAME
FILE_OFFER_TTL_MS = 24 * 60 * 60 * 1000    # FileTransfer.FILE_OFFER_TTL
TABLE_TEXT_LIMIT_BYTES = 4096         # FileTransfer.TABLE_TEXT_LIMIT_BYTES

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

TRANSFER_FAIL_REASONS = (
    "hash-mismatch", "size-mismatch", "path-escape", "part-read-error", "write-error",
    "canceled", "closed", "timeout", "socket-error", "peer-offline",
)

# 落盘根（Android）：AndroidPlatform.dataDirectory() = filesDir/relay，
# RelayController.downloadDir = pathJoin(dataDirectory, "downloads")。
DEV = "/data/user/0/com.kapp.tidings/files/relay/downloads"

# UI 文案（composeResources/values/strings.xml），全部走 dump 出来的 bounds。
UI_ATTACH = "发文件"          # chat_attach
UI_ACCEPT = "接收"            # file_accept
UI_DECLINE = "拒绝"           # file_decline
UI_RESUME = "继续"            # transfer_action_resume
UI_CANCEL = "取消"            # common_cancel
UI_FOLDER_BADGE = "文件夹"    # transfer_folder_badge
UI_DECLINED = "对方已拒绝"     # file_state_declined
UI_FAILED = "传输失败"        # file_state_failed

IMG_HEADER = bytes([0x89]) + b"PNG\r\n\x1a\n"


# ---------------------------------------------------------------------------
# 纯本地协议层：造帧 + 校验（`--dry-run` 全靠这一层）
# ---------------------------------------------------------------------------

def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def sanitize_relative_path(raw):
    """逐条复刻 core/fs/FileIo.kt:32 的 `sanitizeRelativePath`。

    返回 None 表示这条路径不能要（绝对路径、盘符、`..` 逃逸、空段）；
    夹具要拿它预测手机侧会丢掉哪几条 FileMeta。
    """
    if not isinstance(raw, str) or raw.strip() == "":
        return None
    if "\u0000" in raw:
        return None
    normalized = raw.replace("\\", "/")
    if normalized.startswith("/"):
        return None
    if len(normalized) >= 2 and normalized[0].isascii() and normalized[0].isalpha() \
            and normalized[1] == ":":
        return None
    segments = [s for s in normalized.split("/") if s != "" and s != "."]
    if any(s == ".." for s in segments):
        return None
    if not segments:
        return None
    return "/".join(segments)


def file_meta(file_id, path, size, is_dir=None):
    meta = {"fileId": file_id, "path": path, "size": int(size)}
    if is_dir is not None:
        meta["isDir"] = bool(is_dir)
    return meta


def offer_payload(transfer_id, files, total_size=None, root_name=None, seq=1, total=1,
                  file_count=None, purpose=None, group_id=None, group_rev=None,
                  msg_id=None, is_folder=None, expires_at=None, table_text=None,
                  table_text_truncated=None):
    """造一条 `file-ctl offer` 载荷（键顺序按 FileCtlOffer 的声明顺序）。"""
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
                 group_id=None, group_rev=None, is_folder=None, expires_at=None):
    """按 OFFER_FILES_PER_PACKET=6 分片。**必须在造帧时就分对**：
    收端要凑齐 `fileCount` 条才算完整，分片数与 entries 数对不上就是一条永远停在
    «待接收» 的 offer（seq==total 只说明这是最后一片）。"""
    chunks = [metas[i:i + OFFER_FILES_PER_PACKET]
              for i in range(0, len(metas), OFFER_FILES_PER_PACKET)] or [[]]
    total = len(chunks)
    out = []
    for idx, chunk in enumerate(chunks):
        out.append(offer_payload(
            transfer_id, chunk, total_size=total_size, root_name=root_name,
            seq=idx + 1, total=total, file_count=len(metas), purpose=purpose,
            group_id=group_id, group_rev=group_rev, is_folder=is_folder,
            expires_at=expires_at))
    return out


def tcp_frame_bytes(obj, body=b""):
    """4 字节大端长度前缀 + JSON 体（+ 裸流）。与共享库 tcp_frame 同一形状。"""
    raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return struct.pack(">I", len(raw)) + raw + body


def _nonempty_str(x, maxlen):
    return isinstance(x, str) and x != "" and len(x) <= maxlen


def _nonneg_safe_int(x):
    # nonNegativeSafeInteger：1.5 与字符串都被拒，上界 2^53-1。
    if isinstance(x, bool):
        return False
    if isinstance(x, int):
        return 0 <= x <= 9007199254740991
    return False


def validate_offer(payload):
    """本地校验一条 offer 载荷够不够收端 `decodeFromJsonElement` 这一关。

    返回 None 表示通过，否则返回一个短原因串。
    """
    if not isinstance(payload, dict):
        return "not-object"
    for key in OFFER_REQUIRED:
        if key not in payload:
            return "missing:" + key
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
    return None


def frame_keys_ok(obj, expected):
    return isinstance(obj, dict) and len(obj) == len(expected) and all(k in expected for k in obj)


def validate_control_frame(obj):
    """逐条对齐 `TcpFrames.decodeBody`：键集合严格相等 + 逐字段有界。

    返回 None 表示是合法的控制帧，否则返回短原因串（bad-frame-shape / bad-<字段>）。
    """
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


def local_frame_checks():
    """纯本地断言：造帧与校验这一层自己不能错。`--dry-run` 主要靠它。"""
    # --- 路径净化：逐条对齐 sanitizeRelativePath 的返回
    cases = [
        ("a.txt", "a.txt"),
        ("dir/sub/a.txt", "dir/sub/a.txt"),
        ("dir\\sub\\a.txt", "dir/sub/a.txt"),
        ("./dir/a.txt", "dir/a.txt"),
        ("dir//a.txt", "dir/a.txt"),
        ("../evil.txt", None),
        ("dir/../../etc/passwd", None),
        ("/abs/evil.txt", None),
        ("C:evil.txt", None),
        ("c:/evil.txt", None),
        ("a\u0000b.txt", None),
        ("", None),
        ("   ", None),
        ("./", None),
        ("..", None),
        ("/", None),
    ]
    bad = [(raw, want, got) for (raw, want) in cases
           for got in (sanitize_relative_path(raw),) if got != want]
    R.check("local-sanitize-relative-path-matches-kotlin", not bad,
            "16 例逐条对齐 FileIo.kt:32" if not bad else "不符：%s" % bad)

    # --- offer 载荷：必需键 / 额外键容忍 / 分片
    metas = [file_meta("f%d" % i, "d/f%d.bin" % i, 1024) for i in range(7)]
    packets = chunk_offers(metas, "t-chunk", root_name="d", is_folder=True)
    R.check("local-offer-splits-by-6-per-packet",
            len(packets) == 2 and all(len(p["files"]) <= OFFER_FILES_PER_PACKET for p in packets),
            "7 条 ⇒ %d 片：%s" % (len(packets), [len(p["files"]) for p in packets]))
    R.check("local-offer-seq-and-total-form-a-complete-set",
            [p["seq"] for p in packets] == list(range(1, len(packets) + 1))
            and all(p["total"] == len(packets) for p in packets)
            and all(p["fileCount"] == 7 for p in packets),
            "收端要凑齐 fileCount=%d 才算完整" % len(metas))
    R.check("local-offer-required-keys-and-order",
            all(validate_offer(p) is None for p in packets)
            and list(packets[0].keys())[:5] == ["op", "transferId", "seq", "total", "files"],
            "必需键 = %s" % ",".join(OFFER_REQUIRED))
    extra = offer_payload("t-extra", [file_meta("f1", "a.bin", 8)], root_name="a.bin")
    extra["isFolder"] = False
    extra["tableTextTruncated"] = False
    extra["purpose"] = PURPOSE_IMAGE
    extra["expiresAt"] = R.now_ms() + FILE_OFFER_TTL_MS
    R.check("local-offer-optional-keys-still-valid", validate_offer(extra) is None,
            "可选键 %d 个，位置不能提前" % len(OFFER_OPTIONAL))
    R.check("local-offer-rejects-missing-required-key",
            validate_offer({k: v for k, v in extra.items() if k != "rootName"}) == "missing:rootName",
            "缺 rootName 就是 no-payload/反序列化失败")
    R.check("local-offer-rejects-over-six-files-per-packet",
            validate_offer(offer_payload(
                "t-big", [file_meta("f%d" % i, "f%d" % i, 1) for i in range(7)],
                root_name="x")) == "too-many-files-in-packet",
            ">%d 条必须分片" % OFFER_FILES_PER_PACKET)
    float_offer = offer_payload("t-f", [file_meta("f1", "a", 1)], root_name="a")
    float_offer["files"][0]["size"] = 1.5          # int() 会把它截掉，所以在这里塞原始值
    R.check("local-offer-allows-float-size-rejection",
            validate_offer(float_offer) == "bad-size" and not _nonneg_safe_int(1.5)
            and not _nonneg_safe_int("1") and _nonneg_safe_int(0)
            and not _nonneg_safe_int(9007199254740992),
            "nonNegativeSafeInteger：1.5 / \"1\" / 2^53 都被拒，0 可以")
    R.check("local-offer-predicts-dropped-entries",
            [sanitize_relative_path(m["path"]) for m in
             [file_meta("f1", "../evil.txt", 4), file_meta("f2", "ok.txt", 4)]] == [None, "ok.txt"],
            "mapNotNull：坏条目被丢，好条目照收 —— R40 靠这条预测")

    # --- TCP 控制帧：严格键集合
    good = [
        {"type": "pull", "from": "n1", "transferId": "t1", "fileId": "f1", "offset": 0},
        {"type": "pull-ok", "fileId": "f1", "len": 10},
        {"type": "done", "fileId": "f1", "sha256": "a" * 64},
        {"type": "finish", "transferId": "t1"},
        {"type": "err", "reason": "not-found"},
        {"type": "wait"},
    ]
    R.check("local-control-frames-valid", all(validate_control_frame(f) is None for f in good),
            "%d 种控制帧" % len(good))
    drifted = dict(good[0], extra=1)
    R.check("local-control-frame-rejects-extra-key",
            validate_control_frame(drifted) == "bad-frame-shape",
            "hasOnlyKeys 是严格键集合：多一个键整帧拒（与 UDP 侧 file-ctl 载荷不同！）")
    R.check("local-control-frame-rejects-bad-offset",
            validate_control_frame(dict(good[0], offset=-1)) == "bad-offset"
            and validate_control_frame(dict(good[0], offset=1.5)) == "bad-offset"
            and validate_control_frame(dict(good[0], offset="0")) == "bad-offset",
            "offset 是 nonNegativeSafeInteger")
    R.check("local-control-frame-rejects-non-hex64-sha256",
            validate_control_frame({"type": "done", "fileId": "f1", "sha256": "a" * 63})
            == "bad-sha256"
            and validate_control_frame({"type": "done", "fileId": "f1", "sha256": "z" * 64})
            == "bad-sha256",
            "done 的 sha256 必须 isHex64")
    R.check("local-control-frame-rejects-zero-len",
            validate_control_frame({"type": "pull-ok", "fileId": "f1", "len": 0}) == "bad-len",
            "pull-ok.len 至少 1（只发有内容的那一段）")

    # --- 分帧 + 裸流
    frame = tcp_frame_bytes({"type": "pull-ok", "fileId": "f1", "len": 4}, b"DATA")
    R.check("local-tcp-frame-length-prefix-big-endian",
            struct.unpack(">I", frame[:4])[0] == len(frame) - 4 - 4
            and frame[-4:] == b"DATA",
            "4 字节大端长度前缀只覆盖 JSON 体，裸流跟在后面")
    R.check("local-tcp-frame-length-upper-bound",
            MAX_FRAME == 65536 and MAX_FRAME % 4096 == 0,
            "readFrame 对 len<=0 或 >MAX_FRAME 直接断链")

    # --- 上限常量（决定自动接收与群图退化）
    R.check("local-auto-accept-limits",
            IMG_AUTO_ACCEPT == 20 * 1024 * 1024 and GROUP_IMG_AUTO_ACCEPT == 10 * 1024 * 1024
            and GROUP_IMG_AUTO_ACCEPT * 2 == IMG_AUTO_ACCEPT,
            "群图上限是私聊的一半（决议 #33）")
    auto = GROUP_IMG_AUTO_ACCEPT + 1
    R.check("local-group-image-above-limit-needs-manual-accept",
            auto > GROUP_IMG_AUTO_ACCEPT and auto <= IMG_AUTO_ACCEPT,
            "%dB 的群图落在 (10MB, 20MB]：自动接收为假 ⇒ 该停在«待接收»" % auto)
    R.check("local-transfer-fail-reasons-cover-contract",
            {"hash-mismatch", "size-mismatch", "closed", "canceled", "peer-offline"} <=
            set(TRANSFER_FAIL_REASONS),
            "PullException.reason + peer:<reason> 共 %d 种" % len(TRANSFER_FAIL_REASONS))
    R.check("local-table-text-limit-matches-tcp-text-limit", TABLE_TEXT_LIMIT_BYTES == R.TEXT_TCP_LIMIT,
            "%d == TEXT_TCP_LIMIT" % TABLE_TEXT_LIMIT_BYTES)
    return packets, frame


# ---------------------------------------------------------------------------
# 真机侧的 TCP 拉取服务端（TransferServer.kt 的最小对齐实现）
# ---------------------------------------------------------------------------

class _Interrupted(Exception):
    """夹具**故意**在把 len 字节发完之前断流（留下 `.part` 的那条路）。"""


class PullServer(object):
    """监听夹具自己的 `profile.tcpPort`，服务手机发来的 `pull`。

    与共享库 `TcpFrameServer` 的区别：那个只认 `{"type":"msg","envelope":…}`。
    这里要做的是 `TransferServer.serve()` 的那半边 —— 收 `pull`，回
    `pull-ok{len}` + len 字节裸流 + `done{sha256}`；`finish` 只记一笔。

    三个容易写错的地方，逐条对齐 Kotlin：
      * 首帧等 15s 握手、之后按 60s 空闲；`pull` 里 `offset > file.size` 回
        `err reason:"bad-offset"`，`resolve` 不到回 `err reason:"not-found"`。
      * **裸流发完才发 `done`**；`wait` 心跳只在 offset>0 且对端声明 `tw1` 时才开，
        数据面里插帧会被收端当文件内容吃掉。
      * 收端先发的 `pull-ok`/`done`/`err`/`wait` 一律**静默忽略、不回 err** ——
        回了就会把续传时对方先发的 `wait` 判成 `peer:busy` 而整条失败。
    """

    def __init__(self, port=R.TCP_PORT_DEFAULT, bind="0.0.0.0", name="pull"):
        self.port = int(port)
        self.bind = bind
        self.name = name
        self._srv = None
        self._thread = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self.files = {}         # fileId -> (size, data, sha256)
        self.pulls = []         # [(ts, addr, frame)]
        self.finishes = []      # [(ts, addr, frame)]
        self.ignored = []       # [(ts, addr, frame)] 收端帧（wait/pull-ok/done/err）
        self.errors = []        # [(ts, addr, reason)]
        self.incomplete = []    # [(ts, addr, frame)] 夹具故意断流的 pull
        self.disconnects = []   # [(ts, addr)] 发流中途对端断了
        self.send_budget = None  # 每连接最多发多少字节数据（None = 不限）
        self.delay_ms = 0        # 每 8KiB 之间的等待（模拟慢链路）
        self.corrupt = False     # done 里故意给错摘要（测收端 hash-mismatch）

    # -- 生命周期 ---------------------------------------------------------
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

    # -- 服务一条连接 -----------------------------------------------------
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
                    # 收端帧（wait / pull-ok / done / err）：静默忽略，绝不回 err。
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
            with self._lock:
                self.disconnects.append((time.time(), addr))
            return
        if self.corrupt:
            digest = ("0" if digest[0] != "0" else "1") + digest[1:]
        self._write(conn, {"type": "done", "fileId": frame["fileId"], "sha256": digest})


# ---------------------------------------------------------------------------
# 夹具本地素材与登记
# ---------------------------------------------------------------------------

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


def make_material(root, name, size, png=False, seed=0):
    """在临时目录里造一个确定性的素材（dry-run 不调用它）。"""
    path = os.path.join(root, name.replace("/", "_"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    blob = bytearray()
    if png:
        blob += IMG_HEADER
    x = (seed + 7919) & 0xFFFFFFFF
    while len(blob) < size:
        x = (1103515245 * x + 12345) & 0x7FFFFFFF
        blob += struct.pack("<I", x)
    blob = bytes(blob[:size])
    with open(path, "wb") as fh:
        fh.write(blob)
    return path


def new_transfer_id(tag):
    return "t-%s-%s" % (tag, R.uniq_marker("", 6))


def register(server, *files):
    """把素材登记进拉取服务端（fileId → 字节/摘要）。返回 server 本身以便链式调用。"""
    for f in files:
        server.files[f.file_id] = (f.size, f.data, f.sha256)
    return server


# ---------------------------------------------------------------------------
# adb 侧取证（落盘 / 清扫）
# ---------------------------------------------------------------------------

class FsProbe(object):
    """用 `adb shell run-as com.kapp.tidings` 读被应用私有目录。

    只有 debuggable 包能用 `run-as`；release 包上它会拒。这时夹具**不报 FAIL**，
    只是把磁盘断言标成跳过 —— 落盘本身已经有 pull+done+finish 这条边证明，
    取证缺失不该把功能判成坏的。
    """

    def __init__(self, root=DEV):
        self.root = root
        self.available = None
        self.reason = ""

    def _run(self, *args):
        return R.adb("shell", "run-as", R.PKG, *args)

    @staticmethod
    def _q(path):
        """把远端路径包进单引号。

        `adb shell` 的参数是**设备上的 sh 再解析一遍**的：去重落盘的名字是
        `e2e-dup(6).txt` / `e2e-auto(3).png`，括号是 shell 元字符 ——
        真机实测 `run-as com.kapp.tidings sha256sum …/e2e-auto(3).png` 直接
        `/system/bin/sh: syntax error: unexpected '('`（于是 sha256 返回 None、
        断言看着像"摘要不一致"，其实是取证命令没跑起来）。
        """
        return "'%s'" % path

    def probe(self):
        out = self._run("ls", self._q(self.root))
        # 注意：`ls <root>` 列的是**目录内容**，永远不会包含 "downloads" 这个词。
        # 老写法 `"downloads" in out` 于是恒假 —— 整个 files 夹具的落盘取证（.part、
        # sha256、R38 的清扫）都被静默降级成"跳过"（2026-10-09 发现，reason 里正是
        # downloads 的条目清单）。判据改成：没有报错标记且有条目。
        bad = ("not debuggable" in out or "No such file" in out
               or "Permission denied" in out or out.lstrip().startswith("Package "))
        entries = [ln.strip() for ln in out.splitlines() if ln.strip()]
        self.available = (not bad) and len(entries) > 0
        if not self.available:
            self.reason = out.strip().replace("\n", " ")[:160] or "run-as 不可用"
        return self.available

    def exists(self, rel):
        out = self._run("ls", self._q("%s/%s" % (self.root, rel)))
        return rel.split("/")[-1] in out and "No such file" not in out

    def sha256(self, rel):
        out = self._run("sha256sum", self._q("%s/%s" % (self.root, rel)))
        parts = out.split()
        if len(parts) >= 2 and len(parts[0]) == 64:
            return parts[0].lower()
        return None

    def size(self, rel):
        """文件的字节数（`stat -c %s`）。续传的 offset 判据要用它，不能用夹具"打算发"
        的字节数：夹具按 64KiB 分片，`send_budget=100000` 实际只落了 65536B。"""
        out = self._run("stat", "-c", "%s", self._q("%s/%s" % (self.root, rel)))
        for token in out.split():
            if token.isdigit():
                return int(token)
        return None

    def listdir(self, rel=""):
        target = "%s/%s" % (self.root, rel) if rel else self.root
        out = self._run("find", self._q(target), "-maxdepth", "2")
        got = []
        for line in out.splitlines():
            line = line.strip()
            if line.startswith(self.root):
                rest = line[len(self.root):].lstrip("/")
                if rest:
                    got.append(rest)
        # `find <dir>` 会把 <dir> **自己**也打出来。传了 rel 时必须剔掉它，否则
        # `listdir(rel)` 永远含 `rel` 本身 —— 2026-10-09 之前的
        # `fs.listdir(root_auto) == [rel_auto]` 因此**恒假**（真机 find 的输出
        # 就是 `<root>/e2e-auto.png` + 它下面的文件）。
        if rel:
            got = [g for g in got if g != rel]
        return sorted(got)

    def parts(self):
        return [p for p in self.listdir() if p.endswith(".part")]

    def put_file(self, rel, data):
        """把一个本地 bytes 塞进应用私有目录（造 `.part` 时用）。

        走 `adb push` + `run-as cp`，**不做 shell 重定向** —— `run-as ... sh -c '...'`
        的引号在 Android 各版本 sh 上行为不一致，这里绕开它。
        """
        tmpdir = tempfile.mkdtemp(prefix="relay-e2e-push-")
        tmp = os.path.join(tmpdir, "blob")
        with open(tmp, "wb") as fh:
            fh.write(data)
        remote = "/data/local/tmp/relay-e2e-push.bin"
        R.adb("push", tmp, remote)
        parent = rel.rsplit("/", 1)[0] if "/" in rel else ""
        if parent:
            R.adb("shell", "run-as", R.PKG, "mkdir", "-p",
                  self._q("%s/%s" % (self.root, parent)))
        R.adb("shell", "run-as", R.PKG, "cp", self._q(remote),
              self._q("%s/%s" % (self.root, rel)))
        R.adb("shell", "rm", "-f", remote)
        shutil.rmtree(tmpdir, ignore_errors=True)
        return self.exists(rel)


# ---------------------------------------------------------------------------
# 夹具主体
# ---------------------------------------------------------------------------

def _variant_re(root, name):
    """匹配 `root/name` 以及它的**去重变体** `root/base(N)ext`（同一层、同名目录里）。

    收端落盘是「一层同名目录 + 目录内同名文件」，同名再来一次时走
    `dedupeTargetPath` 变成 `base(1)ext`、`base(2)ext`…（`FileIo.kt`，绝不覆盖）。
    真机反复跑会留下历次的文件，所以判据一律用「新增了哪一条」而不是「目录里只有
    一条」—— 后者在干净设备上才成立，是**状态依赖**的假判据。
    """
    cut = name.rindex(".")
    base, ext = name[:cut], name[cut:]
    return re.compile(r"^%s/%s(?:\((\d+)\))?%s$"
                      % (re.escape(root), re.escape(base), re.escape(ext)))


def _new_entries(before, after):
    return sorted(set(after) - set(before))


def _budget(args, cap):
    return max(1.0, min(cap, float(args.secs)))


def phase(title):
    print("\n== %s ==" % title, flush=True)


def send_offer(peer, files, transfer_id, root_name=None, purpose=None, group_id=None,
               group_rev=None, is_folder=None, total_size=None, tag=""):
    """把一个（或多个）`file-ctl offer` 分片按 seq 顺序发出去，返回 [(env, payload, ack)]。

    每条都等 ACK：`file-ctl` 是 RELIABLE 类型，`offerFiles` 在 Kotlin 侧同样是
    "每条 sendReliable 都等到 ACK 才发下一片"。等不到 ACK 就说明手机根本没当它是
    可靠帧（或整帧被拒），这时后面所有的断言都没有意义。
    """
    metas = [f.meta() for f in files]
    media = purpose in ("image", "sticker", "update")
    packets = chunk_offers(
        metas, transfer_id, root_name=root_name, purpose=purpose, group_id=group_id,
        group_rev=group_rev, is_folder=is_folder, total_size=total_size,
        expires_at=None if media else R.now_ms() + FILE_OFFER_TTL_MS)
    out = []
    for payload in packets:
        bad = validate_offer(payload)
        if bad is not None:
            R.check("offer-%s-local-shape-%s-seq%d" % (tag, transfer_id[-6:], payload["seq"]),
                    False, bad)
            return out
        env = peer.env(FILE_CTL, payload)
        peer.send(env)
        ack = peer.wait_ack(env["id"], 8.0)
        out.append((env, payload, ack))
    return out


def wait_pull(server, transfer_id, file_id=None, since=0.0, timeout=10.0, min_offset=None):
    """等一次 `pull`（可按 transferId/fileId/offset 下界过滤）。"""
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
    """等一条 `finish`。"""
    deadline = time.monotonic() + max(0.0, timeout)
    while True:
        with server._lock:
            hits = [f for (ts, a, f) in server.finishes
                    if ts >= since and f["transferId"] == transfer_id]
        if hits:
            return hits[-1]
        if time.monotonic() >= deadline:
            return None
        time.sleep(0.05)


def wait_any(peer, pred, timeout):
    return peer.wait(pred, timeout)


def _op(env):
    return (env.get("payload") or {}).get("op")


def _tid(env):
    return (env.get("payload") or {}).get("transferId")


def run(args):
    material_dir = None
    server = None
    peer = None
    try:
        # ------------------------------------------------------------ 本地自检
        phase("本地自检（不碰网络、不碰设备）")
        packets, frame = local_frame_checks()
        R.log("本地断言 %d 条；offer 分片样例 %s；TCP 帧 %dB"
              % (len(R._CHECKS), [len(p["files"]) for p in packets], len(frame)))

        if args.dry_run:
            print("\n[dry-run] 不碰网络、不碰设备：跳过 adb 与握手/拉取。", flush=True)
            print("[dry-run] 真机将执行：握手 → 图片自动接收(TCP 拉取+sha256) → "
                  "文件夹(isFolder/相对层级) → 路径穿越逐条丢弃 → 同名档案与去重 → "
                  "接收/拒绝/继续/取消四个按钮 → 坏哈希 hash-mismatch → "
                  "落盘 sha256 取证 → 杀进程验 .part 清扫。", flush=True)
            return 0

        # ------------------------------------------------------------ 环境
        phase("设备与环境")
        R.require_device()
        # 夹具必须能单独跑：`adb install -r` 会 force-stop 应用，此时手机上根本没有
        # 监听 17878 的进程，握手必然超时。以前是「碰巧前一夹具把应用留着」才没暴露
        # （2026-10-09 重装 APK 后单跑本条：handshake-alive 20s 超时）。
        R.ensure_app_foreground(timeout=15)
        fs = FsProbe()
        if args.skip_fs:
            R.log("--skip-fs：不做落盘取证")
        elif fs.probe():
            R.log("落盘根可读：%s（run-as %s）" % (fs.root, R.PKG))
        else:
            R.log("run-as 不可用（%s）；落盘断言降级为「跳过」而不是 FAIL" % fs.reason)

        material_dir = tempfile.mkdtemp(prefix="relay-e2e-files-")
        png = make_material(material_dir, "e2e-shot.png", 4096, png=True, seed=1)
        f_img = LocalFile(png, "e2e-shot.png", "f-img")

        # ------------------------------------------------------------ 起服务
        phase("起对端（UDP 握手 + TCP 拉取服务端）")
        server = PullServer(port=args.tcp_port, name="files")
        register(server, f_img)
        server.start()
        R.log("TCP 拉取服务端 :%d（写进夹具 profile.tcpPort）" % server.port)
        peer = R.UdpPeer(args.phone_ip, args.phone_udp, nick=args.nick,
                         tcp_port=server.port, host=args.host_ip).start()
        R.log("UDP 绑定 :%d；夹具 nodeId=%s nick=%s"
              % (peer.port, R.FIXTURE_NODE[:8], args.nick))
        return _body(args, peer, server, fs, material_dir, f_img)
    finally:
        if peer is not None:
            peer.stop()
        if server is not None:
            server.stop()
        if material_dir:
            shutil.rmtree(material_dir, ignore_errors=True)


def _body(args, peer, server, fs, material_dir, f_img):
    # ---------------------------------------------------------------- 握手
    phase("握手与对端能力")
    prof = peer.handshake(secs=_budget(args, 20), probe=True)
    if not R.check("handshake-alive", prof is not None,
                   "手机 alive 里的 profile" if prof
                   else "%ds 内没握上手" % min(args.secs, 20)):
        return None
    caps = prof.get("caps") or []
    phone_tcp_port = int(prof.get("tcpPort") or 0)
    R.log("手机 profile nick=%r platform=%s tcpPort=%s caps=%s"
          % (prof.get("nick"), prof.get("platform"), phone_tcp_port, ",".join(caps)))
    R.check("handshake-phone-tcp-port", 0 < phone_tcp_port < 65536,
            "tcpPort=%d（它才是 pull 的目的端口；用 udpPort 会 connection refused）"
            % phone_tcp_port)
    # 手机不声明 fd1（CAP_FILE_DIRECT）：`direct` 是桌面免确认用的，手机不发。
    R.check("handshake-phone-does-not-declare-file-direct",
            R.CAP_FILE_DIRECT not in caps,
            "caps 里有 fd1 的话手机的「免确认接收」开关语义变了")

    # ============================================================== 图片自动接收
    phase("R36/R41/R43/R44 图片 offer → 自动接收 → TCP 拉取 → sha256 校验")
    t_auto = new_transfer_id("auto")
    root_auto = "e2e-auto.png"
    f_img.name = root_auto
    before_auto = fs.listdir(root_auto) if fs.available else []
    R.log("落盘基线：downloads/%s/ 已有 %d 条（%s）—— R43/R44/R45 一律判「新增了哪一条」，"
          "不判「目录里只有一条」：真机反复跑会留下历次文件，而收端**从不覆盖**同名文件"
          "（dedupeTargetPath 会落成 (N) 变体）"
          % (root_auto, len(before_auto), ", ".join(before_auto[:4]) or "空"))
    since = time.time()
    sent = send_offer(peer, [f_img], t_auto, root_name=root_auto, purpose=PURPOSE_IMAGE,
                      tag="-auto")
    R.check("r36-offer-acked", len(sent) == 1 and sent[0][2] is not None,
            "offer %s 的 ACK（file-ctl 是可靠帧，等不到 ACK 后面全白测）" % t_auto[:12])
    R.check("r41-image-under-limit-offered-as-image",
            f_img.size <= IMG_AUTO_ACCEPT and sent and sent[0][1]["purpose"] == PURPOSE_IMAGE,
            "%dB ≤ %dB ⇒ 收端 autoAccept=true" % (f_img.size, IMG_AUTO_ACCEPT))

    accept = wait_any(peer, lambda e: _op(e) == OP_ACCEPT and _tid(e) == t_auto,
                      _budget(args, 12))
    R.check("r41-image-auto-accepted-without-a-tap", accept is not None,
            "UDP 收到 op=accept transferId=%s（这一条不点任何按钮）" % t_auto[:12])

    pull1 = wait_pull(server, t_auto, f_img.file_id, since=since, timeout=_budget(args, 12))
    R.check("r35-accept-opens-a-pull-on-our-tcp-port", pull1 is not None,
            "手机连 :%d 发 pull fileId=%s" % (server.port, f_img.file_id))
    R.check("r36-pull-frame-shape",
            pull1 is not None and validate_control_frame(pull1) is None,
            "键集合 {type,from,transferId,fileId,offset} 严格相等（多一个键整帧拒）"
            if pull1 else "没收到 pull")
    R.check("r36-pull-carries-puller-node-id-and-zero-offset",
            bool(pull1) and pull1.get("from") == (prof or {}).get("nodeId")
            and int(pull1.get("offset", -1)) == 0,
            "from=%s offset=%s（`pull` 的 `from` 是**拉取方**＝手机自己的 nodeId；"
            "首次拉取 offset 必须是 0）"
            % ((pull1 or {}).get("from", "?")[:8], (pull1 or {}).get("offset")))
    fin1 = wait_finish(server, t_auto, since=since, timeout=_budget(args, 20))
    R.check("r36-all-files-done-triggers-finish", fin1 is not None,
            "收到 finish transferId=%s（它只在收端把所有文件 rename 完之后发）" % t_auto[:12])
    R.check("r36-no-intra-stream-frame-injected",
            not [f for (_, _, f) in server.ignored if f["type"] == "err"],
            "数据面里插控制帧会被收端当文件内容吃掉；收端帧一律静默忽略")

    # ------------------------------------------------ 落盘路径与校验和（R43/R44/R45 第一半）
    if fs.available:
        after_auto = fs.listdir(root_auto)
        new_auto = _new_entries(before_auto, after_auto)
        R.check("r43-received-file-lands-under-downloads", len(new_auto) == 1,
                "downloads/%s/ 下新增了恰好一条 %s（downloadDir() = 数据目录/downloads）"
                % (root_auto, new_auto[0] if len(new_auto) == 1 else new_auto or "（无）"))
        got = fs.sha256(new_auto[0]) if len(new_auto) == 1 else None
        R.check("r44-landed-copy-sha256-matches-source",
                got is not None and got == f_img.sha256,
                "手机 %s vs 源 %s" % ((got or "?")[:16], f_img.sha256[:16]))
        R.check("r45-archive-is-one-level-same-name-dir",
                len(new_auto) == 1
                and _variant_re(root_auto, root_auto).match(new_auto[0]) is not None
                and all(_variant_re(root_auto, root_auto).match(p) for p in after_auto),
                "downloads/%s/ 里的每一条都是 %s 或它的 (N) 变体（一层同名目录，"
                "没有外来的/多一层的文件）：%s" % (root_auto, root_auto, after_auto))
    else:
        R.log("跳过 R43/R44/R45 的磁盘取证（%s）" % (fs.reason or "--skip-fs"))

    # ============================================================== R39 文件夹
    phase("R39 文件夹传输：isFolder / rootName / files[].path 相对层级")
    t_dir = new_transfer_id("dir")
    root_dir = "e2e-bundle"
    rel_a, rel_b = "top.txt", "nested/inner.txt"
    a = LocalFile(make_material(material_dir, "bundle-a.txt", 1536, seed=11), rel_a, "f-dir-a")
    b = LocalFile(make_material(material_dir, "bundle-b.txt", 2048, seed=12), rel_b, "f-dir-b")
    register(server, a, b)
    since = time.time()
    send_offer(peer, [a, b], t_dir, root_name=root_dir, is_folder=True, tag="-dir")
    accept_dir = wait_any(peer, lambda e: _op(e) == OP_ACCEPT and _tid(e) == t_dir,
                          _budget(args, 6))
    if args.skip_ui:
        R.check("r39-folder-offer-needs-a-tap",
                accept_dir is None,
                "非图片 offer 不会自动接收（这正是 R35 存在的理由）；"
                "--skip-ui 下没人点「接收」，后续 pull/落盘断言跳过")
        R.log("--skip-ui：文件夹那条没有 accept，后续 pull/落盘断言跳过")
    else:
        if accept_dir is None:
            # 这条 offer 是**手动接收**：必须有人点「接收」。夹具自己点 —— 先开聊天页，
            # 再点到 rootName 是 root_dir 的那张卡上的按钮（同屏还有别的卡，见 _card_button）。
            R.ensure_app_foreground()
            R.open_chat(args.nick)
            _card_button(args, root_dir, UI_ACCEPT, "r39-tap-folder-card-accept")
            accept_dir = wait_any(peer, lambda e: _op(e) == OP_ACCEPT and _tid(e) == t_dir,
                                  _budget(args, 12))
        R.check("r39-folder-offer-manually-accepted", accept_dir is not None,
                "点「%s」后 UDP 上出现 op=accept transferId=%s（非图片 offer 不会自动接收）"
                % (UI_ACCEPT, t_dir[:12]))
        pulls = []
        deadline = time.monotonic() + _budget(args, 20)
        while time.monotonic() < deadline:
            with server._lock:
                pulls = [(ts, f) for (ts, _, f) in server.pulls if f["transferId"] == t_dir]
            if len(pulls) >= 2:
                break
            time.sleep(0.1)
        R.check("r39-folder-pulls-both-entries-with-relative-paths",
                len(pulls) == 2,
                "收到 %d/2 次 pull：%s" % (len(pulls), [f["fileId"] for (_, f) in pulls]))
        fin_dir = wait_finish(server, t_dir, since=since, timeout=_budget(args, 20))
        R.check("r39-folder-transfer-finishes", fin_dir is not None,
                "finish transferId=%s" % t_dir[:12])
        if fs.available:
            R.check("r39-folder-keeps-relative-hierarchy",
                    fs.exists("%s/%s" % (root_dir, rel_a))
                    and fs.exists("%s/%s" % (root_dir, rel_b)),
                    "downloads/%s/{top.txt, nested/inner.txt} 都在（嵌套层级靠 relPath 还原）"
                    % root_dir)

    # ============================================================== R40 路径穿越
    phase("R40 路径穿越净化：坏条目被逐条丢弃，好条目照收")
    t_esc = new_transfer_id("esc")
    root_esc = "e2e-escape"
    evil = [file_meta("f-evil-1", "../evil.txt", 16),
            file_meta("f-evil-2", "/abs/evil.txt", 16),
            file_meta("f-evil-3", "C:evil.txt", 16),
            file_meta("f-evil-4", "a\u0000b.txt", 16),
            file_meta("f-good", "ok.txt", 16)]
    good_file = LocalFile(make_material(material_dir, "ok.txt", 16, seed=21), "ok.txt", "f-good")
    register(server, good_file)
    payloads = chunk_offers(evil, t_esc, root_name=root_esc, total_size=16 * 5)
    since = time.time()
    bad_shape = [p for p in payloads if validate_offer(p) is not None]
    for payload in payloads:
        env = peer.env(FILE_CTL, payload)
        peer.send(env)
        peer.wait_ack(env["id"], 8.0)
    R.check("r40-escape-offer-shape-valid-locally",
            not bad_shape,
            "线上形状合法（净化发生在收端组装那一步，不是帧校验）"
            if not bad_shape else "本地就不合法：%s" % [validate_offer(p) for p in bad_shape])
    R.check("r40-fixture-predicts-four-of-five-dropped",
            [sanitize_relative_path(m["path"]) for m in evil] ==
            [None, None, None, None, "ok.txt"],
            "5 条里只有 ok.txt 能过 sanitizeRelativePath")
    if args.skip_ui:
        # 手动接收需要点一下，--skip-ui 下没人能点 ⇒ 如实跳过，不写恒真断言。
        R.log("--skip-ui：没人点「%s」，R40 的 accept/pull 断言跳过（净化形状断言照跑）"
              % UI_ACCEPT)
        accept_esc = None
    else:
        _card_button(args, root_esc, UI_ACCEPT, "r40-tap-escape-card-accept")
        accept_esc = wait_any(peer, lambda e: _op(e) == OP_ACCEPT and _tid(e) == t_esc,
                              _budget(args, 12))
        R.check("r40-offer-with-mixed-paths-is-accepted", accept_esc is not None,
                "恶意条目不会让整条 offer 作废（mapNotNull 只是丢掉那几条）")
        pull_esc = wait_pull(server, t_esc, since=since, timeout=_budget(args, 15))
        R.check("r40-only-the-safe-entry-is-pulled",
                pull_esc is not None and pull_esc["fileId"] == "f-good",
                "只 pull 了 fileId=%s（另外 4 条永远不该出现在网络上）"
                % ((pull_esc or {}).get("fileId")))
    R.check("r40-no-pull-for-escaped-file-ids",
            not any(f["fileId"].startswith("f-evil") for (_, _, f) in server.pulls),
            "f-evil-* 一次都没被 pull")
    if fs.available:
        R.check("r40-nothing-escaped-the-downloads-root",
                not fs.exists("../evil.txt") and not fs.exists("evil.txt"),
                "downloads/ 之外没有 evil.txt")

    # ============================================================== UI 段
    if not args.skip_ui:
        _ui_phase(args, peer, server, fs, material_dir)
    else:
        R.log("--skip-ui：跳过「接收/拒绝/继续/取消」四条按钮与卡片文案断言")

    # ============================================================== R42 坏哈希
    phase("R42 坏哈希 → hash-mismatch（收端必须删 .part 并报失败）")
    t_bad = new_transfer_id("bad")
    root_bad = "e2e-corrupt.bin"
    f_bad = LocalFile(make_material(material_dir, "corrupt-src.bin", 24576, seed=31),
                      root_bad, "f-bad")
    register(server, f_bad)
    server.corrupt = True
    since = time.time()
    send_offer(peer, [f_bad], t_bad, root_name=root_bad, purpose=PURPOSE_IMAGE, tag="-bad")
    fin_bad = wait_finish(server, t_bad, since=since, timeout=_budget(args, 20))
    R.check("r42-corrupt-sha256-never-reaches-finish", fin_bad is None,
            "摘要是错的 ⇒ 收端 sha.hexDigest() != digest ⇒ hash-mismatch，"
            "不可能回 finish" if fin_bad is None else "竟然回了 finish")
    if fs.available:
        R.check("r42-hash-mismatch-leaves-no-part",
                not fs.exists("%s/%s.part" % (root_bad, root_bad)),
                "verify 失败时先删 .part 再报 hash-mismatch")
        R.check("r42-hash-mismatch-leaves-no-final-file",
                not fs.exists("%s/%s" % (root_bad, root_bad)),
                "未通过校验的字节不能被 rename 成正式文件")
    server.corrupt = False

    # ============================================================== R45 同名再来一次
    phase("R45 同名文件的存档与去重（不覆盖）")
    t_dup = new_transfer_id("dup")
    root_dup = "e2e-dup.txt"
    d1 = LocalFile(make_material(material_dir, "dup-1.txt", 1024, seed=41), root_dup, "f-dup-1")
    register(server, d1)
    before_dup = fs.listdir(root_dup) if fs.available else []
    since = time.time()
    send_offer(peer, [d1], t_dup, root_name=root_dup, purpose=PURPOSE_IMAGE, tag="-dup")
    fin_dup = wait_finish(server, t_dup, since=since, timeout=_budget(args, 20))
    R.check("r45-first-copy-finishes", fin_dup is not None, "第一次收下 %s" % root_dup)
    after1 = []
    first_new = []
    if fs.available:
        after1 = fs.listdir(root_dup)
        first_new = _new_entries(before_dup, after1)
        # 名字可能是原名（目录里还没有它）也可能是 (N) 变体（历次跑留下的）——判据是
        # **新增的那一条**的摘要与源一致，而不是某个硬编码的名字。
        R.check("r45-first-copy-sha256-matches",
                len(first_new) == 1 and fs.sha256(first_new[0]) == d1.sha256,
                "downloads/%s 下新增 %s 的 sha256 与源一致（新增 %d 条）"
                % (root_dup, first_new[0] if len(first_new) == 1 else first_new, len(first_new)))
    # 同名第二次：dedupeTargetPath 从 (1) 往上找到第一个空位
    t_dup2 = new_transfer_id("dup2")
    d2 = LocalFile(make_material(material_dir, "dup-2.txt", 1024, seed=42), root_dup, "f-dup-2")
    register(server, d2)
    since = time.time()
    send_offer(peer, [d2], t_dup2, root_name=root_dup, purpose=PURPOSE_IMAGE, tag="-dup2")
    fin_dup2 = wait_finish(server, t_dup2, since=since, timeout=_budget(args, 20))
    R.check("r45-second-copy-also-finishes", fin_dup2 is not None,
            "同名不覆盖：第二次也要能完成")
    if fs.available:
        after2 = fs.listdir(root_dup)
        second_new = _new_entries(after1, after2)
        rx = _variant_re(root_dup, root_dup)
        # 期望的名字 = 从 (1) 起第一个还没被占用的空位（真机反复跑会留下 (1)(2)…）。
        n = 1
        while "%s/%s(%d)%s" % (root_dup, root_dup[:root_dup.rindex(".")], n,
                               root_dup[root_dup.rindex("."):]) in after1:
            n += 1
        expect = "%s/%s(%d)%s" % (root_dup, root_dup[:root_dup.rindex(".")], n,
                                  root_dup[root_dup.rindex("."):])
        R.check("r45-dedupe-name-is-the-first-free-index",
                len(second_new) == 1 and second_new[0] == expect
                and rx.match(second_new[0]) is not None
                and rx.match(second_new[0]).group(1) is not None,
                "第二次落在 %s（期望 %s：%s 起第一个空位；不能覆盖，不能落在别处）"
                % (second_new[0] if len(second_new) == 1 else second_new, expect,
                   "从 (1) 起第一个空位"))
        R.check("r45-original-is-not-overwritten",
                len(first_new) == 1 and fs.sha256(first_new[0]) == d1.sha256,
                "第一份 %s 的 sha256 没被第二份覆盖"
                % (first_new[0] if len(first_new) == 1 else first_new))

    # ============================================================== R41 超限
    phase("R41 自动接收上限：群图 >10MB 退化成手动接收")
    t_over = new_transfer_id("over")
    root_over = "e2e-over.webp"
    over = LocalFile(make_material(material_dir, "over-src.webp", 128), root_over, "f-over")
    register(server, over)
    over_payload = chunk_offers([over.meta()], t_over, root_name=root_over,
                                purpose=PURPOSE_IMAGE, group_id="g-e2e",
                                group_rev=1, total_size=GROUP_IMG_AUTO_ACCEPT + 1)[0]
    peer.send(peer.env(FILE_CTL, over_payload))
    R.check("r41-group-image-above-10mb-is-not-auto-accepted",
            (GROUP_IMG_AUTO_ACCEPT < over_payload["totalSize"] <= IMG_AUTO_ACCEPT),
            "声明 totalSize=%d（>10MB 且 ≤20MB）：收端按群图上限判 ⇒ 停在«待接收»"
            % over_payload["totalSize"])
    # 内容只有 128B，但声明的 totalSize 超限 —— 收端按声明判据决定要不要自动收。
    time.sleep(2.0)
    auto_over = wait_any(peer, lambda e: _op(e) == OP_ACCEPT and _tid(e) == t_over, 3.0)
    R.check("r41-over-limit-group-image-stays-pending", auto_over is None,
            "没有自动 accept（这条 offer 该停在卡片上等人点「接收」）")
    # 收尾：体面地撤回，别在手机上留一张永远不动的卡片。
    peer.send(peer.env(FILE_CTL, simple_payload(OP_CANCEL, t_over)))
    time.sleep(0.5)

    # ============================================================== R36 续传（协议面）
    phase("R36/R37 断流留 .part → 继续 → 第二次 pull 带 offset")
    t_res = new_transfer_id("res")
    root_res = "e2e-resume.bin"
    f_res = LocalFile(make_material(material_dir, "resume-src.bin", args.resume_bytes, seed=51),
                      root_res, "f-res")
    register(server, f_res)
    server.send_budget = args.resume_budget
    since = time.time()
    send_offer(peer, [f_res], t_res, root_name=root_res, purpose=PURPOSE_IMAGE, tag="-res")
    first = wait_pull(server, t_res, f_res.file_id, since=since, timeout=_budget(args, 15))
    R.check("r37-first-pull-starts", first is not None,
            "第一次 pull offset=%s" % (first or {}).get("offset"))
    time.sleep(1.5)
    R.check("r37-fixture-cut-the-stream-mid-flight",
            any(f["transferId"] == t_res for (_, _, f) in server.incomplete),
            "夹具在 %dB 处断流（模拟对端掉线/超时）" % args.resume_budget)
    R.check("r37-no-finish-after-truncated-stream",
            wait_finish(server, t_res, since=since, timeout=2.0) is None,
            "发流没完就不可能 finish")
    if fs.available:
        R.check("r37-truncated-transfer-leaves-a-part",
                fs.exists("%s/%s.part" % (root_res, root_res)),
                "downloads/%s/%s.part 留着（跨进程续传不存在，但同进程内它必须还在）"
                % (root_res, root_res))
    # 续传：U 点「继续」→ resumeTransfer → pull(offset = .part 已有字节数)
    if args.skip_ui:
        # 没有 UI 就没有「继续」这个动作：收端 resumeTransfer 只认已 accept 的
        # transfer，夹具无法代它点。跳过而不是假装通过。
        R.log("--skip-ui：无法触发「继续」，第二次 pull 的 offset 断言跳过")
    else:
        # 「继续」按钮在**文件卡片**里，得先把聊天页拉出来 —— 这一段在协议相位里，
        # 之前只是碰巧因为上一次跑（或探针）把聊天页留在了前台才找得到。
        R.ensure_app_foreground()
        R.open_chat(args.nick)
        # 续传的 offset 是**盘上 .part 的实际字节数**，不是夹具"打算发"的预算：夹具按
        # 64KiB 分片，`send_budget=100000` 实际只落了 65536B（2026-10-09 真机实测：
        # 第二次 pull 带 offset=65536，而夹具按 100000 断言 ⇒ 三条恒红、还白等 20s 超时）。
        part_rel = "%s/%s.part" % (root_res, root_res)
        on_disk = fs.size(part_rel) if fs.available else None
        R.log("续传前 %s = %s 字节（夹具发出的预算是 %d）"
              % (part_rel, on_disk, args.resume_budget))
        # 放开发送预算必须**在点「继续」之前**：否则续传还是会断在同一个 budget 上，
        # 永远等不到 finish。
        server.send_budget = None
        tapped = _card_button(args, root_res, UI_RESUME, "r37-tap-resume-button")
        if tapped:
            since_resume = time.time() - 5
            second = wait_pull(server, t_res, f_res.file_id, since=since_resume,
                               timeout=_budget(args, 20), min_offset=1)
            got_offset = int(second["offset"]) if second is not None else None
            R.check("r37-resume-pull-carries-nonzero-offset",
                    second is not None and got_offset > 0,
                    "第二次 pull offset=%s（必须 > 0：收端先量 .part 再续，不是从头来）"
                    % got_offset)
            if on_disk is not None:
                R.check("r37-resume-offset-matches-bytes-already-on-disk",
                        got_offset == on_disk,
                        "offset=%s == 盘上 .part 的 %s 字节" % (got_offset, on_disk))
            else:
                R.check("r37-resume-offset-matches-bytes-already-on-disk",
                        got_offset is not None and 0 < got_offset <= args.resume_budget,
                        "run-as 不可用，只能做上界断言：offset=%s ∈ (0, %d]"
                        % (got_offset, args.resume_budget))
            fin_res = wait_finish(server, t_res, since=since_resume, timeout=_budget(args, 25))
            R.check("r37-resumed-transfer-finishes", fin_res is not None,
                    "续传跑完 ⇒ finish（收端把 .part rename 成正式文件）")
            if fs.available:
                R.check("r37-resumed-file-matches-source",
                        fs.sha256("%s/%s" % (root_res, root_res)) == f_res.sha256,
                        "续传后的整文件 sha256 与源一致（拼起来的字节没缺没重）")
                R.check("r37-part-is-gone-after-success",
                        not fs.exists("%s/%s.part" % (root_res, root_res)),
                        "rename 成功后 .part 不该还在")
    server.send_budget = None

    # ============================================================== R38 重启清扫
    phase("R38 杀进程重启 → 盘上的 .part 被 sweepStaleParts 扫掉")
    if not fs.available:
        R.log("跳过：run-as 不可用（%s），没有别的办法看盘上的 .part" % fs.reason)
    else:
        # 造一个没人能认领的 .part：重启后内存认领表是空的，启动清扫必须把它删掉。
        rel_orphan = "e2e-orphan/e2e-orphan.bin"
        planted = fs.put_file(rel_orphan + ".part", b"\x00" * 8192)
        R.check("r38-orphan-part-planted", planted, "已放 %s.part（8192B）" % rel_orphan)
        R.adb("shell", "am", "force-stop", R.PKG)
        time.sleep(2.0)
        restored = R.ensure_app_foreground(timeout=15)
        R.check("r38-app-restarted", bool(restored), "前台恢复为 %s" % restored)
        time.sleep(3.0)
        R.check("r38-startup-sweep-removed-the-orphan-part",
                not fs.exists(rel_orphan + ".part"),
                "重启后 .part 不在了（TransferManager.start() 自动跑一次清扫；"
                "跨进程续传本来就不存在 ⇒ 删掉不损失）")
        R.check("r38-sweep-left-no-parts-at-all", fs.parts() == [],
                "盘上 .part 清单 %s" % fs.parts())
    return None


def _tap_ui(args, label, check_name, timeout=6.0):
    """用 uiautomator dump 出来的 bounds 点一个文字节点（**绝不目测坐标**）。

    只在「同屏至多一个这样的按钮」时才用它；卡片上的按钮一律用 [_card_button]。
    """
    node = R.find_ui(text=label, timeout=timeout)
    ok = bool(node) and R.tap_node(node)
    R.check(check_name, ok, "在 dump 里找到 %r，点它的 bounds 中心 %s"
            % (label, node.center if node else None))
    return ok


# 卡片按钮离卡名（rootName）的纵向最大距离。真机实测（2026-10-09，Pixel 6，
# 单文件卡片）：卡名 y=384、按钮 y=564 ⇒ 同一张卡里按钮比卡名低 180px；
# 相邻卡片间隔 394px。取 420 既能容忍两行的长文件名，又不会串到下一张卡。
_CARD_BUTTON_MAX_DY = 420


def _card_button(args, root_name, label, check_name, timeout=8.0, desc=False):
    """点**某一张卡片**上的按钮：先用卡名（rootName）定位那张卡，再取它下方最近的按钮。

    为什么不能 `R.find_ui(text="接收")`：那是按 dump 顺序取第一个。会话页上会同时留着
    前面几个 phase 的卡片（真机实测同一屏 2 张卡 ⇒ 4 个按钮），于是「点 R35 的接收」会点到
    R40 那张卡上：同一次真机跑里 R35 的三条断言全红，而按钮功能完全正常 —— 逐张卡点一次的
    探针显示两张卡各自发出 `op=accept` 且 transferId 各自正确。
    """
    deadline = time.monotonic() + timeout
    while True:
        nodes = R.ui_all()
        heads = [n for n in nodes if (n.text or "").strip() == root_name]
        buttons = [n for n in nodes
                   if ((n.desc if desc else n.text) or "").strip() == label]
        for head in heads:
            if not head.center:
                continue
            near = [b for b in buttons if b.center
                    and 0 <= b.center[1] - head.center[1] <= _CARD_BUTTON_MAX_DY]
            if near:
                near.sort(key=lambda b: b.center[1] - head.center[1])
                ok = R.tap_node(near[0])
                R.check(check_name, ok, "卡 %r 上的 %r at %s（同屏 %d 个同名按钮，"
                        "按卡名下方的纵向距离挑最近的）"
                        % (root_name, label, near[0].center, len(buttons)))
                return ok
        if time.monotonic() >= deadline:
            break
        time.sleep(0.6)
    R.check(check_name, False,
            "卡 %r 上找不到 %r（同屏 %d 张卡的卡名 %s，%d 个同名按钮 %s）"
            % (root_name, label, len(heads), [h.center for h in heads],
               len(buttons), [b.center for b in buttons]))
    return False


def _ui_phase(args, peer, server, fs, material_dir):
    """R35/R39/R42 的 UI 面：卡片文案、两个按钮、拒绝与继续。"""
    phase("R35 UI：入向 offer 的「接收 / 拒绝」两个按钮")
    R.ensure_app_foreground()
    opened = R.open_chat(args.nick)
    R.check("ui-open-chat-with-fixture", opened,
            "会话列表里点开 %r（夹具先握过手并发过 file-ctl，会话一定存在）" % args.nick)

    # ---- 接收 ----
    t_ui = new_transfer_id("ui")
    root_ui = "e2e-manual.txt"
    f_ui = LocalFile(make_material(material_dir, "manual-src.txt", 3072, seed=61),
                     root_ui, "f-ui")
    register(server, f_ui)
    since = time.time()
    send_offer(peer, [f_ui], t_ui, root_name=root_ui, tag="-ui")
    node_accept = R.find_ui(text=UI_ACCEPT, timeout=_budget(args, 10)) if opened else None
    R.check("r35-offer-card-shows-accept-button", node_accept is not None,
            "非图片 offer 的卡片上有「%s」（file_accept）" % UI_ACCEPT)
    node_decline = R.find_ui(text=UI_DECLINE, timeout=3.0) if opened else None
    R.check("r35-offer-card-shows-decline-button", node_decline is not None,
            "同一张卡片上还有「%s」（file_decline）" % UI_DECLINE)
    if opened and node_accept:
        _card_button(args, root_ui, UI_ACCEPT, "r35-tap-accept-button")
        accepted = wait_any(peer, lambda e: _op(e) == OP_ACCEPT and _tid(e) == t_ui,
                            _budget(args, 12))
        R.check("r35-accept-button-sends-file-ctl-accept", accepted is not None,
                "点「%s」后 UDP 上出现 op=accept transferId=%s" % (UI_ACCEPT, t_ui[:12]))
        pull_ui = wait_pull(server, t_ui, f_ui.file_id, since=since, timeout=_budget(args, 15))
        R.check("r35-accept-button-starts-the-pull", pull_ui is not None,
                "同一条 transfer 立刻来了 pull（按钮 → acceptTransfer → pull 这条链是通的）")
        fin_ui = wait_finish(server, t_ui, since=since, timeout=_budget(args, 20))
        R.check("r35-manual-transfer-completes", fin_ui is not None,
                "finish transferId=%s" % t_ui[:12])
        if fs.available:
            landed = False
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline:
                if fs.exists("%s/%s" % (root_ui, root_ui)):
                    landed = True
                    break
                time.sleep(0.5)
            R.check("r43-manual-transfer-lands-under-downloads", landed,
                    "downloads/%s/%s 出现" % (root_ui, root_ui))

    # ---- 拒绝 ----
    t_rej = new_transfer_id("rej")
    root_rej = "e2e-declined.txt"
    f_rej = LocalFile(make_material(material_dir, "declined-src.txt", 1024, seed=62),
                      root_rej, "f-rej")
    register(server, f_rej)
    send_offer(peer, [f_rej], t_rej, root_name=root_rej, tag="-rej")
    if opened:
        node_decl = R.find_ui(text=UI_DECLINE, timeout=_budget(args, 10))
        R.check("r35-decline-button-visible-for-second-offer", node_decl is not None,
                "第二张卡片上也有「%s」" % UI_DECLINE)
        if node_decl:
            _card_button(args, root_rej, UI_DECLINE, "r35-tap-decline-button")
            declined = wait_any(peer, lambda e: _op(e) == OP_DECLINE and _tid(e) == t_rej,
                                _budget(args, 12))
            R.check("r35-decline-button-sends-file-ctl-decline", declined is not None,
                    "点「%s」后 UDP 上出现 op=decline transferId=%s" % (UI_DECLINE, t_rej[:12]))
            R.check("r35-declined-transfer-is-never-pulled",
                    wait_pull(server, t_rej, since=0.0, timeout=2.0) is None,
                    "decline 之后不该有任何 pull")
            if fs.available:
                R.check("r35-declined-transfer-writes-nothing",
                        not fs.exists("%s/%s" % (root_rej, root_rej)),
                        "downloads/%s 不存在" % root_rej)

    # ---- R42 对方已拒绝 ----
    phase("R42 撤回（我们发 file-ctl cancel）→ 收端「对方已拒绝」")
    t_cancel = new_transfer_id("cancel")
    root_cancel = "e2e-cancelled.txt"
    f_ca = LocalFile(make_material(material_dir, "cancel-src.txt", 1024, seed=63),
                     root_cancel, "f-ca")
    register(server, f_ca)
    send_offer(peer, [f_ca], t_cancel, root_name=root_cancel, tag="-cancel")
    time.sleep(1.5)
    # 「接收」的个数必须**只少这一个**：会话页上还留着前面几张仍是 Offered 的卡
    # （R39 的文件夹、R40 的越界条目），断言"整页没有接收"会把别人的卡算成自己的失败。
    acc_before = len([n for n in R.ui_all() if (n.text or "").strip() == UI_ACCEPT]) if opened else 0
    peer.send(peer.env(FILE_CTL, simple_payload(OP_CANCEL, t_cancel)))
    time.sleep(2.5)
    if opened:
        nodes = R.ui_all()
        R.check("r42-peer-cancel-shows-declined-state", R.ui_has(UI_DECLINED, nodes),
                "卡片变成「%s」（onPeerKilled：decline 与 cancel 本地处理相同）" % UI_DECLINED)
        acc_after = len([n for n in nodes if (n.text or "").strip() == UI_ACCEPT])
        R.check("r42-peer-cancel-removes-the-accept-button",
                acc_after == acc_before - 1,
                "撤回前同屏 %d 个「%s」，撤回后 %d 个（只该少这一张卡的）"
                % (acc_before, UI_ACCEPT, acc_after))

    # ---- R42 收端中止在途拉取 ----
    phase("R42 收端没有「取消」出口；在途拉取由对端撤回打断")
    # 矩阵第 42 行的 `common_cancel` 指**发端**卡片上那个图标
    # （`ChatScreen.kt:1160` `if (onCancel != null && card.mine)`）——收端（card.mine=false）
    # 的活动卡片不画它，用户也没有别的入口中止一次入向拉取（`cancelIncoming` 只被
    # `recallLocally` 调用）。所以这里如实断言"收端卡片没有这条出口"，再用夹具自己发
    # `file-ctl {op:cancel}` 收尾 —— 那正好走 2026-10-09 修掉的 `onPeerKilled`：
    # 撤回必须停掉在途 pulling、删掉 `.part`，卡片落成「对方已拒绝」。
    t_ab = new_transfer_id("abort")
    root_ab = "e2e-abort.bin"
    f_ab = LocalFile(make_material(material_dir, "abort-src.bin", 262144, seed=64),
                     root_ab, "f-ab")
    register(server, f_ab)
    # 这一步要跑一次 dump + 一次 fs 取证，窗口给足（262144B / 8KiB = 32 块）。
    server.delay_ms = max(800, int(args.slow_ms))
    since = time.time()
    send_offer(peer, [f_ab], t_ab, root_name=root_ab, purpose=PURPOSE_IMAGE, tag="-abort")
    pull_ab = wait_pull(server, t_ab, f_ab.file_id, since=since, timeout=_budget(args, 15))
    R.check("r42-slow-transfer-pull-arrived", pull_ab is not None,
            "慢链路（每 8KiB 停 %dms）下的第一次 pull" % server.delay_ms)
    if pull_ab is not None and opened:
        active = R.find_ui(text="正在接收", exact=False, timeout=_budget(args, 8))
        R.check("r42-progress-indicator-visible", active is not None,
                "卡片进入 Active（file_state_active_in =「正在接收 %d%%」）")
        nodes = R.ui_all()
        head = R.find_node(nodes, text=root_ab)
        band = [n for n in nodes if head and head.center and n.center
                and -40 <= n.center[1] - head.center[1] <= _CARD_BUTTON_MAX_DY]
        R.check("r42-received-active-card-has-no-cancel-entry",
                R.find_node(band, text=UI_CANCEL) is None
                and R.find_node(band, desc=UI_CANCEL) is None,
                "收端活动卡片上没有「%s」出口（ChatScreen.kt:1160 只给 card.mine 画；"
                "矩阵的 common_cancel 是发端那条）" % UI_CANCEL)
        if fs.available:
            R.check("r42-inflight-pull-keeps-its-part",
                    fs.exists("%s/%s.part" % (root_ab, root_ab)),
                    "没人中止 ⇒ downloads/%s/%s.part 留在盘上（缺口的可见后果）"
                    % (root_ab, root_ab))
        # 夹具侧撤回：onPeerKilled 必须停掉这条 pulling 并删掉 .part（2026-10-09 修复）。
        with server._lock:
            server.disconnects[:] = []       # 只数这次撤回之后的断流
        peer.send(peer.env(FILE_CTL, simple_payload(OP_CANCEL, t_ab)))
        time.sleep(3.0)
        with server._lock:
            cut = any(f["transferId"] == t_ab for (_, _, f) in server.incomplete) \
                or bool(server.disconnects)
        R.check("r42-peer-cancel-tears-the-pull-down", cut,
                "撤回后夹具侧看到连接断开/发流中断（onPeerKilled → cancelIncoming 停 pulling）")
        R.check("r42-peer-cancel-never-finishes",
                wait_finish(server, t_ab, since=since, timeout=2.0) is None,
                "撤回之后不该再 finish")
        if fs.available:
            R.check("r42-peer-cancel-discards-the-part",
                    not fs.exists("%s/%s.part" % (root_ab, root_ab)),
                    "撤回时 .part 一并删掉（discardParts=true）")
        R.check("r42-peer-cancel-card-becomes-declined",
                R.ui_has(UI_DECLINED, R.ui_all()),
                "卡片落成「%s」（pull 的 catch 认 cancelRef.canceled ⇒ Declined 而非 Failed）"
                % UI_DECLINED)
    server.delay_ms = 0

    # ---- R39 文件夹卡片文案 ----
    phase("R39 UI：文件夹卡片的「文件夹 / N 个文件」")
    t_fb = new_transfer_id("fold")
    root_fb = "e2e-folderview"
    fa = LocalFile(make_material(material_dir, "fv-a.txt", 512, seed=71), "one.txt", "f-fv-a")
    fb = LocalFile(make_material(material_dir, "fv-b.txt", 512, seed=72), "two.txt", "f-fv-b")
    register(server, fa, fb)
    send_offer(peer, [fa, fb], t_fb, root_name=root_fb, is_folder=True, tag="-fold")
    if opened:
        time.sleep(2.0)
        nodes = R.ui_all()
        R.check("r39-folder-badge-visible", R.ui_has(UI_FOLDER_BADGE, nodes),
                "卡片上有「%s」（transfer_folder_badge）" % UI_FOLDER_BADGE)
        R.check("r39-folder-count-visible", R.ui_has("2 个文件", nodes),
                "卡片上有「2 个文件」（transfer_folder_count）")
        R.check("r39-folder-root-name-visible", R.ui_has(root_fb, nodes),
                "卡片名字是 rootName=%r" % root_fb)
    # 收尾：撤回，别留卡片
    peer.send(peer.env(FILE_CTL, simple_payload(OP_CANCEL, t_fb)))
    return None


# ---------------------------------------------------------------------------
def extra_args(ap):
    ap.add_argument("--tcp-port", type=int, default=R.TCP_PORT_DEFAULT,
                    help="夹具 TCP 拉取服务端端口（写进 profile.tcpPort），默认 %(default)s；"
                         "17879 被其他进程占用时换一个")
    ap.add_argument("--skip-ui", action="store_true",
                    help="只测协议面：不 dump UI、不注入按键（「接收/拒绝/继续/取消」"
                         "四条按钮无从触发，相关断言会被跳过）")
    ap.add_argument("--skip-fs", action="store_true",
                    help="跳过 adb run-as 的落盘取证（release 包上 run-as 不可用）")
    ap.add_argument("--nick", default=R.DEFAULT_NICK,
                    help="夹具在手机上的昵称，默认 %(default)s")
    ap.add_argument("--resume-bytes", type=int, default=300000,
                    help="续传素材大小（要够大到能中途断流），默认 %(default)s")
    ap.add_argument("--resume-budget", type=int, default=100000,
                    help="续传第一次只发多少字节就断流，默认 %(default)s")
    ap.add_argument("--slow-ms", type=int, default=400,
                    help="慢链路模式每 8KiB 的等待毫秒（用来抓「取消」的窗口），默认 %(default)s")
    ap.set_defaults(secs=420)


if __name__ == "__main__":
    sys.exit(R.main_wrapper(run, description=__doc__, extra_args=extra_args))
