# luciola 原生库（打包用）

这里的 `libluciola_uniffi.{dylib,so,dll}` **不入库**（二进制、体积大、来源应可追溯）。
本地/CI 打包前用：

```bash
TIDINGS_LUCIOLA_NATIVE=/path/to/cargo-target/debug ./gradlew :desktopApp:createDistributable
```

`copyLuciolaNative` 会把库复制到本目录的 `common/native/`，jpackage 随后打进
安装包的 app resources；运行时 `DesktopLuciolaHost` 从
`compose.application.resources.dir/native` 加载（JNA `jna.library.path`）。

正式发版应改为从 luciola 的发布构件取库并校验 sha256 —— 与 Android 发布形态一起做。
