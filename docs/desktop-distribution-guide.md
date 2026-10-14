# 捎话 (Tidings) 桌面端打包与免签名运行指引

本文档面向 macOS / Linux / Windows 用户及开发者，提供捎话桌面端（Tidings Desktop）的打包构建命令、无 Apple 开发者付费证书环境下的 Gatekeeper 绕过与安全启动指引，以及基于 Homebrew 的本地安装分发方案。

---

## 1. 产物打包命令

桌面端构建由 Compose Multiplatform DSL 与 `jpackage` 驱动。

### 1.1 前置要求
- **JDK 21**：推荐使用 Homebrew OpenJDK 21 或 Amazon Corretto 21。
- 若使用 Homebrew JDK 21，`gradle.properties` 已配置 `compose.desktop.packaging.checkJdkVendor=false` 避免误报。

### 1.2 各平台打包指令

```bash
# 1. 针对 macOS 构建 .dmg 安装包
./gradlew :desktopApp:packageDmg

# 2. 针对 macOS 生成解包即用的原生 .app 目录
./gradlew :desktopApp:createDistributable

# 3. 针对 Windows 构建 .msi 安装包（需在 Windows 环境或支持 WiX 的环境）
./gradlew :desktopApp:packageMsi

# 4. 针对 Linux 构建 .deb 安装包（需在 Linux 环境或带有 dpkg 的环境）
./gradlew :desktopApp:packageDeb

# 5. 生成当前系统对应的打包分发产物
./gradlew :desktopApp:packageDistributionForCurrentOS
```

### 1.3 产物输出路径
- **macOS DMG**: `desktopApp/build/compose/binaries/main/dmg/捎话 Tidings-1.0.0.dmg`
- **macOS App 目录**: `desktopApp/build/compose/binaries/main/app/捎话 Tidings.app`
- **Windows MSI**: `desktopApp/build/compose/binaries/main/msi/捎话 Tidings-1.0.0.msi`
- **Linux DEB**: `desktopApp/build/compose/binaries/main/deb/relay-desktop_1.0.0_amd64.deb`

---

## 2. macOS 免签名运行与 Gatekeeper 绕过指引

由于开源自编译版本未配置年费 \$99 的 Apple Developer ID 证书并进行 Apple Notarization（公证），macOS Gatekeeper 安全机制默认会拦截并弹出：
> “无法打开‘捎话 Tidings’，因为 Apple 无法检查其是否包含恶意软件”  
> 或  
> “‘捎话 Tidings’已损坏，打不开。您应该将它移到废纸篓”

请指导用户按以下标准安全方案进行绕过启动：

### 方案 A：右键打开（最适合普通用户，仅需操作一次）
1. 双击打开 `.dmg` 并将 `捎话 Tidings.app` 拖入 `/Applications`（应用程序目录）。
2. 在访达（Finder）中进入“应用程序”文件夹。
3. **按住键盘上的 `Control` 键不放，然后鼠标右键点击 `捎话 Tidings.app`**。
4. 在右键菜单中点击第一项 **“打开” (Open)**。
5. 系统弹窗中将出现多余的 **“打开”** 确认按钮（而非仅有“移到废纸篓”）。点击“打开”即可正常运行，此后可直接正常双击启动。

### 方案 B：终端清除隔离扩展属性（适合开发者 / 极客用户）
若系统提示“应用已损坏，打不开”，这是因为从网络下载或 DMG 复制的文件带有 `com.apple.quarantine` 扩展属性。
在终端执行以下命令清除隔离属性：

```bash
# 清除已安装应用的隔离属性
xattr -cr /Applications/"捎话 Tidings.app"

# 若直接从 DMG 挂载卷运行，可执行：
xattr -cr "/Volumes/捎话 Tidings/捎话 Tidings.app"
```
清除后即可双击直接运行。

### 方案 C：系统设置放行（macOS Sequoia / Sonoma / Ventura）
1. 双击被系统拦截后，打开 **系统设置 (System Settings) → 隐私与安全性 (Privacy & Security)**。
2. 页面向下滚动至“安全性”区域。
3. 会看到提示：*“已阻止‘捎话 Tidings’，因为其来自身份不明的开发者”*。
4. 点击右侧的 **“仍要打开” (Open Anyway)**，输入 Mac 开机密码即可放行。

---

## 3. Homebrew Cask 本地发布与维护规则

为方便内网或本地团队快速分发，可将 DMG 纳入自定义 Homebrew Tap。

### 3.1 本地 Cask 模板 (`relay.rb`)
```ruby
cask "relay" do
  version "1.0.0"
  sha256 "<执行 shasum -a 256 输出的实际哈希值>"

  url "https://github.com/your-org/teahouse-mobile/releases/download/v#{version}/%E6%8D%8E%E8%AF%9D.Tidings-#{version}.dmg"
  name "捎话 Tidings"
  desc "局域网直连的消息与文件传输"
  homepage "https://github.com/your-org/teahouse-mobile"

  app "捎话 Tidings.app"

  # 自动清除隔离属性，用户 brew install 完即开即用
  postflight do
    system_command "/usr/bin/xattr",
                   args: ["-cr", "#{appdir}/捎话 Tidings.app"]
  end

  zap trash: [
    "~/Library/Application Support/relay",
    "~/Library/Saved Application State/app.relay.desktop.savedState",
  ]
end
```

### 3.2 使用本地路径直接安装测试
```bash
# 本地直接安装生成的 DMG
brew install --cask ./relay.rb
```
