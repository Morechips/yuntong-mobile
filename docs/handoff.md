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

### R01 Issue #1 实测和处置

基线为 `field-r01`（rc17-r01/116），附件记录 Android 16 且 APK 哈希匹配。Issue 正文附了中文报告、JSON 和诊断三份文件，没有评论；设备实装版本未填写。以下按附件的人工结果和日志记录。 [R01 实测 Issue #1](https://github.com/Morechips/yuntong-mobile/issues/1)

| R01 现象 | 定位与 R02 处理 | 请回归或补证 |
| --- | --- | --- |
| 安装和启动通过 | R02 提升版本号并沿用 R01 测试签名。 | 确认可原位升级，核对工具的 APK 哈希。 |
| 电脑端口多次不可达，后续电脑握手通过 | 按第 2 轮补充，遇到手机接口服务未启动、SSH 可达而 8767 不通。分开检查服务和 SSH 端口，补充监听地址及密钥获取步骤。 | 重新检查，记下接线方式与服务状态；手机成功配对单独记录。 |
| 手机局域网直连当时未接通；USB 转发连通并通过连接检查 | 网络原因待定。工具区分板端地址和手机回环地址，记录运行中的转发表。 | 先用已走通的 USB；直连另测，可跳过。 |
| USB 预览通过，图像清晰但略卡 | R01 缺帧率与源侧诊断。手机改为有界最新帧解码，并显示近似帧率。RDK 当前约 250ms 检查一次 JPEG。低帧率和卡顿根因仍待测。 | 台架移动纸卡，记录手机帧率、停顿和使用的板端版本。 |
| USB 主动取图人工通过 | 取图正常路径有实测记录。代码排查另发现迟到预览可覆盖取图、等待无客户端超时。R02 按请求 ID 固定快照，等待有进度、失败和重试。 | 预览中直接拍两张不同纸卡，核对等待、定格和重试。 |
| 日志记录 USB 映射移除；没有断线重连和停止观察的人工结果 | 新增媒体失联与重订阅、清除过期帧、录像中断和安全日志标记。 | 先验证失败提示，再恢复转发和观察，补填停止观察的实机结果。 |

### R02 交付和实测顺序

1. **连接与媒体，约 12–18 分钟**。以 USB 为主，回归安装、状态、预览帧率、新画面、无新图、重连和停止。RDK 端同时补了 JPEG 读取一致性与时间边界，现有 rc7 协议保持兼容。 [设备 PR #1](https://github.com/Morechips/yuntong-smart-glasses/pull/1)
2. **录像与回放，约 8–12 分钟**。保存有效编码帧后登记录像；断流尝试保留可播放片段，缺帧或保存失败给出提示。真实眼镜、手机原生录像与合成输入分别核验。
3. **识字与场景，约 10–15 分钟**。主动新图可接本地识字和朗读，空白或模糊图给重取建议，结果可停止、重读和收藏。首页可说「描述场景」，由用户选图或取图后确认上传。实际云端回答需要已配置的 HTTPS 服务，未做线上验收。
4. **导航与无障碍，约 15–25 分钟**。锁屏通知分别提供暂停、恢复和结束；定位变弱、恢复和结束时清理旧坐标。实际高德定位、偏航、锁屏、实走和 RDK 回执需要包名、签名与 Key 匹配的验收包。目标设备 TalkBack 和系统听感待测。求助练习保留两次确认及 10 秒取消，受控跌倒事件与真实跌倒算法分别记录。

每组支持独立执行、跳过和恢复。APK 和 Windows 工具按同一提交打包，设备 PR 的版本和实装号另行记录。检查口径、下载和步骤见 [R02 发布](https://github.com/Morechips/yuntong-mobile/releases/tag/field-r02) 与 [中文说明](../field-test/README.md)。

真实图传需要 RDK 已部署佩戴运行包并启动手机接口服务、ESP 已上电联网，以及当前配对密钥。独立测试包未嵌入高德 Key，导航路线验收使用签名匹配的本地配置。先收集 R02 现象与诊断，定位持续卡顿或恢复问题，再推进真实行走和长时体验。

使用 [反馈入口](https://github.com/Morechips/yuntong-mobile/issues) 记录各轮现象与脱敏摘要。软件检查结果和 APK SHA 随测试包发布，实测结论收到后在本页链接证据并更新下一轮，维持一份安卓进度。

## 开发时如何验证

改界面或状态先用模拟器和手机独立入口。涉及 RDK 协议时一起核对控制和媒体路径，做断线、过期、重复消息和停止后的回归。

仪器测试可通过 Android Studio 按类运行。需要固定模拟端令牌的用例可在仓库根目录执行下列 PowerShell 命令，随后使用 Android Studio 模拟器。

```powershell
Set-Content -LiteralPath simulator-token.txt -Value 'instrumentation-test-token' -Encoding ascii
python simulator/server.py --scenario blocked --token-file simulator-token.txt
```

其中 `GlassesRecorderTest` 使用合成画面核对手机编解码，可在支持的设备上单独运行。它只验证本机编码器和解码器。眼镜链路另验。
