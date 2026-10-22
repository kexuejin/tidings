// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.io.File
import java.text.AttributedCharacterIterator
import java.time.Instant

/**
 * 按键诊断：把**系统分发到进程的每一个按键**如实记下来。
 *
 * ## 为什么需要它
 *
 * 「桌面端打不进空格」被报了三轮，每轮的结论都停在「读代码看不出来」：输入框本身在
 * 隔离环境里收得到空格，所以问题只可能发生在**它之前**——按键根本没走到输入框，
 * 或者走过去了但类型不对（按住/释放，而不是"输入了一个字符"）。
 *
 * 这一层用 AWT 的全局事件监听器（`addAWTEventListener`，KEY_EVENT mask）在
 * **应用看到按键的第一个点**记账：id（按下/释放/输入字符）、keyCode、keyChar、
 * modifiers、以及事件最终落在哪个组件类上。有了这份记录，"空白"就能被二分：
 *
 *   · 记录里有 `KEY_TYPED keyChar=32`  ⇒ 按键到了应用，问题在应用内部（分发/输入框）；
 *   · 记录里只有 `KEY_PRESSED/RELEASED keyCode=32`、没有 `KEY_TYPED`
 *     ⇒ 系统没把它当成"输入字符"（输入法/硬件键盘层），应用再怎么改也没用；
 *   · 记录里什么都没有 ⇒ 事件根本没进本进程（全局热键、辅助功能权限、别的 app 抢焦点）。
 *
 * 三个结论指向三个完全不同的修法，这正是这条诊断存在的理由。
 *
 * ## 为什么是常驻而不是"出问题时手动打开"
 *
 * 它只写一个很小、固定的文件，且**只在按键时追加**；出问题那一刻用户未必会想到去开诊断。
 * 与"输入法自检"那一页同一立场：把仪器放进产品里，用户报问题时截一段就能说清。
 *
 * 默认关闭，由环境变量 `RELAY_KEY_TRACE=1` 打开 —— 常驻监听每个按键对隐私是一种
 * 侵入（键值会落在磁盘上），所以不让它默认就开着。
 */
internal object DesktopKeyTrace {

    private const val ENABLED_ENV = "RELAY_KEY_TRACE"
    private const val FILE_NAME = "key-trace.log"

    /** 记录文件最多留这么多字节，超出就重命名成 .1（只留最近一段，不做轮转链）。 */
    private const val MAX_BYTES = 512L * 1024

    @Volatile
    private var installed = false

    @Volatile
    private var file: File? = null

    /** 目录里放了它就算打开 —— 不依赖启动方式（程序坞/自动登录/命令行启动的 env 各不相同）。 */
    private const val FLAG_FILE_NAME = "key-trace.on"

    fun install(dataDirectory: String) {
        if (installed) return
        // 两种打开方式，任一即可：
        //   · 环境变量 `RELAY_KEY_TRACE=1`：适合从终端起的实例；
        //   · 数据目录里的标记文件 `key-trace.on`：适合**用户正常点图标启动**的实例 ——
        //     这条是必需的，因为 macOS 从程序坞/登录项起的进程拿不到交互 shell 的 env。
        //     （踩过：用 env 启动的诊断实例被关掉后，用户再点是另一个没带 env 的进程，
        //     日志里只剩一行"installed"，看起来像"按键彻底没进来"。）
        val enabledByEnv = System.getenv(ENABLED_ENV) == "1"
        val enabledByFlag = File(dataDirectory, FLAG_FILE_NAME).exists()
        if (!enabledByEnv && !enabledByFlag) return
        val dir = runCatching { File(dataDirectory).also { it.mkdirs() } }.getOrNull() ?: return
        file = File(dir, FILE_NAME)
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
                when (event) {
                    is KeyEvent -> record(event)
                    // **macOS 上真正的文字输入不走 KeyEvent。**
                    //
                    // 上一版只记 AWT 按键，结果"空格没出现"被误读成"系统没送进来" ——
                    // 而事实上 Compose Desktop 的文字是经 AWT 的输入法会话
                    // （`InputMethodEvent`）提交的：字母、空格、中文候选词都由
                    // `committedText` 一栏进来，根本不会产生 `KEY_TYPED`。
                    // 只记按键会得到一份"看起来什么都没发生"的日志，这是量具本身的盲区。
                    is InputMethodEvent -> recordIme(event)
                }
            }, AWTEvent.KEY_EVENT_MASK or AWTEvent.INPUT_METHOD_EVENT_MASK)
            // **比 AWTEventListener 更早的一站**：KeyEventDispatcher 链。
            //
            // 派发顺序（JDK EventQueue 源码）：KeyEventDispatcher 链 → AWTEventListener →
            // 焦点组件。所以链里任何一个 dispatcher 返回 true，事件就被判为"已处理"，
            // **后面的 AWTEventListener 根本看不到它** —— 这正好能解释"字母有记录、
            // 空格连 PRESSED 都没有"。
            //
            // 这里注册一个**只读**的 dispatcher：记账后一律返回 false（绝不拦事件），
            // 所以它能证明"空格到底进没进 JVM"，而不会自己改变行为。
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { event ->
                record(event, stage = "DISPATCH")
                false
            }
            installed = true
            append("trace installed: pid=${ProcessHandle.current().pid()}")
            append("java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            dumpAwtPipeline()
        }.onFailure {
            // 诊断输出走英文：它不是界面文案，进 strings.xml 反而更别扭；
            // 而 CJK 字面量门禁守的正是「生产源码里不新增硬编码中文」。
            println("relay: key trace not installed (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    /**
     * 把 AWT 的**按键前置拦截链**摊开写进日志。
     *
     * ## 为什么要看这一层
     *
     * AWT 派发按键的顺序是：
     *   1. `KeyboardFocusManager` 的 `KeyEventDispatcher` 链 —— **链里任何一个返回 true，
     *      事件就被判定为"已处理"，后面的所有人（包括 `AWTEventListener`）都看不到**；
     *   2. `AWTEventListener`（本诊断所在的层）；
     *   3. 焦点组件 → Compose。
     *
     * 于是"字母能到、空格连一条事件都没有"有一种极其符合的机制：链里有某个 dispatcher
     * 专门吞空格（比如把空格当"激活当前控件"的键），字母放行。
     *
     * 这些 dispatcher 是**别人注册的**（CMP/skiko/JDK 内部），静态读仓内代码看不到，
     * 所以只能在运行时反射列出。列出的是**类型名**，不含任何用户数据。
     *
     * 反射读私有字段在任何 JDK 上都可能被模块系统拒绝 —— 所以整个方法包在
     * `runCatching` 里，拿不到就如实写一行"拿不到"，绝不假装那里是空的
     * （"空"与"读不到"是两件事，混起来就会得出相反结论）。
     */
    private fun dumpAwtPipeline() {
        val read = runCatching {
            val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val field = kfm.javaClass.superclass.getDeclaredField("keyEventDispatchers")
            field.isAccessible = true
            val chain = (field.get(kfm) as? java.util.LinkedList<*>)?.toList().orEmpty()
            val names = chain.map { it?.javaClass?.name ?: "null" }
            val post = runCatching {
                val f2 = kfm.javaClass.superclass.getDeclaredField("keyEventPostProcessors")
                f2.isAccessible = true
                ((f2.get(kfm) as? java.util.LinkedList<*>)?.toList().orEmpty())
                    .map { it?.javaClass?.name ?: "null" }
            }.getOrElse { listOf("read-failed:" + it.javaClass.simpleName) }
            "dispatchers=" + names + " postProcessors=" + post
        }
        append("awt-chain " + read.getOrElse { "UNREADABLE: " + it.javaClass.simpleName + ": " + it.message })
    }

    private fun record(e: KeyEvent, stage: String = "AWT") {
        val id = when (e.id) {
            KeyEvent.KEY_PRESSED -> "PRESSED"
            KeyEvent.KEY_RELEASED -> "RELEASED"
            KeyEvent.KEY_TYPED -> "TYPED"
            else -> "ID${e.id}"
        }
        // keyChar 对 TYPED 才有意义：它是"文字"；对按下/释放，keyCode 才有意义。
        val ch = e.keyChar
        val charText = when {
            ch == KeyEvent.CHAR_UNDEFINED -> "undef"
            ch.code in 32..126 -> "'${ch}'(${ch.code})"
            else -> "U+${ch.code.toString(16).uppercase()}"
        }
        val mods = buildString {
            if (e.isShiftDown) append("shift ")
            if (e.isControlDown) append("ctrl ")
            if (e.isAltDown) append("alt ")
            if (e.isMetaDown) append("meta ")
            if (e.isAltGraphDown) append("altgr ")
        }.trim().ifEmpty { "-" }
        val source = e.component?.let { c ->
            val n = c.javaClass.name
            // 只留最后一段类名：完整名动辄两百字符，读起来全是包名。
            "${n.substringAfterLast('.').take(60)}@${System.identityHashCode(c).toString(16)}"
        } ?: "none"
        append("$stage $id keyCode=${e.keyCode} char=$charText mods=$mods on=$source")
    }

    /**
     * 记录一次输入法事件 —— **这才是 macOS 上文字进门的地方**。
     *
     * `committedText` 是"已经确定的文字"（字母、空格、选中的汉字都在这里），
     * `composingText` 是"还在拼写、没确定的预编辑串"（拼音）。两者分开记，
     * 因为它们的语义完全不同：
     *
     *   · 空格在 `committed` 里 ⇒ 系统把它当文字提交了，问题在应用接了之后处理错；
     *   · 空格只出现在 `composing` 里、或者干脆没有 ⇒ 它被当成"选词/上屏"的指令
     *     交给了输入法，Compose 这一侧根本没有收到那个字符。
     *
     * `consumed` 也要记：Compose 的 mediator 处理完会 `event.consume()`，
     * 一个"没被消费"的 IME 事件说明它压根没被这个输入会话接住。
     */
    /**
     * 让别的诊断模块也能往同一份日志里写一行（如 [DesktopSpaceKeyFix] 的判定过程）。
     *
     * 统一到一个文件是刻意的：排查"空格去哪了"时，按键、输入法、补丁判定必须
     * **按时间排在一起**看，分成两个文件就再也对不上先后。
     */
    internal fun note(tag: String, message: String) {
        if (!installed) return
        append("$tag $message")
    }

    private fun recordIme(e: InputMethodEvent) {
        val text = e.text
        val committedCount = e.committedCharacterCount
        val committed = text.iteratorSlice(0, committedCount)
        val composing = text.iteratorSlice(committedCount, null)
        append(
            "IME committed=" + quote(committed) +
                " composing=" + quote(composing) +
                " consumed=" + e.isConsumed +
                " on=" + (e.source?.let { it.javaClass.name.substringAfterLast('.').take(60) } ?: "none"),
        )
    }

    /**
     * 从 `AttributedCharacterIterator` 里取一段。
     *
     * AWT 的 IME 文本是"两段拼在一个 iterator 里"：前 `committedCharacterCount` 个字符
     * 是已确定的文字，后面才是预编辑串。这个切法就是 AWT 自己的口径，不能靠字符串
     * 长度去猜（预编辑串的字符数和它占的格数不一定相等）。
     */
    private fun AttributedCharacterIterator?.iteratorSlice(from: Int, to: Int?): String {
        if (this == null) return ""
        val end = to?.coerceAtMost(endIndex) ?: endIndex
        if (from >= end) return ""
        val sb = StringBuilder(end - from)
        setIndex(from)
        while (index < end) {
            sb.append(current())
            val ch = next()
            if (ch == AttributedCharacterIterator.DONE) break
        }
        return sb.toString()
    }

    /** 把一段文字转成可读且不含歧义的记法：空白字符显式写出来。 */
    private fun quote(s: String): String {
        if (s.isEmpty()) return "\"\""
        val body = s.map { ch ->
            when {
                ch == ' ' -> "<space>"
                ch == '\n' -> "<lf>"
                ch == '\t' -> "<tab>"
                ch.code < 32 -> "<U+" + ch.code.toString(16).uppercase() + ">"
                else -> ch.toString()
            }
        }.joinToString("")
        return "\"" + body + "\""
    }

    private fun append(line: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val backup = File(f.parentFile, "$FILE_NAME.1")
                f.copyTo(backup, overwrite = true)
                f.delete()
            }
            f.appendText("${Instant.now()} $line\n")
        }
    }
}
