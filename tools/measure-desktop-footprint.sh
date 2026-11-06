#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
#
# measure-desktop-footprint.sh —— 桌面端 JVM 进程内存占用采样器（macOS）
#
# 为什么需要它：桌面端报「内存占多少」时，三种量具的读数差得很远 ——
#   * ps 的 RSS 把共享页、文件缓存、GPU 映射全算进来，通常只涨不跌；
#   * jcmd 的 GC.heap_info 只看 Java 堆 + Metaspace，看不到 skia / IOSurface；
#   * vmmap 的 Physical footprint 才是 macOS 真正压给这台机器的量（含 GPU 映射）。
# 只报其中一个字段，就会得出「堆只有 380MB」或「RSS 700MB」这种互相矛盾的口径。
# 本脚本每个采样点把三者一起取，最后给一张可复现的汇总表。
#
# 可复现性：产物随时可能被重新构建替换，而 -Xmx / 选哪个 GC 一换读数就不可比。
# 所以脚本在采样前把被测进程的命令行、bundle 标识、.cfg 里的全部 java-options、
# 以及 Contents/app 下全部 jar 拼接后的 sha256 一起打进证据头，采样结束后再算一遍
# 指纹做对比 —— 中间被换掉了会明确写「产物指纹已变，读数需重跑」。
#
# 用法（两种模式二选一）：
#   tools/measure-desktop-footprint.sh --app /Applications/Tidings.app --samples 3 --interval 5 \
#                                      --out design/runtime-evidence/xxx.txt -- --background
#   tools/measure-desktop-footprint.sh --pid 1234 --samples 3 --interval 5
#
# 参数：
#   --app PATH        要启动的 .app 包 / 可执行 jar / 可执行文件（启动后由本脚本负责测量并收尾）
#   --pid PID         直接测一个已经在跑的进程（attach 模式，绝不 kill 它）
#   --samples N       采样点数，默认 3
#   --interval SEC    采样间隔秒，默认 5
#   --out FILE        同时把全部输出写入 FILE（"-" = 只打 stdout）
#   --warmup SEC      启动后等待稳定再采第一个点，默认 5
#   --startup SEC     等待进程起来的上限秒数，默认 30
#   --args "a b"      传给被测应用的参数（也可以直接写在 -- 之后）
#   --force-gc        先跑一次 jcmd GC.run（默认关闭：它会改进程内状态）
#   --jdk PATH        jcmd/java 所在目录（不给则依次找 JAVA_HOME、PATH、Homebrew openjdk）
#   --keep            测完不杀自己拉起的进程（默认杀；attach 模式从不杀）
#   --attach-retries N   jcmd attach 失败的重试次数，默认 3
#
# 退出码：
#   0  正常完成
#   2  参数错误 / 被测目标不存在
#   5  自己启动的进程没起来
#   6  采样过程中目标进程消失（已明确报告「进程已退出」）
#
# 已知的真实坑（都在这台机器上实测过，脚本必须吃掉它们）：
#   1. jcmd 偶发 AttachNotSupportedException / Unable to open socket file /
#      target process doesn't respond within 10500ms —— 这是 JVM attach 的正常抖动，
#      不是被测进程坏了。脚本重试 N 次，仍失败就只记一行 attach-failed 并继续，
#      不中断整次测量、不输出空值冒充读数。
#   2. 进程可能在采样中途退出（托盘应用被用户退出、崩溃）。这时必须立刻停止并
#      明确报「进程已退出」，而不是把空字符串当读数写进汇总表。
#   3. macOS 上 vmmap 可能因权限 / TCC 拒绝。失败只报一行并继续采 RSS 与堆，
#      让整次测量仍然可用。
#   4. 判活不能只看 ps -p 成不成功：僵尸进程（Z 状态）也能查到，但读数是上一瞬间的
#      残影。pid_alive 会排除 Z，读不到 RSS 时给 n/a 而不是 0。
#   5. 读数不是常数：同一份二进制、同一台机器，Physical footprint 会随 GPU / VM_ALLOCATE
#      区域在数百 MB 区间内摆动（实测 saw 422M–620M），且与同机并发构建的负载相关。
#      所以固定样本数与间隔、并在证据里记下当时的负载，比报一个「好看的单点值」有用。
#      同机并发构建（Gradle / jpackage）期间实测见过 422M–620M 的跨度；空载时同参数
#      稳定在 578–584M。报数时必须连负载一起报。
#   6. 「同类实例数」按**可执行文件路径前缀**精确匹配，.app 形态是准的；但如果被测进程
#      是以 java -jar 起的，匹配到的就是 java 可执行文件本身，会把同机所有 JVM 都算进来
#      （实测把一个 reposilite 量成了「同类实例 2 个」）。这种情形下这个数字是上界，
#      不等于「真的跑了两个同样的应用」——读报告时别把它当唯一判据。
#
# 关于读数口径（写报告时必须一起说，否则会被误读）：
#   Physical footprint 含 GPU / IOSurface 映射，是 macOS 压给机器的量；
#   RSS 含共享页与文件缓存，只适合同一台机器同一产物上横向比；
#   堆 committed 只是 GC.heap_info 里的 young+old，**不含** Metaspace 与堆外。
#
# 依赖：macOS 自带 ps / vmmap；jcmd 由 --jdk / JAVA_HOME / PATH / Homebrew openjdk 提供。

set -uo pipefail

PROG=$(basename "$0")

APP=""
PID=""
SAMPLES=3
INTERVAL=5
OUT=""
WARMUP=5
STARTUP=30
EXTRA_ARG=""
FORCE_GC=0
JDK_DIR=""
KEEP=0
ATTACH_RETRIES=3
OUT_READY=0

# ---------------------------------------------------------------- 输出与错误

emit() {
  if [ "$OUT_READY" = 1 ] && [ -n "$OUT" ] && [ "$OUT" != "-" ]; then
    printf '%s\n' "$*" >>"$OUT"
  fi
  printf '%s\n' "$*"
}

die() {
  _code="$1"; shift
  printf '%s: 错误：%s\n' "$PROG" "$*" >&2
  exit "$_code"
}

# 用法说明直接从脚本自身的头部注释取，改注释不用同步改行号
usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --app)             APP="${2:-}"; shift 2 ;;
    --pid)             PID="${2:-}"; shift 2 ;;
    --samples)         SAMPLES="${2:-}"; shift 2 ;;
    --interval)        INTERVAL="${2:-}"; shift 2 ;;
    --out)             OUT="${2:-}"; shift 2 ;;
    --warmup)          WARMUP="${2:-}"; shift 2 ;;
    --startup)         STARTUP="${2:-}"; shift 2 ;;
    --args)            EXTRA_ARG="${2:-}"; shift 2 ;;
    --force-gc)        FORCE_GC=1; shift ;;
    --jdk)             JDK_DIR="${2:-}"; shift 2 ;;
    --keep)            KEEP=1; shift ;;
    --attach-retries)  ATTACH_RETRIES="${2:-}"; shift 2 ;;
    -h|--help)         usage ;;
    --)                shift; EXTRA_ARG="$*"; break ;;
    "")                shift ;;
    *)                 die 2 "未知参数：$1（--help 看用法）" ;;
  esac
done

# ---------------------------------------------------------------- 参数校验

[ -n "$APP" ] && [ -n "$PID" ] && die 2 "--app 与 --pid 只能二选一（现在两个都给了）"
[ -z "$APP" ] && [ -z "$PID" ] && die 2 "必须给 --app <path> 或 --pid <pid> 之一"

case "$SAMPLES" in ''|*[!0-9]*) die 2 "--samples 必须是正整数，收到 [$SAMPLES]";; esac
[ "$SAMPLES" -ge 1 ] || die 2 "--samples 必须 >= 1，收到 [$SAMPLES]"
case "$INTERVAL" in ''|*[!0-9]*) die 2 "--interval 必须是非负整数秒，收到 [$INTERVAL]";; esac
case "$WARMUP" in ''|*[!0-9]*) die 2 "--warmup 必须是非负整数秒，收到 [$WARMUP]";; esac
case "$STARTUP" in ''|*[!0-9]*) die 2 "--startup 必须是非负整数秒，收到 [$STARTUP]";; esac
case "$ATTACH_RETRIES" in ''|*[!0-9]*) die 2 "--attach-retries 必须是正整数，收到 [$ATTACH_RETRIES]";; esac
[ "$ATTACH_RETRIES" -ge 1 ] || die 2 "--attach-retries 必须 >= 1"

if [ -n "$PID" ]; then
  case "$PID" in ''|*[!0-9]*) die 2 "--pid 必须是数字，收到 [$PID]";; esac
  ps -p "$PID" >/dev/null 2>&1 || die 2 "--pid $PID 对应的进程不存在（先 ps -p $PID 确认）"
else
  # 路径不存在就立刻非 0 退出 —— 绝不「静默继续」然后报一堆空读数
  [ -e "$APP" ] || die 2 "--app 路径不存在：$APP"
  if [ -d "$APP" ]; then
    case "$APP" in
      *.app|*.app/) ;;
      *) die 2 "--app 是个目录但不是 .app 包：$APP" ;;
    esac
  elif [ ! -f "$APP" ]; then
    die 2 "--app 既不是普通文件也不是目录：$APP"
  fi
fi

if [ -n "$OUT" ] && [ "$OUT" != "-" ]; then
  _out_dir=$(dirname "$OUT")
  [ -d "$_out_dir" ] || mkdir -p "$_out_dir" || die 2 "无法创建 --out 的目录：$_out_dir"
  : >"$OUT" || die 2 "无法写入 --out：$OUT"
  OUT_READY=1
fi

# ---------------------------------------------------------------- 工具定位

# 依次在 --jdk / JAVA_HOME / PATH / Homebrew openjdk / 系统 JVM 目录里找可执行文件
find_tool() {
  _want="$1"
  if [ -n "$JDK_DIR" ]; then
    for _d in "$JDK_DIR" "$JDK_DIR/bin" "$JDK_DIR/Contents/Home/bin"; do
      [ -x "$_d/$_want" ] && { printf '%s\n' "$_d/$_want"; return 0; }
    done
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/$_want" ]; then
    printf '%s\n' "${JAVA_HOME}/bin/$_want"; return 0
  fi
  if command -v "$_want" >/dev/null 2>&1; then command -v "$_want"; return 0; fi
  for _d in /opt/homebrew/opt/openjdk*/bin /opt/homebrew/opt/openjdk@*/bin \
            /Library/Java/JavaVirtualMachines/*/Contents/Home/bin; do
    [ -x "$_d/$_want" ] && { printf '%s\n' "$_d/$_want"; return 0; }
  done
  return 1
}

JCMD=$(find_tool jcmd || true)
[ -n "$JCMD" ] || emit "注意：找不到 jcmd，堆指标将记为 n/a（可用 --jdk / JAVA_HOME 指定）"

# ---------------------------------------------------------------- 单位换算

# vmmap 的尺寸串（831.5M / 512K / 1.2G）→ KB 整数；解析不出来就给空串
size_to_kb() {
  awk -v s="$1" 'BEGIN{
    if (s == "" || s == "n/a") { print ""; exit }
    u = substr(s, length(s), 1)
    if (u != "K" && u != "M" && u != "G" && u != "T") { print ""; exit }
    n = substr(s, 1, length(s) - 1)
    if (n !~ /^[0-9.]+$/) { print ""; exit }
    m = 1
    if (u == "M") m = 1024
    else if (u == "G") m = 1024 * 1024
    else if (u == "T") m = 1024 * 1024 * 1024
    printf "%.0f", n * m
  }'
}

kb_to_human() {
  awk -v k="$1" 'BEGIN{
    if (k == "") { print "n/a"; exit }
    if (k >= 1048576) printf "%.1fG", k / 1048576
    else if (k >= 1024) printf "%.1fM", k / 1024
    else printf "%dK", k
  }'
}

# ---------------------------------------------------------------- 进程探针

# 判活要排除僵尸（ps -p 对 Z 进程仍然成功，但它的读数是上一个瞬间的残影）
pid_alive() {
  _st=$(ps -o state= -p "$1" 2>/dev/null | tr -d ' ')
  [ -n "$_st" ] || return 1
  case "$_st" in Z*) return 1 ;; esac
  return 0
}

# 进程不在就给 0（只在「活跃度」判定里用）；要区分「读不到」请用 rss_or_empty
rss_of() {
  _r=$(ps -o rss= -p "$1" 2>/dev/null | tr -d ' ')
  printf '%s\n' "${_r:-0}"
}

# 读不到（进程已不在、或 ps 失败）就给空串 —— 绝不用 0 冒充读数
rss_or_empty() {
  _r=$(ps -o rss= -p "$1" 2>/dev/null | tr -d ' ')
  printf '%s\n' "${_r:-}"
}

# 数「同一份可执行文件还起了几个实例」。为什么必须记：桌面端同时跑两份时，它们抢同一个
# 端口和同一个数据目录，读数会互相污染，而单看某一个 pid 的 footprint 完全看不出来。
# 判据用可执行文件路径的**前缀**匹配（不是 pgrep -f 的子串），避免把命令行里恰好提到
# 该路径的编辑器、grep、构建进程算进来。
EXE_PATH=""

sibling_count() {
  [ -n "$EXE_PATH" ] || { printf '0\n'; return 0; }
  ps -Ao command= 2>/dev/null | awk -v p="$EXE_PATH" '
    { cmd = $0; sub(/^[ \t]+/, "", cmd) }
    cmd == p { n++; next }
    index(cmd, p " ") == 1 { n++ }
    END { print n + 0 }'
}

# .app 的 launcher 有时会 fork 出真正吃内存的 JVM。判据：活着的直接子进程里 RSS 最大的那个。
resolve_target_pid() {
  _root="$1"
  _best="$_root"
  _best_rss=$(rss_of "$_root")
  for _c in $(pgrep -P "$_root" 2>/dev/null || true); do
    _cr=$(rss_of "$_c")
    if [ "$_cr" -gt "$_best_rss" ] 2>/dev/null; then _best="$_c"; _best_rss="$_cr"; fi
  done
  printf '%s\n' "$_best"
}

# ---------------------------------------------------------------- 启动被测程序

LAUNCH_PID=""
LAUNCH_LOG=""

# 把 --app 翻译成可执行命令（每行一个 argv 元素）
build_launch_cmd() {
  _app="$1"
  if [ -d "$_app" ]; then
    _base=$(basename "$_app")
    _base=${_base%.app}
    _exe="$_app/Contents/MacOS/$_base"
    if [ ! -x "$_exe" ] && [ -x /usr/libexec/PlistBuddy ]; then
      _be=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$_app/Contents/Info.plist" 2>/dev/null || true)
      [ -n "$_be" ] && [ -x "$_app/Contents/MacOS/$_be" ] && _exe="$_app/Contents/MacOS/$_be"
    fi
    if [ ! -x "$_exe" ]; then
      _exe=$(ls -1 "$_app/Contents/MacOS/"* 2>/dev/null | head -1)
    fi
    [ -n "$_exe" ] && [ -x "$_exe" ] || die 5 ".app 里找不到可执行文件：$_app/Contents/MacOS/"
    printf '%s\n' "$_exe"
    return 0
  fi
  case "$_app" in
    *.jar)
      _j=$(find_tool java || true)
      [ -n "$_j" ] || die 5 "要跑 jar 但找不到 java（用 --jdk 指定）"
      printf '%s\n' "$_j"
      printf '%s\n' "-jar"
      printf '%s\n' "$_app"
      return 0 ;;
  esac
  [ -x "$_app" ] || die 2 "--app 不是可执行文件：${_app}（chmod +x 或换 .app / .jar）"
  printf '%s\n' "$_app"
}

launch_target() {
  CMD=()
  while IFS= read -r _line; do CMD[${#CMD[@]}]="$_line"; done < <(build_launch_cmd "$APP")

  # macOS 的 mktemp 要求模板以 X 结尾，所以先建无后缀临时文件再改名 —— 否则会同时
  # 留下一个空的 XXXXXX 和一个 .log，收尾只删得掉后者，前者的垃圾就一直在。
  LAUNCH_LOG=$(mktemp "${TMPDIR:-/tmp}/measure-footprint-launch.XXXXXX") || die 5 "无法创建临时日志"
  mv "$LAUNCH_LOG" "$LAUNCH_LOG.log" || die 5 "无法重命名临时日志"
  LAUNCH_LOG="$LAUNCH_LOG.log"
  emit "启动命令 : ${CMD[*]} ${EXTRA_ARG:-}"
  # shellcheck disable=SC2086
  nohup "${CMD[@]}" ${EXTRA_ARG:-} >"$LAUNCH_LOG" 2>&1 &
  LAUNCH_PID=$!

  _waited=0
  while [ "$_waited" -lt "$STARTUP" ]; do
    if ! pid_alive "$LAUNCH_PID"; then
      emit "启动失败 ：进程 $LAUNCH_PID 在 ${_waited}s 内退出，启动日志（${LAUNCH_LOG}）末尾："
      tail -20 "$LAUNCH_LOG" 2>/dev/null | sed 's/^/  | /'
      exit 5
    fi
    if [ "$(rss_of "$LAUNCH_PID")" -gt 0 ] 2>/dev/null; then break; fi
    sleep 1
    _waited=$((_waited + 1))
  done

  PID=$(resolve_target_pid "$LAUNCH_PID")
  emit "启动完成 : launcher pid=${LAUNCH_PID}，被测量 pid=${PID}（日志 ${LAUNCH_LOG}）"
}

# 把「到底测的是哪个产物、带什么 JVM 参数」钉进证据头。产物随时可能被重新构建替换，
# 而 -Xmx / GC 一换读数就不可比 —— 没有这一段的读数事后无法复现。
# launch 与 attach 两种模式都调用它：attach 模式下从被测进程的命令行反推 .app 路径。
FPSHA=""
FPAPP=""

bundle_sha() {
  find "$1/Contents/app" -name '*.jar' -type f 2>/dev/null | LC_ALL=C sort \
    | xargs cat 2>/dev/null | shasum -a 256 | cut -d' ' -f1
}

describe_target() {
  _cmdline=$(ps -o command= -p "$PID" 2>/dev/null)
  emit "被测命令行: $(printf '%s' "$_cmdline" | cut -c1-400)"

  # argv0 = 可执行文件路径，后面才是参数（路径里可能有空格，所以从尾部按参数切不可靠；
  # .app 形态直接取到 /Contents/MacOS/<exe>，其余形态取第一个空白前的整段）
  case "$_cmdline" in
    */Contents/MacOS/*)
      _rest=${_cmdline#*/Contents/MacOS/}
      EXE_PATH=${_cmdline%%/Contents/MacOS/*}/Contents/MacOS/${_rest%% *}
      ;;
    *)
      EXE_PATH=${_cmdline%% *}
      ;;
  esac
  _sib=$(sibling_count)
  emit "同类实例  : $_sib 个（按可执行文件路径 ${EXE_PATH:-?} 精确匹配；>1 表示有别的实例在跑，读数可能互相污染）"

  # 从命令行里认出路径形状的 .app（--app 模式直接用入参；attach 模式从 argv0 反推）
  _apppath=""
  if [ -n "$APP" ]; then
    _apppath="$APP"
  else
    case "$_cmdline" in
      */Contents/MacOS/*) _apppath=${_cmdline%%/Contents/MacOS/*} ;;
    esac
  fi

  case "$_apppath" in
    *.app|*.app/)
      _plist="$_apppath/Contents/Info.plist"
      if [ -f "$_plist" ]; then
        emit "产物标识 : $(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$_plist" 2>/dev/null || echo '?') / version $(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$_plist" 2>/dev/null || echo '?')"
      fi
      _cfg="$_apppath/Contents/app/$(basename "$_apppath" .app).cfg"
      [ -f "$_cfg" ] || _cfg=$(ls -1 "$_apppath/Contents/app/"*.cfg 2>/dev/null | head -1)
      if [ -f "$_cfg" ]; then
        emit "JVM 参数 : $(grep -h '^java-options=' "$_cfg" | sed 's/^java-options=//' | tr '\n' ' ')"
      else
        emit "JVM 参数 : ?（找不到 $_apppath/Contents/app/*.cfg）"
      fi
      FPSHA=$(bundle_sha "$_apppath")
      FPAPP="$_apppath"
      emit "产物指纹 : Contents/app 下全部 jar 拼接后的 sha256 = ${FPSHA:-?}（重新构建会变，据此判断读数是否可比）"
      ;;
    *)
      # jar / 裸可执行文件：命令行本身就是能复现的全部信息（已在上面打印）
      ;;
  esac
}

stop_pid() {
  _p="$1"
  [ -n "$_p" ] || return 0
  pid_alive "$_p" || return 0
  kill -TERM "$_p" 2>/dev/null || true
  _n=0
  while [ "$_n" -lt 10 ]; do
    pid_alive "$_p" || return 0
    sleep 1
    _n=$((_n + 1))
  done
  pid_alive "$_p" && kill -KILL "$_p" 2>/dev/null || true
  return 0
}

cleanup() {
  [ -n "$LAUNCH_PID" ] || return 0
  if [ "$KEEP" = 1 ]; then
    emit "收尾     : --keep，保留本脚本拉起的进程 pid=$LAUNCH_PID"
    return 0
  fi
  # 先杀被测量进程，再杀 launcher 与它剩下的孩子（终态不留在用户机器上）
  stop_pid "${PID:-}"
  stop_pid "$LAUNCH_PID"
  for _c in $(pgrep -P "$LAUNCH_PID" 2>/dev/null || true); do stop_pid "$_c"; done
  # 先删临时日志再 emit：stdout 已被下游关掉时（SIGPIPE），任何一次 emit 都可能
  # 再次触发退出，把「删日志」这一句永远跳过，机器上就会一直积着临时文件。
  [ -n "$LAUNCH_LOG" ] && [ -f "$LAUNCH_LOG" ] && rm -f "$LAUNCH_LOG"
  if pid_alive "$LAUNCH_PID"; then
    emit "收尾     : 警告，pid=$LAUNCH_PID 未能终止（请手动 kill -9）"
  else
    emit "收尾     : 已终止本脚本拉起的进程（launcher pid=$LAUNCH_PID 及其子进程）"
  fi
  return 0
}
trap cleanup EXIT
trap 'exit 130' INT TERM
# 被管道下游（head/grep -m1）关掉 stdout 时 bash 会收到 SIGPIPE 直接退出，
# EXIT 陷阱因此不跑、拉起的进程就残留了。显式接住 PIPE，让收尾照常执行。
trap 'exit 141' PIPE

# ---------------------------------------------------------------- 采样

FP_ARR=(); PEAK_ARR=(); RSS_ARR=(); HEAP_ARR=()
VMMAP_FAILS=0
ATTACH_FAILS=0
PROC_EXITED=0
SAMPLED=0
MAX_SIBS=0

# 采样中途发现进程消失时置 1，主循环据此立刻收工（不重试、不往下采空读数）
GONE_MID_SAMPLE=0

sample_memory() {
  GONE_MID_SAMPLE=0
  VMMAP_OUT=$(vmmap -summary "$PID" 2>&1)
  VMMAP_RC=$?
  FP_LINE=""; PEAK_LINE=""; IOSURF_LINE=""; VMALLOC_LINE=""

  if [ "$VMMAP_RC" -ne 0 ]; then
    VMMAP_FAILS=$((VMMAP_FAILS + 1))
    emit "  vmmap    : unavailable（exit=${VMMAP_RC}，$(printf '%s' "$VMMAP_OUT" | head -1)）—— 继续采 RSS 与堆"
    emit "  Physical footprint: n/a"
    emit "  Physical footprint (peak): n/a"
    IOSURF_LINE="n/a（vmmap 不可用）"
    VMALLOC_LINE="n/a（vmmap 不可用）"
  else
    FP_LINE=$(printf '%s\n' "$VMMAP_OUT" | grep -a '^Physical footprint:' | head -1)
    PEAK_LINE=$(printf '%s\n' "$VMMAP_OUT" | grep -a '^Physical footprint (peak):' | head -1)
    IOSURF_LINE=$(printf '%s\n' "$VMMAP_OUT" | awk '$1 == "IOSurface" && $2 ~ /^[0-9.]+[KMG]?$/ {print; exit}')
    VMALLOC_LINE=$(printf '%s\n' "$VMMAP_OUT" | awk '$1 == "VM_ALLOCATE" && $2 ~ /^[0-9.]+[KMG]?$/ {print; exit}')
    if [ -n "$FP_LINE" ]; then emit "  $FP_LINE"; else emit "  Physical footprint: n/a（vmmap 输出里没有这一行）"; fi
    [ -n "$PEAK_LINE" ] && emit "  $PEAK_LINE"
    [ -n "$IOSURF_LINE" ] || IOSURF_LINE="n/a（无 IOSurface 区域）"
    [ -n "$VMALLOC_LINE" ] || VMALLOC_LINE="n/a（无 VM_ALLOCATE 区域）"
  fi

  FP_KB=$(size_to_kb "$(printf '%s\n' "$FP_LINE" | awk '{print $3}')")
  PEAK_KB=$(size_to_kb "$(printf '%s\n' "$PEAK_LINE" | awk '{print $4}')")

  RSS_KB=$(rss_or_empty "$PID")
  if [ -n "$RSS_KB" ]; then
    emit "  RSS      : ${RSS_KB} KB（$(kb_to_human "$RSS_KB")；ps -o rss=，含共享页）"
  else
    emit "  RSS      : n/a"
  fi

  HEAP_KB=""
  JCMD_OUT=""; JCMD_RC=1; JCMD_GONE=0
  if [ -n "$JCMD" ]; then
    _try=1
    while : ; do
      JCMD_OUT=$("$JCMD" "$PID" GC.heap_info 2>&1)
      JCMD_RC=$?
      # 进程真的没了：jcmd 报 No such process / target process not found。这不是 attach 抖动，
      # 一个字节都不重试，交给主循环立刻报「进程已退出」。
      if printf '%s' "$JCMD_OUT" | grep -q 'No such process\|target process not found\|Process .* not found'; then
        JCMD_GONE=1; break
      fi
      if [ "$JCMD_RC" -eq 0 ] && ! printf '%s' "$JCMD_OUT" | grep -q 'AttachNotSupportedException\|Unable to open socket'; then
        break
      fi
      [ "$_try" -ge "$ATTACH_RETRIES" ] && break
      _try=$((_try + 1))
      sleep 1
    done
  fi

  if [ "$JCMD_GONE" = 1 ]; then
    GONE_MID_SAMPLE=1
    # 进程在本次采样中途消失：本样本缺堆读数，但这不是「量错了」而是「被测对象没了」
    emit "  jcmd     : 进程已退出（jcmd 报 $(printf '%s' "$JCMD_OUT" | grep -aE 'No such process|target process not found|Process .* not found' | head -1)）"
    emit "  def new / tenured / Metaspace: n/a（进程已退出）"
  elif [ "$JCMD_RC" -ne 0 ] || printf '%s' "$JCMD_OUT" | grep -q 'AttachNotSupportedException\|Unable to open socket'; then
    ATTACH_FAILS=$((ATTACH_FAILS + 1))
    emit "  jcmd     : attach-failed（pid=${PID}，重试 $ATTACH_RETRIES 次仍失败：$(printf '%s' "$JCMD_OUT" | grep -aE 'AttachNotSupportedException|Unable to open socket|Exception' | head -1)）"
    emit "  def new / tenured / Metaspace: n/a（attach-failed）"
  else
    _l=$(printf '%s\n' "$JCMD_OUT" | awk '/^[[:space:]]*def new generation/ {print; exit}')
    [ -n "$_l" ] && emit "  | $_l"
    _young=$(printf '%s\n' "$_l" | awk '{for(i=1;i<=NF;i++) if($i=="total"){v=$(i+1); sub(/,?$/,"",v); sub(/K$/,"",v); print v; exit}}')

    _l=$(printf '%s\n' "$JCMD_OUT" | awk '/^[[:space:]]*tenured generation/ {print; exit}')
    [ -n "$_l" ] && emit "  | $_l"
    _old=$(printf '%s\n' "$_l" | awk '{for(i=1;i<=NF;i++) if($i=="total"){v=$(i+1); sub(/,?$/,"",v); sub(/K$/,"",v); print v; exit}}')

    _l=$(printf '%s\n' "$JCMD_OUT" | awk '/^[[:space:]]*Metaspace/ {print; exit}')
    [ -n "$_l" ] && emit "  | $_l"
    _meta=$(printf '%s\n' "$_l" | awk '{for(i=1;i<=NF;i++) if($i=="committed"){v=$(i+1); sub(/,?$/,"",v); sub(/K$/,"",v); print v; exit}}')

    # 换 GC 也能用：G1 / Parallel 的堆不是 def new + tenured
    if [ -z "$_young" ] && [ -z "$_old" ]; then
      _l=$(printf '%s\n' "$JCMD_OUT" | grep -aE '^[[:space:]]*(garbage-first heap|PSYoungGen|ParOldGen)' | head -1)
      [ -n "$_l" ] && emit "  | $_l"
      _young=$(printf '%s\n' "$_l" | awk '{for(i=1;i<=NF;i++) if($i=="total"){v=$(i+1); sub(/,?$/,"",v); sub(/K$/,"",v); print v; exit}}')
      _old=""
    fi

    if [ -n "$_young" ] && [ -n "$_old" ]; then
      HEAP_KB=$((_young + _old))
    elif [ -n "$_young" ]; then
      HEAP_KB="$_young"
    fi
    if [ -n "$HEAP_KB" ]; then
      emit "  堆 committed: $(kb_to_human "$HEAP_KB")（young $(kb_to_human "$_young") + old $(kb_to_human "$_old")），Metaspace $(kb_to_human "$_meta")"
    else
      emit "  堆 committed: n/a（GC.heap_info 格式不认识）"
    fi
  fi

  emit "  IOSurface: $IOSURF_LINE"
  emit "  VM_ALLOCATE: $VMALLOC_LINE"
  _sib_now=$(sibling_count)
  [ "$_sib_now" -gt "$MAX_SIBS" ] && MAX_SIBS="$_sib_now"
  emit "  同类实例数: ${_sib_now}（采样点当时的瞬时读数，>1 提示本组不可比）"

  FP_ARR[${#FP_ARR[@]}]="$FP_KB"
  PEAK_ARR[${#PEAK_ARR[@]}]="$PEAK_KB"
  RSS_ARR[${#RSS_ARR[@]}]="$RSS_KB"
  HEAP_ARR[${#HEAP_ARR[@]}]="$HEAP_KB"
  SAMPLED=$((SAMPLED + 1))
}

# ---------------------------------------------------------------- 表头与汇总

print_header() {
  emit "=================================================================="
  emit " 桌面端内存测量 — $PROG"
  emit "=================================================================="
  emit "时间      : $(date '+%Y-%m-%d %H:%M:%S %z')"
  emit "主机      : $(uname -m) / $(sw_vers -productName 2>/dev/null || echo macOS) $(sw_vers -productVersion 2>/dev/null || echo '?')（build $(sw_vers -buildVersion 2>/dev/null || echo '?')）"
  _jbin=$(find_tool java || true)
  if [ -n "$_jbin" ]; then
    emit "JVM       : $("$_jbin" -version 2>&1 | head -1)（${_jbin}）"
  else
    emit "JVM       : unknown（找不到 java）"
  fi
  emit "jcmd      : ${JCMD:-not found}"
  emit "vmmap     : $(command -v vmmap || echo 'not found')"
  emit "系统负载  : load average $(uptime | sed 's/.*load averages: //')"
  if [ -n "$APP" ]; then
    emit "模式      : launch（--app ${APP}；传给应用的参数「${EXTRA_ARG:-}」）"
  else
    emit "模式      : attach（--pid ${PID}；不启动也不终止被测进程）"
  fi
  emit "参数      : --samples $SAMPLES --interval ${INTERVAL}s --warmup ${WARMUP}s --force-gc $FORCE_GC --attach-retries $ATTACH_RETRIES"
  emit ""
}

print_summary() {
  emit ""
  emit "---------------- 汇总 ----------------"
  emit "样本数              : ${SAMPLED}（请求 ${SAMPLES}）"
  if [ "$SAMPLED" -eq 0 ]; then
    emit "Physical footprint  : n/a（没有采到任何样本）"
    emit "RSS                 : n/a（没有采到任何样本）"
    emit "堆 committed        : n/a（没有采到任何样本）"
  else
    _last=$((SAMPLED - 1))
    _peak=""; _i=0
    while [ "$_i" -lt "$SAMPLED" ]; do
      _p="${PEAK_ARR[$_i]}"
      if [ -n "$_p" ]; then
        if [ -z "$_peak" ] || [ "$_p" -gt "$_peak" ]; then _peak="$_p"; fi
      fi
      _i=$((_i + 1))
    done
    emit "Physical footprint  : 首 $(kb_to_human "${FP_ARR[0]}") / 末 $(kb_to_human "${FP_ARR[$_last]}") / 峰值 $(kb_to_human "$_peak")（vmmap -summary，含 GPU/IOSurface）"
    emit "RSS                 : 首 $(kb_to_human "${RSS_ARR[0]}") / 末 $(kb_to_human "${RSS_ARR[$_last]}")（ps -o rss=，含共享页）"
    emit "堆 committed        : 首 $(kb_to_human "${HEAP_ARR[0]}") / 末 $(kb_to_human "${HEAP_ARR[$_last]}")（GC.heap_info young+old，不含 Metaspace）"
    emit "逐样本 footprint    :"
    _i=0
    while [ "$_i" -lt "$SAMPLED" ]; do
      emit "  #$((_i + 1))  footprint=$(kb_to_human "${FP_ARR[$_i]}")  峰值=$(kb_to_human "${PEAK_ARR[$_i]}")  RSS=$(kb_to_human "${RSS_ARR[$_i]}")  堆=$(kb_to_human "${HEAP_ARR[$_i]}")"
      _i=$((_i + 1))
    done
  fi
  if [ -n "$FPSHA" ]; then
    _after=$(bundle_sha "$FPAPP")
    if [ "$_after" = "$FPSHA" ]; then
      emit "产物指纹一致        : 采样前后同为 ${FPSHA}（读数属于同一个二进制）"
    else
      emit "产物指纹已变        : 采样前 ${FPSHA} → 采样后 ${_after:-?}（本组读数跨越了一次替换，不可直接比较，需重跑）"
    fi
  fi
  emit "采样期间同类实例    : 最多 ${MAX_SIBS:-0} 个（>1 的样本读数不可与其他实例的读数直接比较）"
  emit "vmmap 不可用次数    : $VMMAP_FAILS"
  emit "jcmd attach 失败次数: ${ATTACH_FAILS}（按行记 attach-failed，未中断测量）"
  if [ "$PROC_EXITED" = 1 ]; then
    emit "进程状态            : 进程已退出（process exited）—— 采样在第 $SAMPLED 个样本后停止"
  else
    emit "进程状态            : 采样期间存活"
  fi
  [ -n "$PID" ] && emit "被测 pid            : $PID"
  emit "--------------------------------------"
}

# ---------------------------------------------------------------- 主流程

print_header

if [ -n "$APP" ]; then
  launch_target
  [ -n "$PID" ] || die 5 "启动后没能定位到被测量的进程"
  sleep "$WARMUP"
fi

describe_target

if [ "$FORCE_GC" = 1 ]; then
  if [ -n "$JCMD" ]; then
    emit "force-gc  : 执行 jcmd $PID GC.run（会改进程内状态，只在显式开启时跑）"
    "$JCMD" "$PID" GC.run >/dev/null 2>&1 || emit "force-gc  : GC.run 失败（不影响后续采样）"
    sleep 1
  else
    emit "force-gc  : 跳过（没有 jcmd）"
  fi
fi

emit "开始采样 : pid=${PID}，共 $SAMPLES 个点，间隔 ${INTERVAL}s"
emit ""

_i=1
while [ "$_i" -le "$SAMPLES" ]; do
  if ! pid_alive "$PID"; then
    PROC_EXITED=1
    emit ""
    emit "[样本 $_i/$SAMPLES] 进程已退出：pid=$PID 在采样间隔内消失（用户退出 / 崩溃 / 被 kill）"
    emit "  立即停止采样，不输出空读数。已完成的样本数：$SAMPLED"
    break
  fi
  emit "[样本 $_i/$SAMPLES] pid=$PID  t=+$(((_i - 1) * INTERVAL))s"
  sample_memory
  emit ""
  if [ "$GONE_MID_SAMPLE" = 1 ]; then
    PROC_EXITED=1
    emit "[样本 $_i/$SAMPLES] 进程已退出：pid=$PID 在本次采样中途消失（用户退出 / 崩溃 / 被 kill）"
    emit "  立即停止采样，不输出空读数。已完成 ${SAMPLED} 个样本，其中最后一个的堆读数缺失。"
    break
  fi
  [ "$_i" -lt "$SAMPLES" ] && sleep "$INTERVAL"
  _i=$((_i + 1))
done

print_summary

if [ "$OUT_READY" = 1 ] && [ "$OUT" != "-" ]; then
  emit "输出已写入: $OUT"
fi

[ "$PROC_EXITED" = 1 ] && exit 6
exit 0
