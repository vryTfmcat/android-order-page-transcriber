from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ReceiverConfig:
    vault_path: Path
    runtime_path: Path
    token: str
    local_host: str = "mac.local"
    local_port: int = 43117
    loopback_port: int = 43118
    tailscale_url: str = ""
    cert_path: Path | None = None
    key_path: Path | None = None

    @classmethod
    def load(cls, path: Path) -> "ReceiverConfig":
        data = json.loads(path.read_text(encoding="utf-8"))
        runtime = Path(data["runtimePath"]).expanduser().resolve()
        return cls(
            vault_path=Path(data["vaultPath"]).expanduser().resolve(),
            runtime_path=runtime,
            token=data["token"],
            local_host=data.get("localHost", "mac.local"),
            local_port=int(data.get("localPort", 43117)),
            loopback_port=int(data.get("loopbackPort", 43118)),
            tailscale_url=data.get("tailscaleUrl", ""),
            cert_path=Path(data.get("certPath", runtime / "receiver.crt")),
            key_path=Path(data.get("keyPath", runtime / "receiver.key")),
        )

    def to_dict(self) -> dict[str, object]:
        return {
            "vaultPath": str(self.vault_path),
            "runtimePath": str(self.runtime_path),
            "token": self.token,
            "localHost": self.local_host,
            "localPort": self.local_port,
            "loopbackPort": self.loopback_port,
            "tailscaleUrl": self.tailscale_url,
            "certPath": str(self.cert_path or self.runtime_path / "receiver.crt"),
            "keyPath": str(self.key_path or self.runtime_path / "receiver.key"),
        }

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temp = path.with_suffix(path.suffix + ".tmp")
        temp.write_text(json.dumps(self.to_dict(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.chmod(temp, 0o600)
        os.replace(temp, path)

    def validate(self) -> None:
        if not self.vault_path.is_dir():
            raise ValueError(f"Vault does not exist: {self.vault_path}")
        required = [
            self.vault_path / "50_实体" / "_系统" / "字段规范.md",
            self.vault_path / "50_实体" / "_系统" / "模板" / "单件物品模板.md",
            self.vault_path / "50_实体" / "_系统" / "模板" / "物品批次模板.md",
        ]
        missing = [str(path) for path in required if not path.is_file()]
        if missing:
            raise ValueError("Vault is missing entity schema files: " + ", ".join(missing))
        if len(self.token) < 32:
            raise ValueError("Pairing token must contain at least 32 characters")
