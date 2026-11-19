#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# 团队责任链的**审查棒受控替身**（不是产品代码，只被 `agent_dispatch_e2e.py
# --scenario team` 用）。worker 棒由 stub_cli.sh 顶（codex 形态），审查棒用本脚本顶
# dsh 形态（`dsh --profile headless --json <prompt>`，末位参数是 prompt）。
#
# 为什么单独一个：worker 替身必须造 diff（harvest 要割 patch），审查棒**不能**造 diff
# —— 它只读上一棒的提交 + patch 指针做判断，patchless 审查是产品明确合法的路径
# （`DesktopStageChain.land` 对无 patch 棒返回 null，`ReviewerAudit` 记 SKIP）。
# 而且它**绝不回声 prompt**：审查棒的 prompt 尾部有字面量
# "End with ... VERDICT: PASS or VERDICT: REJECT."，整体回声会被 ReviewVerdict 的
# REJECT 优先规则误判成 REJECT，链就返工/阻断 —— 所以这里只打一句干净的 PASS。
#
# 判据（让这一棒被算作 finished + 审查通过）：
#   ① exit 0 → status finished（非 didNothing：有一行被 mapper 认出的 agent_message）
#   ② 文本里含显式 `VERDICT: PASS` → ReviewVerdict.parseOf 判 Merge
#   ③ 不写任何文件 → escapedWrite=false
# 留一行 sleep 让 working 态在 0.4s 采样里可见（与 worker 替身同理）。

sleep 2

printf '{"type":"agent_message","text":"reviewer stub: worker change reviewed, VERDICT: PASS"}\n'

exit 0
