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

## 开发时如何验证

改界面或状态先用模拟器和手机独立入口。涉及 RDK 协议时一起核对控制和媒体路径，做断线、过期、重复消息和停止后的回归。

仪器测试可通过 Android Studio 按类运行。需要固定模拟端令牌的用例可在仓库根目录执行下列 PowerShell 命令，随后使用 Android Studio 模拟器。

```powershell
Set-Content -LiteralPath simulator-token.txt -Value 'instrumentation-test-token' -Encoding ascii
python simulator/server.py --scenario blocked --token-file simulator-token.txt
```

其中 `GlassesRecorderTest` 使用合成画面核对手机编解码，可在支持的设备上单独运行。它只验证本机编码器和解码器。眼镜链路另验。
