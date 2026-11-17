# 第三方许可与原生依赖

OneTV 组合版本按 [GPL-3.0](COPYING) 分发。原有 [Apache-2.0 许可](LICENSE) 和 [第三方许可](app/src/main/assets/licenses) 随源码保留。

| 组件 | 源码与许可 |
| --- | --- |
| Aerial TV | [上游项目](https://github.com/pengchujin/aerial-tv) · [MIT](app/src/main/assets/licenses/aerial-MIT.txt) |
| NextLib | [GPL-3.0](app/src/main/assets/licenses/GPL-3.0.txt) · [源码](native/nextlib-source.tar.gz)，提交 `50eedfd724336ad7c8cd78c2791080c8307f0760` · [FFmpeg 6.0](native/ffmpeg-6.0.tar.gz) |

NextLib 的构建入口为其源码中的 `ffmpeg/setup.sh` 和 Gradle 配置。
