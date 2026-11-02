#!/usr/bin/env python3
"""跑一条命令并限时，打印 stdout/stderr/exit（含超时判定）。

为什么不用 `timeout`：macOS 自带没有（要 gtimeout），而验收脚本必须在
干净机器上能直接跑。
"""
import subprocess
import sys

secs = float(sys.argv[1])
cmd = sys.argv[2:]
try:
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=secs)
    out, err, code = p.stdout, p.stderr, p.returncode
    timed_out = False
except subprocess.TimeoutExpired as e:
    out = (e.stdout or b"").decode("utf-8", "replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
    err = (e.stderr or b"").decode("utf-8", "replace") if isinstance(e.stderr, bytes) else (e.stderr or "")
    code = "TIMEOUT"
    timed_out = True
print(f"--- exit={code} timed_out={timed_out} ---")
if out.strip():
    print("[stdout]")
    print(out.strip()[:2000])
if err.strip():
    print("[stderr]")
    print(err.strip()[:2000])
if not out.strip() and not err.strip():
    print("(no output at all)")
