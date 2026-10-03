from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any


CAPTURE_ID_RE = re.compile(r"^cap_[0-9a-z_-]{10,80}$")
ALLOWED_KINDS = {"order", "generic"}
ALLOWED_SEVERITIES = {"ERROR", "WARN", "INFO"}


class ValidationError(ValueError):
    pass


def _text(value: Any, *, maximum: int = 50_000) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        raise ValidationError("Expected text value")
    value = value.strip()
    if len(value) > maximum:
        raise ValidationError(f"Text exceeds {maximum} characters")
    return value


def _optional_number(value: Any) -> float | None:
    if value in (None, ""):
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValidationError("Expected numeric value")
    if value < -100_000_000 or value > 100_000_000:
        raise ValidationError("Numeric value outside allowed range")
    return float(value)


def _optional_int(value: Any) -> int | None:
    number = _optional_number(value)
    if number is None:
        return None
    if not number.is_integer():
        raise ValidationError("Expected whole number")
    return int(number)


@dataclass(frozen=True)
class CaptureIssue:
    severity: str
    code: str
    message: str
    field: str = ""
    source: str = ""

    @classmethod
    def from_dict(cls, value: Any) -> "CaptureIssue":
        if not isinstance(value, dict):
            raise ValidationError("Each issue must be an object")
        severity = _text(value.get("severity"), maximum=10).upper() or "WARN"
        if severity not in ALLOWED_SEVERITIES:
            raise ValidationError("issue severity must be ERROR, WARN, or INFO")
        code = _text(value.get("code"), maximum=100)
        message = _text(value.get("message"), maximum=500)
        if not code or not message:
            raise ValidationError("issue code and message are required")
        return cls(severity, code, message, _text(value.get("field"), maximum=200), _text(value.get("source"), maximum=100))

    def to_dict(self) -> dict[str, Any]:
        return {"severity": self.severity, "code": self.code, "message": self.message, "field": self.field, "source": self.source}


@dataclass(frozen=True)
class OrderAmounts:
    product_total: float | None = None
    shipping_fee: float | None = None
    store_discount: float | None = None
    platform_discount: float | None = None
    coin_discount: float | None = None
    payment_discount: float | None = None
    payable: float | None = None
    actual_paid: float | None = None
    pay_after_receipt: float | None = None
    deposit: float | None = None

    @classmethod
    def from_dict(cls, value: Any, *, legacy_total: float | None = None) -> "OrderAmounts":
        if value in (None, {}):
            return cls(actual_paid=legacy_total)
        if not isinstance(value, dict):
            raise ValidationError("amounts must be an object")
        return cls(*(_optional_number(value.get(key)) for key in (
            "productTotal", "shippingFee", "storeDiscount", "platformDiscount", "coinDiscount",
            "paymentDiscount", "payable", "actualPaid", "payAfterReceipt", "deposit",
        )))

    def to_dict(self) -> dict[str, Any]:
        return {
            "productTotal": self.product_total, "shippingFee": self.shipping_fee,
            "storeDiscount": self.store_discount, "platformDiscount": self.platform_discount,
            "coinDiscount": self.coin_discount, "paymentDiscount": self.payment_discount,
            "payable": self.payable, "actualPaid": self.actual_paid,
            "payAfterReceipt": self.pay_after_receipt, "deposit": self.deposit,
        }


@dataclass(frozen=True)
class CaptureItem:
    name: str
    specification: str = ""
    quantity: int | None = None
    line_price: float | None = None
    amount_type: str = "unknown"
    refund_state: str = "none"
    source_page: int | None = None
    evidence: str = ""
    confidence: str = "medium"

    @classmethod
    def from_dict(cls, value: Any) -> "CaptureItem":
        if not isinstance(value, dict):
            raise ValidationError("Each item must be an object")
        name = _text(value.get("name"), maximum=500)
        if not name:
            raise ValidationError("Item name is required")
        return cls(
            name, _text(value.get("specification"), maximum=500), _optional_int(value.get("quantity")),
            _optional_number(value.get("linePrice")), _text(value.get("amountType"), maximum=50) or "unknown",
            _text(value.get("refundState"), maximum=50) or "none", _optional_int(value.get("sourcePage")),
            _text(value.get("evidence"), maximum=1_000), _text(value.get("confidence"), maximum=20) or "medium",
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name, "specification": self.specification, "quantity": self.quantity,
            "linePrice": self.line_price, "amountType": self.amount_type, "refundState": self.refund_state,
            "sourcePage": self.source_page, "evidence": self.evidence, "confidence": self.confidence,
        }


@dataclass(frozen=True)
class CapturePage:
    page_index: int
    capture_id: str
    captured_at: str

    @classmethod
    def from_dict(cls, value: Any) -> "CapturePage":
        if not isinstance(value, dict):
            raise ValidationError("Each page must be an object")
        return cls(_optional_int(value.get("pageIndex")) or 1, _text(value.get("captureId"), maximum=100), _text(value.get("capturedAt"), maximum=100))

    def to_dict(self) -> dict[str, Any]:
        return {"pageIndex": self.page_index, "captureId": self.capture_id, "capturedAt": self.captured_at}


@dataclass(frozen=True)
class OrderData:
    platform: str = ""
    merchant: str = ""
    order_number: str = ""
    total_paid: float | None = None
    status: str = ""
    ordered_at: str = ""
    cancelled_at: str = ""
    shipping_address: str = ""
    amounts: OrderAmounts = OrderAmounts()
    refund_state: str = "none"
    actual_spend: float | None = None
    items: tuple[CaptureItem, ...] = ()

    @classmethod
    def from_dict(cls, value: Any) -> "OrderData":
        if value in (None, {}):
            return cls()
        if not isinstance(value, dict):
            raise ValidationError("order must be an object")
        raw_items = value.get("items", [])
        if not isinstance(raw_items, list) or len(raw_items) > 100:
            raise ValidationError("items must be an array with at most 100 entries")
        legacy_total = _optional_number(value.get("totalPaid"))
        amounts = OrderAmounts.from_dict(value.get("amounts"), legacy_total=legacy_total)
        return cls(
            _text(value.get("platform"), maximum=100), _text(value.get("merchant"), maximum=300),
            _text(value.get("orderNumber"), maximum=200), (
                legacy_total if legacy_total is not None else
                amounts.actual_paid if amounts.actual_paid is not None else
                amounts.pay_after_receipt
            ),
            _text(value.get("status"), maximum=100), _text(value.get("orderedAt"), maximum=100),
            _text(value.get("cancelledAt"), maximum=100), _text(value.get("shippingAddress"), maximum=500),
            amounts, _text(value.get("refundState"), maximum=50) or "none", _optional_number(value.get("actualSpend")),
            tuple(CaptureItem.from_dict(item) for item in raw_items),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "platform": self.platform, "merchant": self.merchant, "orderNumber": self.order_number,
            "totalPaid": self.total_paid, "status": self.status, "orderedAt": self.ordered_at,
            "cancelledAt": self.cancelled_at, "shippingAddress": self.shipping_address,
            "amounts": self.amounts.to_dict(), "refundState": self.refund_state,
            "actualSpend": self.actual_spend, "items": [item.to_dict() for item in self.items],
        }


@dataclass(frozen=True)
class CaptureEnvelope:
    schema_version: int
    capture_id: str
    captured_at: str
    source_app: str
    source_url: str
    title: str
    kind: str
    raw_text: str
    keep_address: bool
    order: OrderData
    warnings: tuple[str, ...]
    issues: tuple[CaptureIssue, ...] = ()
    pages: tuple[CapturePage, ...] = ()
    recognition_profile: str = ""

    @classmethod
    def from_dict(cls, value: Any) -> "CaptureEnvelope":
        if not isinstance(value, dict):
            raise ValidationError("Request body must be an object")
        version = value.get("schemaVersion")
        if version not in (1, 2):
            raise ValidationError("Only CaptureEnvelope schemaVersion 1 and 2 are supported")
        capture_id = _text(value.get("captureId"), maximum=100)
        if not CAPTURE_ID_RE.fullmatch(capture_id):
            raise ValidationError("Invalid captureId")
        captured_at = _text(value.get("capturedAt"), maximum=100)
        try:
            datetime.fromisoformat(captured_at.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValidationError("capturedAt must be ISO-8601") from exc
        kind = _text(value.get("kind"), maximum=20)
        if kind not in ALLOWED_KINDS:
            raise ValidationError("kind must be order or generic")
        raw_text = _text(value.get("rawText"))
        if not raw_text:
            raise ValidationError("rawText is required")
        keep_address = value.get("keepAddress", False)
        if not isinstance(keep_address, bool):
            raise ValidationError("keepAddress must be a boolean")
        raw_warnings = value.get("warnings", [])
        raw_issues = value.get("issues", [])
        raw_pages = value.get("pages", [])
        if not isinstance(raw_warnings, list) or len(raw_warnings) > 100:
            raise ValidationError("warnings must be a short array")
        if not isinstance(raw_issues, list) or len(raw_issues) > 200:
            raise ValidationError("issues must be a short array")
        if not isinstance(raw_pages, list) or len(raw_pages) > 50:
            raise ValidationError("pages must be a short array")
        return cls(
            version, capture_id, captured_at, _text(value.get("sourceApp"), maximum=300),
            _text(value.get("sourceUrl"), maximum=2_000), _text(value.get("title"), maximum=500) or "未命名页面转录",
            kind, raw_text, keep_address, OrderData.from_dict(value.get("order")),
            tuple(_text(item, maximum=500) for item in raw_warnings),
            tuple(CaptureIssue.from_dict(item) for item in raw_issues),
            tuple(CapturePage.from_dict(item) for item in raw_pages),
            _text(value.get("recognitionProfile"), maximum=100),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version, "captureId": self.capture_id, "capturedAt": self.captured_at,
            "sourceApp": self.source_app, "sourceUrl": self.source_url, "title": self.title, "kind": self.kind,
            "rawText": self.raw_text, "keepAddress": self.keep_address, "order": self.order.to_dict(),
            "warnings": list(self.warnings), "issues": [issue.to_dict() for issue in self.issues],
            "pages": [page.to_dict() for page in self.pages],
            "recognitionProfile": self.recognition_profile,
        }
