# ColorOS GMS 解限

一个针对 **ColorOS 16** 的精简 LSPosed 模块，用于解除 OPlus Battery 中的 GMS 动态联网限制。

## 原理

目标类：

```text
com.oplus.battery.restrictdynamicfeature.google.GoogleRestrictionController
```

当前分析到的关键路径：

```text
Google 网络探测失败
    -> K(true, ...)
    -> C(true, ...)
    -> OplusNetworkingControlManager.setUidPolicy(uid, 4)
    -> GMS UID 被限制联网
```

其中 `K()` 还会发送：

```text
oplus.intent.action.google_restrict_change
restrict_enable=true
```

并更新：

```text
Settings.Secure[google_restric_info] = 1
```

本模块不直接阻断方法，也不全局 Hook `OplusNetworkingControlManager`，而是只在
`GoogleRestrictionController` 内将两个关键方法的第一个 `restricted` 参数强制为 `false`：

```text
K(true, ...) -> K(false, ...)
C(true, ...) -> C(false, ...)
```

这样 ColorOS 会继续走自己的正常状态机，但最终执行的是 OEM 自带的解除路径：

```text
setUidPolicy(uid, 0)
restrict_enable=false
google_restric_info=0
```

这比直接 `return` 更适合清理已经存在的限制状态。

## 默认受 ColorOS UID policy 控制的包

在当前 ColorOS 16 样本中，`google_network_restriction_list` 默认包含：

```text
com.google.android.gms
com.google.android.configupdater
com.android.vending
```

另外，广播层使用的 Google restriction list 还可能包含 GSF、BackupTransport 等包。

## 兼容策略

当前样本的方法名是：

```text
K(boolean, boolean, int)
C(boolean, Set)
```

由于 `K/C` 属于混淆名，OTA 后可能变化。模块会：

1. 优先按 `K` / `C` 精确定位；
2. 找不到时按参数签名和 `void` 返回类型做唯一匹配；
3. 如果同一签名出现多个候选，拒绝 Hook，避免误伤其它逻辑。

因此它不会为了“兼容”而盲目 Hook 整个联网控制层。

## LSPosed

本项目使用 Modern libxposed API 102：

```text
io.github.libxposed:api:102.0.0
```

静态作用域只有：

```text
com.oplus.battery
```

安装 APK 后，在 LSPosed 中启用模块，然后重启手机。无需手工扩大作用域，也不要勾选系统框架。

## 验证

启用并重启后查看：

```sh
logcat -d | grep -E 'ColorOSGMSUnrestrict|GoogleController'
```

出现类似日志表示 Hook 已命中：

```text
ColorOSGMSUnrestrict: hooks installed ...
ColorOSGMSUnrestrict: K: restricted=true -> false
ColorOSGMSUnrestrict: C: blocked UID policy request -> unrestrict path
```

查看 ColorOS 当前记录的限制状态：

```sh
settings get secure google_restric_info
```

正常应回到：

```text
0
```

也可以查看相关设置：

```sh
settings get system customize_control_cn_gms
settings get global oplus_user_change_gms_network_control
settings get global oplus_comm_trafficmonitor_gms_network_control
```

> 不建议通过把 `customize_control_cn_gms` 改成 `0` 来解限。根据当前控制器逻辑，这并不是简单的“关闭限制”开关，在存在 GMS 时反而可能走到 `K(true, ...)`。

## 本地构建

要求：

- JDK 17
- Android SDK 36
- Gradle 8.13

构建：

```sh
gradle :app:assembleRelease
```

APK：

```text
app/build/outputs/apk/release/
```

没有 `keystore.properties` 时，release 构建会使用本机 debug keystore，适合测试。
正式发布请配置自己的固定签名。

## Release 签名

复制：

```text
keystore.properties.example -> keystore.properties
```

填写：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`keystore.properties` 和密钥文件已在 `.gitignore` 中排除。

GitHub Actions 的 tag Release 使用以下 Repository Secrets：

```text
SIGNING_KEY          # JKS/PKCS12 文件 base64 后的内容
KEY_STORE_PASSWORD
ALIAS
KEY_PASSWORD
```

然后推送 tag：

```sh
git tag v1.0.0
git push origin v1.0.0
```

Actions 会编译、签名并发布 APK。

## 当前目标

本模块是针对已分析的 ColorOS 16 `Battery.apk` 实现的。如果后续 ColorOS OTA 改变
`GoogleRestrictionController` 的类名、参数签名，日志会明确报告目标方法不存在，而不是继续执行不确定的 Hook。

## License

MIT
