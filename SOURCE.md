# 第三方许可与原生依赖

OneTV 组合版本按 [GPL-3.0](COPYING) 分发。原有 [Apache-2.0 许可](LICENSE) 和 [第三方许可](app/src/main/assets/licenses) 随源码保留。

| 组件 | 源码与许可 |
| --- | --- |
| Aerial TV | [上游项目](https://github.com/pengchujin/aerial-tv) · [MIT](app/src/main/assets/licenses/aerial-MIT.txt) |
| CJS | [原生源码](native/cjs)，提交 `98f8a5222b99f4f7aa176ca8216c322cc41716dc` · [MIT](app/src/main/assets/licenses/cctv-h5e-MIT.txt) |
| NativeWasmTv | [构建脚本与补丁](native/h5e-runtime)，提交 `be11b9e2f4d12878d747d2d7ee8cfd19e4373030` · [GPL-3.0](native/h5e-runtime/LICENSE) |
| NextLib | [源码](native/nextlib-source.tar.gz)，提交 `50eedfd724336ad7c8cd78c2791080c8307f0760` · [FFmpeg 6.0](native/ffmpeg-6.0.tar.gz) |

构建央视频原生库使用 `native/cjs/sites/yangshipin.cn/native/Android.mk`。构建 H5E 流式库运行 `native/h5e-runtime/tools/cjs/build-h5e-streaming.py native/cjs --ndk <NDK 路径> --bundle`。产物放入 `app/src/main/jniLibs` 的对应 ABI 目录。NextLib 的构建入口为其源码中的 `ffmpeg/setup.sh` 和 Gradle 配置。
