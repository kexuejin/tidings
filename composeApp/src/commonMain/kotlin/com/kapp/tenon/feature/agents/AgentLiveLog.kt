// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tenon.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kapp.tenon.ui.theme.LocalAppSpacing
import com.kapp.tenon.generated.resources.Res
import com.kapp.tenon.generated.resources.agent_live_log_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 实时输出缓冲（T-G3）：delta 类增量的**进程内**环形仓 —— 桌面侧（agentNode 的
 * liveAgentEvents 流）写、Inspector 面板读。不落 SQLite（不变量 3），重启即逝是
 * 设计语义。单例：desktopApp 是唯一的写者（collect 流），composeApp 的壳是唯一
 * 的读者 —— 依赖方向 desktopApp → composeApp 合法。
 *
 * 缓冲用**不可变 Map + CAS** 而不是 `ConcurrentHashMap` / `synchronized`：后两者都是
 * JVM-only，写在 commonMain 会让 iOS 目标编译不过（默认验收链只跑 JVM/Android，
 * 症状是「门禁全绿但 iOS 编译红」）。`AtomicReference` 是 stdlib 的跨平台原子类型，
 * 不引入新依赖；每次 append 只替换一个会话的 List，读侧拿到的永远是完整快照，
 * 不需要逐 deque 加锁。
 */
@OptIn(ExperimentalAtomicApi::class)
object AgentLiveLog {

    private const val MAX_LINES_PER_SESSION = 200

    private val buffer = AtomicReference<Map<String, List<String>>>(emptyMap())

    /** 版本号：append 时自增 —— 读侧读它触发重组（比 ObservableList 轻）。 */
    private val revision by mutableStateOf(0L)

    fun append(sessionId: String, line: String) {
        while (true) {
            val before = buffer.load()
            val kept = (before[sessionId].orEmpty() + line).takeLast(MAX_LINES_PER_SESSION)
            if (buffer.compareAndSet(before, before + (sessionId to kept))) break
        }
        bump()
    }

    fun snapshot(sessionId: String): List<String> {
        revision // 读：append 触发重组
        return buffer.load()[sessionId].orEmpty()
    }

    fun clear(sessionId: String) {
        while (true) {
            val before = buffer.load()
            if (sessionId !in before) break
            if (buffer.compareAndSet(before, before - sessionId)) break
        }
        bump()
    }

    private fun bump() {
        mutableRevision.value = mutableRevision.value + 1
    }

    private val mutableRevision = mutableStateOf(0L)
}

/**
 * 实时输出面板（T-G3）：会话维度的流式日志视图（桌面 Inspector 挂载）。
 * 空会话 / 无输出 = 面板不画（inspector 是附加信息，空着比填料诚实）。
 */
@Composable
fun AgentLiveLogPanel(sessionId: String?, modifier: Modifier = Modifier) {
    if (sessionId == null) return
    // snapshot 内部读 revision 状态：直接调用才会在 append 后触发重组；
    // remember(sessionId) 只会把首帧快照钉死，看起来「不实时」。
    val lines = AgentLiveLog.snapshot(sessionId)
    if (lines.isEmpty()) return
    val scroll = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.agent_live_log_title),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.4f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .verticalScroll(scroll)
                .padding(LocalAppSpacing.current.md10),
        ) {
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
