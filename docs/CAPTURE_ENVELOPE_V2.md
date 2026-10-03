# CaptureEnvelope v2（多平台规则版）

v2 以“忠实记录页面字段、派生值可审计”为原则。接收端继续兼容 v1；Android v2 默认保留收货地址，但仍过滤电话、支付账号和物流单号。

## 核心变化

- `order.amounts` 分别保存商品总价、运费、各类优惠、应付款、实付款、确认收货后付款和定金，禁止相互静默代填。
- `order.actualSpend` 是校验后的派生值；整单退款为 `0`，部分退款证据不足时为 `null`。
- `issues` 使用 `ERROR/WARN/INFO + code`，任何自动修正都必须留下记录。
- `items` 保存金额类型、退款状态、来源页、证据与置信度。
- `pages` 保存多屏采集来源；合并时只消除相邻截图边界重叠，不全局删除同名 SKU。
- `recognitionProfile` 记录实际使用的规则 ID，例如 `douyin_mall`、`douyin_groupbuy`、`pinduoduo` 或 `eleme`。

```json
{
  "schemaVersion": 2,
  "captureId": "cap_…",
  "capturedAt": "2026-10-03T12:30:00+08:00",
  "kind": "order",
  "keepAddress": true,
  "recognitionProfile": "douyin_mall",
  "order": {
    "platform": "抖音商城",
    "orderNumber": "6900000000000000001",
    "amounts": {
      "productTotal": 100,
      "shippingFee": 0,
      "storeDiscount": 10,
      "platformDiscount": 20,
      "coinDiscount": 5,
      "paymentDiscount": null,
      "payable": null,
      "actualPaid": 65,
      "payAfterReceipt": null,
      "deposit": null
    },
    "refundState": "none",
    "actualSpend": 65,
    "items": [{
      "name": "商品",
      "quantity": 1,
      "linePrice": 65,
      "amountType": "order_fallback",
      "refundState": "none",
      "sourcePage": 1,
      "evidence": "页面商品行",
      "confidence": "medium"
    }]
  },
  "issues": [{
    "severity": "INFO",
    "code": "ITEM_AMOUNT_FROM_ORDER_TOTAL",
    "message": "单商品行金额沿用订单付款金额",
    "field": "order.items.linePrice",
    "source": "derived"
  }],
  "pages": [{"pageIndex": 1, "captureId": "cap_…", "capturedAt": "2026-10-03T12:30:00+08:00"}]
}
```

## 可切换的平台规则

| 规则 ID | 界面名称 | 输出平台 |
|---|---|---|
| `pinduoduo` | 拼多多规则 | 拼多多 |
| `douyin_mall` | 抖音商城规则 | 抖音商城 |
| `douyin_groupbuy` | 抖音团购规则 | 抖音团购 |
| `meituan` | 美团规则 | 美团 |
| `eleme` | 饿了么规则 | 饿了么 |
| `taobao` | 淘宝规则 | 淘宝 |
| `jd` | 京东规则 | 京东 |
| `idlefish` | 闲鱼规则 | 闲鱼 |

自动模式按 App 包名、URL 和页面文字评分；手动固定后不再根据页面猜测平台。抖音团购优先检查「团购订单」「券码详情」「到店使用」等强特征，避免与抖音商城混淆。

## 抖音校验代码

- `DOUYIN_ORDER_ID_INVALID`：不是 19 位纯数字，级别 `ERROR`。
- `DOUYIN_ORDER_ID_TRAILING_ONE_FIXED`：20 位且末尾为 `1`，自动截为 19 位并记录原值。
- `AMOUNT_FRAGMENTED_PAIR`：金额由分离的标签和值配对，需复核。
- `AMOUNT_EQUATION_MISMATCH`：商品总价、运费、优惠与付款字段不平。
- `ITEMS_COLLAPSED`：仍存在“查看剩余 N 件商品”，本次采集不完整。
- `PARTIAL_REFUND_SPEND_UNKNOWN`：部分退款但缺少可靠逐件实付。
- `REFUND_SCOPE_UNKNOWN`：存在退款成功，但无法判断整单或单品范围。
- `QUANTITY_SUSPICIOUS`：数量大于等于 100，可能是 OCR 串位。

## 兼容字段

`order.totalPaid` 暂时保留，取 `actualPaid`，没有时取 `payAfterReceipt`。它只用于旧界面兼容，不参与判断原始字段含义。新逻辑必须读取 `order.amounts`。
