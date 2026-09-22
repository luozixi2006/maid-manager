# 构建与发布

## 环境

- JDK 17
- Android SDK 34
- Windows、macOS 或 Linux

首次构建需要访问 Google Maven、Maven Central 和 Gradle Plugin Portal。不要提交 `local.properties`、Gradle 缓存、API 密钥、keystore 或签名口令。

## Debug

Windows：

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug
```

macOS / Linux：

```bash
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`。

手表与共享层：`./gradlew :companion-core:testDebugUnitTest :watch:testDebugUnitTest :watch:assembleDebug`。
手表 APK 在 `watch/build/outputs/apk/`。Release 使用仓库外 `WATCH_STORE_FILE` 指定已有手表升级 keystore，保留已安装测试包证书；不得提交私钥。CI Secret `ANDROID_WATCH_KEYSTORE_BASE64` 保存该文件，不使用每次运行重新生成的临时签名。手表首次用户测试时使用的本地调试证书被沿用；正式换签名需要单独迁移，不得让用户靠卸载清数据来普通更新。

未授权上传手表私钥时，CI 仍编译手表并发布手机更新，手表 Release 在本机签名后只上传 APK。可选 Secret 未配置不会让手机更新失败；不会把 CI 临时调试签名的手表包作为升级包发布。

手机包含约 57 MB 本地语义模型。仓库已包含固定哈希的 ONNX、词表与许可证，正常构建不下载权重。需重新导出时使用 `tools/export_memory_model.py` 的独立 Python 依赖和固定官方 revision；不要把下载缓存提交 Git。

## 本地 Release

Release 不会回退到 Debug 签名。把 keystore 保存在仓库外，并通过环境变量或本机 Gradle 属性提供：

```text
RELEASE_STORE_FILE
RELEASE_STORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

仓库绑定信息通过 Gradle 属性写入 BuildConfig：

```text
UPDATE_GITHUB_OWNER
UPDATE_GITHUB_REPO
```

构建示例：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease `
  -PUPDATE_GITHUB_OWNER=owner `
  -PUPDATE_GITHUB_REPO=repository
```

## GitHub 自动发布

`.github/workflows/release.yml` 会在每次推送到 `main` 后：

1. 运行单元测试；
2. 用固定升级密钥构建 Release APK；
3. 验证 APK 证书与 applicationId；
4. 生成包含版本、下载地址和 SHA-256 的 `update-manifest.json`；
5. 创建 GitHub Release。

仓库需要配置以下 Actions Secrets：

```text
ANDROID_UPGRADE_KEYSTORE_BASE64
ANDROID_UPGRADE_STORE_PASSWORD
ANDROID_UPGRADE_KEY_ALIAS
ANDROID_UPGRADE_KEY_PASSWORD
ANDROID_UPGRADE_CERT_SHA256
```

`ANDROID_UPGRADE_CERT_SHA256` 必须是固定升级证书的 SHA-256。更新密钥一旦用于首个 3.x Release 就不可随意更换；丢失私钥将无法继续覆盖安装。

应用只接受 HTTPS 下载地址，并在打开系统安装页前校验 SHA-256、包名、版本号和 APK 签名。Android 仍会要求用户确认安装；应用不会静默安装更新。
