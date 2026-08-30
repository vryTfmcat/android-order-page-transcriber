from __future__ import annotations

import difflib
import json
import os
import re
import secrets
import shutil
import sqlite3
import tempfile
import threading
from contextlib import contextmanager
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Iterator

from .config import ReceiverConfig
from .markdown import entity_markdown, inbox_markdown, safe_filename
from .models import CaptureEnvelope, ValidationError
from .ulid import new_entity_id


ENTITY_ID_RE = re.compile(r"(?m)^entityId:\s*(ent_[0-9a-z]{26})\s*$")
TITLE_RE = re.compile(r"(?m)^title:\s*[\"']?(.*?)[\"']?\s*$")
FRONTMATTER_RE = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)


def _normalize(value: str) -> str:
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", value.casefold())


def _fill_missing_frontmatter(content: str, values: dict[str, str]) -> str:
    """Add only absent/blank purchase fields; never overwrite confirmed facts."""
    match = FRONTMATTER_RE.search(content)
    if not match:
        return content
    body = match.group(1)
    additions: list[str] = []
    for key, value in values.items():
        field = re.compile(rf"(?m)^{re.escape(key)}:\s*(.*)$")
        existing = field.search(body)
        if existing:
            if existing.group(1).strip() in {'', '\"\"', "''", "null"}:
                body = body[:existing.start()] + f"{key}: {value}" + body[existing.end():]
        else:
            additions.append(f"{key}: {value}")
    if additions:
        body = body.rstrip() + "\n" + "\n".join(additions)
    return content[:match.start()] + "---\n" + body + "\n---\n" + content[match.end():]


class VaultStorage:
    def __init__(self, config: ReceiverConfig):
        self.config = config
        self.config.validate()
        self.runtime = config.runtime_path
        self.runtime.mkdir(parents=True, exist_ok=True)
        self.db_path = self.runtime / "receiver.sqlite3"
        self._lock = threading.RLock()
        self._init_db()

    @contextmanager
    def _db(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.db_path)
        connection.row_factory = sqlite3.Row
        try:
            yield connection
            connection.commit()
        finally:
            connection.close()

    def _init_db(self) -> None:
        with self._db() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS captures (
                    capture_id TEXT PRIMARY KEY,
                    path TEXT NOT NULL,
                    response_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS drafts (
                    token TEXT PRIMARY KEY,
                    capture_json TEXT NOT NULL,
                    response_json TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    commit_response_json TEXT
                );
                """
            )

    def _atomic_write(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temp_name = tempfile.mkstemp(prefix=".order-capture-", dir=path.parent)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_name, path)
        except Exception:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass
            raise

    def create_inbox_capture(self, envelope: CaptureEnvelope) -> dict[str, Any]:
        with self._lock, self._db() as db:
            row = db.execute("SELECT response_json FROM captures WHERE capture_id = ?", (envelope.capture_id,)).fetchone()
            if row:
                response = json.loads(row["response_json"])
                response["status"] = "existing"
                return response
            markdown, warnings = inbox_markdown(envelope)
            captured = datetime.fromisoformat(envelope.captured_at.replace("Z", "+00:00")).astimezone()
            folder = self.config.vault_path / "00_Inbox" / "订单转录"
            base = f"{captured:%Y-%m-%d %H%M} - {safe_filename(envelope.title)}"
            path = folder / f"{base}.md"
            if path.exists():
                path = folder / f"{base} - {envelope.capture_id[-8:]}.md"
            self._atomic_write(path, markdown)
            relative = path.relative_to(self.config.vault_path).as_posix()
            response = {
                "status": "created",
                "path": relative,
                "wikilink": f"[[{relative[:-3]}]]",
                "warnings": warnings,
            }
            db.execute(
                "INSERT INTO captures(capture_id,path,response_json,created_at) VALUES(?,?,?,?)",
                (envelope.capture_id, relative, json.dumps(response, ensure_ascii=False), datetime.now().astimezone().isoformat()),
            )
            return response

    def categories(self, entity_type: str) -> list[str]:
        root_name = "物品" if entity_type == "item" else "批次"
        root = self.config.vault_path / "50_实体" / root_name
        categories = []
        for path in root.rglob("*"):
            if not path.is_dir() or path.name.startswith("_"):
                continue
            relative = path.relative_to(root).as_posix()
            if relative:
                categories.append(relative)
        return sorted(categories, key=lambda value: (value.count("/"), value.casefold()))

    def _entity_index(self) -> list[dict[str, str]]:
        root = self.config.vault_path / "50_实体"
        index: list[dict[str, str]] = []
        for path in [root / "物品", root / "批次"]:
            for note in path.rglob("*.md"):
                try:
                    content = note.read_text(encoding="utf-8")
                except (OSError, UnicodeError):
                    continue
                title_match = TITLE_RE.search(content)
                title = title_match.group(1).strip() if title_match else note.stem
                entity_match = ENTITY_ID_RE.search(content)
                index.append(
                    {
                        "path": note.relative_to(self.config.vault_path).as_posix(),
                        "title": title,
                        "normalized": _normalize(title),
                        "entityId": entity_match.group(1) if entity_match else "",
                        "content": content,
                    }
                )
        return index

    def create_entity_draft(self, envelope: CaptureEnvelope) -> dict[str, Any]:
        if envelope.kind != "order" or not envelope.order.items:
            raise ValidationError("Entity drafts require an order with at least one item")
        index = self._entity_index()
        item_results = []
        for position, item in enumerate(envelope.order.items):
            normalized = _normalize(item.name)
            candidates = []
            for existing in index:
                reasons = []
                if envelope.order.order_number and envelope.order.order_number in existing["content"]:
                    reasons.append("订单号相同")
                ratio = difflib.SequenceMatcher(None, normalized, existing["normalized"]).ratio()
                if normalized and (normalized == existing["normalized"] or ratio >= 0.72):
                    reasons.append("商品名称相近")
                if reasons:
                    candidates.append(
                        {
                            "path": existing["path"],
                            "wikilink": f"[[{existing['path'][:-3]}]]",
                            "title": existing["title"],
                            "entityId": existing["entityId"],
                            "reasons": reasons,
                        }
                    )
            item_results.append({"index": position, "item": item.to_dict(), "duplicateCandidates": candidates[:20]})
        token = secrets.token_urlsafe(32)
        expires = datetime.now().astimezone() + timedelta(minutes=30)
        response = {
            "status": "draft",
            "draftToken": token,
            "expiresAt": expires.isoformat(),
            "items": item_results,
            "categories": {"item": self.categories("item"), "item-batch": self.categories("item-batch")},
            "warnings": list(envelope.warnings),
        }
        with self._db() as db:
            db.execute(
                "INSERT INTO drafts(token,capture_json,response_json,expires_at) VALUES(?,?,?,?)",
                (token, json.dumps(envelope.to_dict(), ensure_ascii=False), json.dumps(response, ensure_ascii=False), expires.isoformat()),
            )
        return response

    def _target_path(self, title: str, entity_type: str, category: str) -> Path:
        root_name = "物品" if entity_type == "item" else "批次"
        root = (self.config.vault_path / "50_实体" / root_name).resolve()
        category_root = (root / category).resolve()
        if category_root == root or root not in category_root.parents or not category_root.is_dir():
            raise ValidationError("Category must be an existing entity category")
        path = category_root / f"{safe_filename(title, 100)}.md"
        return path

    def commit_entities(self, draft_token: str, selections: list[dict[str, Any]]) -> dict[str, Any]:
        with self._lock, self._db() as db:
            row = db.execute("SELECT * FROM drafts WHERE token = ?", (draft_token,)).fetchone()
            if not row:
                raise ValidationError("Unknown draftToken")
            if row["commit_response_json"]:
                response = json.loads(row["commit_response_json"])
                response["status"] = "existing"
                return response
            if datetime.fromisoformat(row["expires_at"]) < datetime.now().astimezone():
                raise ValidationError("Draft has expired; request a new duplicate check")
            envelope = CaptureEnvelope.from_dict(json.loads(row["capture_json"]))
            if not isinstance(selections, list) or not selections:
                raise ValidationError("At least one selected item is required")
            staged: list[tuple[Path, str, str, Path | None]] = []
            results: list[dict[str, Any]] = []
            now = datetime.now().astimezone()
            seen_indexes: set[int] = set()
            backup_root = Path(tempfile.mkdtemp(prefix="order-capture-backup-", dir=self.runtime))
            try:
                for selection in selections:
                    if not isinstance(selection, dict):
                        raise ValidationError("Each selection must be an object")
                    index = selection.get("index")
                    if not isinstance(index, int) or index < 0 or index >= len(envelope.order.items) or index in seen_indexes:
                        raise ValidationError("Invalid or duplicate item index")
                    seen_indexes.add(index)
                    entity_type = selection.get("entityType")
                    if entity_type not in {"item", "item-batch"}:
                        raise ValidationError("entityType must be item or item-batch")
                    category = str(selection.get("category", "")).strip()
                    action = selection.get("action", "create")
                    item = envelope.order.items[index]
                    entity_id = new_entity_id()
                    if action == "create":
                        destination = self._target_path(item.name, entity_type, category)
                        if destination.exists():
                            raise ValidationError(f"Target note already exists: {destination.name}")
                        content = entity_markdown(
                            envelope=envelope, item=item, entity_id=entity_id,
                            entity_type=entity_type, category=category, now=now,
                        )
                        staged.append((destination, content, "create", None))
                    elif action == "update":
                        candidate = str(selection.get("candidatePath", ""))
                        destination = (self.config.vault_path / candidate).resolve()
                        entity_root = (self.config.vault_path / "50_实体").resolve()
                        if entity_root not in destination.parents or not destination.is_file():
                            raise ValidationError("candidatePath must be an existing entity note")
                        original = destination.read_text(encoding="utf-8")
                        marker = f"order-capture:{envelope.capture_id}"
                        if marker in original:
                            content = original
                        else:
                            order = envelope.order
                            purchase_fields = {
                                "purchaseRef": json.dumps("[[#购买信息]]", ensure_ascii=False),
                            }
                            if order.platform:
                                purchase_fields["purchasePlatform"] = json.dumps(order.platform, ensure_ascii=False)
                            if order.merchant:
                                purchase_fields["purchaseMerchant"] = json.dumps(order.merchant, ensure_ascii=False)
                            if item.line_price is not None:
                                purchase_fields["purchasePrice"] = f"{item.line_price:.2f}".rstrip("0").rstrip(".")
                            if order.order_number:
                                purchase_fields["purchaseOrder"] = json.dumps(order.order_number, ensure_ascii=False)
                            if item.quantity is not None:
                                purchase_fields["purchasedQuantity"] = str(item.quantity)
                            purchase_fields["updated"] = json.dumps(now.isoformat(timespec="seconds"), ensure_ascii=False)
                            content = _fill_missing_frontmatter(original, purchase_fields)
                            block = [
                                "", f"<!-- {marker}:start -->", "## 购买信息（订单转录）", "",
                                f"- 商品：{item.name}",
                                f"- 规格：{item.specification or '待确认'}",
                                f"- 平台：{order.platform or '待确认'}",
                                f"- 商家：{order.merchant or '待确认'}",
                                f"- 订单号：`{order.order_number}`" if order.order_number else "- 订单号：待确认",
                                f"- 订单行实付：{item.line_price} 元" if item.line_price is not None else "- 订单行实付：待确认",
                                f"- 购买数量：{item.quantity}" if item.quantity is not None else "- 购买数量：待确认",
                                "- 来源：由订单页面转录器在本地转写；未保存订单截图。",
                                f"<!-- {marker}:end -->", "",
                            ]
                            content = content.rstrip() + "\n" + "\n".join(block)
                        backup = backup_root / f"{index}.md"
                        shutil.copy2(destination, backup)
                        staged.append((destination, content, "update", backup))
                        entity_match = ENTITY_ID_RE.search(original)
                        entity_id = entity_match.group(1) if entity_match else ""
                    else:
                        raise ValidationError("action must be create or update")
                    relative = destination.relative_to(self.config.vault_path.resolve()).as_posix()
                    results.append(
                        {
                            "index": index,
                            "action": action,
                            "path": relative,
                            "wikilink": f"[[{relative[:-3]}]]",
                            "entityId": entity_id,
                        }
                    )

                written: list[tuple[Path, str, Path | None]] = []
                try:
                    for destination, content, action, backup in staged:
                        self._atomic_write(destination, content)
                        written.append((destination, action, backup))
                except Exception:
                    for destination, action, backup in reversed(written):
                        if action == "create":
                            destination.unlink(missing_ok=True)
                        elif backup and backup.exists():
                            os.replace(backup, destination)
                    raise
                response = {"status": "committed", "entities": results, "warnings": list(envelope.warnings)}
                db.execute("UPDATE drafts SET commit_response_json = ? WHERE token = ?", (json.dumps(response, ensure_ascii=False), draft_token))
                return response
            finally:
                shutil.rmtree(backup_root, ignore_errors=True)
