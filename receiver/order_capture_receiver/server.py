from __future__ import annotations

import json
import signal
import ssl
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlsplit

from . import __version__
from .config import ReceiverConfig
from .models import CaptureEnvelope, ValidationError
from .storage import VaultStorage


MAX_BODY_BYTES = 1_000_000


class ReceiverHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], handler: type[BaseHTTPRequestHandler], storage: VaultStorage, token: str):
        super().__init__(address, handler)
        self.storage = storage
        self.token = token


class ReceiverHandler(BaseHTTPRequestHandler):
    server_version = "OrderCaptureReceiver/" + __version__

    @property
    def receiver(self) -> ReceiverHTTPServer:
        return self.server  # type: ignore[return-value]

    def log_message(self, fmt: str, *args: object) -> None:
        # Never log bodies or authorization headers.
        print(f"{self.address_string()} - {fmt % args}")

    def _route(self) -> str:
        path = urlsplit(self.path).path.rstrip("/") or "/"
        prefix = "/order-capture"
        if path == prefix:
            return "/"
        if path.startswith(prefix + "/"):
            return path[len(prefix):]
        return path

    def _json(self, status: int, value: dict[str, Any]) -> None:
        payload = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(payload)

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        return header == f"Bearer {self.receiver.token}"

    def _body(self) -> dict[str, Any]:
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length or "0")
        except ValueError as exc:
            raise ValidationError("Invalid Content-Length") from exc
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValidationError("Request body must contain 1 to 1,000,000 bytes")
        try:
            value = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise ValidationError("Body must be UTF-8 JSON") from exc
        if not isinstance(value, dict):
            raise ValidationError("Body must be a JSON object")
        return value

    def do_GET(self) -> None:  # noqa: N802
        if self._route() == "/v1/health":
            self._json(200, {"status": "ok", "version": __version__})
            return
        self._json(404, {"status": "error", "error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if not self._authorized():
            self._json(401, {"status": "error", "error": "unauthorized"})
            return
        try:
            value = self._body()
            route = self._route()
            if route == "/v1/inbox-captures":
                response = self.receiver.storage.create_inbox_capture(CaptureEnvelope.from_dict(value))
                self._json(200, response)
            elif route == "/v1/entity-drafts":
                response = self.receiver.storage.create_entity_draft(CaptureEnvelope.from_dict(value))
                self._json(200, response)
            elif route == "/v1/entities/commit":
                token = value.get("draftToken")
                selections = value.get("selections")
                if not isinstance(token, str) or not isinstance(selections, list):
                    raise ValidationError("draftToken and selections are required")
                response = self.receiver.storage.commit_entities(token, selections)
                self._json(200, response)
            else:
                self._json(404, {"status": "error", "error": "not_found"})
        except ValidationError as exc:
            self._json(400, {"status": "error", "error": "validation", "message": str(exc)})
        except Exception as exc:  # Keep internal paths and request data out of the response.
            print(f"Internal receiver error: {type(exc).__name__}: {exc}")
            self._json(500, {"status": "error", "error": "internal"})


def _server(config: ReceiverConfig, storage: VaultStorage, *, local: bool) -> ReceiverHTTPServer:
    address = ("0.0.0.0", config.local_port) if local else ("127.0.0.1", config.loopback_port)
    server = ReceiverHTTPServer(address, ReceiverHandler, storage, config.token)
    if local:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(config.cert_path), str(config.key_path))
        server.socket = context.wrap_socket(server.socket, server_side=True)
    return server


def serve(config: ReceiverConfig) -> None:
    storage = VaultStorage(config)
    local_server = _server(config, storage, local=True)
    loopback_server = _server(config, storage, local=False)
    mdns: subprocess.Popen[bytes] | None = None
    try:
        mdns = subprocess.Popen(
            ["/usr/bin/dns-sd", "-R", "Order Capture Receiver", "_ordercapture._tcp", "local", str(config.local_port)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError:
        print("mDNS registration unavailable; paired local URL remains usable")

    threads = [
        threading.Thread(target=local_server.serve_forever, name="local-https", daemon=True),
        threading.Thread(target=loopback_server.serve_forever, name="tailscale-loopback", daemon=True),
    ]
    for thread in threads:
        thread.start()
    print(f"Local HTTPS: https://{config.local_host}:{config.local_port}")
    print(f"Tailnet backend: http://127.0.0.1:{config.loopback_port}")

    stopped = threading.Event()

    def stop(_signum: int, _frame: object) -> None:
        stopped.set()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    stopped.wait()
    local_server.shutdown()
    loopback_server.shutdown()
    if mdns is not None:
        mdns.terminate()
        try:
            mdns.wait(timeout=3)
        except subprocess.TimeoutExpired:
            mdns.kill()
