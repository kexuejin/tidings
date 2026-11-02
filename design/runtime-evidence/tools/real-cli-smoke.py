#!/usr/bin/env python3
"""验证本机真 agent CLI 能否走 opencode-go 网关（自定义 base URL + model）。

## 为什么需要它

`2026-11-01-sdlc-acceptance-weather.md` §13.0 记录过「本机没有可用真 CLI」——
那是**默认上游**的结论：`codex` / `claude` 未登录，且 `api.openai.com` 被切断。
但三家 CLI 都支持自定义 base URL；把上游换成 opencode-go（同时提供 OpenAI 兼容
`/chat/completions`、`/responses` 与 Anthropic 兼容 `/messages`）后，三家都能跑
`deepseek-v4.1-flash`。本脚本就是这条结论的量具。

## 用法

    export OPENCODE_API_KEY=oc_sk_...
    python3 design/runtime-evidence/tools/real-cli-smoke.py             # 对话冒烟
    python3 design/runtime-evidence/tools/real-cli-smoke.py --agentic   # 另测改文件
    python3 design/runtime-evidence/tools/real-cli-smoke.py --only claude,codex

退出码：0 = 全通过；1 = 有失败；2 = 环境缺 key / CLI。

## 密钥不落盘

- codex：`env_key = "OPENCODE_API_KEY"`，配置文件里只有变量名；
- opencode：`apiKey = "{env:OPENCODE_API_KEY}"`，配置里只有占位符；
- claude：只走 `ANTHROPIC_API_KEY` 环境变量。

## 两个不明显的坑（都实测踩过）

1. **codex 0.145 移除了 `wire_api = "chat"`**，只认 `"responses"`。网关两种都提供，
   所以填 `responses` 即可。
2. **opencode 用 `$PWD` 解析工作区，不是进程 cwd**。`subprocess(cwd=...)` 不会改
   `$PWD`，于是它会把文件写到父进程的项目根。这里显式把 `PWD` 设成工作目录。
   另外 `opencode run` 必须带 `--standalone`：后台常驻服务会缓存旧配置，不带它
   自定义 provider 不生效（报 `Model unavailable`）。
"""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

DEFAULT_BASE = "https://opencode.ai/zen/go/v1"
MODEL = "deepseek-v4.1-flash"
CHAT_PROMPT = "Reply with exactly: PONG"
AGENTIC_PROMPT = (
    "Create a file named proof.txt in the current directory containing exactly "
    "CLI_OK and nothing else. Do not modify any other file."
)


def die(msg):
    print("错误: " + msg, file=sys.stderr)
    sys.exit(2)


def make_workspace(root: pathlib.Path, agentic: bool) -> pathlib.Path:
    ws = root / "ws"
    ws.mkdir(parents=True)
    if not agentic:
        return ws
    for args in (
        ["git", "init", "-q"],
        ["git", "config", "user.email", "smoke@example.invalid"],
        ["git", "config", "user.name", "smoke"],
    ):
        subprocess.run(args, cwd=ws, check=True)
    (ws / "seed.txt").write_text("seed\n")
    subprocess.run(["git", "add", "."], cwd=ws, check=True)
    subprocess.run(["git", "commit", "-qm", "init"], cwd=ws, check=True)
    return ws


def run(cmd, ws: pathlib.Path, env: dict, timeout: int):
    """子进程统一带 PWD（opencode 按它解析工作区；对另外两家无害）。"""
    e = dict(env)
    e["PWD"] = str(ws)
    try:
        p = subprocess.run(
            cmd, cwd=ws, env=e, stdin=subprocess.DEVNULL,
            capture_output=True, text=True, timeout=timeout,
        )
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as exc:
        out = exc.stdout.decode("utf-8", "replace") if isinstance(exc.stdout, bytes) else (exc.stdout or "")
        err = exc.stderr.decode("utf-8", "replace") if isinstance(exc.stderr, bytes) else (exc.stderr or "")
        return "TIMEOUT", out, err


def codex_home(root: pathlib.Path, base: str) -> pathlib.Path:
    home = root / "codex-home"
    home.mkdir()
    (home / "config.toml").write_text(
        'model = "%s"\n'
        'model_provider = "opencode-go"\n'
        "\n"
        "[model_providers.opencode-go]\n"
        'name = "opencode-go"\n'
        'base_url = "%s"\n'
        'env_key = "OPENCODE_API_KEY"\n'
        'wire_api = "responses"\n'
        'http_headers = { "x-opencode-session" = "codex-cli" }\n' % (MODEL, base)
    )
    return home


def opencode_config(root: pathlib.Path, base: str) -> pathlib.Path:
    cfg = root / "opencode.json"
    cfg.write_text(json.dumps({
        "$schema": "https://opencode.ai/config.json",
        "providers": {
            "opencode-go": {
                "name": "opencode-go",
                "package": "@ai-sdk/openai-compatible",
                "settings": {
                    "baseURL": base,
                    "apiKey": "{env:OPENCODE_API_KEY}",
                },
                "headers": {"x-opencode-session": "opencode-cli"},
                "models": {MODEL: {"name": "DeepSeek V4.1 Flash"}},
            }
        },
        "model": "opencode-go/%s" % MODEL,
    }, indent=2))
    return cfg


def check_claude(root, base, key, agentic, timeout):
    ws = make_workspace(root, agentic)
    env = dict(os.environ)
    env.update({
        "ANTHROPIC_BASE_URL": base.removesuffix("/v1"),
        "ANTHROPIC_API_KEY": key,
        "ANTHROPIC_MODEL": MODEL,
        "ANTHROPIC_SMALL_FAST_MODEL": MODEL,
        "ANTHROPIC_CUSTOM_HEADERS": "x-opencode-session: claude-code",
        "CLAUDE_CONFIG_DIR": str(root / "claude-home"),
    })
    cmd = ["claude", "-p", AGENTIC_PROMPT if agentic else CHAT_PROMPT, "--output-format", "text"]
    if agentic:
        cmd += ["--permission-mode", "bypassPermissions"]
    rc, out, err = run(cmd, ws, env, timeout)
    return ws, rc, out, err


def check_codex(root, base, key, agentic, timeout):
    ws = make_workspace(root, agentic)
    env = dict(os.environ)
    env["CODEX_HOME"] = str(codex_home(root, base))
    cmd = ["codex", "exec", "--skip-git-repo-check", "-s", "danger-full-access",
           AGENTIC_PROMPT if agentic else CHAT_PROMPT]
    rc, out, err = run(cmd, ws, env, timeout)
    return ws, rc, out, err


def check_opencode(root, base, key, agentic, timeout):
    ws = make_workspace(root, agentic)
    env = dict(os.environ)
    env["OPENCODE_CONFIG"] = str(opencode_config(root, base))
    cmd = ["opencode", "run", "--standalone", "-m", "opencode-go/%s" % MODEL]
    if agentic:
        cmd.append("--auto")
    cmd.append(AGENTIC_PROMPT if agentic else CHAT_PROMPT)
    rc, out, err = run(cmd, ws, env, timeout)
    return ws, rc, out, err


CHECKS = {"claude": check_claude, "codex": check_codex, "opencode": check_opencode}


def main(argv=None):
    ap = argparse.ArgumentParser(description="真 agent CLI × opencode-go 冒烟")
    ap.add_argument("--agentic", action="store_true", help="另测「改文件」（创建 proof.txt）")
    ap.add_argument("--only", default="", help="逗号分隔：claude,codex,opencode")
    ap.add_argument("--timeout", type=int, default=240, help="单个 CLI 超时秒数")
    ap.add_argument("--keep", action="store_true", help="保留临时目录（排障用）")
    args = ap.parse_args(argv)

    key = os.environ.get("OPENCODE_API_KEY", "").strip()
    if not key:
        die("缺 OPENCODE_API_KEY（oc_sk_…）")
    base = os.environ.get("OPENCODE_BASE_URL", DEFAULT_BASE).strip() or DEFAULT_BASE
    names = [n.strip() for n in args.only.split(",") if n.strip()] or list(CHECKS)
    for n in names:
        if n not in CHECKS:
            die("未知 CLI: %s（可选 %s）" % (n, ", ".join(CHECKS)))
        if not shutil.which(n):
            die("PATH 里没有 %s" % n)

    print("网关: %s" % base)
    print("模型: %s" % MODEL)
    print("模式: %s" % ("agentic（改文件）" if args.agentic else "chat"))
    print("-" * 72)

    failures = []
    for name in names:
        root = pathlib.Path(tempfile.mkdtemp(prefix="cli-smoke-%s-" % name))
        try:
            ws, rc, out, err = CHECKS[name](root, base, key, args.agentic, args.timeout)
            if args.agentic:
                proof = ws / "proof.txt"
                ok = proof.exists() and proof.read_text().strip() == "CLI_OK"
                detail = "proof.txt=%s" % ("CLI_OK" if ok else "MISSING/WRONG")
            else:
                ok = "PONG" in out
                detail = "reply=%s" % ("PONG" if ok else out.strip().splitlines()[-1:] or "(empty)")
            status = "PASS" if ok and rc == 0 else "FAIL"
            if status == "FAIL":
                failures.append(name)
            print("%-9s %s  rc=%s  %s" % (name, status, rc, detail))
            if status == "FAIL":
                tail = (err or out).strip().splitlines()[-6:]
                for line in tail:
                    print("           | " + line[:200])
        finally:
            if args.keep:
                print("           (kept %s)" % root)
            else:
                shutil.rmtree(root, ignore_errors=True)

    print("-" * 72)
    if failures:
        print("失败: %s" % ", ".join(failures))
        return 1
    print("全部通过（%d/%d）" % (len(names), len(names)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
