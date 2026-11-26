# 困困 Android 签名

应用安装标识为 `io.github.shuimowang.kunkunime`。覆盖安装需要相同的应用标识和签名证书，版本号也必须允许升级。

## 开发预览

仓库不保存任何签名密钥。未配置个人签名时，Android 构建工具使用当前构建环境的默认调试密钥；没有现成密钥时会自动生成。
不同云端构建的调试证书可能不同，不能保证预览包互相覆盖安装。调试签名不能用于正式发布身份。

切换签名证书后，即使应用名称相同，也不能直接覆盖原安装。切换前应导出个人词库和设置。

## 个人发布密钥

私有 jks 和密码不提交 Git。把 jks 放在本目录，在 `android/local.properties` 中填写：

```
signing.storeFile=keystore/kunkun-release.jks
signing.storePassword=…
signing.keyAlias=…
signing.keyPassword=…
```

当前 Gradle 配置在四项齐全时，让 debug 与 release 使用这把指定密钥；缺少任何一项时，debug 使用构建环境的调试证书，release 保持未签名。
发布包需在受信环境中提供完整签名配置。CI 中应通过受保护的 Secrets 注入，生成的 jks 和 `local.properties` 不应放入源码或构建产物。

## 核对 APK

```bash
"$ANDROID_HOME/build-tools/<version>/apksigner" verify --print-certs <path-to.apk>
keytool -list -keystore keystore/kunkun-release.jks
```

核对 APK 的 signer SHA-256 与预期证书指纹；临时预览签名与个人正式签名应明确区分。
