#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在渲染锁下执行一条命令 —— 给"非渲染但会写 build 的 gradle 任务"用。

为什么需要它：`render-preview.py` 的 flock 只护住它自己那次渲染。桌面打包
（`:desktopApp:createDistributable` / `packageDmg`）同样写 `build` 与
`desktopApp/build`，与并发渲染撞在一起会把在途产物固化成"正常"。

锁文件与 `render-preview.py` **共用同一个路径**（`build/.render-preview.lock`）
⇒ 两类任务互斥，不需要第二套约定。

用法：
    python3 tools/gradle-locked.py -- <命令> [参数...]
    python3 tools/gradle-locked.py --label "打包 dmg" -- \
        env JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
        :desktopApp:packageDmg --console=plain

**别把它的输出接进管道**（`… | tail -40`）—— 那样拿到的是 `tail` 的退出码 0，
一次"gradlew 根本没启动"会被读成"打包成功"。要截断就先写文件再读，或用 `set -o pipefail`。

退出码 = 被包装命令的退出码；锁获取失败或参数缺失返回 2。
"""
import fcntl
import os
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCK_PATH = os.path.join(REPO, "build", ".render-preview.lock")


def main(argv):
    if "--" not in argv:
        print("用法：python3 tools/gradle-locked.py [--label 说明] -- <命令> [参数...]\n"
              "注意：命令的 cwd 是仓库根目录",
              file=sys.stderr)
        return 2
    i = argv.index("--")
    head, cmd = argv[:i], argv[i + 1:]
    label = "命令"
    if "--label" in head:
        j = head.index("--label")
        if j + 1 < len(head):
            label = head[j + 1]
    if not cmd:
        print("FAIL — `--` 后面没有命令", file=sys.stderr)
        return 2

    os.makedirs(os.path.dirname(LOCK_PATH), exist_ok=True)
    with open(LOCK_PATH, "w") as lock:
        print(f"[lock] 等渲染锁…（{label}）", flush=True)
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            print(f"[lock] 已获得，开始：{' '.join(cmd)}", flush=True)
            return subprocess.call(cmd, cwd=REPO)
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)
            print("[lock] 已释放", flush=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
