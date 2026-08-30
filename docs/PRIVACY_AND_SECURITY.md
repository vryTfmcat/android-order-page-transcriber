# 隐私与安全边界

## 数据流

1. 分享文字优先，其次是当前窗口的无障碍文字节点。
2. 节点文字过少时，仅在用户点击后调用窗口截图 API，把 Bitmap 交给本地 ML Kit 中文 OCR。
3. OCR 回调完成后立即 `recycle` Bitmap 和关闭 HardwareBuffer；不写相册、文件、日志或网络。
4. 去敏纯文本才能进入加密队列或发送给 Mac。Mac 在写入前再做一次去敏。

## 权限和明确不做的事

- 无障碍服务只在手动指令时读取当前页；不调用点击、手势、自动滚动或业务操作。
- 不使用 MediaProjection，不保留屏幕录制会话。
- 安全窗口返回 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` 时停止，建议用“分享”或“复制文字”，不尝试绕过。
- 不发送云端 AI，不使用第三方分析 SDK，不采集使用统计。
- 地址、手机号、卡号/支付账号、快递/物流单号默认删除。

## 传输与秘密

- 局域网连接使用自签 HTTPS，Android 不信任通用自签证书，只信任配对时保存的 SHA-256 指纹。
- Tailscale 入口使用 Tailnet HTTPS，再转发给 Mac 回环地址；回环 HTTP 不对局域网监听。
- 每台 Android 设备使用长随机令牌。令牌、证书私钥、配对链接、SQLite 和日志都在 Git 忽略的 `runtime/`。
- Android 用 Keystore AES-256-GCM 加密配对配置、当前会话和待发队列。

## 依据

- [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)
- [AccessibilityService 窗口截图与安全窗口错误](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [ML Kit 中文文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [Android 接收分享数据](https://developer.android.com/training/sharing/receive)
- [Android 16 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)
- [Tailscale Serve](https://tailscale.com/kb/1247/funnel-serve-use-cases)
