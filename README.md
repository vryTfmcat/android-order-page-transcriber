# Android 订单与页面转录器

个人侧载的 Android App + Mac 本地接收服务。手机端一键读取当前页文字，必要时在内存中做中文 OCR，然后复制 Markdown、写入 Obsidian Inbox，或在查重和人工确认后建立实体卡。

> 当前版本：`0.2.2` 真机修正版。点击采集会显示处理反馈，完成后在原订单页显示可点击的临时结果横幅，不依赖 HyperOS 允许后台自动打开预览；抖音页面会强制补充内存 OCR，并从结构化结果中排除直播推荐。Mac 接收端自动化测试和 Android 单元测试已通过；真机上的六个购物 App 验收继续在小米 17 上完成。

## 已实现

- Android 分享目标：接收文字、链接或图片，图片只做本地 OCR。
- App 内扫描 Mac 配对二维码，Google 扫码模块不可用时可粘贴 `ordercapture://pair` 链接。
- 无障碍按钮与快捷设置磁贴：优先读节点文字，文字不足时采样当前窗口。
- 会缓存最近的非系统窗口文字，并拒绝把 MIUI 桌面、最近任务或系统设置误记为订单。
- `FLAG_SECURE` 窗口明确报错，不绕过 Android 限制。
- 手动滚动后“追加一页”：按文本行、订单号和字段去重；不自动滚动、点击或下单。
- 订单和通用页面两种模式；默认删除地址、手机号、支付账号与物流单号。
- 离线队列、配对信息和队列内容均由 Android Keystore 生成的 AES-GCM 密钥加密；不缓存截图。
- Mac 同时提供局域网证书指纹锁定 HTTPS 与 Tailscale Serve 回环后端。
- `captureId` 幂等 Inbox 写入，实体草稿查重，以确认令牌原子创建/更新实体。

## 目录

- `android/`：Kotlin 原生 App，最低 Android 11，目标 Android 16 / API 36。
- `receiver/`：仅使用 Python 标准库的 HTTPS/API/写入服务；`segno` 只用于生成配对二维码。
- `scripts/`：本机初始化、launchd 常驻、Tailscale 路径追加和 Android 构建。
- `docs/`：数据协议、隐私边界和真机验收表。
- `runtime/`：本机私有证书、令牌、SQLite、日志和配对码；被 Git 忽略。

## Mac 端安装

需要 Python 3、OpenSSL 和 Tailscale。本机的 Tailnet 地址为示例：

```bash
./scripts/setup_receiver.sh 'https://mac-mini.tail73cba1.ts.net/order-capture'
./scripts/start_receiver.sh
./scripts/configure_tailscale.sh
```

`configure_tailscale.sh` 会先保存 Serve 配置快照，只追加 `/order-capture -> 127.0.0.1:43118`，然后验证原 `/` 代理未改变；验证失败会恢复原配置，不调用 `serve reset`。

运行状态：

```bash
kill -0 "$(cat runtime/receiver.pid)" && echo running
curl -k https://localhost:43117/v1/health
tailscale serve status
```

配对二维码在 `runtime/pairing-qr.svg`。它含设备令牌，不应发布、同步或放入 Obsidian。

`start_receiver.sh` 在终端权限下启动脱离会话的守护进程。这是因为 macOS 不允许普通 LaunchAgent 直接读取受保护的 `Documents` 目录。重启 Mac 后需再运行一次启动脚本；不把源码或秘密复制到库外的系统目录。

## Android 构建与安装

Android Studio、SDK 36 和 JDK 17 就绪后：

```bash
./scripts/build_android.sh
/Users/a13713912476/Documents/Codex/Android/sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

安装后：

1. 在系统设置启用“提取当前页面”无障碍服务。
2. 在 App 首页点“扫描配对二维码”，扫描 Mac 上的 `runtime/pairing-qr.svg`。
3. 在快捷设置中添加“提取页面”磁贴，或使用 Android 无障碍按钮。

## 使用流程

1. 在 App 首页选择“下次：新建转录”，再打开订单详情并点击磁贴/无障碍按钮；或从购物 App 的分享面板选择本 App。
2. 需要第二屏时自己滚动，再点“追加一页”。
3. 在预览页修正 OCR 数字和商品名，选择复制 Markdown、发送 Inbox 或查重建立实体。
4. 实体写入必须选择已有分类；有重复候选时，必须显式选择“更新已有”或“仍创建新实体”。

## 测试

```bash
PYTHONPATH=receiver python3 -m unittest discover -s receiver/tests -v
./scripts/build_android.sh
```

不安装云端 AI，不保存截图，不上架应用商店。有关 Android 无障碍、窗口截图、ML Kit 和 Tailscale 的设计依据见 [docs/PRIVACY_AND_SECURITY.md](docs/PRIVACY_AND_SECURITY.md)。
