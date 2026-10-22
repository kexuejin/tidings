// SPDX-License-Identifier: GPL-3.0-only

package app.relay.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import java.io.File

/**
 * 空格诊断的**最小对照窗口**（默认关闭）。
 *
 * ## 它回答的唯一一个问题
 *
 * 「空格进不来」到底在**本仓的壳层**，还是在 **CMP 的 desktop 桥接**？
 *
 * 这个窗口与产品同进程、同 Compose 栈、同 AWT/Skiko 桥接，唯一的差别是**不含产品壳**
 * （没有 DesktopShell 的快捷键处理器、没有 ChatScreen、没有 DesktopComposer 的外层容器）。
 * 于是：
 *
 *   · 这里空格**能**打进去 ⇒ 缺陷在壳层（本仓代码），CMP 是无辜的；
 *   · 这里空格**也**打不进去 ⇒ 缺陷在 CMP 的 desktop 输入桥接，改壳层永远修不好。
 *
 * ## 为什么不能靠"读代码"回答
 *
 * 这个缺陷被报了四轮，每一轮的结论都停在"读代码看不出来"。而前面几轮已经证明：
 * 隔离场景（ImageComposeScene）里空格完全正常 —— 那是**无窗口**的渲染；真实窗口里
 * 空格连 `KEY_PRESSED` 都没有（AWT 全局监听器证明）。两者之间差的正是这个窗口。
 *
 * ## 怎么打开
 *
 * 数据目录里放一个 `space-lab.on` 标记文件，重启应用即可。它不会出现在托盘、菜单或
 * 任何用户可见入口里 —— 这是诊断设施，不是产品功能。
 *
 * 关闭方式：删掉标记文件，重启。
 */
object SpaceLab {

    private const val FLAG_FILE_NAME = "space-lab.on"

    @Volatile
    private var enabled = false

    fun install(dataDirectory: String) {
        enabled = System.getenv("RELAY_SPACE_LAB") == "1" || File(dataDirectory, FLAG_FILE_NAME).exists()
    }

    fun isEnabled(): Boolean = enabled
}

/**
 * 最小窗口：一个输入框 + 一行实时读数。
 *
 * 读数三格各自回答一件事：
 *   · [letters]：字母能不能进门（对照组 —— 它必须一直是通的）；
 *   · [spaces]：空格能不能进门（这就是要测的）；
 *   · [lastEvent]：**最近一次按键事件的原始值** —— 如果空格连事件都没有，
 *     这一行会一直停在上一个字母上，那就说明空格根本没送到这个窗口。
 */
@Composable
internal fun SpaceLabWindow() {
    Window(onCloseRequest = { /* 诊断窗口，不自带关闭语义 */ }, title = "space lab") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var value by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
            var value2 by remember { mutableStateOf(TextFieldValue("", TextRange(0))) }
            var letters by remember { mutableStateOf(0) }
            var spaces by remember { mutableStateOf(0) }
            var letters2 by remember { mutableStateOf(0) }
            var spaces2 by remember { mutableStateOf(0) }
            var lastEvent by remember { mutableStateOf("-") }
            var lastEvent2 by remember { mutableStateOf("-") }
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("space lab", style = MaterialTheme.typography.titleMedium)

                // A 组：与产品同款（挂 preview 处理器）。
                Text("A (with preview handler)  letters=$letters  spaces=$spaces  last=$lastEvent",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { next ->
                        // 只数"变长的部分"：删字符不该被算成一次输入。
                        val added = next.text.length - value.text.length
                        if (added > 0) {
                            val tail = next.text.takeLast(added)
                            spaces += tail.count { it == ' ' }
                            letters += tail.count { it != ' ' }
                        }
                        value = next
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                lastEvent = "key=${event.key}"
                            }
                            false
                        },
                )

                // B 组：**完全相同**的输入框，但**不挂** preview 处理器。
                //
                // 这是唯一能把"preview 处理器是否本身就是触发条件"这件事分开的对照组：
                // 两个框在同一个窗口、同一时刻、同一种输入源下挨着。A 打不进 B 打得进
                // ⇒ preview 处理器是元凶；两个都打不进 ⇒ 与它无关，在更底层。
                Text("B (no preview handler)  letters=$letters2  spaces=$spaces2  last=$lastEvent2",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value2,
                    onValueChange = { next ->
                        val added = next.text.length - value2.text.length
                        if (added > 0) {
                            val tail = next.text.takeLast(added)
                            spaces2 += tail.count { it == ' ' }
                            letters2 += tail.count { it != ' ' }
                        }
                        value2 = next
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
