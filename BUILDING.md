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
