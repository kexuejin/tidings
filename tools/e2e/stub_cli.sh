#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
#
# E2E 的**受控 CLI 替身**（不是产品代码，只被 `agent_dispatch_e2e.py --worker stub` 用）。
#
# 为什么要替身：真机 E2E 要验的是**宿主编排链**（身份 → 执行档 → 授权 → 项目目录 → 工单 →
# 卡片状态机 → 产物收割），不是「某家 CLI 今天能不能连上模型」。把外部 CLI 接进断言，
# 结果就是环境一变（CLI 崩了/没登录/限流）整条链路红成一片，而产品其实是对的。
# 替身把 CLI 这一格钉成确定行为：**回声一条 agent_message + 改一行 README + 睡 4s**，
# 于是「working 态可被观测」与「有 diff 可收割」都成了断言的前提而不是运气。
#
# 它按 codex 的位置参数形态被调用（`codex exec --json --sandbox <mode> <prompt>`），
# 只认最后一个参数，其余一律忽略 —— 保留位置参数形态，是为了「驱动真的把参数传下来了」
# 这件事也能从 log 里看出来。
#
# 停机开关：本脚本只做两件事（写一行 README、打一行 JSON），不读任何宿主数据。

prompt=""
for arg in "$@"; do
  prompt="$arg"
done

# 行协议要求**一行一个 JSON**：prompt 里带换行（派单会前置一段工作区约束），直接塞进去
# 会把这一行拆成半行 JSON，映射器只能整行丢掉 —— 那正是「卡上没有 worker 说法」的来路。
one_line=$(printf '%s' "$prompt" | tr '\n\r\t' '   ' | cut -c1-80)

# 打回轮识别：打回反馈被前置成 "HUMAN REJECTION FEEDBACK ..."（DesktopStageChain.runChain）。
# 首轮 prompt 没有这个段落，打回重派才有 —— 用它让替身产出**可区分的产物**，这样
# 「反馈作为硬约束被执行」才能被真机断言（不是只证明反馈被注入、产物却一字不变）。
# 这个标记串是替身与 E2E 脚本的约定，不进产品代码、不改卡面文案。
revision_round=0
case "$prompt" in
  *"HUMAN REJECTION FEEDBACK"*) revision_round=1 ;;
esac

# 产物：让 worktree 里真的出现 diff（harvest 才有东西可割）。
# 打回轮换一行内容，harvest 割出的 patch.diff 因此与首轮不同 —— E2E 用这行做
# 「反馈真的影响产物」的判据，而不是采信一句「注入了就算执行了」的日志。
if [ -f README.md ]; then
  if [ "$revision_round" -eq 1 ]; then
    printf '\nrevision applied: human rejection feedback honored\n' >> README.md
  else
    printf '\nhello from stub worker\n' >> README.md
  fi
else
  if [ "$revision_round" -eq 1 ]; then
    printf 'revision applied: human rejection feedback honored\n' > README.md
  else
    printf 'hello from stub worker\n' > README.md
  fi
fi

# 回声也带标记：task.log 里能直接看出这一棒吃到了反馈，便于排查假绿。
if [ "$revision_round" -eq 1 ]; then
  printf '{"type":"agent_message","text":"stub worker [revision applied]: %s"}\n' "$(printf '%s' "$one_line" | sed 's/\\/\\\\/g; s/"/\\"/g')"
else
  printf '{"type":"agent_message","text":"stub worker: %s"}\n' "$(printf '%s' "$one_line" | sed 's/\\/\\\\/g; s/"/\\"/g')"
fi

# 「干了一会儿」：真机采样周期是 0.4s，秒级完成会让 working 态整段看不见 ——
# 那不是产品没进 working，是断言来不及看。这一行就是为了让状态机可观测。
sleep 4

exit 0
