# 手机与眼镜接口

## 连接方式

| 对端 | WebSocket 路径 | schema | 握手 |
| --- | --- | --- | --- |
| 电脑模拟端 | `/link`，默认 8766 | `yuntong-link-test-v1` | `hello.accepted` 的 `testOnly` 为 `true` |
| RDK X5 控制 | `/rdk`，默认 8767 | `yuntong-rdk-link-v1` | `hello.accepted` 的 `testOnly` 为 `false` |
| RDK X5 媒体 | `/rdk/media`，沿用控制连接主机和端口 | `yuntong-rdk-link-v1` | 升级请求鉴权后直接发媒体命令 |

WebSocket 升级请求带 `Authorization: Bearer <token>`。控制连接先由 App 发送 `hello`，字段 `client` 为 `yuntong-android`。RDK 控制握手返回 `capabilities`，当前 App 检查 `navigation.receive`、`device.status`、`device.command`。局域网台架可使用 `ws://`，跨网络使用配置受信证书的 `wss://`。

## RDK 控制

| 方向 | 消息 | 使用约定 |
| --- | --- | --- |
| App → RDK | `device.command` | 带 UUID `requestId` 及 `action`，当前为 `start`、`stop`、`prepare`、`front.confirm` |
| RDK → App | `device.command.ack`、`device.status` | 命令回执包含同一 `requestId`，阶段为 `received`、`executed` 或 `failed`。持续状态含 `deviceSequence`、`status` 和 `perception` |
| App → RDK | `session.*`、`route.replace`、`navigation.*`、`user.action` | 使用 `sessionId`、`routeVersion`、`sequence`、`source`、`phase`。RDK 接受真实 `amap` 来源，模拟路线走电脑端 |
| RDK → App | `ack`、`rejected`、`guidance` | 回执关联相同会话、路线版本、序号和来源。候选提示另外带 `validForMs`，手机按当前会话及时效过滤 |
| RDK → App | `fall.confirmed` | 带 `eventId`、`validForMs`。手机经既有授权、联系人和事件去重检查后才可能请求短信 |

## RDK 媒体

- **实时预览** App 发送 `media.subscribe` 并设置 `enabled`。RDK 在独立连接回 `frame`，包含 `frameId`、`capturedAt` 和 Base64 JPEG 字段 `jpeg`。
- **主动取图** App 发送 `media.capture` 和 UUID `requestId`。RDK 等候请求后出现的新图，回带同一 `requestId` 的 `frame`。约 2 秒无新图时回 `media.unavailable`。台架上需先启动产出画面的观察任务。R02 在手机端最多等待约 6.5 秒（含解码），取消、超时、来源切换和断线后的旧回执不会替换画面。
- **预览帧率** RDK 现有接口大约每 250ms 检查一次 JPEG，Android 显示手机端成功接收、解码并发布的近似帧率。该值用于排障，板端采样和用户实际看到的画面更新另行实测。
- **本机录像** App 对订阅收到的眼镜画面编码为无声 MP4，本机保存和回放。

## 双仓分工

Android 通过上述 WebSocket 与 RDK X5 对接。ESP32-S3 的相机、IMU 和外设控制协议由 RDK 与固件侧维护，Android 无直连 ESP32-S3 的接口。设备侧实现与发布记录归[云瞳眼镜工程仓库](https://github.com/Morechips/yuntong-smart-glasses)，此页供安卓开发核对消息。涉及字段变化时两个仓库应同步，先核对兼容性和现有回执。
