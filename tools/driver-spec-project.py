#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""driver-specs.json -> DriverSpecTable.generated.kt 投影器 + 漂移门禁。

方向：JSON 是真源，代码是投影产物（与 design-spec-project.py 的「代码→JSON」相反，
但漂移检测两头通用：重新生成一遍，与工作区不一致就判红）。

为什么校验必须前移到 codegen
----------------------------
身份卡原先是手写的 Kotlin 表，`Spec.init` 的两条 require 在构造期兜底。JSON 一旦成为真源，
拼错键、缺字段、role/mechanism 写错、支持性与通道不一致，全是「编译通过、派单时才炸」甚至
静默回落默认。所以这里在生成前就把这些判据跑一遍，任一条不过就不出包；Kotlin 侧的 require
保留作双保险（它是公开 data class 的构造契约，别人 new 出来也要挡）。

用法（工作目录 = 仓库根）：
    python3 tools/driver-spec-project.py --write --root .     # 从 JSON 重新投影 .kt
    python3 tools/driver-spec-project.py --check --root .     # 门禁：漂移即退出码 1
    python3 tools/driver-spec-project.py --self-test          # 量具自检，不碰工作区
"""
import argparse
import json
import os
import sys
from typing import Any, Dict, List, Tuple

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC_REL = "agent-contract/driver-specs.json"
OUT_REL = "agent-contract/src/main/kotlin/com/kapp/tenon/agent/drivers/DriverSpecTable.generated.kt"

TOP_KEYS = {"schemaVersion", "note", "drivers", "sandboxHomeLinks", "launch", "argvPlaceholders", "probeLimits"}
DRIVER_KEYS = {
    "key", "displayName", "vendor", "role", "supportsPerTaskModel", "modelFlag",
    "modelMechanism", "shortName", "workerPriority", "reviewerPriority",
    "envKeys", "networkPolicy", "sandboxMode", "discovery", "argv", "modelProbe", "authProbe",
}
ROLES = {"WORKER", "REVIEWER", "BOTH"}
MECHANISMS = {"FLAG", "CONFIG_PATCH", "NONE"}
NETWORK_POLICIES = {"OPEN", "AIR_GAPPED", "LOOPBACK"}
SANDBOX_MODE_KEYS = {"envVar", "allowed", "default"}
DISCOVERY_KEYS = {"envVar", "bin", "extraBinRelative", "extraPathDirs"}
LAUNCH_KEYS = {"nodeExtensions", "note"}
HOME_LINK_KEYS = {"path", "owner", "note"}
ARGV_KEYS = {"plain", "sandbox", "resumePlain", "resumeSandbox", "note"}
MODEL_PROBE_KINDS = {"LIST", "CONFIG", "NONE"}
MODEL_PROBE_KEYS = {"kind", "argv", "parser", "catalogSource", "note"}
CATALOG_SOURCE_KEYS = {"provider", "driver", "prefix"}
# 模型探针 parser 是「机制 id」：表里只放 id，函数实现在 DriverModelCatalog 注册。
# codegen 钉 id 在这个集合里，守卫再钉代码侧注册表与它严格相等（新增 parser 必须两头同步）。
MODEL_PARSERS = {"two-column", "codex-json", "dsh-default"}
PROBE_LIMIT_KEYS = {"modelCatalogTtlMs", "modelProbeTimeoutMs", "note"}
# authProbe 三形态（方案 §2.3 + agy 的 CLI_MARKER 实测形态）：
#   CLI_CMD   跑厂家只读 status 子命令，signals 有序（首个命中即返回），blank 信号判空输出；
#   CLI_MARKER 跑 models（agy），exit 0 = 已登录，marker 命中 = 未登录，其余 UNKNOWN；
#   FILE      凭证文件/env 弱判据（dsh），只给 HINT；
#   NONE      没有可信判据（codebuddy/zcode），恒 UNKNOWN/HINT。
AUTH_PROBE_KINDS = {"CLI_CMD", "CLI_MARKER", "FILE", "NONE"}
AUTH_PROBE_KEYS = {
    "kind", "argv", "signals", "authenticatedOn", "notAuthenticatedMarker", "markerIgnoreCase",
    "credentialKeys", "credentialFile", "golden", "authenticatedIsHint", "note",
}
AUTH_SIGNAL_KEYS = {"verdict", "regex", "ignoreCase", "blank"}
AUTH_VERDICTS = {"AUTHENTICATED", "NOT_AUTHENTICATED", "UNKNOWN"}
GOLDEN_KEYS = {"exitCode", "text", "verdict"}
AUTH_CLI_PATH = "${cliPath}"
# 裸路径不得静默放开的提权旗标。zcode 的 `--mode yolo` 不在其中：它没有沙箱/裸分支差异，
# yolo 是它唯一的运行形态（且它不支持按次模型），不是「在一次性 worktree 里才放开」的那种。
PRIVILEGE_FLAGS = {"bypassPermissions", "--dangerously-skip-permissions", "-y"}
PLACEHOLDER_RE = __import__("re").compile(r"^\$\{([a-zA-Z]+)\}$")
# 沙箱 HOME 挂载的白名单反向纪律：这些敏感目录永远不许进清单。
FORBIDDEN_HOME_LINKS = {".ssh", ".gnupg", ".aws", ".workbuddy", ".workbuddy-ai"}
SUPPORTED_SCHEMA = 1


class SpecError(Exception):
    """真源不合法或投影漂移。"""


# ── 校验（生成前跑，等价于 kotlinx `ignoreUnknownKeys = false` + Spec.init） ──
def _require_keys(obj: Dict[str, Any], allowed: set, label: str) -> None:
    unknown = set(obj) - allowed
    if unknown:
        raise SpecError(f"{label}: 未知键 {sorted(unknown)}（键写错不许静默忽略）")
    missing = allowed - set(obj)
    # driver 的 10 个字段必须全给（含显式 null），否则 JSON 与构造参数无法一一对应。
    if missing:
        raise SpecError(f"{label}: 缺字段 {sorted(missing)}")


def validate(spec: Dict[str, Any]) -> List[Dict[str, Any]]:
    _require_keys(spec, TOP_KEYS, "driver-specs.json")
    if spec.get("schemaVersion") != SUPPORTED_SCHEMA:
        raise SpecError(f"schemaVersion={spec.get('schemaVersion')!r}，本投影器只认 {SUPPORTED_SCHEMA}")
    drivers = spec.get("drivers")
    if not isinstance(drivers, list) or not drivers:
        raise SpecError("drivers 必须是非空数组")

    seen = set()
    for i, d in enumerate(drivers):
        label = f"drivers[{i}]"
        if not isinstance(d, dict):
            raise SpecError(f"{label}: 必须是对象")
        _require_keys(d, DRIVER_KEYS, label)

        key = d["key"]
        if not isinstance(key, str) or not key.strip():
            raise SpecError(f"{label}.key 必须是非空字符串")
        if key in seen:
            raise SpecError(f"{label}.key={key!r} 重复")
        seen.add(key)

        for field in ("displayName", "vendor"):
            if not isinstance(d[field], str) or not d[field].strip():
                raise SpecError(f"{label}.{field} 必须是非空字符串")

        if d["role"] not in ROLES:
            raise SpecError(f"{label}.role={d['role']!r} 不在 {sorted(ROLES)}")
        if not isinstance(d["supportsPerTaskModel"], bool):
            raise SpecError(f"{label}.supportsPerTaskModel 必须是布尔")

        flag = d["modelFlag"]
        if flag is not None and (not isinstance(flag, str) or not flag.strip()):
            raise SpecError(f"{label}.modelFlag 必须是非空字符串或 null")
        mech = d["modelMechanism"]
        if mech is not None and mech not in MECHANISMS:
            raise SpecError(f"{label}.modelMechanism={mech!r} 不在 {sorted(MECHANISMS)} 或 null")
        short = d["shortName"]
        if short is not None and (not isinstance(short, str) or not short.strip()):
            raise SpecError(f"{label}.shortName 必须是非空字符串或 null")

        for field in ("workerPriority", "reviewerPriority"):
            v = d[field]
            if v is not None and (not isinstance(v, int) or isinstance(v, bool)):
                raise SpecError(f"{label}.{field} 必须是整数或 null")

        # 复刻 CliDriverSpecs.Spec.init 的两条不变式（effectiveModelMechanism = mech ?: (flag ? FLAG : NONE)）
        effective = mech if mech is not None else ("FLAG" if flag is not None else "NONE")
        supports = d["supportsPerTaskModel"]
        if supports != (effective != "NONE"):
            raise SpecError(
                f"{label}: supportsPerTaskModel={supports} 与通道 {effective} 不一致"
                "（NONE 即不支持）"
            )
        if (flag is not None) != (effective == "FLAG"):
            raise SpecError(f"{label}: modelFlag 只属于 FLAG 通道，当前通道 {effective}")

        # ── 行为声明面校验 ──
        env_keys = d["envKeys"]
        if not isinstance(env_keys, list) or any(
            not isinstance(x, str) or not x.strip() for x in env_keys
        ):
            raise SpecError(f"{label}.envKeys 必须是非空字符串数组（可为空表）")
        if len(set(env_keys)) != len(env_keys):
            raise SpecError(f"{label}.envKeys 有重复键")

        if d["networkPolicy"] not in NETWORK_POLICIES:
            raise SpecError(f"{label}.networkPolicy={d['networkPolicy']!r} 不在 {sorted(NETWORK_POLICIES)}")

        sm = d["sandboxMode"]
        if sm is not None:
            if not isinstance(sm, dict):
                raise SpecError(f"{label}.sandboxMode 必须是对象或 null")
            unknown_sm = set(sm) - SANDBOX_MODE_KEYS
            missing_sm = SANDBOX_MODE_KEYS - set(sm)
            if unknown_sm or missing_sm:
                raise SpecError(
                    f"{label}.sandboxMode 键不符：未知 {sorted(unknown_sm)} / 缺 {sorted(missing_sm)}"
                )
            if not isinstance(sm["envVar"], str) or not sm["envVar"].strip():
                raise SpecError(f"{label}.sandboxMode.envVar 必须是非空字符串")
            if not isinstance(sm["allowed"], list) or not sm["allowed"] or any(
                not isinstance(x, str) or not x.strip() for x in sm["allowed"]
            ):
                raise SpecError(f"{label}.sandboxMode.allowed 必须是非空字符串数组")
            if len(set(sm["allowed"])) != len(sm["allowed"]):
                raise SpecError(f"{label}.sandboxMode.allowed 有重复值")
            if sm["default"] not in sm["allowed"]:
                raise SpecError(f"{label}.sandboxMode.default 必须在 allowed 内（否则默认就是非法值）")

        disc = d["discovery"]
        if not isinstance(disc, dict):
            raise SpecError(f"{label}.discovery 必须是对象")
        unknown_disc = set(disc) - DISCOVERY_KEYS
        missing_disc = DISCOVERY_KEYS - set(disc)
        if unknown_disc or missing_disc:
            raise SpecError(
                f"{label}.discovery 键不符：未知 {sorted(unknown_disc)} / 缺 {sorted(missing_disc)}"
            )
        for field in ("envVar", "bin"):
            v = disc[field]
            if not isinstance(v, str) or not v.strip():
                raise SpecError(f"{label}.discovery.{field} 必须是非空字符串")
        ebr = disc["extraBinRelative"]
        if ebr is not None and (not isinstance(ebr, str) or not ebr.strip() or ebr.startswith("/")):
            raise SpecError(f"{label}.discovery.extraBinRelative 必须是相对路径或 null")
        epd = disc["extraPathDirs"]
        if not isinstance(epd, list) or any(
            not isinstance(x, str) or not x.strip() or x.startswith("/") for x in epd
        ):
            raise SpecError(f"{label}.discovery.extraPathDirs 必须是相对 HOME 的目录数组")

        _validate_argv(d, spec, label)
        _validate_model_probe(d, spec, label)
        _validate_auth_probe(d, label)

    _validate_launch(spec)
    _validate_home_links(spec, drivers)
    _validate_probe_limits(spec)
    return drivers


def _validate_model_probe(d: Dict[str, Any], spec: Dict[str, Any], label: str) -> None:
    """模型探针声明校验：kind 决定形态，parser 必须是已注册机制 id，CONFIG 必须有 catalogSource。"""
    mp = d["modelProbe"]
    if not isinstance(mp, dict):
        raise SpecError(f"{label}.modelProbe 必须是对象")
    unknown = set(mp) - MODEL_PROBE_KEYS
    if unknown:
        raise SpecError(f"{label}.modelProbe 未知键 {sorted(unknown)}")
    kind = mp.get("kind")
    if kind not in MODEL_PROBE_KINDS:
        raise SpecError(f"{label}.modelProbe.kind={kind!r} 不在 {sorted(MODEL_PROBE_KINDS)}")

    if kind == "NONE":
        # NONE 只许 kind + note：带了 argv/parser/catalogSource 就是自相矛盾的死声明。
        extra = set(mp) - {"kind", "note"}
        if extra:
            raise SpecError(f"{label}.modelProbe.kind=NONE 不许带 {sorted(extra)}")
        if not isinstance(mp.get("note"), str) or not mp["note"].strip():
            raise SpecError(f"{label}.modelProbe.kind=NONE 必须带 note 说明为什么不探")
        return

    # LIST / CONFIG 必须有非空 argv 与已注册 parser。
    argv = mp.get("argv")
    if not isinstance(argv, list) or not argv or any(
        not isinstance(x, str) or not x.strip() for x in argv
    ):
        raise SpecError(f"{label}.modelProbe.argv 必须是非空字符串数组")
    parser = mp.get("parser")
    if parser not in MODEL_PARSERS:
        raise SpecError(f"{label}.modelProbe.parser={parser!r} 不在已注册机制 {sorted(MODEL_PARSERS)}")

    if kind == "CONFIG":
        src = mp.get("catalogSource")
        if not isinstance(src, dict):
            raise SpecError(f"{label}.modelProbe.kind=CONFIG 必须有 catalogSource 对象")
        unknown_src = set(src) - CATALOG_SOURCE_KEYS
        missing_src = CATALOG_SOURCE_KEYS - set(src)
        if unknown_src or missing_src:
            raise SpecError(
                f"{label}.modelProbe.catalogSource 键不符：未知 {sorted(unknown_src)} / 缺 {sorted(missing_src)}"
            )
        for f in CATALOG_SOURCE_KEYS:
            v = src[f]
            if not isinstance(v, str) or not v.strip():
                raise SpecError(f"{label}.modelProbe.catalogSource.{f} 必须是非空字符串")
        keys = {x["key"] for x in spec["drivers"]}
        if src["driver"] not in keys:
            raise SpecError(
                f"{label}.modelProbe.catalogSource.driver={src['driver']!r} 不是内置驱动 key"
                "（候选目录必须来自已知驱动）"
            )
    else:
        # LIST 不许带 catalogSource（那是 CONFIG 的推导通道）。
        if "catalogSource" in mp:
            raise SpecError(f"{label}.modelProbe.kind=LIST 不许带 catalogSource")


def _validate_auth_probe(d: Dict[str, Any], label: str) -> None:
    """登录态探针声明校验：kind 定形态，正则预编译，golden 结构合法且覆盖多 verdict。

    判定逻辑（signals 顺序、退出码纪律、证据强度映射）是机制，留代码；这里只保证
    声明本身自洽、正则能编译、golden 不是全 UNKNOWN 的假夹具。golden 的 verdict
    正确性由 Kotlin 测试用生产 classify 逐条验证（合成输入，过 envDependentTestGate）。
    """
    import re as _re
    ap = d["authProbe"]
    if not isinstance(ap, dict):
        raise SpecError(f"{label}.authProbe 必须是对象")
    unknown = set(ap) - AUTH_PROBE_KEYS
    if unknown:
        raise SpecError(f"{label}.authProbe 未知键 {sorted(unknown)}")
    kind = ap.get("kind")
    if kind not in AUTH_PROBE_KINDS:
        raise SpecError(f"{label}.authProbe.kind={kind!r} 不在 {sorted(AUTH_PROBE_KINDS)}")

    allowed = {
        "CLI_CMD": {"kind", "argv", "signals", "golden", "authenticatedIsHint", "note"},
        "CLI_MARKER": {
            "kind", "argv", "authenticatedOn", "notAuthenticatedMarker",
            "markerIgnoreCase", "golden",
        },
        "FILE": {"kind", "credentialKeys", "credentialFile", "note"},
        "NONE": {"kind", "note"},
    }[kind]
    extra = set(ap) - allowed
    if extra:
        raise SpecError(f"{label}.authProbe.kind={kind} 不许带 {sorted(extra)}")

    def _argv_of() -> None:
        argv = ap.get("argv")
        if not isinstance(argv, list) or not argv or any(
            not isinstance(x, str) or not x.strip() for x in argv
        ):
            raise SpecError(f"{label}.authProbe.argv 必须是非空字符串数组")
        if argv.count(AUTH_CLI_PATH) != 1 or argv[0] != AUTH_CLI_PATH:
            raise SpecError(
                f"{label}.authProbe.argv 必须以 {AUTH_CLI_PATH} 开头且恰好一次"
                "（首项为 CLI 可执行路径，由发现器注入）"
            )

    def _golden_of() -> None:
        golden = ap.get("golden")
        if not isinstance(golden, list) or not golden:
            raise SpecError(f"{label}.authProbe.golden 必须是非空数组")
        verdicts = set()
        for i, g in enumerate(golden):
            tag = f"{label}.authProbe.golden[{i}]"
            if not isinstance(g, dict):
                raise SpecError(f"{tag}: 必须是对象")
            unknown_g = set(g) - GOLDEN_KEYS
            if unknown_g:
                raise SpecError(f"{tag}: 未知键 {sorted(unknown_g)}")
            ec = g.get("exitCode")
            if not isinstance(ec, int) or isinstance(ec, bool):
                raise SpecError(f"{tag}.exitCode 必须是整数（含 -1 哨兵）")
            if not isinstance(g.get("text"), str):
                raise SpecError(f"{tag}.text 必须是字符串（空串是合法样本）")
            if g.get("verdict") not in AUTH_VERDICTS:
                raise SpecError(f"{tag}.verdict={g.get('verdict')!r} 不在 {sorted(AUTH_VERDICTS)}")
            verdicts.add(g["verdict"])
        if len(verdicts) < 2:
            raise SpecError(
                f"{label}.authProbe.golden 只覆盖 {verdicts} —— 至少两种 verdict 才是有效夹具"
                "（全 UNKNOWN 会让『改坏正则』无人发现）"
            )

    if kind == "CLI_CMD":
        _argv_of()
        signals = ap.get("signals")
        if not isinstance(signals, list) or not signals:
            raise SpecError(f"{label}.authProbe.signals 必须是非空数组")
        for i, s in enumerate(signals):
            tag = f"{label}.authProbe.signals[{i}]"
            if not isinstance(s, dict):
                raise SpecError(f"{tag}: 必须是对象")
            unknown_s = set(s) - AUTH_SIGNAL_KEYS
            if unknown_s:
                raise SpecError(f"{tag}: 未知键 {sorted(unknown_s)}")
            if s.get("verdict") not in AUTH_VERDICTS - {"UNKNOWN"}:
                raise SpecError(f"{tag}.verdict={s.get('verdict')!r} 必须是具体态（UNKNOWN 是兜底，不做信号）")
            has_regex = "regex" in s
            has_blank = s.get("blank") is True
            if has_regex == has_blank:
                raise SpecError(f"{tag}: regex 与 blank 必须恰好有一个")
            if has_regex:
                rx = s["regex"]
                if not isinstance(rx, str) or not rx.strip():
                    raise SpecError(f"{tag}.regex 必须是非空字符串")
                # 预编译抓语法错；Kotlin Regex 与 Python re 对这些简单模式同义。
                flags = _re.IGNORECASE if s.get("ignoreCase") is True else 0
                try:
                    _re.compile(rx, flags)
                except _re.error as e:
                    raise SpecError(f"{tag}.regex 编译失败：{e}")
        if "authenticatedIsHint" in ap and not isinstance(ap["authenticatedIsHint"], bool):
            raise SpecError(
                f"{label}.authProbe.authenticatedIsHint 必须是布尔"
                "（true = 命中「已登录」信号也不足以画绿灯，只降级为 HINT）"
            )
        _golden_of()
    elif kind == "CLI_MARKER":
        _argv_of()
        if ap.get("authenticatedOn") != "EXIT_ZERO":
            raise SpecError(f"{label}.authProbe.authenticatedOn 必须是 EXIT_ZERO")
        marker = ap.get("notAuthenticatedMarker")
        if not isinstance(marker, str) or not marker.strip():
            raise SpecError(f"{label}.authProbe.notAuthenticatedMarker 必须是非空字符串")
        if not isinstance(ap.get("markerIgnoreCase"), bool):
            raise SpecError(f"{label}.authProbe.markerIgnoreCase 必须是布尔")
        _golden_of()
    elif kind == "FILE":
        keys = ap.get("credentialKeys")
        if not isinstance(keys, list) or not keys or any(
            not isinstance(k, str) or not k.strip() for k in keys
        ):
            raise SpecError(f"{label}.authProbe.credentialKeys 必须是非空字符串数组")
        cf = ap.get("credentialFile")
        if not isinstance(cf, str) or not cf.strip() or cf.startswith("/"):
            raise SpecError(f"{label}.authProbe.credentialFile 必须是相对 HOME 的路径")
        if not isinstance(ap.get("note"), str) or not ap["note"].strip():
            raise SpecError(f"{label}.authProbe.kind=FILE 必须带 note 说明判据")
    else:  # NONE
        if not isinstance(ap.get("note"), str) or not ap["note"].strip():
            raise SpecError(f"{label}.authProbe.kind=NONE 必须带 note 说明为何不判")


def _validate_probe_limits(spec: Dict[str, Any]) -> None:
    limits = spec.get("probeLimits")
    if not isinstance(limits, dict):
        raise SpecError("probeLimits 必须是对象")
    unknown = set(limits) - PROBE_LIMIT_KEYS
    if unknown:
        raise SpecError(f"probeLimits: 未知键 {sorted(unknown)}")
    for field in ("modelCatalogTtlMs", "modelProbeTimeoutMs"):
        v = limits.get(field)
        if not isinstance(v, int) or isinstance(v, bool) or v <= 0:
            raise SpecError(f"probeLimits.{field} 必须是正整数（毫秒）")


def _validate_argv(d: Dict[str, Any], spec: Dict[str, Any], label: str) -> None:
    """argv 模板校验：占位符合法、prompt 恰好一次、裸路径无提权旗标。"""
    argv = d["argv"]
    if not isinstance(argv, dict):
        raise SpecError(f"{label}.argv 必须是对象")
    unknown = set(argv) - ARGV_KEYS
    if unknown:
        raise SpecError(f"{label}.argv 未知键 {sorted(unknown)}")
    if "plain" not in argv:
        raise SpecError(f"{label}.argv 必须给 plain 模板")

    allowed_ph = spec.get("argvPlaceholders")
    if not isinstance(allowed_ph, list) or not allowed_ph:
        raise SpecError("argvPlaceholders 必须是非空数组")

    templates = [(name, argv[name]) for name in ("plain", "sandbox", "resumePlain", "resumeSandbox")
                 if name in argv]
    for name, tokens in templates:
        tl = f"{label}.argv.{name}"
        if not isinstance(tokens, list) or not tokens or any(
            not isinstance(t, str) or not t.strip() for t in tokens
        ):
            raise SpecError(f"{tl} 必须是非空字符串数组")
        # 占位符白名单 + prompt 恰好一次。
        prompt_hits = 0
        for t in tokens:
            m = PLACEHOLDER_RE.match(t)
            if m is None:
                if "${" in t:
                    raise SpecError(f"{tl} 含非法占位符写法：{t!r}")
                continue
            ph = m.group(1)
            if ph not in allowed_ph:
                raise SpecError(f"{tl} 含未声明占位符 ${{{ph}}}")
            if ph == "prompt":
                prompt_hits += 1
        if prompt_hits != 1:
            raise SpecError(f"{tl} 的 ${{prompt}} 必须恰好出现一次，实际 {prompt_hits}")
        # resume 模板必须带 ${session}（否则「续跑」没带会话 id = 从零重跑）。
        if name.startswith("resume") and "${session}" not in tokens:
            raise SpecError(f"{tl} 必须含 ${{session}}（续跑不带会话 id 等于从零重跑）")
        # 裸路径（plain）不许出现提权旗标：写操作只能在一次性 worktree 里放开。
        # 只有声明了 sandbox 覆盖模板的驱动才检查——没分支差异的驱动（codex/opencode/dsh/zcode）
        # 的 plain 就是它唯一形态，用「是否比 sandbox 多放开」来判才不会误伤 zcode 的 --mode yolo。
        if name == "plain" and "sandbox" in argv:
            for t in tokens:
                if t in PRIVILEGE_FLAGS:
                    raise SpecError(f"{tl} 含提权旗标 {t!r}——裸路径是机主自己的仓，不许静默放开")

    # 「按次选模型」的通道必须在新会话模板里真的能表达出来：FLAG 靠 `${model}`，
    # CONFIG_PATCH 靠 `${patch}`。缺了占位符，`DriverModelFlags` / dsh patch 算出来的参数
    # 会被 `CliArgv.render` 静默丢掉 —— 用户配了模型却跑默认，全线无报错。本仓最贵的一类。
    #
    # resume 模板例外：会话已绑定模型，且有些 CLI 的 `--resume` 不认 `--model`（硬塞会报错）。
    # 所以只要求「新会话」模板（plain / sandbox）带通道占位符，续跑模板允许不带。
    eff = d["modelMechanism"] or ("FLAG" if d["modelFlag"] is not None else "NONE")
    required = {"FLAG": "${model}", "CONFIG_PATCH": "${patch}"}.get(eff)
    if required is not None:
        for name, tokens in templates:
            if required == "${model}" and name.startswith("resume"):
                continue
            if required not in tokens:
                raise SpecError(
                    f"{label}.argv.{name} 缺 {required}（通道 {eff}）："
                    "占位符不到场，按次模型/补丁参数会被静默丢弃"
                )


def _validate_launch(spec: Dict[str, Any]) -> None:
    launch = spec.get("launch")
    if not isinstance(launch, dict):
        raise SpecError("launch 必须是对象")
    unknown = set(launch) - LAUNCH_KEYS
    if unknown:
        raise SpecError(f"launch: 未知键 {sorted(unknown)}")
    exts = launch.get("nodeExtensions")
    if not isinstance(exts, list) or not exts or any(
        not isinstance(x, str) or not x.startswith(".") for x in exts
    ):
        raise SpecError("launch.nodeExtensions 必须是形如 ['.cjs', '.js'] 的非空数组")


def _validate_home_links(spec: Dict[str, Any], drivers: List[Dict[str, Any]]) -> None:
    """顶层 sandboxHomeLinks：顺序即投影顺序；owner 必须归属某家或 shared（带理由）。"""
    links = spec.get("sandboxHomeLinks")
    if not isinstance(links, list) or not links:
        raise SpecError("sandboxHomeLinks 必须是非空数组")
    keys = {d["key"] for d in drivers}
    seen = set()
    for i, link in enumerate(links):
        label = f"sandboxHomeLinks[{i}]"
        if not isinstance(link, dict):
            raise SpecError(f"{label}: 必须是对象")
        unknown = set(link) - HOME_LINK_KEYS
        if unknown:
            raise SpecError(f"{label}: 未知键 {sorted(unknown)}")
        if "path" not in link or "owner" not in link:
            raise SpecError(f"{label}: 必须给 path 与 owner")
        path = link["path"]
        if not isinstance(path, str) or not path.startswith(".") or len(path) < 2:
            raise SpecError(f"{label}.path 必须是形如 '.xxx' 的 HOME 条目")
        if "/" in path or "\\" in path:
            raise SpecError(f"{label}.path 不许含路径分隔符（只挂 HOME 一级目录/文件）")
        if path in FORBIDDEN_HOME_LINKS:
            raise SpecError(f"{label}.path={path!r} 是宿主敏感目录，不许进沙箱挂载白名单")
        if path in seen:
            raise SpecError(f"{label}.path={path!r} 重复")
        seen.add(path)
        owner = link["owner"]
        if owner != "shared" and owner not in keys:
            raise SpecError(f"{label}.owner={owner!r} 既不是内置驱动 key 也不是 shared")
        if owner == "shared" and not link.get("note"):
            raise SpecError(f"{label}: shared 项必须带 note 理由（半年后没人敢改就得靠它）")


# ── 投影 ──
def _kt_string(s: str) -> str:
    # 基础转义防手改引入非法字面量。$ 也必须转义：argv 模板里的 "${prompt}" 若原样投影，
    # Kotlin 会把它当字符串插值、编译期找不叫 prompt 的标识符而红（本仓 KDoc 注释吃 */ 同类陷阱）。
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("$", "\\$") + '"'


def _kt_string_list(items: List[str], indent: str) -> str:
    if not items:
        return "emptyList()"
    return "listOf(\n" + "".join(f"{indent}    {_kt_string(x)},\n" for x in items) + f"{indent})"


def render(drivers: List[Dict[str, Any]], spec: Dict[str, Any]) -> str:
    header = (
        "// SPDX-License-Identifier: GPL-3.0-only\n"
        "\n"
        "// DO NOT EDIT —— 由 `./gradlew :driverSpecProject` 从 agent-contract/driver-specs.json 投影生成。\n"
        "// 手改会在 :driverSpecDrift 判红；要改规格请改 JSON 后重新投影。\n"
        "package com.kapp.tenon.agent.drivers\n"
        "\n"
        "/**\n"
        " * 内置 CLI 驱动数据行，由 JSON 真源投影。公开面 [CliDriverSpecs] 只读引用本表，\n"
        " * 枚举与 require 在 Kotlin 侧保留作双保险。行为声明（envKeys/networkPolicy/\n"
        " * sandboxMode/homeLinks）也在这里投影，生产代码经 [CliDriverSpecs] 的公开访问器消费。\n"
        " */\n"
        "internal val driverSpecRows: List<CliDriverSpecs.Spec> = listOf(\n"
    )
    lines = [header]
    for d in drivers:
        lines.append("    CliDriverSpecs.Spec(\n")
        lines.append(f"        key = {_kt_string(d['key'])},\n")
        lines.append(f"        displayName = {_kt_string(d['displayName'])},\n")
        lines.append(f"        vendor = {_kt_string(d['vendor'])},\n")
        lines.append(f"        role = CliDriverSpecs.Role.{d['role']},\n")
        lines.append(f"        supportsPerTaskModel = {'true' if d['supportsPerTaskModel'] else 'false'},\n")
        if d["modelFlag"] is not None:
            lines.append(f"        modelFlag = {_kt_string(d['modelFlag'])},\n")
        if d["modelMechanism"] is not None:
            lines.append(f"        modelMechanism = CliDriverSpecs.ModelMechanism.{d['modelMechanism']},\n")
        if d["shortName"] is not None:
            lines.append(f"        shortName = {_kt_string(d['shortName'])},\n")
        if d["workerPriority"] is not None:
            lines.append(f"        workerPriority = {d['workerPriority']},\n")
        if d["reviewerPriority"] is not None:
            lines.append(f"        reviewerPriority = {d['reviewerPriority']},\n")
        lines.append("    ),\n")
    lines.append(")\n")

    # 每家的凭证白名单（键名，永不含值）。
    lines.append("\n/** 每家子进程凭证注入白名单的变量名（只含名字，值绝不进投影）。 */\n")
    lines.append("internal val driverEnvKeysByKey: Map<String, List<String>> = linkedMapOf(\n")
    for d in drivers:
        lines.append(f"    {_kt_string(d['key'])} to {_kt_string_list(d['envKeys'], '        ')},\n")
    lines.append(")\n")

    # 每家的沙箱网络策略。
    lines.append("\n/** 每家沙箱网络策略（全集 OPEN：模型 API 必须出网，OPEN 已含 loopback）。 */\n")
    lines.append("internal val driverNetworkPolicyByKey: Map<String, CliDriverSpecs.NetworkPolicy> = linkedMapOf(\n")
    for d in drivers:
        lines.append(f"    {_kt_string(d['key'])} to CliDriverSpecs.NetworkPolicy.{d['networkPolicy']},\n")
    lines.append(")\n")

    # sandboxMode：只有声明了的对象才进表，其余家缺席（不是 null 占位）。
    lines.append("\n/** 需要 --sandbox 取值的驱动（当前仅 codex）：env 键 / 合法值 / 默认值。 */\n")
    lines.append("internal val driverSandboxModeByKey: Map<String, CliDriverSpecs.SandboxModeSpec> = linkedMapOf(\n")
    for d in drivers:
        sm = d["sandboxMode"]
        if sm is None:
            continue
        allowed = _kt_string_list(sm["allowed"], "            ")
        lines.append(f"    {_kt_string(d['key'])} to CliDriverSpecs.SandboxModeSpec(\n")
        lines.append(f"        envVar = {_kt_string(sm['envVar'])},\n")
        lines.append(f"        allowed = {allowed},\n")
        lines.append(f"        default = {_kt_string(sm['default'])},\n")
        lines.append("    ),\n")
    lines.append(")\n")

    # 每家的可执行文件发现声明。
    lines.append("\n/** 每家可执行文件发现声明（机制在 CliDiscovery，值在这里）。 */\n")
    lines.append("internal val driverDiscoveryByKey: Map<String, CliDriverSpecs.DiscoverySpec> = linkedMapOf(\n")
    for d in drivers:
        disc = d["discovery"]
        ebr = _kt_string(disc["extraBinRelative"]) if disc["extraBinRelative"] is not None else "null"
        epd = _kt_string_list(disc["extraPathDirs"], "            ")
        lines.append(f"    {_kt_string(d['key'])} to CliDriverSpecs.DiscoverySpec(\n")
        lines.append(f"        envVar = {_kt_string(disc['envVar'])},\n")
        lines.append(f"        bin = {_kt_string(disc['bin'])},\n")
        lines.append(f"        extraBinRelative = {ebr},\n")
        lines.append(f"        extraPathDirs = {epd},\n")
        lines.append("    ),\n")
    lines.append(")\n")

    # 每家的 argv 模板骨架（四份可选）。
    lines.append("\n/** 每家 argv 模板骨架；动态 token 由 CliArgv.render 按占位符替换。 */\n")
    lines.append("internal val driverArgvByKey: Map<String, CliDriverSpecs.ArgvSpec> = linkedMapOf(\n")
    for d in drivers:
        argv = d["argv"]
        plain = _kt_string_list(argv["plain"], "            ")
        sandbox = _kt_string_list(argv["sandbox"], "            ") if "sandbox" in argv else "null"
        resume_plain = _kt_string_list(argv["resumePlain"], "            ") if "resumePlain" in argv else "null"
        resume_sandbox = _kt_string_list(argv["resumeSandbox"], "            ") if "resumeSandbox" in argv else "null"
        lines.append(f"    {_kt_string(d['key'])} to CliDriverSpecs.ArgvSpec(\n")
        lines.append(f"        plain = {plain},\n")
        lines.append(f"        sandbox = {sandbox},\n")
        lines.append(f"        resumePlain = {resume_plain},\n")
        lines.append(f"        resumeSandbox = {resume_sandbox},\n")
        lines.append("    ),\n")
    lines.append(")\n")

    # 启动形态判定规则。
    lines.append("\n/** 启动形态判定：node 后缀。 */\n")
    launch_exts = _kt_string_list(spec["launch"]["nodeExtensions"], "        ")
    lines.append("internal val driverLaunch: CliDriverSpecs.LaunchSpec = CliDriverSpecs.LaunchSpec(\n")
    lines.append(f"    nodeExtensions = {launch_exts},\n")
    lines.append(")\n")

    # 每家的模型目录探针声明（kind/argv/parser id/catalogSource）。parser 函数实现在代码侧。
    lines.append("\n/** 每家模型目录探针声明；parser 是机制 id，函数实现在 DriverModelCatalog 注册。 */\n")
    lines.append("internal val driverModelProbeByKey: Map<String, CliDriverSpecs.ModelProbeSpec> = linkedMapOf(\n")
    for d in drivers:
        mp = d["modelProbe"]
        argv = _kt_string_list(mp["argv"], "            ") if "argv" in mp else "emptyList()"
        parser = _kt_string(mp["parser"]) if "parser" in mp else "null"
        lines.append(f"    {_kt_string(d['key'])} to CliDriverSpecs.ModelProbeSpec(\n")
        lines.append(f"        kind = CliDriverSpecs.ModelProbeKind.{mp['kind']},\n")
        lines.append(f"        argv = {argv},\n")
        lines.append(f"        parser = {parser},\n")
        if "catalogSource" in mp:
            cs = mp["catalogSource"]
            lines.append("        catalogSource = CliDriverSpecs.CatalogSource(\n")
            lines.append(f"            provider = {_kt_string(cs['provider'])},\n")
            lines.append(f"            driver = {_kt_string(cs['driver'])},\n")
            lines.append(f"            prefix = {_kt_string(cs['prefix'])},\n")
            lines.append("        ),\n")
        else:
            lines.append("        catalogSource = null,\n")
        lines.append("    ),\n")
    lines.append(")\n")

    # 探测时限（TTL / 单次超时）。
    limits = spec["probeLimits"]
    lines.append("\n/** 探测时限：目录缓存 TTL 与单次列表命令超时（毫秒）。 */\n")
    lines.append("internal val driverProbeLimits: CliDriverSpecs.ProbeLimits = CliDriverSpecs.ProbeLimits(\n")
    lines.append(f"    modelCatalogTtlMs = {limits['modelCatalogTtlMs']}L,\n")
    lines.append(f"    modelProbeTimeoutMs = {limits['modelProbeTimeoutMs']}L,\n")
    lines.append(")\n")

    # 每家的登录态探针声明（kind/argv/signals/golden/marker）。判定机制留代码，这里只投影声明与夹具。
    lines.append("\n/** 每家登录态探针声明：argv/正则信号/golden 夹具/marker；判定逻辑在 CliAuthProbes/AgyAuthProbe。 */\n")
    lines.append("internal val driverAuthProbeByKey: Map<String, CliDriverSpecs.AuthProbeSpec> = linkedMapOf(\n")
    for d in drivers:
        ap = d["authProbe"]
        lines.append(f"    {_kt_string(d['key'])} to CliDriverSpecs.AuthProbeSpec(\n")
        lines.append(f"        kind = CliDriverSpecs.AuthProbeKind.{ap['kind']},\n")
        argv = _kt_string_list(ap["argv"], "            ") if "argv" in ap else "emptyList()"
        lines.append(f"        argv = {argv},\n")
        lines.append(f"        authenticatedOnExitZero = {'true' if ap.get('authenticatedOn') == 'EXIT_ZERO' else 'false'},\n")
        # signals（CLI_CMD）
        if "signals" in ap:
            lines.append("        signals = listOf(\n")
            for s in ap["signals"]:
                lines.append("            CliDriverSpecs.AuthSignal(\n")
                lines.append(f"                verdict = CliDriverSpecs.AuthVerdict.{s['verdict']},\n")
                regex = _kt_string(s["regex"]) if "regex" in s else "null"
                lines.append(f"                regex = {regex},\n")
                lines.append(f"                ignoreCase = {'true' if s.get('ignoreCase') is True else 'false'},\n")
                lines.append(f"                blank = {'true' if s.get('blank') is True else 'false'},\n")
                lines.append("            ),\n")
            lines.append("        ),\n")
        else:
            lines.append("        signals = emptyList(),\n")
        # CLI_MARKER 字段
        marker = _kt_string(ap["notAuthenticatedMarker"]) if "notAuthenticatedMarker" in ap else "null"
        lines.append(f"        notAuthenticatedMarker = {marker},\n")
        lines.append(f"        markerIgnoreCase = {'true' if ap.get('markerIgnoreCase') is True else 'false'},\n")
        # 「已登录信号不足以画绿灯」的声明（opencode 的 stored 只证明存过凭证）。
        lines.append(f"        authenticatedIsHint = {'true' if ap.get('authenticatedIsHint') is True else 'false'},\n")
        # FILE 字段
        cks = _kt_string_list(ap["credentialKeys"], "            ") if "credentialKeys" in ap else "emptyList()"
        lines.append(f"        credentialKeys = {cks},\n")
        cf = _kt_string(ap["credentialFile"]) if "credentialFile" in ap else "null"
        lines.append(f"        credentialFile = {cf},\n")
        # golden
        if "golden" in ap:
            lines.append("        golden = listOf(\n")
            for g in ap["golden"]:
                lines.append("            CliDriverSpecs.AuthGolden(\n")
                lines.append(f"                exitCode = {g['exitCode']},\n")
                lines.append(f"                text = {_kt_string(g['text'])},\n")
                lines.append(f"                verdict = CliDriverSpecs.AuthVerdict.{g['verdict']},\n")
                lines.append("            ),\n")
            lines.append("        ),\n")
        else:
            lines.append("        golden = emptyList(),\n")
        lines.append("    ),\n")
    lines.append(")\n")

    # 沙箱 HOME 挂载清单：扁平有序，owner 仅作注释（供人/守卫读，不进投影值）。
    lines.append("\n/** 沙箱 HOME 挂载白名单：扁平有序，顺序即投影顺序。 */\n")
    lines.append("internal val driverSandboxHomeLinks: List<String> = listOf(\n")
    for link in spec["sandboxHomeLinks"]:
        owner_note = f"— {link['owner']}"
        if link.get("note"):
            owner_note += f": {link['note']}"
        lines.append(f"    {_kt_string(link['path'])}, // {owner_note}\n")
    lines.append(")\n")
    return "".join(lines)


def load_spec(root: str) -> Dict[str, Any]:
    path = os.path.join(root, SPEC_REL)
    if not os.path.isfile(path):
        raise SpecError(f"真源不存在：{path}")
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def out_path(root: str) -> str:
    return os.path.join(root, OUT_REL)


# ── 量具自检 ──
def self_test() -> int:
    base: Dict[str, Any] = {
        "schemaVersion": 1,
        "note": "self-test",
        "drivers": [{
            "key": "x", "displayName": "X", "vendor": "v", "role": "WORKER",
            "supportsPerTaskModel": True, "modelFlag": "--model", "modelMechanism": None,
            "shortName": None, "workerPriority": 1, "reviewerPriority": None,
            "envKeys": ["X_API_KEY"],
            "networkPolicy": "OPEN",
            "sandboxMode": {"envVar": "TENON_X_SANDBOX", "allowed": ["a", "b"], "default": "a"},
            "discovery": {"envVar": "TENON_X_CLI", "bin": "x", "extraBinRelative": None, "extraPathDirs": []},
            "argv": {"plain": ["-p", "${prompt}", "${model}"]},
            "modelProbe": {"kind": "LIST", "argv": ["models"], "parser": "two-column"},
            "authProbe": {
                "kind": "CLI_CMD",
                "argv": ["${cliPath}", "auth", "status"],
                "signals": [
                    {"verdict": "NOT_AUTHENTICATED", "regex": "not\\s+logged\\s+in", "ignoreCase": True},
                    {"verdict": "AUTHENTICATED", "regex": "logged\\s+in", "ignoreCase": True},
                ],
                "golden": [
                    {"exitCode": 0, "text": "Not logged in", "verdict": "NOT_AUTHENTICATED"},
                    {"exitCode": 0, "text": "Logged in as x", "verdict": "AUTHENTICATED"},
                ],
            },
        }],
        "argvPlaceholders": ["prompt", "model", "sandbox", "patch", "session"],
        "launch": {"nodeExtensions": [".cjs", ".js"], "note": "self-test"},
        "sandboxHomeLinks": [
            {"path": ".x", "owner": "x"},
            {"path": ".cache", "owner": "shared", "note": "公共缓存"},
        ],
        "probeLimits": {"modelCatalogTtlMs": 600000, "modelProbeTimeoutMs": 20000, "note": "self-test"},
    }
    failures = 0

    def expect_ok(name: str, spec: Dict[str, Any]) -> None:
        nonlocal failures
        try:
            validate(spec)
            print(f"  ok   {name} 良性输入通过")
        except SpecError as e:
            failures += 1
            print(f"  FAIL {name} 良性输入被误拒：{e}")

    def expect_bad(name: str, mutate) -> None:
        nonlocal failures
        import copy
        spec = copy.deepcopy(base)
        mutate(spec)
        try:
            validate(spec)
            failures += 1
            print(f"  FAIL {name} 坏输入没被抓住")
        except SpecError as e:
            print(f"  ok   {name} 坏输入被拒：{str(e)[:70]}")

    import copy
    expect_ok("baseline", base)
    expect_bad("未知顶层键", lambda s: s.update({"typoKey": 1}))
    expect_bad("未知 driver 键", lambda s: s["drivers"][0].update({"modelFalg": "--model"}))
    expect_bad("缺字段", lambda s: s["drivers"][0].pop("shortName"))
    expect_bad("role 拼错", lambda s: s["drivers"][0].update({"role": "WORKR"}))
    expect_bad("schema 版本不符", lambda s: s.update({"schemaVersion": 99}))
    expect_bad("重复 key", lambda s: s["drivers"].append(copy.deepcopy(s["drivers"][0])))
    expect_bad("支持性与通道冲突", lambda s: s["drivers"][0].update({"supportsPerTaskModel": False}))
    expect_bad("flag 不属于 NONE 通道", lambda s: s["drivers"][0].update(
        {"modelMechanism": "NONE", "modelFlag": "--model", "supportsPerTaskModel": False}))
    expect_bad("mechanism 拼错", lambda s: s["drivers"][0].update({"modelMechanism": "CONFIG_PATCHX"}))
    # 良性 CONFIG_PATCH（dsh 形态）必须放行，别把双保险写成一刀切。
    good_patch = copy.deepcopy(base)
    good_patch["drivers"][0].update({
        "modelFlag": None,
        "modelMechanism": "CONFIG_PATCH",
        "argv": {"plain": ["${patch}", "${prompt}"]},
    })
    expect_ok("CONFIG_PATCH 通道", good_patch)

    # ── 批1 新字段校验 ──
    expect_bad("envKeys 重复", lambda s: s["drivers"][0].update({"envKeys": ["K", "K"]}))
    expect_bad("envKeys 含空白", lambda s: s["drivers"][0].update({"envKeys": ["  "]}))
    expect_bad("networkPolicy 拼错", lambda s: s["drivers"][0].update({"networkPolicy": "WIDE_OPEN"}))
    expect_bad("sandboxMode 默认值非法", lambda s: s["drivers"][0]["sandboxMode"].update({"default": "z"}))
    expect_bad("sandboxMode 缺键", lambda s: s["drivers"][0]["sandboxMode"].pop("allowed"))
    expect_bad("homeLinks 敏感目录", lambda s: s["sandboxHomeLinks"].append({"path": ".ssh", "owner": "shared", "note": "no"}))
    expect_bad("homeLinks owner 野驱动", lambda s: s["sandboxHomeLinks"].append({"path": ".ghost", "owner": "ghost"}))
    expect_bad("homeLinks shared 无理由", lambda s: s["sandboxHomeLinks"].append({"path": ".foo", "owner": "shared"}))
    expect_bad("homeLinks path 带斜杠", lambda s: s["sandboxHomeLinks"].append({"path": ".a/b", "owner": "x"}))
    expect_bad("homeLinks 重复 path", lambda s: s["sandboxHomeLinks"].append({"path": ".x", "owner": "x"}))
    expect_bad("homeLinks 缺顶层键", lambda s: s.pop("sandboxHomeLinks"))
    expect_bad("discovery 缺键", lambda s: s["drivers"][0]["discovery"].pop("bin"))
    expect_bad("discovery envVar 空白", lambda s: s["drivers"][0]["discovery"].update({"envVar": "  "}))
    expect_bad("discovery extraBinRelative 绝对路径", lambda s: s["drivers"][0]["discovery"].update({"extraBinRelative": "/abs/x"}))
    expect_bad("discovery extraPathDirs 绝对路径", lambda s: s["drivers"][0]["discovery"].update({"extraPathDirs": ["/abs"]}))
    expect_bad("launch 后缀不加点", lambda s: s["launch"].update({"nodeExtensions": ["cjs"]}))
    expect_bad("launch 缺顶层键", lambda s: s.pop("launch"))
    expect_bad("argv 缺 plain", lambda s: s["drivers"][0].pop("argv"))
    expect_bad("argv prompt 零次", lambda s: s["drivers"][0]["argv"].update({"plain": ["-p", "${model}"]}))
    expect_bad("argv prompt 两次", lambda s: s["drivers"][0]["argv"].update({"plain": ["${prompt}", "${prompt}"]}))
    expect_bad("argv 未知占位符", lambda s: s["drivers"][0]["argv"].update({"plain": ["${prompt}", "${bogus}"]}))
    expect_bad("argv 裸路径提权旗标", lambda s: s["drivers"][0]["argv"].update(
        {"plain": ["${prompt}", "-y"], "sandbox": ["${prompt}"]}))
    expect_bad("argv resume 缺 session", lambda s: s["drivers"][0]["argv"].update(
        {"resumePlain": ["--resume", "${prompt}"]}))
    # 通道占位符不到场 = 按次模型/补丁参数被静默丢弃（不是「少个可选参数」）。
    expect_bad("argv FLAG 缺 ${model}", lambda s: s["drivers"][0]["argv"].update(
        {"plain": ["-p", "${prompt}"]}))
    # resume 例外：会话已绑定模型，某些 CLI 的 `--resume` 不认 `--model` ⇒ 允许不带。
    good_resume = copy.deepcopy(base)
    good_resume["drivers"][0]["argv"]["resumePlain"] = ["--resume", "${session}", "-p", "${prompt}"]
    expect_ok("argv FLAG resume 不带 ${model}", good_resume)
    expect_bad("argv CONFIG_PATCH 缺 ${patch}", lambda s: s["drivers"][0].update({
        "modelFlag": None,
        "modelMechanism": "CONFIG_PATCH",
        "argv": {"plain": ["--profile", "headless", "${prompt}"]},
    }))

    # ── 批4a modelProbe / probeLimits 校验 ──
    expect_bad("modelProbe 缺键", lambda s: s["drivers"][0].pop("modelProbe"))
    expect_bad("modelProbe kind 拼错", lambda s: s["drivers"][0]["modelProbe"].update({"kind": "LISTX"}))
    expect_bad("modelProbe parser 未注册", lambda s: s["drivers"][0]["modelProbe"].update({"parser": "magic"}))
    expect_bad("modelProbe LIST 空 argv", lambda s: s["drivers"][0]["modelProbe"].update({"argv": []}))
    expect_bad("modelProbe LIST 带 catalogSource", lambda s: s["drivers"][0]["modelProbe"].update(
        {"catalogSource": {"provider": "p", "driver": "x", "prefix": "p/"}}))
    expect_bad("modelProbe NONE 带多余字段", lambda s: s["drivers"][0]["modelProbe"].update(
        {"kind": "NONE", "parser": "two-column", "note": "x"}))
    expect_bad("modelProbe NONE 无 note", lambda s: s["drivers"][0].update(
        {"modelProbe": {"kind": "NONE"}}))
    expect_bad("modelProbe CONFIG 缺 catalogSource", lambda s: s["drivers"][0]["modelProbe"].update({"kind": "CONFIG"}))
    expect_bad("modelProbe CONFIG 野 driver", lambda s: s["drivers"][0]["modelProbe"].update(
        {"kind": "CONFIG", "parser": "dsh-default",
         "catalogSource": {"provider": "p", "driver": "ghost", "prefix": "p/"}}))
    expect_bad("probeLimits 非正 TTL", lambda s: s["probeLimits"].update({"modelCatalogTtlMs": 0}))
    expect_bad("probeLimits 缺顶层键", lambda s: s.pop("probeLimits"))
    # 良性 CONFIG（dsh 形态）必须放行。
    good_config = copy.deepcopy(base)
    good_config["drivers"][0]["modelProbe"] = {
        "kind": "CONFIG", "argv": ["--dump-config"], "parser": "dsh-default",
        "catalogSource": {"provider": "dsh-p", "driver": "x", "prefix": "p/"},
    }
    expect_ok("CONFIG 形态 modelProbe", good_config)

    # ── 批4b authProbe 校验 ──
    expect_bad("authProbe 缺键", lambda s: s["drivers"][0].pop("authProbe"))
    expect_bad("authProbe kind 拼错", lambda s: s["drivers"][0]["authProbe"].update({"kind": "CLI"}))
    expect_bad("authProbe CLI_CMD 缺 argv", lambda s: s["drivers"][0]["authProbe"].pop("argv"))
    expect_bad("authProbe argv 首项非 cliPath", lambda s: s["drivers"][0]["authProbe"].update(
        {"argv": ["auth", "status"]}))
    expect_bad("authProbe argv cliPath 两次", lambda s: s["drivers"][0]["authProbe"].update(
        {"argv": ["${cliPath}", "${cliPath}", "status"]}))
    expect_bad("authProbe signals verdict=UNKNOWN", lambda s: s["drivers"][0]["authProbe"]["signals"][0].update(
        {"verdict": "UNKNOWN"}))
    expect_bad("authProbe signal regex+blank 都有", lambda s: s["drivers"][0]["authProbe"]["signals"][0].update(
        {"blank": True}))
    expect_bad("authProbe signal 正则编译失败", lambda s: s["drivers"][0]["authProbe"]["signals"][0].update(
        {"regex": "([unclosed"}))
    expect_bad("authProbe CLI_CMD 带 marker", lambda s: s["drivers"][0]["authProbe"].update(
        {"notAuthenticatedMarker": "x"}))
    expect_bad("authProbe golden verdict 拼错", lambda s: s["drivers"][0]["authProbe"]["golden"][0].update(
        {"verdict": "MAYBE"}))
    expect_bad("authProbe golden exitCode 非整数", lambda s: s["drivers"][0]["authProbe"]["golden"][0].update(
        {"exitCode": "1"}))
    expect_bad("authProbe golden 只一种 verdict", lambda s: s["drivers"][0]["authProbe"].update(
        {"golden": [{"exitCode": 0, "text": "x", "verdict": "UNKNOWN"}]}))
    expect_bad("authProbe CLI_MARKER 缺 authenticatedOn", lambda s: s["drivers"][0]["authProbe"].update(
        {"kind": "CLI_MARKER", "argv": ["${cliPath}", "models"]}))
    expect_bad("authProbe FILE 缺 credentialFile", lambda s: s["drivers"][0].update(
        {"authProbe": {"kind": "FILE", "credentialKeys": ["K"], "note": "x"}}))
    expect_bad("authProbe NONE 无 note", lambda s: s["drivers"][0].update({"authProbe": {"kind": "NONE"}}))
    expect_bad("authProbe authenticatedIsHint 非布尔", lambda s: s["drivers"][0]["authProbe"].update(
        {"authenticatedIsHint": "yes"}))
    expect_bad("authProbe CLI_MARKER 带 authenticatedIsHint", lambda s: s["drivers"][0]["authProbe"].update(
        {"kind": "CLI_MARKER", "authenticatedOn": "EXIT_ZERO",
         "notAuthenticatedMarker": "x", "markerIgnoreCase": True,
         "authenticatedIsHint": True}))
    # 良性 CLI_MARKER（agy 形态）必须放行。
    good_marker = copy.deepcopy(base)
    good_marker["drivers"][0]["authProbe"] = {
        "kind": "CLI_MARKER", "argv": ["${cliPath}", "models"],
        "authenticatedOn": "EXIT_ZERO", "notAuthenticatedMarker": "not logged in",
        "markerIgnoreCase": True,
        "golden": [
            {"exitCode": 0, "text": "m-1", "verdict": "AUTHENTICATED"},
            {"exitCode": 1, "text": "not logged in", "verdict": "NOT_AUTHENTICATED"},
        ],
    }
    expect_ok("CLI_MARKER 形态 authProbe", good_marker)

    if failures:
        print(f"量具自检失败 {failures} 项")
        return 1
    print("量具自检全过")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true", help="从 JSON 重新投影 .kt")
    ap.add_argument("--check", action="store_true", help="门禁：重新生成并与工作区 .kt 逐字节比对")
    ap.add_argument("--self-test", action="store_true", help="量具自检")
    ap.add_argument("--root", default=".", help="仓库根，默认当前目录")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if args.write == args.check:
        print("必须且只能给 --write 或 --check 之一", file=sys.stderr)
        return 2

    root = os.path.abspath(args.root)
    try:
        spec = load_spec(root)
        drivers = validate(spec)
    except (SpecError, json.JSONDecodeError) as e:
        print(f"driver-specs.json 校验失败：{e}", file=sys.stderr)
        return 1

    generated = render(drivers, spec)
    path = out_path(root)
    if args.write:
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(generated)
        print(f"已投影 {len(drivers)} 家 -> {path}")
        return 0

    # --check
    if not os.path.isfile(path):
        print(f"生成物缺失：{path}（跑 :driverSpecProject）", file=sys.stderr)
        return 1
    with open(path, encoding="utf-8") as fh:
        actual = fh.read()
    if actual != generated:
        print("driver-specs.json 与 DriverSpecTable.generated.kt 漂移：跑 :driverSpecProject 重新投影。",
              file=sys.stderr)
        import difflib
        sys.stderr.writelines(difflib.unified_diff(
            actual.splitlines(keepends=True), generated.splitlines(keepends=True),
            fromfile="工作区", tofile="JSON 投影",
        ))
        return 1
    print(f"漂移门禁通过：{len(drivers)} 家身份卡与生成物一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
