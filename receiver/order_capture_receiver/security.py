from __future__ import annotations

import hashlib
import secrets
import ssl
import subprocess
from pathlib import Path


def generate_token() -> str:
    return secrets.token_urlsafe(48)


def ensure_certificate(runtime_path: Path, host: str) -> tuple[Path, Path]:
    runtime_path.mkdir(parents=True, exist_ok=True)
    cert = runtime_path / "receiver.crt"
    key = runtime_path / "receiver.key"
    if cert.is_file() and key.is_file():
        return cert, key
    config = runtime_path / "openssl.cnf"
    config.write_text(
        "[req]\n"
        "distinguished_name=dn\n"
        "x509_extensions=ext\n"
        "prompt=no\n"
        "[dn]\nCN=Order Capture Receiver\n"
        "[ext]\nsubjectAltName=@alt\nkeyUsage=digitalSignature,keyEncipherment\n"
        "extendedKeyUsage=serverAuth\n"
        "[alt]\nDNS.1=" + host + "\nDNS.2=localhost\nIP.1=127.0.0.1\n",
        encoding="utf-8",
    )
    subprocess.run(
        [
            "/opt/homebrew/bin/openssl" if Path("/opt/homebrew/bin/openssl").exists() else "openssl",
            "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "825",
            "-keyout", str(key), "-out", str(cert), "-config", str(config),
        ],
        check=True,
        capture_output=True,
    )
    key.chmod(0o600)
    cert.chmod(0o644)
    return cert, key


def certificate_sha256(cert_path: Path) -> str:
    pem = cert_path.read_text(encoding="utf-8")
    der = ssl.PEM_cert_to_DER_cert(pem)
    return hashlib.sha256(der).hexdigest()
