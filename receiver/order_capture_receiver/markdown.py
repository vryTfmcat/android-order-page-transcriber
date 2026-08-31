from __future__ import annotations

import json
import re
import unicodedata
from datetime import datetime

from .models import CaptureEnvelope, CaptureItem


SENSITIVE_LINE_RE = re.compile(
    r"(?:收货(?:人|地址)|详细地址|联系电话|手机号码|手机号|银行卡|支付账号|快[递遞]单号|运单号|物流单号)\s*[:：]?",
    re.IGNORECASE,
)
PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
MASKED_PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d(?:[\d*＊•·xX\s]{3,10})\d{2,4}(?!\d)")
CARD_RE = re.compile(r"(?i)(银行卡|卡号|支付账号)(\s*[:：]?\s*)[\d *-]{8,30}")
PAYMENT_CARD_RE = re.compile(r"(?:银[行銀]|储[蓄蕴]|信用).*?卡|(?:卡|CARD)\s*[(*（]?\d{3,6}[)*）]?", re.IGNORECASE)
ADDRESS_WORD_RE = re.compile(r"省|市|自治区|区|县|镇|街道|街|路|巷|村|社区|小区|花园|大厦|栋|室")
ILLEGAL_FILENAME_RE = re.compile(r"[\\/:*?\"<>|\x00-\x1f]")


def redact_sensitive_text(text: str) -> tuple[str, list[str]]:
    warnings: list[str] = []
    output: list[str] = []
    for line in text.splitlines():
        address_count = len(ADDRESS_WORD_RE.findall(line))
        if (
            SENSITIVE_LINE_RE.search(line)
            or MASKED_PHONE_RE.search(line)
            or PAYMENT_CARD_RE.search(line)
            or address_count >= 2
            or (address_count >= 1 and "展开" in line)
        ):
            output.append("[已去除敏感字段]")
            warnings.append("已过滤地址、联系方式、支付账号或物流编号字段")
            continue
        redacted = PHONE_RE.sub("[已去除手机号]", line)
        redacted = CARD_RE.sub(r"\1\2[已去除]", redacted)
        if redacted != line:
            warnings.append("已过滤正文中的手机号或支付卡号")
        output.append(redacted)
    unique_warnings = list(dict.fromkeys(warnings))
    return "\n".join(output).strip(), unique_warnings


def yaml_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def safe_filename(value: str, maximum: int = 80) -> str:
    value = unicodedata.normalize("NFKC", value).strip().replace("\n", " ")
    value = ILLEGAL_FILENAME_RE.sub("-", value)
    value = re.sub(r"\s+", " ", value).strip(" .-")
    return (value[:maximum].rstrip() or "未命名转录")


def _money(value: float | None) -> str:
    if value is None:
        return ""
    return f"{value:.2f}".rstrip("0").rstrip(".")


def inbox_markdown(envelope: CaptureEnvelope) -> tuple[str, list[str]]:
    redacted, redaction_warnings = redact_sensitive_text(envelope.raw_text)
    warnings = list(dict.fromkeys([*envelope.warnings, *redaction_warnings]))
    order = envelope.order
    lines = [
        "---",
        "type: order-transcription",
        "status: pending-review",
        f"captureId: {yaml_string(envelope.capture_id)}",
        f"capturedAt: {yaml_string(envelope.captured_at)}",
        f"sourceApp: {yaml_string(envelope.source_app)}",
        f"sourceUrl: {yaml_string(envelope.source_url)}",
        f"kind: {envelope.kind}",
        "containsSensitiveData: false",
        "---",
        "",
        f"# {envelope.title}",
        "",
    ]
    if envelope.kind == "order":
        lines.extend(
            [
                "## 结构化订单",
                "",
                f"- 平台：{order.platform}",
                f"- 商家：{order.merchant}",
                f"- 订单号：`{order.order_number}`" if order.order_number else "- 订单号：待确认",
                f"- 实付：{_money(order.total_paid)} 元" if order.total_paid is not None else "- 实付：待确认",
                f"- 状态：{order.status or '待确认'}",
                f"- 下单时间：{order.ordered_at or '待确认'}",
                "",
                "### 商品",
                "",
            ]
        )
        if order.items:
            for item in order.items:
                details = [item.specification]
                if item.quantity is not None:
                    details.append(f"数量 {item.quantity}")
                if item.line_price is not None:
                    details.append(f"实付 {_money(item.line_price)} 元")
                suffix = "；".join(detail for detail in details if detail)
                lines.append(f"- {item.name}" + (f"（{suffix}）" if suffix else ""))
        else:
            lines.append("- 待从原始转录中确认")
        lines.append("")
    lines.extend(["## 原始转录（已去敏）", "", "```text", redacted, "```", ""])
    if warnings:
        lines.extend(["## 待核对", "", *[f"- {warning}" for warning in warnings], ""])
    lines.append("来源说明：由订单页面转录器在本地读取并转写；未保存页面截图。")
    return "\n".join(lines).rstrip() + "\n", warnings


def entity_markdown(
    *,
    envelope: CaptureEnvelope,
    item: CaptureItem,
    entity_id: str,
    entity_type: str,
    category: str,
    now: datetime,
) -> str:
    title = item.name.strip()
    date = now.date().isoformat()
    timestamp = now.isoformat(timespec="seconds")
    order = envelope.order
    tag = "实体/物品" if entity_type == "item" else "实体/批次"
    lines = [
        "---",
        f"title: {yaml_string(title)}",
        f"entityType: {entity_type}",
        "schemaVersion: 1",
        f"entityId: {entity_id}",
        f"category: {yaml_string(category)}",
        "owner: 我",
        "status: active",
    ]
    if entity_type == "item-batch":
        lines.extend(
            [
                "quantity: null",
                "unit: 件",
                "countMode: quantity",
                'quantityConfidence: ""',
                "quantityStatus: unverified",
                'lastObservedQuantity: ""',
                'expiresOn: ""',
            ]
        )
    lines.extend(
        [
            'homePlace: ""',
            'currentPlace: ""',
            'container: ""',
            'tagId: ""',
            "tagStatus: unbound",
            'nfcUidHash: ""',
            "qrStatus: missing",
            "contactVersion: 1",
            f"lastChecked: {date}",
            'detailRef: ""' if entity_type == "item" else None,
            f"purchaseRef: {yaml_string('[[#购买信息]]')}",
        ]
    )
    if order.platform:
        lines.append(f"purchasePlatform: {yaml_string(order.platform)}")
    if order.merchant:
        lines.append(f"purchaseMerchant: {yaml_string(order.merchant)}")
    if item.line_price is not None:
        lines.append(f"purchasePrice: {_money(item.line_price)}")
    if order.order_number:
        lines.append(f"purchaseOrder: {yaml_string(order.order_number)}")
    if item.quantity is not None:
        lines.append(f"purchasedQuantity: {item.quantity}")
    status_map = {
        "待付款": "ordered", "待发货": "ordered", "已付款": "ordered",
        "运输中": "in-transit", "待收货": "in-transit", "已发货": "in-transit",
        "已签收": "received", "交易完成": "received", "已完成": "received",
        "退款成功": "returned", "已退货": "returned", "交易关闭": "cancelled", "已取消": "cancelled",
    }
    procurement = next((value for key, value in status_map.items() if key in order.status), "")
    if procurement:
        lines.append(f"procurementStatus: {procurement}")
    lines.extend(
        [
            f"created: {timestamp}",
            f"updated: {timestamp}",
            "tags:",
            f"  - {tag}",
            "---",
            "",
            f"# {title}",
            "",
            "## 识别信息" if entity_type == "item" else "## 批次范围",
            "",
            f"- 型号/规格：{item.specification or '待确认'}",
            "" if entity_type == "item" else "- 现场数量：尚未核实；订单数量不作为盘点数量。",
            "## 购买信息",
            "",
            f"- 平台：{order.platform or '待确认'}",
            f"- 商家：{order.merchant or '待确认'}",
            f"- 订单号：`{order.order_number}`" if order.order_number else "- 订单号：待确认",
            f"- 订单行实付：{_money(item.line_price)} 元" if item.line_price is not None else "- 订单行实付：待确认",
            f"- 购买数量：{item.quantity}" if item.quantity is not None else "- 购买数量：待确认",
            f"- 订单状态：{order.status or '待确认'}",
            f"- 下单时间：{order.ordered_at or '待确认'}",
            "- 来源：由订单页面转录器在本地转写；未保存订单截图。",
            "",
            "## 备注",
            "",
            f"- 转录标识：`{envelope.capture_id}`",
        ]
    )
    return "\n".join(line for line in lines if line is not None).rstrip() + "\n"
