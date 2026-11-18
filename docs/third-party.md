# 第三方依赖与无障碍参考

本项目原生业务代码、UI、导航发布与测试适配器为本次开发成果。第三方 SDK、模型、图标库和系统服务不作为自主研发成果申报。发布/提交前按下载版本的许可证和高德服务条款逐项核对。

| 组件 | 用途 | 来源/许可边界 |
|---|---|---|
| Kotlin / AndroidX Compose / Room / DataStore / CameraX / Media3 | 原生界面、存储、相机和视频 | Kotlin与AndroidX开源许可随发行物保留 |
| OkHttp 4.12.0 | WebSocket、HTTPS | Apache-2.0 |
| 高德导航10.0.800 + 搜索9.7.0 | 真实地图步行导航和地点搜索 | 商业 SDK，需申请 Android key 并遵守高德服务条款；9.7.1与导航包存在重复类，因此使用9.7.0 |
| ML Kit Chinese Text Recognition16.0.1 | 随包中文OCR | Google ML Kit条款；模型不作自主训练 |
| TensorFlow Lite Task Vision0.4.4 | 本地目标检测推理 | Apache-2.0；支持ARM和x86系列，替代缺少模拟器架构的MediaPipe运行库 |
| EfficientDet Lite0 int8 | COCO80类检测 | 官方发布模型，未自行训练；不支持任意文字指定类别 |
| Vosk Android 0.3.75 / JNA 5.18.1 | 本机固定求助口令识别 | Vosk 为 Apache-2.0；JNA 可选 Apache-2.0 或 LGPL-2.1，按 Apache-2.0 使用；不作为自研识别模型 |
| vosk-model-small-cn-0.22 | 手机离线中文声学模型 | [Vosk 官方模型表](https://alphacephei.com/vosk/models)列为 Apache-2.0；ZIP SHA-256 `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`，约 44 MB |
| websockets15.0.1 / Pillow | 电脑测试端与示例图像 | 分别按BSD/HPND发行许可保留 |

模型官方地址：`https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite`

模型 SHA256：`2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b`。采用带4输出后处理与元数据的Task Library官方版本；此前2输出MediaPipe版本不兼容本次推理库，已更正。

仅仪器测试使用的猫狗样图来源：`https://storage.googleapis.com/mediapipe-tasks/object_detector/cat_and_dog.jpg`，不放入主App资产，不作为自采数据或模型精度评测集。

## 无障碍参考与采用

- [Soundscape Android](https://github.com/Scottish-Tech-Army/Soundscape-Android)，参考版本 `7cdd395663dc7dfb6af88fb52d87d8a43e8f0385`：参考路线预习、文字描述和读屏动作命名。未复制整套导航业务、声景资产或借其验证结果背书；MIT代码与资产许可分别处理。
- [TalkBack](https://github.com/google/talkback)：参考标准焦点浏览、明确动作/状态。TalkBack是系统读屏工具，不打包替代。
- [Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android)：作为检查依赖与自动化参考，不把扫描通过等同于视障使用者可用。
- [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics)：标题 heading、按钮文字、开关角色、选择状态、合并状态信息、错误提示 polite live region。

设计落实：普通点击区≥48dp、核心操作≥56dp；文字可换行、纵向滚动、系统字号叠加用户字号；状态使用文字；图像/定位不设逐帧live region；不使用自动动画。

rc4更正：此前按触摸探索状态一律跳过自动语音会漏掉导航信息，现保留地图导航、设备助行与重要状态提醒，重复自动文本在6秒内合并，主动重复不受限。取景的普通提示在读屏开启时交由live region，避免双重朗读；相同画面提示先稳定两次、变化后至少间隔4秒才更新。页面加入paneTitle和浏览分组，返回保存滚动位置；这些语义与系统节点检查不替代TalkBack真人验收。
