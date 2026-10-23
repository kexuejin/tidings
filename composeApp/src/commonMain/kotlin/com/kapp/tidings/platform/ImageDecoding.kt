// SPDX-License-Identifier: GPL-3.0-only

package com.kapp.tidings.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 把一段图片字节解成可画的位图。解不出来（格式不认识、数据坏了、太大）返回 null。
 *
 * 为什么不引图片库：三个平台各自的原生解码器都已经在依赖里了（Android 的 `BitmapFactory`、
 * 桌面与 iOS 的 Skia），而 Coil / Kamel 那类库解决的是「按 URL 取图 + 磁盘缓存 + 请求取消」，
 * 我们手上已经是沙盒里的一个本地文件。为一张内联预览引进本工程的第一个第三方依赖，
 * 换不来这里真正需要的任何东西。
 *
 * 契约：**不抛异常**。返回 null 就是「这张画不出来」，调用方退回原来的文件卡，
 * 不要弹错误 —— 一个后缀是 `.png` 的文本文件不该让聊天界面报错。
 */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?
