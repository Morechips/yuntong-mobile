# 云瞳一键联调 R01

## 下载并打开

1. 在 [GitHub 测试包](https://github.com/Morechips/yuntong-mobile/releases/tag/field-r01) 下载发布附件 `yuntong-field-r01-windows.zip`。用 Windows 解压到普通目录，支持中文与空格。请解压整包。
2. 双击根目录 **开始测试.cmd**。等待中文窗口。右下方显示本轮编号。安装验证用 Windows 10/11 和一台 Android 手机，真实图传还需上电的 RDK X5 与眼镜。预算约 15–25 分钟。
3. 若提示缺少 ADB，点「准备 ADB」。需要先看许可说明时选「否」打开 [Google 官方页面](https://developer.android.com/tools/releases/platform-tools)，同意后回窗口选「是」下载。若自动下载失败，从同一页面保存 Windows ZIP，把整个 `platform-tools` 文件夹解压到资源管理器地址栏输入 `%LOCALAPPDATA%\Yuntong\FieldTest` 打开的目录，然后重新检查。无需安装 Python、Gradle 或 Android Studio。

## 安装与检查

1. 用可传数据的 USB 线连接已解锁的手机。如果手机尚无开发者选项，在「设置 → 关于本机 → 版本信息」连续点「版本号」约 7 次，再在「设置」搜索「USB 调试」开启。菜单名随系统版本变化。
2. 手机弹出「允许 USB 调试」时，仅在信任这台电脑后点「允许」。回电脑点「重新检查」。如果未发现手机，先换线或 USB 口，关闭其他模拟器，保持手机解锁。
3. 点「安装本轮 APK」并确认。ColorOS 弹出 USB 安装时，在手机确认本次测试应用；若权限被限制，可在开发者选项检查「通过 USB 安装」。手机桌面应出现「云瞳 · 联调测试」图标，点电脑的「打开测试应用」会进入云瞳首页。这是独立应用，保留正式云瞳及其数据，也没有直接拨号或发短信权限。这轮无需地图 Key。
4. 如果提示签名或版本不一致，记录现象并联系维护者取匹配包。已有数据保留。遇到测试版尚未安装但能测试其他项时，先记录「无法测试」及原因。

## 真实眼镜和模拟端

1. 真实眼镜链路先确认两台设备已上电，RDK 已由维护者部署佩戴运行包并启动手机接口，获取 RDK 服务地址和配对密钥。选择「真实 RDK（手机局域网）」并填写 RDK IP 或完整 WebSocket 地址。手机与 RDK 应处在能互通的网络。点击「重新检查」探测电脑侧端口；如需验证服务协议，可在手机尚未连接时把配对密钥临时填在电脑的密钥框，再点「检查协议」。密钥只用于这次内存检查。
2. 手机无法直接访问 RDK、但电脑可访问时，选择「真实 RDK（USB 转发）」并填电脑可达的 RDK 局域网地址，点「使用 USB 转发」。保持 USB 和窗口打开，在手机填写窗口显示的地址及本轮配对密钥。此功能只转发电脑可达的明文 WebSocket，受信证书的 WSS 请走直连。电脑也连不到时，检查供电、网络和板端服务；维护者的板端准备见 [板端接口说明](https://github.com/Morechips/yuntong-smart-glasses/blob/main/docs/COMPANION.md)。
3. 每项先选「开始 / 重试」，阅读窗口准备条件。手机配对后应显示真实 RDK 状态。确认观察任务当前未从其他入口开启，再点「启动眼镜静默观察」。用不含个人信息的两张不同纸卡验证预览与主动取新图。主动取图看到新卡才选通过，若显示旧卡、无新图或超时，则选失败并记录提示。结束时先在手机停止本轮观察，再停电脑转发。
4. 没有真实眼镜可选择「手机独立」验证安装。已由维护者准备电脑模拟端时，选择「模拟 RDK」，填本轮模拟地址和测试令牌，验证示例画面。模拟结果只对应模拟端。

每项把实际看到或听到的现象填进窗口，例如「预览会动，但暂停后换卡并主动取图，画面仍停在旧卡」。选择「通过」「失败」「无法测试 / 跳过」或「停止本项」。连接恢复后用「重新检查」和「开始 / 重试」继续。中途关闭再打开同一包可恢复已保存的结果；ADB 转发随窗口关闭而结束。

## 导出和交回

- 「打开结果目录」可预览中文报告、机器可读记录和按类别计数的诊断。「导出反馈包」生成包含这三个文件的 ZIP。截图按钮会留一份本机副本；要分享截图，请自己先查看、裁掉联系人、地址或拍摄现场中的隐私。
- 「提交测试结果」先显示待分享文件，再打开浏览器 GitHub 草稿。已有浏览器登录可继续；需登录时由你本人登录。将 ZIP 拖入草稿，查看附件，点击 **Submit new issue**。同一轮已有 Issue 时，也可把 ZIP 拖入该 Issue 的新评论再提交，或把 ZIP 私下发给项目维护者代为处理。
- 提交前看一眼报告。工具会清理常见凭据、号码、坐标和链接，日志只汇总分类与次数。请勿在现象中手填姓名、密码、Key、精确地址或位置。

R01 尚待验证 Morechips 的 ColorOS 授权、实际 RDK 网络及眼镜画面。录像、识别、掉线恢复、步行导航和紧急联络属于后续轮次。

## 维护者构建同轮测试包

先按仓库根 README 配好 SDK 和模型。使用本机持久保存的专用 debug 签名，构建 `.\gradlew.bat -PyuntongFieldTest=true "-PyuntongFieldSigningStore=<本机专用调试签名路径>" assembleDebug testDebugUnitTest lintDebug`。AGP 的专用 debug keystore 使用标准 Android 调试别名和密码；正式签名和地图 Key 不参与该包。随后从干净的源码提交执行：

```powershell
python field-test/build_package.py --apk app/build/outputs/apk/debug/app-debug.apk --build-tools "$env:ANDROID_HOME/build-tools/35.0.0" --output <工作区输出目录>/yuntong-field-r01-windows.zip
```

先在 Windows 跑 `powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File field-test/tests.ps1 -ScratchDirectory <工作区临时目录>`。测试源码入口不含 APK，交付应使用打包脚本生成的整包。
