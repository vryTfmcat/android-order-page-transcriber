# CaptureEnvelope v1

Android 向 Mac 发送 UTF-8 JSON，图片永不进入协议。所有请求必须带 `Authorization: Bearer <device-token>`。

```json
{
  "schemaVersion": 1,
  "captureId": "cap_…",
  "capturedAt": "2026-08-31T12:30:00+08:00",
  "sourceApp": "com.example.shop",
  "sourceUrl": "https://example.invalid/order",
  "title": "商品名",
  "kind": "order",
  "rawText": "去敏后、合并去重的可见文字",
  "order": {
    "platform": "平台",
    "merchant": "商家",
    "orderNumber": "…",
    "totalPaid": 99.8,
    "status": "待收货",
    "orderedAt": "2026-08-31 12:30",
    "items": [
      {"name": "商品", "specification": "规格", "quantity": 2, "linePrice": 99.8}
    ]
  },
  "warnings": ["本页使用本地 OCR，请核对数字"]
}
```

## API

### `POST /v1/inbox-captures`

以 `captureId` 幂等写入 `00_Inbox/订单转录/`。返回 `status`、`path`、`wikilink` 和 `warnings`。

### `POST /v1/entity-drafts`

仅查重、读取已有分类并生成 30 分钟确认令牌，不写正式实体。候选依据为订单号相同或商品名/型号相近。

### `POST /v1/entities/commit`

```json
{
  "draftToken": "…",
  "selections": [
    {
      "index": 0,
      "entityType": "item",
      "category": "电子设备/充电器",
      "action": "create"
    }
  ]
}
```

`action` 可为 `create` 或 `update`；更新时必须提供草稿候选中的 `candidatePath`。同一令牌重试只返回第一次结果。多文件写入中任一失败时，接收端删除新建文件并恢复已更新文件。

## 约束

- 请求体最大 1 MB，原文最大 50,000 字符，商品最多 100 个。
- `purchasePrice` 只写订单行中确认的实付；总额不被拆分或反推。
- `purchasedQuantity` 是订单数量，不是现场 `quantity`。
- 批次数量未验证时为 `quantity: null` + `quantityStatus: unverified`。
- `purchaseRef` 指向实体卡自身的 `[[#购买信息]]` 章节。
- 分类目录必须已存在，后端不自动创建分类或移动旧笔记。
