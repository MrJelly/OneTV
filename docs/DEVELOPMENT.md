# 主应用构建

对应版本的主应用源码可从对应版本公开 Release 的 **Source code** 下载项（或检出对应版本标签） 下载。源码包不包含直播插件，也不包含签名私钥、用户数据或本地工具缓存。

## 环境

- JDK 17
- Android SDK 35 / Build Tools 35.0.0
- Gradle Wrapper 8.9

配置 Android SDK 路径后执行：

```sh
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintRelease
```

Windows 使用 `gradlew.bat`。APK 输出到 `app/build/outputs/apk/release/`。

默认打包 ARMv7 和 ARM64，需要全架构时使用：

```sh
./gradlew :app:assembleRelease -PoneAbis=armeabi-v7a,arm64-v8a,x86,x86_64
```

自行构建默认使用本机调试签名。正式分发可提供 `ONE_KEYSTORE_PATH`、`ONE_KEYSTORE_PASSWORD`、`ONE_KEY_ALIAS`、`ONE_KEY_PASSWORD` 环境变量；不要将私钥提交到仓库。自行签名的包不能直接覆盖不同签名的历史版本。

## 播放能力

Media3 统一固定为 1.4.1。NextLib 固定为 `v0.8.2`，与 Media3 1.4 系列配套，只在 API 21+、4 KB 页设备加载原生扩展；Android 4.4 与 16 KB 页设备保留平台解码。音轨或视频轨解码失败时分别兜底，格式解析重试有次数上限。

本项目不包含 AV3A 软解、独立 RTP 地址支持、付费 DRM 配置或音轨选择界面。RTSP 与 UDP 仍受服务器、网络和编码限制。

NextLib 原始源码、FFmpeg 获取与构建配置见 https://github.com/anilbeesetti/nextlib/tree/v0.8.2 ，提交 `50eedfd724336ad7c8cd78c2791080c8307f0760`。主应用使用原始 AAR，由 Gradle 按目标架构打包。详细许可和上游源码链接见第三方说明与包内许可。

公开 Release 只保留最新版本；不发布直播插件 APK。自动构建与自动发布工作流尚未启用。
