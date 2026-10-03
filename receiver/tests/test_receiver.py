from __future__ import annotations

import json
import re
import tempfile
import threading
import unittest
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from order_capture_receiver.config import ReceiverConfig
from order_capture_receiver.markdown import redact_sensitive_text
from order_capture_receiver.models import CaptureEnvelope, ValidationError
from order_capture_receiver.server import ReceiverHTTPServer, ReceiverHandler
from order_capture_receiver.storage import VaultStorage


def capture(*, capture_id: str = "cap_01testcapture0001", item_name: str = "测试充电器", quantity: int | None = 2) -> CaptureEnvelope:
    return CaptureEnvelope.from_dict(
        {
            "schemaVersion": 1,
            "captureId": capture_id,
            "capturedAt": datetime.now().astimezone().isoformat(),
            "sourceApp": "com.example.shop",
            "sourceUrl": "https://example.invalid/order",
            "title": "测试订单",
            "kind": "order",
            "rawText": "订单号：ABC123\n测试充电器\n收货地址：不应保留\n手机号 13800138000",
            "order": {
                "platform": "测试平台",
                "merchant": "测试商店",
                "orderNumber": "ABC123",
                "totalPaid": 99.8,
                "status": "待收货",
                "orderedAt": "2026-08-31 12:30",
                "items": [
                    {
                        "name": item_name,
                        "specification": "100W 白色",
                        "quantity": quantity,
                        "linePrice": 99.8,
                    }
                ],
            },
            "warnings": [],
        }
    )


class ReceiverTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.vault = root / "vault"
        self.runtime = root / "runtime"
        for path in [
            self.vault / "50_实体/_系统/模板",
            self.vault / "50_实体/物品/电子设备/充电器",
            self.vault / "50_实体/批次/清洁用品",
            self.vault / "00_Inbox",
        ]:
            path.mkdir(parents=True, exist_ok=True)
        (self.vault / "50_实体/_系统/字段规范.md").write_text("# schema\n", encoding="utf-8")
        (self.vault / "50_实体/_系统/模板/单件物品模板.md").write_text("# item\n", encoding="utf-8")
        (self.vault / "50_实体/_系统/模板/物品批次模板.md").write_text("# batch\n", encoding="utf-8")
        self.storage = VaultStorage(
            ReceiverConfig(vault_path=self.vault, runtime_path=self.runtime, token="x" * 40)
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_redaction_removes_sensitive_lines_and_phone(self) -> None:
        text, warnings = redact_sensitive_text(
            "订单号：ABC123\n收货地址：深圳\n联系 13800138000\n"
            "测试人 138****0000 示例市测试区\n示例市测试区示例路某小区\n"
            "测试用户 138+***0000 号码保护中\n"
            "测试用户 138**\"0000 号码保护中\n"
            "中国银行储蓄卡(2165)支付¥7.90\n快递单号：\nYT8899256802680\n"
            "券号1242 9941 3621 250 ·复制"
            "\n投诉电话0755-27035359、0755-36553673"
            "\n【代收点】您的快件已投递,收件人凭取件码在"
            "\n示例市测试区示例小区2期6栋东门店"
            "\n单元213"
            "\n微信交易号\n1\u200b1\u200b1\u200b2\u200b0\u200b6\u200b0\u200b0\u200b0\u200b2\u200b6\u200b0\u200b9\u200b0\u200b7\u200b6\u200b0\u200b2\u200b6\u200b7\u200b1\u200b0\u200b9\u200b3\u200b9\u200b6\u200b2\u200b4\u200b8"
        )
        self.assertIn("订单号：ABC123", text)
        self.assertNotIn("深圳", text)
        self.assertNotIn("13800138000", text)
        self.assertNotIn("138", text)
        self.assertNotIn("0000", text)
        self.assertNotIn("2165", text)
        self.assertNotIn("YT8899256802680", text)
        self.assertNotIn("1242 9941 3621 250", text)
        self.assertNotIn("99000000000000000000000000001", text)
        self.assertNotIn("0755-", text)
        self.assertNotIn("示例小区", text)
        self.assertNotIn("单元213", text)
        self.assertTrue(warnings)

    def test_inbox_redacts_sensitive_structured_title_and_item_fields(self) -> None:
        payload = capture(capture_id="cap_01testcapture0009").to_dict()
        payload["title"] = "0755-27035359 投诉电话"
        payload["order"]["merchant"] = "测试商店 0755-36553673"
        payload["order"]["items"][0]["name"] = "【代收点】您的快件已投递,收件人凭取件码在"
        payload["order"]["items"][0]["specification"] = "示例市测试区示例小区2期6栋东门店"
        result = self.storage.create_inbox_capture(CaptureEnvelope.from_dict(payload))
        content = (self.vault / result["path"]).read_text(encoding="utf-8")
        self.assertNotIn("0755-", result["path"])
        self.assertNotIn("0755-", content)
        self.assertNotIn("示例小区", content)
        self.assertNotIn("您的快件", content)

    def test_inbox_is_idempotent_and_contains_no_sensitive_text(self) -> None:
        first = self.storage.create_inbox_capture(capture())
        second = self.storage.create_inbox_capture(capture())
        self.assertEqual(first["path"], second["path"])
        self.assertEqual(second["status"], "existing")
        content = (self.vault / first["path"]).read_text(encoding="utf-8")
        self.assertIn("captureId: \"cap_01testcapture0001\"", content)
        self.assertNotIn("不应保留", content)
        self.assertNotIn("13800138000", content)

    def test_inbox_can_keep_shipping_address_but_still_removes_phone(self) -> None:
        payload = capture(capture_id="cap_01testcapture0011").to_dict()
        payload["keepAddress"] = True
        payload["rawText"] = "订单号：ABC123\n收货地址：深圳市龙华区人民路 13800138000\n测试充电器"
        payload["order"]["shippingAddress"] = "深圳市龙华区人民路 13800138000"

        result = self.storage.create_inbox_capture(CaptureEnvelope.from_dict(payload))
        content = (self.vault / result["path"]).read_text(encoding="utf-8")

        self.assertIn("收货地址：深圳市龙华区人民路 [已去除手机号]", content)
        self.assertIn("containsSensitiveData: true", content)
        self.assertNotIn("13800138000", content)

    def test_draft_finds_duplicate_by_order_number(self) -> None:
        existing = self.vault / "50_实体/物品/电子设备/充电器/已有充电器.md"
        existing.write_text(
            "---\ntitle: 已有充电器\nentityId: ent_01abcdefghijklmnopqrstuvwx\n---\n订单号 ABC123\n",
            encoding="utf-8",
        )
        draft = self.storage.create_entity_draft(capture())
        candidates = draft["items"][0]["duplicateCandidates"]
        self.assertEqual(len(candidates), 1)
        self.assertIn("订单号相同", candidates[0]["reasons"])
        self.assertIn("电子设备/充电器", draft["categories"]["item"])

    def test_commit_creates_schema_compliant_item_and_is_idempotent(self) -> None:
        draft = self.storage.create_entity_draft(capture())
        selection = [{"index": 0, "entityType": "item", "category": "电子设备/充电器", "action": "create"}]
        first = self.storage.commit_entities(draft["draftToken"], selection)
        second = self.storage.commit_entities(draft["draftToken"], selection)
        self.assertEqual(second["status"], "existing")
        entity = first["entities"][0]
        content = (self.vault / entity["path"]).read_text(encoding="utf-8")
        self.assertRegex(content, r"(?m)^entityId: ent_[0-9a-z]{26}$")
        self.assertIn("purchasedQuantity: 2", content)
        self.assertNotIn("quantity: 2", content)
        self.assertIn("procurementStatus: in-transit", content)

    def test_batch_uses_null_unknown_quantity(self) -> None:
        draft = self.storage.create_entity_draft(capture(capture_id="cap_01testcapture0002", item_name="清洁湿巾", quantity=None))
        result = self.storage.commit_entities(
            draft["draftToken"],
            [{"index": 0, "entityType": "item-batch", "category": "清洁用品", "action": "create"}],
        )
        content = (self.vault / result["entities"][0]["path"]).read_text(encoding="utf-8")
        self.assertIn("quantity: null", content)
        self.assertIn("quantityStatus: unverified", content)
        self.assertNotIn("purchasedQuantity:", content)

    def test_rejects_invalid_capture_and_unknown_category(self) -> None:
        data = capture().to_dict()
        data["captureId"] = "bad"
        with self.assertRaises(ValidationError):
            CaptureEnvelope.from_dict(data)
        draft = self.storage.create_entity_draft(capture(capture_id="cap_01testcapture0003"))
        with self.assertRaises(ValidationError):
            self.storage.commit_entities(
                draft["draftToken"],
                [{"index": 0, "entityType": "item", "category": "新建目录", "action": "create"}],
            )

    def test_update_preserves_confirmed_fields_and_fills_missing_purchase_fields(self) -> None:
        existing = self.vault / "50_实体/物品/电子设备/充电器/已有充电器.md"
        existing.write_text(
            "---\ntitle: 已有充电器\nentityType: item\n"
            "entityId: ent_01abcdefghijklmnopqrstuvwx\n"
            "purchasePrice: 88\npurchaseRef: \"\"\nupdated: \"\"\n---\n# 已有充电器\n",
            encoding="utf-8",
        )
        draft = self.storage.create_entity_draft(capture(capture_id="cap_01testcapture0004"))
        result = self.storage.commit_entities(
            draft["draftToken"],
            [{
                "index": 0,
                "entityType": "item",
                "category": "电子设备/充电器",
                "action": "update",
                "candidatePath": "50_实体/物品/电子设备/充电器/已有充电器.md",
            }],
        )
        content = (self.vault / result["entities"][0]["path"]).read_text(encoding="utf-8")
        self.assertIn("purchasePrice: 88", content)
        self.assertNotIn("purchasePrice: 99.8", content)
        self.assertIn('purchaseRef: "[[#购买信息]]"', content)
        self.assertIn('purchaseOrder: "ABC123"', content)
        self.assertIn("purchasedQuantity: 2", content)
        self.assertIn("order-capture:cap_01testcapture0004", content)

    def test_http_api_auth_prefix_and_idempotency(self) -> None:
        server = ReceiverHTTPServer(("127.0.0.1", 0), ReceiverHandler, self.storage, "x" * 40)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_port}/order-capture"
            health = json.loads(urlopen(base + "/v1/health", timeout=2).read())
            self.assertEqual(health["status"], "ok")
            payload = json.dumps(capture(capture_id="cap_01testcapture0005").to_dict()).encode()
            unauthorized = Request(base + "/v1/inbox-captures", data=payload, headers={"Content-Type": "application/json"})
            with self.assertRaises(HTTPError) as error:
                urlopen(unauthorized, timeout=2)
            self.assertEqual(error.exception.code, 401)
            request = Request(
                base + "/v1/inbox-captures",
                data=payload,
                headers={"Content-Type": "application/json", "Authorization": "Bearer " + "x" * 40},
            )
            first = json.loads(urlopen(request, timeout=2).read())
            second = json.loads(urlopen(request, timeout=2).read())
            self.assertEqual(first["path"], second["path"])
            self.assertEqual(second["status"], "existing")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_schema_v2_preserves_independent_amounts_issues_and_pages(self) -> None:
        payload = capture(capture_id="cap_01testcapturev2001").to_dict()
        payload["schemaVersion"] = 2
        payload["order"].update({
            "amounts": {
                "productTotal": 120.0,
                "shippingFee": 0.0,
                "storeDiscount": 10.0,
                "platformDiscount": 20.0,
                "coinDiscount": 0.2,
                "paymentDiscount": None,
                "payable": None,
                "actualPaid": 89.8,
                "payAfterReceipt": None,
                "deposit": None,
            },
            "refundState": "none",
            "actualSpend": 89.8,
        })
        payload["order"]["items"][0].update({
            "amountType": "order_fallback", "refundState": "none", "sourcePage": 1,
            "evidence": "测试充电器", "confidence": "medium",
        })
        payload["issues"] = [{
            "severity": "INFO", "code": "ITEM_AMOUNT_FROM_ORDER_TOTAL",
            "message": "单商品行金额沿用订单付款金额", "field": "order.items.linePrice", "source": "derived",
        }]
        payload["pages"] = [{"pageIndex": 1, "captureId": payload["captureId"], "capturedAt": payload["capturedAt"]}]
        payload["recognitionProfile"] = "douyin_mall"

        envelope = CaptureEnvelope.from_dict(payload)
        self.assertEqual(envelope.schema_version, 2)
        self.assertEqual(envelope.order.amounts.actual_paid, 89.8)
        self.assertEqual(envelope.order.items[0].amount_type, "order_fallback")
        self.assertEqual(envelope.recognition_profile, "douyin_mall")
        self.assertEqual(envelope.to_dict()["recognitionProfile"], "douyin_mall")
        result = self.storage.create_inbox_capture(envelope)
        content = (self.vault / result["path"]).read_text(encoding="utf-8")
        self.assertIn("页面实付款：89.8 元", content)
        self.assertIn("实际消费：89.8 元", content)
        self.assertIn("识别规则：douyin_mall", content)
        self.assertIn("`ITEM_AMOUNT_FROM_ORDER_TOTAL`", content)

    def test_zero_paid_amount_is_not_treated_as_missing(self) -> None:
        payload = capture(capture_id="cap_01testcapturezero01").to_dict()
        payload["order"]["totalPaid"] = 0
        payload["order"].pop("amounts", None)
        envelope = CaptureEnvelope.from_dict(payload)
        self.assertEqual(envelope.order.total_paid, 0.0)
        self.assertEqual(envelope.order.amounts.actual_paid, 0.0)


if __name__ == "__main__":
    unittest.main()
