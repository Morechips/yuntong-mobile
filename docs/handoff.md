# 安卓开发交接

## 从哪里开始

| 工作 | 找到入口 |
| --- | --- |
| 四个底部入口及页面 | `ui/YuntongApp.kt`、`HomeScreen.kt`、`NavigationScreen.kt`、`AssistantScreen.kt`、`DeviceScreen.kt` |
| 流程状态及业务 | `AppController.kt` |
| 高德定位、搜索和路线发布 | `navigation/AmapNavigation.kt`、`domain/NavigationPublisher.kt` |
| 设备控制和图传 | `transport/DeviceLink.kt`、`RdkMediaLink.kt` |
| 相机、图像识别和本机录像 | `ui/FramesPage.kt`、`recognition/Recognizers.kt`、`platform/GlassesRecorder.kt` |
| 求助页面、计时、离线识别和短信回执 | `ui/EmergencyPage.kt`、`domain/VoiceSosConfirmation.kt`、`platform/EmergencyVoiceService.kt`、`SmsReceiptReceiver.kt` |
| 设置、资料库及密钥 | `data/` |

上表路径以 `app/src/main/java/cn/yuntong/companion/` 为起点。

## 接手时的功能状态

- 一加 Ace 5 上试过手机定位、手机录像回放、真实高德搜索和路线预习。高德原地启停已有使用回执，实际步行、偏航和锁屏待验证。
- rc16 的练习读秒、自然结束和正式语音取消已由使用者反馈正常。交接源码 rc17 把第二遍确认后的取消窗口从 15 秒改回 10 秒，尚未在手机上复测。手动拨号曾进入系统拨号流程，锁屏实际响铃和短信回执需要独立验收。
- RDK 的静默观察启停、静置准备和正前方确认有台架记录。电脑协议测试已覆盖媒体鉴权、预览和新图请求，真实眼镜预览、眼镜录像回放与掉线恢复仍待真机联调。
- 跌倒确认事件和短信处理已有接口。本机受控事件测试可以验证事件转发，真实跌倒检测需要板端单独实现。联网视觉问答需要有效服务，目标手机上的 TalkBack 体验仍需真人操作检验。

当前安卓源码为 rc17 / 116。设备侧基线、历史台架口径及仍缺的外部权重分别见 [板端接口](https://github.com/Morechips/yuntong-smart-glasses/blob/main/docs/COMPANION.md) 和 [设备实现状态](https://github.com/Morechips/yuntong-smart-glasses/blob/main/docs/STATUS.md)。板端 rc7 接口有协议自动测试；其真实佩戴图传连续性仍待验。ESP 8082 指纹为历史安装记录，本轮实机固件需现场识别；8084 是已编译的候选，声振并行仍需实测。真实跌倒算法、视觉 IMU 融合和完整设备端路径规划均缺实现。

两次「紧急求助」完成确认，第二次后的取消窗口为 10 秒。练习、语音取消和手动拨号已有反馈，只有触及这些行为或补证据缺口时安排回归。ColorOS 可能要求系统确认短信，记录时区分请求发送、发送回执和送达回执。

## 联调轮次与阻塞

1. **R01，约 15–25 分钟**。先核对测试 APK 安装和 USB 授权。接着选择真实 RDK 局域网或 USB 转发，核对连接、静默观察、实时画面和请求后的新图；最后停止本轮观察。可先做手机独立安装。电脑模拟端只用于接口排障。下载与操作见 [R01 中文说明](../field-test/README.md)。
2. **画面链路有稳定证据后**。以真实眼镜预览作为输入验证录像与本机回放，再验证拍照/OCR 和朗读；同时验证断流后的错误提示与恢复。合成画面编解码和普通手机文件播放仅保留各自验证范围。
3. **版本、画面以及真机签名配置对齐后**。再验证真实高德步行、偏航和锁屏，以及 RDK 导航回执。ESP 8084 声振重叠、目标手机 TalkBack、双模型连续性与佩戴效果另设轮次。

首轮真实图传需要 RDK 已部署佩戴运行包、运行手机接口服务、ESP 已供电并连上正确网络、手机持有配对密钥。Windows 工具探测端口和可选协议握手，另可开本轮专用 USB 转发。真实安装授权、板端实际构建号与图像观察均需 Morechips 实测。高德 Key 必须匹配包名和签名；R01 独立测试包不使用高德 Key。正式签名与正确 Key 在后续导航轮次开始前核对，避免默认卸载已有应用。

使用 [反馈入口](https://github.com/Morechips/yuntong-mobile/issues) 记录各轮现象与脱敏摘要。软件检查结果和 APK SHA 随测试包发布，实测结论收到后在本页链接证据并更新下一轮，维持一份安卓进度。

## 开发时如何验证

改界面或状态先用模拟器和手机独立入口。涉及 RDK 协议时一起核对控制和媒体路径，做断线、过期、重复消息和停止后的回归。

仪器测试可通过 Android Studio 按类运行。需要固定模拟端令牌的用例可在仓库根目录执行下列 PowerShell 命令，随后使用 Android Studio 模拟器。

```powershell
Set-Content -LiteralPath simulator-token.txt -Value 'instrumentation-test-token' -Encoding ascii
python simulator/server.py --scenario blocked --token-file simulator-token.txt
```

其中 `GlassesRecorderTest` 使用合成画面核对手机编解码，可在支持的设备上单独运行。它只验证本机编码器和解码器。眼镜链路另验。
