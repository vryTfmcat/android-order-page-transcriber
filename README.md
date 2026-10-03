# Android 订单与页面转录器

个人侧载的 Android App + Mac 本地接收服务。手机端一键读取当前页文字，必要时在内存中做中文 OCR，然后复制 Markdown、写入 Obsidian Inbox，或在查重和人工确认后建立实体卡。

> 当前版本：`0.4.0`，多平台规则切换版。采集层保持通用，拼多多、抖音商城、抖音团购、美团、饿了么、淘宝、京东和闲鱼使用可切换的独立识别配置。每份转录记录实际使用的规则 ID，接收端继续兼容 v1/v2。

## 已实现

- Android 分享目标：接收文字、链接或图片，图片只做本地 OCR。
- App 内扫描 Mac 配对二维码，Google 扫码模块不可用时可粘贴 `ordercapture://pair` 链接。
- 无障碍按钮与快捷设置磁贴：优先读节点文字，文字不足时采样当前窗口。
- 会缓存最近的非系统窗口文字，并拒绝把 MIUI 桌面、最近任务或系统设置误记为订单。
- `FLAG_SECURE` 窗口明确报错，不绕过 Android 限制。
- 手动滚动后“追加一页”：只消除相邻截图的边界重叠，保留真实重复 SKU，并记录每屏 `captureId`；不自动滚动、点击或下单。
- 可开启“点取后自动发送 Inbox”；Mac 离线时加密排队，后续自动补送。追加模式仍需预览后手动发送。
- 平台默认自动识别；也可在首页固定为「拼多多规则」「抖音商城规则」「抖音团购规则」「美团规则」「饿了么规则」「淘宝规则」「京东规则」或「闲鱼规则」。
- 每个配置独立定义 App/链接特征、页面关键词、订单号标签、实付标签、商家标签和状态词；新平台只需新增配置，不需要复制整个采集流程。
- 订单和通用页面两种模式；收货地址默认保留；手机号、座机、支付账号与物流单号始终删除。
- 部分退款统一按“页面实付款已经扣除退款商品”处理，不从实付款二次扣减；页面缺失实付款时才修正兜底金额。
- CaptureEnvelope v2 独立保存商品总价、运费、店铺/平台/金币/支付优惠、应付款、实付款、确认收货后付款和定金。
- 抖音订单号必须为 19 位纯数字；20 位且末位为 `1` 时可修正，但会生成 `WARN` 审计记录。
- 校验问题采用 `ERROR/WARN/INFO + code`，详见 [CaptureEnvelope v2](docs/CAPTURE_ENVELOPE_V2.md)。
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
ORDER_CAPTURE_VAULT='/path/to/your/Obsidian-vault' ./scripts/setup_receiver.sh 'https://your-mac.your-tailnet.ts.net/order-capture'
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

`start_receiver.sh` 在 Terminal 权限下启动脱离会话的守护进程。开机项使用 `scripts/start_receiver_at_login.command`，由 Terminal 继承已授予的 Documents 权限后启动接收端；登录时可能短暂显示 Terminal 窗口。普通 LaunchAgent 直接运行 Python 会被 macOS 拒绝访问 Documents。

## Android 构建与安装

Android Studio、SDK 36 和 JDK 17 就绪后：

```bash
./scripts/build_android.sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

安装后：

1. 在系统设置启用“提取当前页面”无障碍服务。
2. 在 App 的“打开应用后台设置”中将省电策略设为“无限制”并允许自启动。
3. 在 App 首页点“扫描配对二维码”，扫描 Mac 上的 `runtime/pairing-qr.svg`。
4. 在订单页点屏幕左侧蓝色“取”，或使用“提取页面”快捷磁贴/前台通知操作。

## 使用流程

1. 在 App 首页选择「自动识别」或某个平台规则，再选择“下次：新建转录”，打开订单详情点击蓝色“取”；或从购物 App 的分享面板选择本 App。
2. 需要第二屏时自己滚动，再点“追加一页”。
3. 在预览页修正 OCR 数字和商品名，选择复制 Markdown、发送 Inbox 或查重建立实体。
4. 实体写入必须选择已有分类；有重复候选时，必须显式选择“更新已有”或“仍创建新实体”。

## 测试

```bash
PYTHONPATH=receiver python3 -m unittest discover -s receiver/tests -v
./scripts/build_android.sh
```

不安装云端 AI，不保存截图，不上架应用商店。有关 Android 无障碍、窗口截图、ML Kit 和 Tailscale 的设计依据见 [docs/PRIVACY_AND_SECURITY.md](docs/PRIVACY_AND_SECURITY.md)。
