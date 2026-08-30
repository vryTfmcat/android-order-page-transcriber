from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any


CAPTURE_ID_RE = re.compile(r"^cap_[0-9a-z_-]{10,80}$")
ALLOWED_KINDS = {"order", "generic"}


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
    if value < 0 or value > 100_000_000:
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
class CaptureItem:
    name: str
    specification: str = ""
    quantity: int | None = None
    line_price: float | None = None

    @classmethod
    def from_dict(cls, value: Any) -> "CaptureItem":
        if not isinstance(value, dict):
            raise ValidationError("Each item must be an object")
        name = _text(value.get("name"), maximum=500)
        if not name:
            raise ValidationError("Item name is required")
        return cls(
            name=name,
            specification=_text(value.get("specification"), maximum=500),
            quantity=_optional_int(value.get("quantity")),
            line_price=_optional_number(value.get("linePrice")),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "specification": self.specification,
            "quantity": self.quantity,
            "linePrice": self.line_price,
        }


@dataclass(frozen=True)
class OrderData:
    platform: str = ""
    merchant: str = ""
    order_number: str = ""
    total_paid: float | None = None
    status: str = ""
    ordered_at: str = ""
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
        return cls(
            platform=_text(value.get("platform"), maximum=100),
            merchant=_text(value.get("merchant"), maximum=300),
            order_number=_text(value.get("orderNumber"), maximum=200),
            total_paid=_optional_number(value.get("totalPaid")),
            status=_text(value.get("status"), maximum=100),
            ordered_at=_text(value.get("orderedAt"), maximum=100),
            items=tuple(CaptureItem.from_dict(item) for item in raw_items),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "platform": self.platform,
            "merchant": self.merchant,
            "orderNumber": self.order_number,
            "totalPaid": self.total_paid,
            "status": self.status,
            "orderedAt": self.ordered_at,
            "items": [item.to_dict() for item in self.items],
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
    order: OrderData
    warnings: tuple[str, ...]

    @classmethod
    def from_dict(cls, value: Any) -> "CaptureEnvelope":
        if not isinstance(value, dict):
            raise ValidationError("Request body must be an object")
        version = value.get("schemaVersion")
        if version != 1:
            raise ValidationError("Only CaptureEnvelope schemaVersion 1 is supported")
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
        raw_warnings = value.get("warnings", [])
        if not isinstance(raw_warnings, list) or len(raw_warnings) > 100:
            raise ValidationError("warnings must be a short array")
        return cls(
            schema_version=1,
            capture_id=capture_id,
            captured_at=captured_at,
            source_app=_text(value.get("sourceApp"), maximum=300),
            source_url=_text(value.get("sourceUrl"), maximum=2_000),
            title=_text(value.get("title"), maximum=500) or "未命名页面转录",
            kind=kind,
            raw_text=raw_text,
            order=OrderData.from_dict(value.get("order")),
            warnings=tuple(_text(item, maximum=500) for item in raw_warnings),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "captureId": self.capture_id,
            "capturedAt": self.captured_at,
            "sourceApp": self.source_app,
            "sourceUrl": self.source_url,
            "title": self.title,
            "kind": self.kind,
            "rawText": self.raw_text,
            "order": self.order.to_dict(),
            "warnings": list(self.warnings),
        }
