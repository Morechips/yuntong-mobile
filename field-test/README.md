# 云瞳一键联调 R02

## 开始

1. 从 [R02 测试包发布页](https://github.com/Morechips/yuntong-mobile/releases/tag/field-r02) 下载 `yuntong-field-r02-windows.zip`，完整解压后双击 **开始测试.cmd**。Windows 10/11 可使用中文及带空格的路径。
2. 手机解锁后用可传数据的 USB 线连接电脑。在手机设置里搜索「USB 调试」。尚未开启开发者选项时，到「关于本机 → 版本信息」连续点「版本号」约 7 次。只在信任该电脑时，在手机点「允许 USB 调试」。
3. 点「重新检查」。提示缺少 ADB 时点「准备 ADB」，阅读官方许可后同意下载。若自动下载失败，从 [Android 官方页面](https://developer.android.com/tools/releases/platform-tools) 下载 Windows 版，解压整个 `platform-tools` 到资源管理器地址栏输入 `%LOCALAPPDATA%\Yuntong\FieldTest` 打开的目录，再重新检查。
4. 点「安装本轮 APK」，在 ColorOS 弹窗中允许本次安装。测试版可在 R01 上原位升级，保留正式云瞳。若出现签名不一致或版本过高，先保留数据并联系维护者。桌面上的测试图标名为「云瞳 · 联调测试」。

## RDK 服务和两种手机连接

**R01 已走通 USB。** 建议 R02 先选择「真实 RDK（USB 转发）」。电脑需与 RDK 网络互通，眼镜上电并连着 RDK。实测时请保留现有固件。

| 准备 | Windows 的「RDK/服务」输入 | 手机「我的 → 设备与连接」输入 |
| --- | --- | --- |
| 电脑网线直连 RDK，手机用 USB | 电脑所连网口的 **RDK IPv4**。服务器默认端口为 8767，可以只填 IPv4。 | 点「使用 USB 转发」后，填写电脑窗口显示的 **手机端地址** `ws://127.0.0.1:<本次端口>/rdk`。重建时端口会变。 |
| 手机和 RDK 在互通 Wi-Fi 或局域网 | RDK 在这个网络上的接口地址。电脑也在该网络时可做电脑侧检查。 | 填手机可访问的 RDK 局域网 IPv4 对应的 `ws://<RDK 局域网 IPv4>:8767/rdk`，或团队提供的 WSS 地址。 |
| 电脑模拟端 | 团队已启动的模拟服务地址，路径为 `/link`。 | 填团队提供的手机可达模拟地址和测试令牌，结果记在模拟组。 |

**SSH 可达、8767 不通。** 点「重新检查」。工具会分别判断服务端口和 SSH 端口是否可达。已有 RDK SSH 登录时，在该终端运行下面这行预检。看到 `127.0.0.1:8767` 表示只监听板端回环。没有 8767 监听时服务尚未运行或端口不同。

```bash
ip -br -4 addr; ss -ltn 'sport = :8767'
```

在已有 SSH 终端进入设备仓库，由原启动者先停止本轮观察及该进程。使用 [设备仓库启动说明](https://github.com/Morechips/yuntong-smart-glasses/blob/main/docs/COMPANION.md) 中已有的运行包、Python 环境和令牌文件，把启动命令末尾的 `--host 127.0.0.1` 改为本次选用的 RDK 网口 IPv4。看到 `Yuntong RDK listening` 后保持 SSH 终端运行，再回 Windows 重新检查。若没有运行包、权限或现有服务由其他人管理，在连接项记录「无法测试」及具体卡点。

**配对密钥**来自启动命令中 `--token-file` 指定的文件，默认示例为 `~/.config/yuntong/pairing.token`。由已有授权的设备维护者从可信 SSH 终端本地查看，复制到手机配对输入框。若要做电脑协议检查，也可粘进 Windows 的掩码框。电脑握手会占用单连接服务，应在手机配对前检查。电脑握手、USB 映射和手机成功显示 RDK 状态分别记录。USB 模式可保持电脑网线到 RDK，手机只接电脑的数据线。

## 分组执行

在 Windows 左侧下拉选测试组，可以跳过、关掉后继续。每项先点「开始 / 重试」，最后填写所见所闻并选择结果。说明内的等待时间是观察提示。

| 顺序 | 测试组 | 验收现象 |
| --- | --- | --- |
| 1 | 连接与媒体，约 12–18 分钟 | 启动静默观察，用两张不同纸卡核对预览、帧率和卡顿。实时预览中直接取图，收到新卡后保持定格。停止观察后验证无图提示，重新启动后重试。断开并恢复网络或转发，再看预览及取图。结束前只停止本轮自己开的观察。 |
| 2 | 录像与回放，约 8–12 分钟 | 真实眼镜录下移动纸卡，保存后在「本机录像」检查来源、画面及无声播放。录制中断后验证能播放的片段或明确的未保存提示。手机相机另录一段，单独填写结论。 |
| 3 | 识字与场景，约 10–15 分钟 | 用清晰大字和空白纸试读文字、重读、暂停/停止及收藏。首页语音可以说「读文字」或「描述场景」。场景问答先选图，再确认上传；需要配置真实 HTTPS 服务、模型和密钥，缺少时标为无法测试。 |
| 4 | 导航与无障碍，约 15–25 分钟 | TalkBack 验证焦点、等待和失败播报。求助只用练习模式。高德步行需要维护者提供包名、签名与 Key 匹配的验收包，先做定位与暂停恢复，再在有人陪同的短路线验证偏航、锁屏和结束。RDK 回执单独记录。 |

录制中的测试请勿拔掉 RDK 供电。USB 断线回归用「停止转发」，按步骤重建，再把新手机端地址填进 App；实时 Wi-Fi 中断回归用短时间断开、恢复 Wi-Fi。未观察到新纸卡、看到旧图或超时，记录实际结果和停留时间。

R02 使用独立包名与测试签名，测试版去除了直接拨号和发送短信的权限，未嵌入高德 Key。验证求助时进入「语音练习」，说两次「紧急求助」并在第二次后的 10 秒内说「取消求助」。真实跌倒算法和正式紧急联系不作为本轮操作。

## 保存与反馈

- 「打开结果目录」可看中文报告、JSON 和脱敏日志。软件会保留手机媒体通道、取图和录像等预设事件及帧率，供和人工观察对照。填写所测连接方式、观察时的提示、固件标识（若可查）和实际结果。
- 「提交测试结果」打开 GitHub Issue 草稿。先打开报告检查内容，再把生成的 ZIP 拖入草稿提交。可以先导出、私下发给团队公开内容的维护者，也可以分组多次反馈。截图留在本机，确需分享时先遮住现场隐私。密码、密钥、地址和精确位置勿填入结果。

## 维护者构建

按根 README 配置 SDK 和模型。沿用 R01 的本地独立 debug keystore，确保可原位升级。执行 `.\gradlew.bat -PyuntongFieldTest=true "-PyuntongFieldSigningStore=<本机专用调试签名路径>" assembleDebug testDebugUnitTest lintDebug`。常规高德验收包按根 README 在本地配置签名、包名和匹配的 Key，独立记录 APK 摘要。

```powershell
python field-test/build_package.py --apk app/build/outputs/apk/debug/app-debug.apk --build-tools "$env:ANDROID_HOME/build-tools/35.0.0" --output <输出目录>/yuntong-field-r02-windows.zip
```

Windows 自检使用 `powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File field-test/tests.ps1 -ScratchDirectory <工作区临时目录>`。仪器测试与实测分别见 [交接记录](https://github.com/Morechips/yuntong-mobile/blob/main/docs/handoff.md)。
