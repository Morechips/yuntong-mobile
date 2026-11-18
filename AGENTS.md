# 云瞳助行 Android 协作指引

本仓库维护 Kotlin / Jetpack Compose 安卓伴侣。开发入口和已有验证见 [交接与进度](docs/handoff.md)。设备代码属于 [眼镜仓库](https://github.com/Morechips/yuntong-smart-glasses)，修改安卓与 RDK 的合同前先核对 [安卓协议](docs/device-interface.md) 和 [板端接口](https://github.com/Morechips/yuntong-smart-glasses/blob/main/docs/COMPANION.md)。

- 开发者负责代码、构建、静态检查、自动测试、中文测试工具，以及根据实测报告修复。Morechips 在自己的 Windows 上接线并完成手机授权、观察和录入结果；SMG0128 沿用自己的 Git 配置推送安卓，设备改动向设备仓库提 PR。
- 从 [README](README.md) 准备 JDK 17、Android SDK 35 Extension 15 和模型。Android 检查用 `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`，按改动选择仪器用例。模拟端用 `python -B -m unittest -v test_server`（在 `simulator/` 中执行）。界面改动核对 TalkBack 语义、焦点和状态。
- 沿用已有记录。代码、JVM/协议自动测试、模拟端、手机独立、手机与真实 RDK、RDK 与 ESP32-S3、完整实测各自注明范围。未经确认的状态记为待验证。设备协议改动核对控制与媒体、重连、过期和重复消息。
- 每轮集中验证 15–30 分钟，发布对应同一 Git 提交的工具、APK、版本与中文说明。默认双击 `开始测试.cmd`。当前入口和下一轮顺序在 [交接与进度](docs/handoff.md)。优先回归修复点及受影响路径。
- 测试反馈默认通过 [GitHub Issues](https://github.com/Morechips/yuntong-mobile/issues) 附脱敏反馈包。需要以 Git 文件归档的报告使用独立分支 `results/<轮次>/<反馈编号>`，文件放在 `test-results/<轮次>/<反馈编号>/`。结论、已知问题与下轮入口回填交接文档，链接结果记录。不要 force push。
- 配对密钥、SSH 凭据、高德 Key、签名私钥、联系人和未脱敏日志保留在本机。测试安装保留正式 App 和已有数据，核对包名及证书。常规验证不触发真实紧急联络、刷机、清数据或删除录像。
