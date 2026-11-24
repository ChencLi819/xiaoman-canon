# 小满·卡农

小满·卡农是通过 Wi‑Fi PTP/IP 控制相机的 Android 应用。目前仅支持佳能 EOS 200D II（EOS 250D、Rebel SL3、Kiss X10）。

## 功能

- 实时取景、点按对焦，以及机身支持时的对焦框同步
- M 档下调节 ISO、快门速度和光圈
- 遥控拍摄、B 门、间隔拍摄，以及浏览和下载存储卡照片

## 连接

1. 从 [GitHub Releases](../../releases) 下载 APK。手机需要 Android 8.0 或更新版本。
2. 在相机的 Wi‑Fi 功能中选择「遥控（EOS Utility）」，进入设备配对。
3. 在 App 的 Wi‑Fi 连接页输入相机显示的热点名称和密码，确认 Android 的连接请求，再按机身提示完成配对。

拍摄模式和镜头的 AF/MF 状态以机身为准。

## 构建

安装 JDK 17 和 Android SDK Platform 34，设置 `ANDROID_HOME`，然后运行 `./gradlew :app:assembleDebug`。Windows 使用 `gradlew.bat :app:assembleDebug`。

调试 APK 位于 `app/build/distributions/debug/`，使用本地调试密钥签名。

## 许可

项目采用 [LGPL-3.0-or-later](LICENSE)。上游协议实现和构建工具的署名见 [NOTICE](NOTICE.md)。
