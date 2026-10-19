# KMP 三端 TLS 可行性 spike（task-13 的 P2 前置门槛）

> 结论先行：**P2 走 TLS 可行，且不需要引入任何新的 Gradle 依赖、iOS 也不需要 Swift 桥。**
> 建议按 lead 的裁决继续 P2（而不是改走 P3 AEAD）。但有三处"只做到这一步"必须看清（见 §5）。
>
> spike 代码位置（**独立包名，整目录可删**）：
> - `core/src/jvmTest/kotlin/app/relay/core/spike/TlsServerSpikeTest.kt`
> - `core/src/iosTest/kotlin/app/relay/core/spike/IosTlsServerSpikeTest.kt`
> - Android 真机探针**不落仓**：一次性 `/tmp/relay-tls-spike/SpikeMain.java` → `d8` → `adb push` → `dalvikvm`（见 §2 第 3 段；跑完已 `adb shell rm` 清理设备上的临时文件）。
>
> 本文件只报告 spike，不改任何生产协议。

## 1. 结论（可执行）

| 端 | 能不能做服务端 TLS | 需要新依赖 | 证据等级 |
|---|---|---|---|
| **JVM / 桌面** | **能**（已实证） | **零**（JDK JSSE + 自写 DER + 现有 `TlsPin`） | 真起 SSLServerSocket，完成**双向**握手 + 错误 pin fail-closed |
| **Android** | **能**（已实证） | **零** | **真机 Android 16 上跑通**服务端 `SSLServerSocket` + `setNeedClientAuth(true)` + SPKI pin（provider=`AndroidOpenSSL`，结果 `SPIKE_RESULT=OK`） |
| **iOS** | **能，且不需要 Swift**（Network.framework + Security.framework 的 Kotlin/Native 绑定齐全） | **零** | 已在模拟器上起一个**带 TLS 配置**的 `NWListener` 并到达 `ready`、绑定端口；**本地身份导入 + 完整握手未跑**（P2 第一个工作日任务，符号已逐个确认） |

**不建议**因为"TLS 难"改走 P3 AEAD：AEAD 要自己写握手/密钥协商/重放窗口，工作量更大，
而且它仍然要解决"身份从哪来、怎么固定"这同一件事 —— 那部分 P2 无论如何都要做。

## 2. 证据（真实命令与结果）

### 2.1 仓内可复现的两个（JVM 完整握手 + iOS 模拟器监听器）

```
./gradlew :core:jvmTest --tests "app.relay.core.spike.TlsServerSpikeTest"
  → BUILD SUCCESSFUL, tests=3 failures=0
    · jvmCanServeMutualTlsWithSelfSignedIpCertAndSpkiPinning   （双向 TLS，中文载荷原样往返）
    · aWrongPinFailsTheHandshakeInsteadOfSilentlyTrusting      （pin 不匹配 → SSLHandshakeException）
    · theGeneratedCertificateCarriesTheIpSanAndThePinIsTheSpkiHash（SAN 含 127.0.0.1/::1，pin=SPKI）

./gradlew :core:iosSimulatorArm64Test --tests "app.relay.core.spike.IosTlsServerSpikeTest"
  → BUILD SUCCESSFUL, tests=2 failures=0
    · iosCanBuildTlsProtocolOptions            （nw_tls_create_options + peer auth required）
    · iosCanCreateAndStartATlsListener         （secure_tcp + TLS 配置块 → NWListener ready + 端口）
```

### 2.2 Android 真机（不是 JVM，是 Conscrypt）

临时探针（不落仓，跑完已清理设备文件）：

```
keytool -genkeypair -alias id -keyalg EC -groupname secp256r1 ... -keystore id.p12
javac --release 8 SpikeMain.java
d8 --min-api 26 --lib $ANDROID_HOME/platforms/android-36/android.jar --output dex classes/*.class
adb push dex/classes.dex /data/local/tmp/spike.dex
adb shell "CLASSPATH=/data/local/tmp/spike.dex dalvikvm SpikeMain"
  → SPIKE_PROTOCOL=TLS
    SPIKE_PROVIDER=AndroidOpenSSL        # Conscrypt，不是 JVM 的 SunJSSE
    SPIKE_PIN=sha256:94f9817bb07af...
    SPIKE_RESULT=OK                      # 服务端 TLS + 双向 + SPKI pin 全过
```

一个诚实的注脚：这条探针通过 `dalvikvm` 跑（没有 app_process 的完整运行时），
Conscrypt 打日志时调 `SystemProperties.native_get` 会抛一次 `UnsatisfiedLinkError`，
但**握手与回环数据是成功的**（`SPIKE_RESULT=OK`）；在真实 app 进程里不存在这个注脚。

### 2.3 Kotlin/Native 平台库符号探测（`~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.0/klib/platform/ios_simulator_arm64/`）：

```
FOUND nw_tls_create_options / nw_listener_create / nw_listener_set_new_connection_handler
FOUND nw_listener_set_state_changed_handler / nw_listener_start / nw_listener_set_queue
FOUND nw_connection_create / nw_connection_start / nw_connection_send / nw_connection_receive
FOUND sec_identity_create / sec_protocol_options_set_local_identity
FOUND sec_protocol_options_set_verify_block / sec_protocol_options_set_peer_authentication_required
FOUND SecPKCS12Import / SecTrustEvaluateWithError
MISS  SecIdentityCreateWithCertificate（不存在这个 API；身份走 keychain / PKCS#12）
```

## 3. 需要引入什么依赖、包体代价

- **零新依赖**：
  - JVM/Android：`javax.net.ssl`、`java.security` 是 JDK / Android SDK 自带的（Android 侧由
    Conscrypt 提供 JSSE 实现）。**不需要 `ktor-network-tls`**（仓库现在也没有这个依赖）。
  - iOS：`platform.Network`、`platform.Security` 是 Kotlin/Native 自带的 platform libs
    （已在 §2 逐个确认），**不需要 Swift**、不需要 CocoaPods。
- **包体代价**：≈ 我们自己写的那点代码（DER 生成 + 平台 TLS 接线），没有引入任何第三方二进制。
- **构建代价**：Kotlin/Native 的 cinterop 是现成的（platform libs），不会新增 link 时间。

## 4. 两端 API 清单（P2 直接照这个写）

### 4.1 JVM / Android（同一份 `jvmSharedMain` 代码）

1. `KeyPairGenerator.getInstance("EC")` + `ECGenParameterSpec("secp256r1")` → 静态密钥。
2. 手写 DER 组装 X.509（见 spike 里的 `selfSignedCertificateDer`）：v3、`basicConstraints(CA=false,critical)`、
   `keyUsage(digitalSignature,critical)`、`extKeyUsage(serverAuth)`、`subjectAltName` 若干 `iPAddress`；
   `Signature.getInstance("SHA256withECDSA")` 签名。
3. `CertificateFactory.getInstance("X.509")` 解析；`KeyStore.getInstance("PKCS12")` + `KeyManagerFactory`。
4. `SSLContext.getInstance("TLS")` + 自定义 `X509TrustManager`（比对 `TlsPin.ofSpki(cert.publicKey.encoded)`）。
5. 服务端 `SSLServerSocketFactory.createServerSocket(...)` + `needClientAuth = true`（**双向**）；
   客户端 `SSLSocketFactory.createSocket(...)` + `startHandshake()`。
6. 注意：**不要**开 `endpointIdentificationAlgorithm`（我们按 SPKI 固定，不按主机名）。

### 4.2 iOS（Kotlin/Native，无需 Swift）

1. 身份：`SecPKCS12Import(CFData, options, &items)` → 取 `kSecImportItemIdentity` →
   `sec_identity_create(identity)`；或先 `SecItemAdd` 把 key+cert 放进 keychain 再取 `SecIdentity`。
2. `nw_tls_create_options()` → `sec_protocol_options_t`。
3. `sec_protocol_options_set_local_identity(options, identity)`（**这一条决定"服务端 TLS"能不能成**）。
4. 双向固定：`sec_protocol_options_set_peer_authentication_required(options, true)` +
   `sec_protocol_options_set_verify_block(options, block, queue)`，在 block 里比对对端 SPKI 的 `TlsPin`。
5. `nw_parameters_create_secure_tcp(configure_tls, NW_PARAMETERS_DEFAULT_CONFIGURATION)`
   —— 注意第二个参数**不能传 null**（传 null 会返回 null，spike 里踩过）。
6. `nw_listener_create` + `nw_listener_set_queue` + `set_state_changed_handler` +
   `set_new_connection_handler` + `nw_listener_start`；`nw_listener_get_port` 取真实端口。
7. 连接侧（客户端/被叫方）：`nw_connection_create` + `nw_connection_start` +
   `nw_connection_send`/`nw_connection_receive`。

## 5. 只能做到哪一步（诚实边界）

1. **iOS 的本地身份导入 + 完整握手未跑**：spike 证明到"带 TLS 配置的监听器能创建、启动、
   绑定端口"。`SecPKCS12Import`/`sec_identity_create`/`sec_protocol_options_set_local_identity`
   的符号与签名都已确认（§2），但"从我们自签的材料拿到 `sec_identity_t` 并完成一次握手"
   是 **P2 的第一个工作日任务**。若届时发现 Kotlin/Native 的 CF 互操作过不去，
   退路才是加一个极薄的 Swift shim —— 目前证据不支持这个悲观判断。
2. **Android 已上真机**（Android 16，Conscrypt）：服务端 TLS + 双向 + pin 跑通（§2.3）。
   用的是独立 `dalvikvm` 探针（不装 APK、不动被测 app、不占 17878/17879），没有干扰 task-15。
   仍未验证的是「在我们自己的 app 进程里 + 与 Ktor/平台 socket 接线」这一层，属 P2 正常工作量。
3. **证书在 iOS 上的生成方式**：JVM/Android 侧已证明「手写 DER 可行」；iOS 侧要么用
   `SecKeyCreateRandomKey` + `SecKeyCreateSignature` 自己拼同一套 DER，要么用
   Security.framework 的 API 生成。**这一块 spike 没有实证**，但它与 JVM 共用同一份 DER 逻辑，
   平台差异只在"生成密钥 / 签名"两个 expect/actual 上。


### 5.4 实测补记（task-26 ①，2026-10-18）

1. **`sec_*` 符号属于 `platform.Security`，不是 `platform.Network`**：`sec_identity_create`、
   `sec_protocol_options_set_local_identity` / `set_peer_authentication_required` /
   `set_verify_block`、`sec_trust_copy_ref`、`sec_trust_t` 全在 Security 里；
   `nw_tls_create_options` / `nw_parameters_create_secure_tcp` 才在 Network。按 Network 写会得到
   `Unresolved reference`（我踩过两次）。
2. **Kotlin/Native 的测试二进制拿不到 keychain**：`SecItemAdd` 返回 **-25291 errSecNotAvailable**
   —— 它不是 app（没有 app 容器 / keychain-access-group）。真机/模拟器的 **App 进程**里正常。
   所以"iOS 完整握手"**不能**放在 `iosSimulatorArm64Test` 里验，必须由 App 进程跑
   （task-26 的落点：`iosApp` 里一个按启动参数开关的自检入口）。
3. **EC 私钥落盘要带 `kSecAttrKeyClass = kSecAttrKeyClassPrivate`**：缺了它 `SecKeyCreateWithData`
   把字节按公钥解释、读不回来；更糟的是续签路径会静默退回"重新生成密钥"，**pin 跟着变**
   （模拟器实测：`c7cd…` → `0b4a…`）。

4. **必须是「签过名」的 App 进程（2026-10-18 真机/模拟器实测，重要）**：',
   第一次在 App 进程里跑自检得到的是 `listenerStarted=false` —— 因为烟构建是
   **ad-hoc/linker-signed、`CODE_SIGNING_ALLOWED: NO`、`Tidings.entitlements` 是空 `<dict/>`**，
   没有任何 entitlement ⇒ **没有 app 容器** ⇒ `SecItemAdd` 依旧 -25291。也就是说
   「App 进程」这一条还不够，**必须补 `DEVELOPMENT_TEAM` + 打开签名**，端到端握手才验得了。
   证据：同一次运行里 `serverPin=sha256:f62f…` **算出来了** ⇒ 身份生成 + SPKI 拼装 + 落盘那条链
   在 App 进程里是通的，卡的只有「把 key/证书放进 keychain」这一步。

5. **App 进程自检入口（task-26 ① ③）**：iOS 的完整握手只能由 App 进程跑 ——
   `core/src/iosMain` 里的 `relayTlsHandshakeSelfTest()` 起监听 + 客户端跑一次双向握手，
   `composeApp/src/iosMain` 的 `relayTlsSelfTest()` 是**一行包装**（core 是 `implementation`、
   framework 没有 `export(project(":core"))`，Swift 看不到 core 的公开面）。触发方式：

   ```
   xcrun simctl launch <device> <bundle-id> --relay-tls-selftest
   ```

   报告打到 stdout，最后一行 `RESULT=PASS|FAIL`；`correctPinConnected=true` 且
   `wrongPinConnected=false` 才算 PASS。**只有 PASS 之后**才把 iOS 的 `createPlatformLinkTransport`
   切到 `IosTlsLinkTransport`（在那之前它返回明文实现 = 公网被拒的 fail-closed 降级）。

## 6. 对构建与 CI 的代价

- 两个 spike 测试会分别进入 `:core:jvmTest` 与 `:core:iosSimulatorArm64Test`（各几百毫秒）。
- 它们是**可删除**的：`app.relay.core.spike` 包下只有这两个文件，删目录即可。
- 生产代码零改动；`TlsPin`（task-13 的 P1）被 JVM spike 真实使用了一次，
  等于顺手验证了 P1 的接口是够用的。

## 7. 下一步（P2 开工清单，按风险排序）

1. iOS：`SecPKCS12Import` → `sec_identity_create` → `set_local_identity` + 一次本机握手（先解 §5.1）。
2. 三端统一的 `TlsIdentity` expect/actual：`ensureIdentity(addresses)` 返回 `{certDer, keyRef, pin}`，
   证书"快到期或 SAN 缺当前地址"时用**同一把密钥**重签（决策 ⑥ 的自动续签）。
3. TCP 表面接线：`TlsPort`（决策 ③）+ `caps: tls1` + `required` 策略（决策 ②）+ 双向固定（决策 ①）。
4. 公网内容只走 TLS/TCP（决策 ④）：UDP 只保留发现；这条会改 `Messenger` 的路由，
   需要单独一张兼容矩阵与回归（见 `docs/public-transport-security.md` §5）。
5. 中继路径同一套 pin（决策 ⑤）。
6. Android 真机冒烟。

## 8. 复现方式

```bash
# JVM：完整双向 TLS + pin
./gradlew :core:jvmTest --tests "app.relay.core.spike.TlsServerSpikeTest"

# iOS 模拟器：TLS 参数 + NWListener
./gradlew :core:iosSimulatorArm64Test --tests "app.relay.core.spike.IosTlsServerSpikeTest"
```
