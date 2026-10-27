# iOS 收图显示「等你接收」，但文件其实已经收完了（UI 判据缺陷）

日期：2026-10-27。goal 第 (3) 步验证时，用户看一眼截图就指出了问题：
**卡片显示「对方发来的文件，等你接收」+ 接收/拒绝按钮，而文件早就在磁盘上了。**

## 事实分层（先把"到底是哪一层错了"钉住）

**协议层：自动接收确实生效了。** 证据：
- 桌面 `/v1/transfers` 那条记录 `accepted: true`；
- 桌面是在**收到 ACCEPT 之后**才推字节的（`onAccept` → `pushFileOnChannel`，
  见 TransferManager:781 起），而 `[chunk] chunksSent=16 / windows=1 / timeouts=0`
  说明确实推了；
- iOS 沙盒里 `.../Library/Application Support/relay/downloads/ios1m.png/ios1m.png`
  与源文件 sha256 完全一致（`3a814d27…4432d`）。

也就是说：**没有"未接收就落盘"的安全问题** —— 字节是被 ACCEPT 之后才推过来的。

**UI 层：错了。** 卡片停在 `Offered` 状态，画了"等你接收"和两个按钮。

## 根因一（已用代码证实）

`TransferTables.kt` 的 KDoc 明确写了契约：

> 自动接收的（小图片）也会发 `Offered`……**是否要用户点头看 `IncomingOffer.purpose`，
> 不看有没有这条事件**

但 UI 侧的判据是：

```kotlin
// ChatBubbles.kt:1214
internal fun fileCardShowsAccept(card: FileCardUi): Boolean =
    card.state == TransferState.Offered && !card.mine
```

而 **`FileCardUi` 根本没有 `purpose` 字段**（只有 transferId/name/size/mine/state…）。
于是"按 purpose 判断"这件事在 UI 层**做不到**，只能对一切入站 offer 都画按钮。

## 根因二（待查，已定位到缝上）

截图是 20:10 发的 offer、20:14 拍的，卡片**仍停在 Offered** —— 说明自动接收之后的
进度/完成事件没有反映到这张卡上。信道路径的登记是
`registerOnChannel(offer) → registerChannelInbound(offer, saveDir)`（宿主回调），
入站完成时是否发出 `TransferEvent.Completed` 取决于宿主侧的接线，本轮未查完。

## 该怎么修（下一步）

1. `FileCardUi` 增 `purpose: FilePurpose? = null`（或更直接的 `autoAccepted: Boolean`），
   在由 `TransferEvent.Offered` 构造卡片处填上；
2. `fileCardShowsAccept` 改为
   `state == Offered && !mine && !autoAccepted`；
3. 补单测钉住「自动接收的图不出现『接收』按钮」——这个函数本来就为单测抽出来的；
4. 查信道入站完成是否发 `Completed`；不发则补，并加断言测试。

## 我的错误

我先前的结论是"自动接受成功"，依据只有"文件在磁盘上、sha 一致"。
**用户看到的是界面，而我没看界面就下了结论。** 在有 UI 的产品里，
"文件到了"与"用户看到收好了"是两件事，不能用前者代替后者。
