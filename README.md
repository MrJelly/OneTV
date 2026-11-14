# OneTV

面向 Android 电视、盒子和投影仪的桌面，内置直播、浏览器和手机控制。

[下载最新版](https://github.com/MrJelly/OneTV/releases/latest) · [反馈问题](https://github.com/MrJelly/OneTV/issues)

| 首页 | 设置 |
| --- | --- |
| ![首页](docs/screenshots/home.png) | ![设置](docs/screenshots/settings.png) |

## 功能

- 桌面接管、开机启动、应用排序与文件夹、自定义壁纸
- 默认直播、自定义节目单和频道收藏
- 网页浏览、视频全屏播放和进度控制
- 手机扫码遥控、应用管理和局域网文件传输

## 安装

支持 Android 4.4 及以上。32 位设备选 ARMv7，64 位设备选 ARM64，不确定时选通用包。桌面接管需在「设置 → 桌面应用」按设备提示授权。

## 构建与许可

使用 JDK 17 和 Android SDK 35，运行 `./gradlew :app:assembleDebug`。Windows 使用 `gradlew.bat`。

[GPL-3.0](COPYING) · [第三方许可与原生依赖](SOURCE.md)
