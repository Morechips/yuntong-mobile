# 云瞳助行 · Android 伴侣

云瞳智能助行眼镜的安卓伴侣应用。支持高德步行导航、RDK X5 设备协同、眼镜画面与本机录像、文字识别及语音求助。界面使用 Kotlin 与 Jetpack Compose。

当前代码基线为 `1.0.0-rc17`、`versionCode 116`。这是团队开发候选。真机验收范围见[交接说明](docs/handoff.md)。

参加 Windows 联调，从[测试包发布页](https://github.com/Morechips/yuntong-mobile/releases)下载整包，解压后双击 `开始测试.cmd`。本轮项目、接线与反馈办法见[中文使用说明](field-test/README.md)。

## 第一次构建

1. 安装 Android Studio、JDK 17 和 Python 3.11 或更高版本。用 SDK Manager 安装 Android SDK Platform 35、API 35 Extension Level 15 和 Build-Tools 35.0.0。Windows 建议将仓库放在较短的纯英文路径，例如 `C:\dev\yuntong-android`。
2. 在仓库根目录运行 `python tools/fetch_assets.py`。脚本将从官方地址下载 EfficientDet Lite0 和 Vosk 中文模型，并校验 SHA-256。下载完成后，模型随 APK 打包。
3. 用 Android Studio 打开包含 `settings.gradle.kts` 的项目根目录，使用 JDK 17 做 Gradle Sync，选 `app` 配置后运行。也可以用下列命令构建和检查。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

macOS 或 Linux 使用 `./gradlew assembleDebug testDebugUnitTest lintDebug`。命令行需让 Gradle 能找到 SDK，可设置 `ANDROID_HOME`，也可由 Android Studio 生成 `local.properties`。首次构建需要下载 Gradle 和 Maven 依赖。APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

## 本地配置

- **高德地图** 复制 `secrets.properties.example` 为 `secrets.properties`。真实搜索和导航使用高德 Android Key，需匹配包名 `cn.yuntong.companion` 和本机构建证书的 SHA-1。可运行 `.\gradlew.bat signingReport` 查看指纹。Key 留空也能构建，并可使用手机独立功能与电脑演练。
- **安装签名** 本地 debug 构建使用个人调试证书。旧 APK 如果用团队证书签过，个人版无法覆盖安装。开发请优先使用模拟器或空白测试机，保留原手机上的数据。
- **联网问答** 在应用内设置 HTTPS 视觉服务和凭据。请求前由使用者确认选中的一张图。凭据留在手机配置。
- **眼镜连接** 使用 RDK 提供的地址和配对密钥。具体路径、消息和 ESP32-S3 的接口边界见[设备协议](docs/device-interface.md)。

## 没有眼镜时怎么联调

```text
python -m pip install -r simulator/requirements.txt
python simulator/server.py
```

Android Studio 模拟器使用 `ws://10.0.2.2:8766/link`，密钥取启动目录中的 `simulator-token.txt`。在云瞳「我的 → 设备与连接」填写并连接。真机经局域网调试时，电脑运行 `python simulator/server.py --host 0.0.0.0`，App 填电脑的局域网 IPv4。模拟端可以提供示例画面和导航回执。

运行模拟端测试可在 `simulator/` 里执行 `python -B -m unittest -v test_server`。Android 仪器测试涉及猫狗样图时，先执行 `python tools/fetch_assets.py --android-tests`。部分仪器用例还需要电脑模拟端使用测试令牌 `instrumentation-test-token`，详见[交接说明](docs/handoff.md)。

## 仓库内容

| 路径 | 内容 |
| --- | --- |
| `app/src/main/` | UI、地图、网络、识别、数据存储、媒体与求助源码 |
| `app/src/test/`、`app/src/androidTest/` | JVM 与设备用例 |
| `simulator/` | 手机联调用的电脑 WebSocket 模拟端 |
| `tools/fetch_assets.py` | 官方模型和测试照片的下载与哈希校验 |
| `docs/` | [交接说明](docs/handoff.md)、[设备协议](docs/device-interface.md)和[第三方依赖](docs/third-party.md) |

眼镜侧代码在[云瞳眼镜工程仓库](https://github.com/Morechips/yuntong-smart-glasses)维护。团队自有代码的授权方式由权利人确定，第三方组件按各自条款使用。
