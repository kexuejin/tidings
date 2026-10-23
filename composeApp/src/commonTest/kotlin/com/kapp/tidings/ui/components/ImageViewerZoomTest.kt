// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 图片查看器缩放边界的单测。
 *
 * 真身：查看器原来只有一个 `ContentScale.Fit` + 「点一下关闭」—— 截图里的小字放大不了，
 * 多图只能关掉再点下一张。加捏合缩放后，两条边界必须能判：
 * **不能缩到比「看全」更小**、**缩回 1 倍必须复位位移**。
 * 抽成纯函数是因为组合测试要跑设备，而边界正是不该只靠肉眼发现的。
 */
class ImageViewerZoomTest {

    @Test
    fun zoomIsClampedToTheReadableRange() {
        assertEquals(1f, imageViewerScaleAfter(gestureZoom = 0.2f, current = 1f), "不能缩到比看全更小")
        assertEquals(6f, imageViewerScaleAfter(gestureZoom = 100f, current = 1f), "上限是 6 倍")
        assertEquals(2f, imageViewerScaleAfter(gestureZoom = 2f, current = 1f))
        assertEquals(3f, imageViewerScaleAfter(gestureZoom = 1.5f, current = 2f))
    }

    @Test
    fun panResetsOnlyAtFullZoomOut() {
        assertTrue(imageViewerShouldResetPan(1f), "回到 1 倍必须清零位移，否则图停在偏心位置")
        assertTrue(imageViewerShouldResetPan(0.5f), "低于 1 倍（理论不可达）也算复位")
        assertFalse(imageViewerShouldResetPan(1.01f), "还在放大态时要保留位移")
        assertFalse(imageViewerShouldResetPan(3f))
    }
}
