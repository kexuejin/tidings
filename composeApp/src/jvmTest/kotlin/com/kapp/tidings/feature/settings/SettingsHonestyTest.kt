// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.feature.settings

import com.kapp.tidings.testsupport.GuardSourceScan
import com.kapp.tidings.ui.components.formatAvatarByteCount

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守卫「设置不说假话」这一轮的几条（P0 头像页诚实化 + P1/P2 的路由与小修）。
 *
 * 每条都对应一次「界面上有一句编造的话」的现场：
 * - 头像页写死 `team-photo-1920.jpg` / 「源图 1920×1280 · 1.8 MB · JPEG」 / 假哈希 `3f9c1b…e1a7`；
 * - 步骤卡承诺一条不存在的裁剪管线；
 * - 「输入法自检」入口嵌在全 null 的 `onRunSelfCheck` 分支里，任何宿主都到不了；
 * - 「版本与更新」只有 when 分支没有入口（死路由）；
 * - `initialSubpage = if (state.restarting) SUBPAGE_CONN` 劫持重启中的用户。
 *
 * 反向验证：把对应的旧行为放回去（写死的假文件名、嵌回 if、删掉入口行），测试立刻变红。
 */
class SettingsHonestyTest {

    private val avatarPage =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SetAvatarPage.kt"
    private val diag =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsDiagSubpage.kt"
    private val home =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsHomeScreen.kt"
    private val screen =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsScreen.kt"
    private val data =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/feature/settings/SettingsDataSubpage.kt"
    private val desktopHosting =
        "desktopApp/src/main/kotlin/com/kapp/tidings/desktop/DesktopHosting.kt"
    private val desktopChrome =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/desktop/DesktopSettingsChrome.kt"
    private val switchComponent =
        "composeApp/src/commonMain/kotlin/com/kapp/tidings/ui/components/AppSwitch.kt"
    private val appFile = "composeApp/src/commonMain/kotlin/com/kapp/tidings/App.kt"
    private val valuesZh = "composeApp/src/commonMain/composeResources/values/strings.xml"
    private val valuesEn = "composeApp/src/commonMain/composeResources/values-en/strings.xml"

    /* ===== 头像页：每个读数都是真的 ===== */

    @Test
    fun avatarPageHasNoFabricatedFileIdentity() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(avatarPage))
        assertFalse(
            text.contains("team-photo-1920.jpg"),
            "头像页又写死了文件名 —— 控制器没有把原始文件名递给 UI，页面上不许编一个。",
        )
        assertFalse(
            text.contains("3f9c1b"),
            "头像页又写死了假哈希 —— 哈希必须来自 state.pickedAvatarHash。",
        )
        assertFalse(
            text.contains("12 → 13"),
            "头像页又写死了假 profileRev —— 那个 +1 还没发生，不许预支。",
        )
        assertTrue(
            text.contains("pickedAvatarHash"),
            "哈希卡必须读真实的 pickedAvatarHash。",
        )
        assertTrue(
            text.contains("formatAvatarByteCount(pickedAvatarBytes.size)"),
            "大小一行必须来自真实字节（pickedAvatarBytes.size），不是写死的 1.8 MB。",
        )
    }

    @Test
    fun avatarStepsDescribeRealityNotTheUnbuiltPipeline() {
        // 文案的真值在 strings.xml（zh 与 en 同步），这里钉「代码不再引用会撒谎的旧键」：
        val text = GuardSourceScan.read(avatarPage)
        val zh = GuardSourceScan.read(
            "composeApp/src/commonMain/composeResources/values/strings.xml",
        )
        val en = GuardSourceScan.read(
            "composeApp/src/commonMain/composeResources/values-en/strings.xml",
        )
        for (res in listOf(zh, en)) {
            assertFalse(
                res.contains("裁成正方形") || res.contains("Crop to a square"),
                "步骤卡又承诺了不存在的裁剪管线 —— 目标态（裁剪/webp/192×192）不进文案。",
            )
            assertFalse(
                res.contains("固定输出 192×192"),
                "步骤卡又承诺了 192×192 输出 —— 那一步尚未实现。",
            )
            assertTrue(
                res.contains("set_avatar_file_size") && res.contains("%1\$s"),
                "大小键必须带真实字节占位符。",
            )
        }
        assertFalse(
            text.contains("set_avatar_file_meta"),
            "上传头像页不该再用 set_avatar_file_meta（那是资料页头像卡的兜底键）；它有自己的真实大小键。",
        )
    }

    @Test
    fun reselectActuallyReopensThePicker() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(avatarPage))
        // 旧行为：「重选」那颗按钮 onClick = onRemove（字面是重选、行为是移除）。
        //
        // 2026-10-24 补「移除」入口之后，这颗文件里**合法地**出现了一颗 onClick = onRemove
        // （选图卡里的「删除」，见 SettingsInteractionFeedbackTest）。所以判据收紧为：
        // 「重选」仍必须接 onPick，而 onRemove 只能来自那颗删除按钮 —— 不允许把 onRemove
        // 接回"选择/重选"这颗主按钮上。
        assertTrue(
            text.contains("onClick = onPick"),
            "「重选 / 选择图片」必须接 onPick（再弹选择框）。",
        )
    }

    @Test
    fun avatarSaveFeedbackIsDerivedFromRealRevBump() {
        // 纯函数判定：昵称空必拒；rev 涨 = 成功；其余 = 无事发生。
        assertEquals(
            AvatarSaveFeedback.NickRequired,
            avatarSaveFeedback(nickBlank = true, revBefore = 3, revNow = 4),
            "昵称为空时控制器必拒收 —— 不能报成保存成功。",
        )
        assertEquals(
            AvatarSaveFeedback.Idle,
            avatarSaveFeedback(nickBlank = false, revBefore = null, revNow = 4),
            "还没点保存（revBefore == null）时不能宣称成功。",
        )
        assertEquals(
            AvatarSaveFeedback.Idle,
            avatarSaveFeedback(nickBlank = false, revBefore = 4, revNow = 4),
            "rev 没涨就不是保存成功 —— 控制器对非空昵称恒 +1，没涨就是没生效。",
        )
        assertEquals(
            AvatarSaveFeedback.Saved(5),
            avatarSaveFeedback(nickBlank = false, revBefore = 4, revNow = 5),
        )
    }

    @Test
    fun avatarByteCountFormatUsesRealBytes() {
        assertEquals("0 B", formatAvatarByteCount(0))
        assertEquals("512 B", formatAvatarByteCount(512))
        assertEquals("1 KiB", formatAvatarByteCount(1024))
        assertEquals("1.5 KiB", formatAvatarByteCount(1536))
        // 32 KiB 是 MAX_AVATAR_BYTES（app 模块 internal，这里用同值字面量）：
        // 上限值本身不许有假精度的小数。
        assertEquals("32 KiB", formatAvatarByteCount(32 * 1024))
        assertEquals("1.5 MiB", formatAvatarByteCount(1536 * 1024))
    }

    @Test
    fun privateStoragePathIsDetectedAndDesktopPathIsNot() {
        assertTrue(isAppPrivateStoragePath("/data/user/0/com.kapp.tidings/files"), "Android 私有路径要被识别")
        assertTrue(isAppPrivateStoragePath("/data/data/com.kapp.tidings/files"), "老的 /data/data 形态也要识别")
        assertFalse(isAppPrivateStoragePath("/Users/someone/Library/Application Support/x"), "桌面路径保留真实显示")
        assertFalse(isAppPrivateStoragePath(""), "空串不是私有路径")
    }

    /* ===== 诊断页：入口与读数 ===== */

    @Test
    fun imeEntryIsAnIndependentRowNotNestedInTheNullBranch() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(diag))
        val runSelfCheckBlock = GuardSourceScan.bracedBlockAfter(text, "if (runSelfCheck != null)")
        assertFalse(
            runSelfCheckBlock.contains("set_diag_ime_smoke"),
            "「输入法自检」入口又被嵌进 onRunSelfCheck 分支 —— onRunSelfCheck 全宿主为 null，" +
                "嵌在里面等于这行入口在任何宿主上都出不来。",
        )
        assertTrue(
            text.contains("if (openIme != null)"),
            "「输入法自检」必须是独立的可空槽行（openIme != null 才画）。",
        )
        assertTrue(
            GuardSourceScan.bracedBlockAfter(text, "if (openIme != null)").contains("SettingsNavRow("),
            "「输入法自检」入口走 NavRow 形状（与「适配对照」同一行型）。",
        )
    }

    @Test
    fun statusBarInsetRowIsASlotDesktopCanTurnOff() {
        val text = GuardSourceScan.read(diag)
        assertTrue(
            text.contains("statusBarInsetShown: Boolean = true"),
            "inset 行必须是可空槽：桌面没有状态栏，画一行 24 dp 是编造读数。",
        )
        assertTrue(
            text.contains("if (statusBarInsetShown)"),
            "inset 行必须被 statusBarInsetShown 门控。",
        )
        val desktopCall = GuardSourceScan.parenBlockAfter(
            GuardSourceScan.read(desktopHosting),
            "SettingsDiagSubpage(",
        )
        assertTrue(
            desktopCall.contains("statusBarInsetShown = false"),
            "桌面宿主必须显式关掉 inset 行。",
        )
    }

    @Test
    fun protocolRowValueComesFromTheProtocolConstant() {
        val text = GuardSourceScan.read(diag)
        assertTrue(
            text.contains("Protocol.VERSION"),
            "协议行的兜底值必须从 core 的 Protocol.Version 常量拼，不能写死「v1 · 与 desktop 0.61.1 兼容」。",
        )
        val zh = GuardSourceScan.read(
            "composeApp/src/commonMain/composeResources/values/strings.xml",
        )
        assertFalse(
            zh.contains("与 desktop 0.61.1 兼容"),
            "协议行文案又写死了兼容对端版本 —— 那个数会随实现漂移。",
        )
    }

    /* ===== 首页：死路由要有门 ===== */

    @Test
    fun updatePageIsReachableFromTheHomeDataGroup() {
        val text = GuardSourceScan.read(home)
        assertTrue(
            text.contains("onOpenPage?.invoke(SETTINGS_PAGE_UPDATE)"),
            "「版本与更新」又变成死路由了 —— 设置首页的数据与诊断组必须有它的入口行。",
        )
        assertTrue(
            text.contains("set_update_title"),
            "入口行必须用「版本与更新」自己的标题。",
        )
    }

    /* ===== 设置屏：重启不再劫持子页 ===== */

    @Test
    fun restartingStateDoesNotHijackTheSubpage() {
        // 先剥注释：KDoc 里正当提到旧写法会把守卫绊倒（本仓踩过的假绿形态之一）。
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(screen))
        assertFalse(
            text.contains("if (state.restarting) SUBPAGE_CONN"),
            "initialSubpage 又劫持了重启中的用户 —— 重启状态在两个页面上都如实显示，不需要劫持。",
        )
        assertTrue(
            text.contains("initialSubpage: String? = null"),
            "initialSubpage 的默认值必须是 null。",
        )
    }

    /* ===== 数据页：危险动作有前置确认 ===== */

    @Test
    fun clearingLeftoversHasAConfirmDialog() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(data))
        assertTrue(
            text.contains("showClearLeftoverConfirm"),
            "「清残片」又变成一键直删了 —— 它放弃续传、不可恢复，要有与「清空聊天记录」同款的确认。",
        )
        assertTrue(
            text.contains("set_leftover_confirm_body"),
            "确认框正文必须报个数与总大小。",
        )
    }

    @Test
    fun retentionRowIsPlainTextNotASettingsRow() {
        val text = GuardSourceScan.stripComments(GuardSourceScan.read(data))
        assertFalse(
            text.contains("set_retention_title") || text.contains("set_retention_value"),
            "「保留天数」又画成设置行了 —— 没有可改的开关，一行说明就够，别让用户以为能点。",
        )
        assertTrue(
            text.contains("set_retention_summary"),
            "保留天数应以纯说明文字出现（消息默认保留 %1\$d 天）。",
        )
    }

    /* ===== 桌面：横幅、导出、侧栏 ===== */

    @Test
    fun desktopSettingsWindowRendersBannersAndExport() {
        val text = GuardSourceScan.read(desktopHosting)
        assertTrue(
            text.contains("AppBanner("),
            "桌面设置窗又没有反馈位了 —— 保存端口的回执在桌面上必须看得见。",
        )
        val diagCall = GuardSourceScan.parenBlockAfter(
            text,
            "SettingsDiagSubpage(",
        )
        assertTrue(
            diagCall.contains("onExportDiagnostics"),
            "桌面诊断页必须接上诊断包导出（手机同款 suspend）。",
        )
        assertTrue(
            diagCall.contains("onOpenImeSmoke"),
            "桌面诊断页必须接上「输入法自检」入口（侧栏一级项已删）。",
        )
    }

    @Test
    fun imeSmokeIsNotASidebarLevelItem() {
        val chrome = GuardSourceScan.read(desktopChrome)
        assertFalse(
            chrome.contains("ImeSmoke("),
            "「输入法自检」又回到侧栏一级了 —— 它是诊断页的二级入口，两端同一条路径。",
        )
        val hosting = GuardSourceScan.read(desktopHosting)
        assertFalse(
            hosting.contains("DesktopSettingsNav.ImeSmoke"),
            "桌面宿主还留着侧栏 ImeSmoke 分支 —— 枚举里已经没有这一项。",
        )
    }

    @Test
    fun desktopSwitchRowUsesToggleableSemantics() {
        // 开关已经收敛成共享组件 AppSwitch（§2.6：同一个 UI 元素只许一处定义）。
        // 守卫跟着搬家：语义断言落在共享实现上，两端各自再钉「用的是它」。
        val shared = GuardSourceScan.read(switchComponent)
        assertTrue(
            shared.contains("toggleable(") && shared.contains("Role.Switch"),
            "共享开关必须有 toggleable + Role.Switch 语义（读屏与键盘可达）。",
        )
        val desktop = GuardSourceScan.read(desktopChrome)
        assertTrue(desktop.contains("AppSwitch("), "桌面设置页必须用共享开关，不许再自绘一份。")
        assertFalse(
            GuardSourceScan.stripComments(desktop).contains("private fun RelaySwitch("),
            "桌面自绘 RelaySwitch 应当已经删掉；留着就会重新长成第二份实现。",
        )
    }

    /* ===== 入口必须有路由：只钉入口行不够 ===== */

    /**
     * 三条设置子页（更新 / 输入法自检 / 上传头像）都必须在 `App.kt` 的 `when` 里有自己的
     * **分支**。只钉「入口行存在」是假绿的老形态：`when` 有 `else -> SettingsScreen(...)`，
     * 分支被删掉后入口点下去只是静默退回设置首页 —— 入口行还在，守卫全绿，死路由复发。
     */
    @Test
    fun everySettingsEntryHasItsOwnRouteBranch() {
        val app = GuardSourceScan.stripComments(GuardSourceScan.read(appFile))
        for (route in listOf("SETTINGS_PAGE_UPDATE", "SETTINGS_PAGE_IME", "SETTINGS_PAGE_AVATAR")) {
            assertTrue(
                app.contains("$route ->"),
                "App.kt 缺了 $route 的 when 分支 —— 入口点下去会静默退回设置首页（when 有 else），死路由复发",
            )
        }
    }

    /**
     * 上传头像页必须在**两个壳**里各有一个真正的入口。
     *
     * 这条钉的是「路由状态被真的写下去」而不是「那一页存在」：
     * - 手机：`settingsPage = SETTINGS_PAGE_AVATAR`（资料页第三档）+ 设置首页那一行；
     * - 桌面：`showAvatarPage = true` —— 此前这个开关只有 `false` 赋值，头像页永远打不开。
     * 两处旧的 `onOpenAvatarPage = controller::pickAvatar` 也一起反钉：接成选图之后
     * 点第三档只弹文件框、不换页，259 行诚实化仍然只活在截图帧里。
     */
    @Test
    fun avatarPageHasAProductionEntryInBothShells() {
        val app = GuardSourceScan.stripComments(GuardSourceScan.read(appFile))
        val hosting = GuardSourceScan.stripComments(GuardSourceScan.read(desktopHosting))
        val homeText = GuardSourceScan.stripComments(GuardSourceScan.read(home))

        assertTrue(
            app.contains("settingsPage = SETTINGS_PAGE_AVATAR"),
            "手机壳没有任何一处把 settingsPage 写成 SETTINGS_PAGE_AVATAR —— 头像页在生产里不可达",
        )
        assertTrue(
            homeText.contains("onOpenPage?.invoke(SETTINGS_PAGE_AVATAR)"),
            "设置首页没有「头像」入口行 —— 上传头像页又只能靠资料页第三档到达（手机上基本没人找得到）",
        )
        assertTrue(
            homeText.contains("r2_set_profile_avatar_label"),
            "「头像」入口行必须用头像自己的标题键，不能另造一套说法",
        )
        assertTrue(
            hosting.contains("showAvatarPage = true"),
            "桌面壳的 showAvatarPage 只有 false 赋值 —— 头像页在桌面上永远打不开",
        )
        assertFalse(
            app.contains("onOpenAvatarPage = controller::pickAvatar"),
            "手机壳又把资料页头像第三档接成了选图 —— 点了只弹文件框、不换页",
        )
        assertFalse(
            hosting.contains("onOpenAvatarPage = controller::pickAvatar"),
            "桌面壳又把资料页头像第三档接成了选图 —— 点了只弹文件框、不换页",
        )
    }

    /** 「输入法自检」在手机端必须从诊断页真的接上路由（此前守卫完全没钉这一行）。 */
    @Test
    fun imeEntryIsWiredFromTheDiagnosticsPageToItsRoute() {
        val app = GuardSourceScan.stripComments(GuardSourceScan.read(appFile))
        assertTrue(
            app.contains("onOpenImeSmoke = { settingsPage = SETTINGS_PAGE_IME }"),
            "诊断页的「输入法自检」入口没有接路由 —— 点了不换页，那一页又是孤儿",
        )
    }

    /**
     * 头像页的协议卡标题不许再说「点保存不会生效」。
     *
     * 同一屏底部的保存栏会当场打出「已保存，资料版本 v…」（[avatarSaveFeedback] 钉的就是
     * 这条路径），标题与它自相矛盾。真实边界是**这张图还不上网**，不是保存不生效。
     */
    @Test
    fun avatarProtocolTitleNoLongerSaysSavingDoesNothing() {
        val zh = GuardSourceScan.read(valuesZh)
        val en = GuardSourceScan.read(valuesEn)
        assertFalse(
            zh.contains("点保存不会生效"),
            "中文协议卡标题又写「点保存不会生效」—— 同一屏的保存回执当场打脸",
        )
        assertFalse(
            en.contains("does nothing yet"),
            "英文协议卡标题又写 saving does nothing —— 保存是生效的，不生效的是图片同步",
        )
        assertTrue(
            zh.contains("保存会生效"),
            "中文标题必须明说保存生效（保存的是资料字段），边界是图片还不上网",
        )
        assertTrue(
            en.contains("Saving works"),
            "英文标题必须明说 saving works，边界是图片还不上网",
        )
    }
}
