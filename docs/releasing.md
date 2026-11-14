# CodeReader 发布流程

正式应用为 `com.codereader.app`，调试应用为 `com.codereader.app.debug`。版本维护在 `app/build.gradle.kts`；Debug 与 Release 使用同一 `arm64-v8a` ABI 范围。

## 签名

在用户级 `~/.gradle/gradle.properties` 配置以下属性，仓库中不保存签名材料或密码：

- `codeReaderReleaseStoreFile`
- `codeReaderReleaseStorePassword`
- `codeReaderReleaseKeyAlias`
- `codeReaderReleaseKeyPassword`

没有签名配置时 Gradle 可以生成未签名 Release，但不能把它作为正式安装包发布。

## 构建与真机验证

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease --console=plain
```

先通过 `adb devices -l` 核对 Redmi Note 8 Pro 的序列号，再为每次安装和 instrumentation 命令显式指定该设备。详细验收记录保存在 [真机验证报告](真机验证报告.md)。

发布前必须检查：

- APK 包名、应用名、版本号和 versionCode。
- 正式签名验证成功。
- APK 原生库只有 `lib/arm64-v8a/`。
- 方形、圆形、自适应与单色图标资源存在。
- 相关真机回归通过，正式包能够安装和启动。

## 发行文件

正式文件命名固定为 `CodeReader-v版本号-arm64-v8a.apk`，另附 `SHA256SUMS`。在线更新只识别与 Release 标签精确对应的该名称，并要求 GitHub 资产提供有效大小、APK MIME 和 SHA-256 digest。

示例：标签 `v1.0.0` 对应 `CodeReader-v1.0.0-arm64-v8a.apk`。

Git 提交说明使用中文。提交、标签和远端发行记录应指向同一份已验收源码。上传后再次下载 APK，对比 SHA-256，并核对 Release API 中的文件名、digest 与大小。
