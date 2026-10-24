#!/usr/bin/env bash
# 重复跑同一套测试 N 轮，任何一轮红就失败。
#
# 为什么需要它：异步/并发用例的失败在单轮里表现为「偶发」，而「偶发」既可能是产品竞态，
# 也可能是测试替身不线程安全。单轮绿说明不了什么；连跑 N 轮才能把「每 3 轮红 1 次」
# 这种量级暴露出来（本仓真实读数：修之前每 3 轮红 3 次、每次红的不一样）。
#
# 用法：
#   bash tools/repeat-tests.sh 3                 # 默认跑 core + app，串行
#   bash tools/repeat-tests.sh 5 :core:jvmTest   # 指定任务
set -uo pipefail

RUNS="${1:-3}"
shift || true
TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then
  TASKS=(:core:jvmTest :app:jvmTest)
fi

failures=0
for i in $(seq 1 "$RUNS"); do
  echo "=== run $i/$RUNS: ${TASKS[*]} ==="
  if ./gradlew "${TASKS[@]}" --rerun-tasks --max-workers=1 --console=plain \
      > "/tmp/repeat-tests-$i.log" 2>&1; then
    echo "    PASS"
  else
    failures=$((failures + 1))
    echo "    FAIL（日志 /tmp/repeat-tests-$i.log）"
    grep -E 'FAILED|tests completed' "/tmp/repeat-tests-$i.log" | tail -5 | sed 's/^/      /'
  fi
done

if [ "$failures" -ne 0 ]; then
  echo "重复跑 $RUNS 轮：$failures 轮失败 —— 这不是偶发，是还没修干净的竞态。"
  exit 1
fi
echo "重复跑 $RUNS 轮：全部通过。"
