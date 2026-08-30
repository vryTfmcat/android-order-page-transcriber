from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from urllib.parse import urlencode

from .config import ReceiverConfig
from .security import certificate_sha256, ensure_certificate, generate_token
from .server import serve


DEFAULT_VAULT = Path("/Users/a13713912476/Documents/Obsidian-codx")


def _pairing_uri(config: ReceiverConfig) -> str:
    fingerprint = certificate_sha256(config.cert_path or config.runtime_path / "receiver.crt")
    query = urlencode(
        {
            "local": f"https://{config.local_host}:{config.local_port}",
            "tailnet": config.tailscale_url,
            "token": config.token,
            "sha256": fingerprint,
        }
    )
    return "ordercapture://pair?" + query


def init(args: argparse.Namespace) -> int:
    config_path = Path(args.config).expanduser().resolve()
    runtime = Path(args.runtime).expanduser().resolve()
    runtime.mkdir(parents=True, exist_ok=True)
    cert, key = ensure_certificate(runtime, args.local_host)
    token = generate_token()
    config = ReceiverConfig(
        vault_path=Path(args.vault).expanduser().resolve(),
        runtime_path=runtime,
        token=token,
        local_host=args.local_host,
        local_port=args.local_port,
        loopback_port=args.loopback_port,
        tailscale_url=args.tailscale_url.rstrip("/"),
        cert_path=cert,
        key_path=key,
    )
    config.validate()
    config.save(config_path)
    uri = _pairing_uri(config)
    pairing_text = runtime / "pairing-uri.txt"
    pairing_text.write_text(uri + "\n", encoding="utf-8")
    os.chmod(pairing_text, 0o600)
    try:
        import segno  # type: ignore

        qr_path = runtime / "pairing-qr.svg"
        segno.make_qr(uri, error="m").save(qr_path, scale=5, border=3)
        os.chmod(qr_path, 0o600)
        print(f"Pairing QR: {qr_path}")
    except ImportError:
        print("Optional package segno is not installed; pairing URI was generated without an SVG QR")
    print(f"Config: {config_path}")
    print(f"Pairing URI: {pairing_text}")
    return 0


def run(args: argparse.Namespace) -> int:
    config = ReceiverConfig.load(Path(args.config).expanduser().resolve())
    config.validate()
    serve(config)
    return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="Order Capture Receiver")
    result.add_argument(
        "--config",
        default=str(Path(__file__).resolve().parents[2] / "runtime" / "config.json"),
        help="Path to private receiver config",
    )
    sub = result.add_subparsers(dest="command", required=True)
    init_parser = sub.add_parser("init", help="Generate private config, TLS certificate, and pairing data")
    init_parser.add_argument("--vault", default=str(DEFAULT_VAULT))
    init_parser.add_argument("--runtime", default=str(Path(__file__).resolve().parents[2] / "runtime"))
    init_parser.add_argument("--local-host", default="mac.local")
    init_parser.add_argument("--local-port", type=int, default=43117)
    init_parser.add_argument("--loopback-port", type=int, default=43118)
    init_parser.add_argument("--tailscale-url", default="")
    init_parser.set_defaults(func=init)
    run_parser = sub.add_parser("serve", help="Start local HTTPS and loopback HTTP listeners")
    run_parser.set_defaults(func=run)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        return int(args.func(args))
    except KeyboardInterrupt:
        return 130
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
