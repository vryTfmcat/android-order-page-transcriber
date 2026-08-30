#!/bin/zsh
set -euo pipefail

project_root=${0:A:h:h}
tailnet_url=${1:-}
local_name=$(/usr/sbin/scutil --get LocalHostName 2>/dev/null || /bin/hostname -s)
local_host="${local_name}.local"

cd "$project_root"
/usr/bin/python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r receiver/requirements.txt

PYTHONPATH=receiver .venv/bin/python -m order_capture_receiver \
  --config "$project_root/runtime/config.json" \
  init \
  --vault "/Users/a13713912476/Documents/Obsidian-codx" \
  --runtime "$project_root/runtime" \
  --local-host "$local_host" \
  --tailscale-url "$tailnet_url"

/bin/chmod 700 "$project_root/runtime"
/bin/chmod 600 "$project_root/runtime/config.json" "$project_root/runtime/pairing-uri.txt"
/bin/echo "Receiver initialized. Pairing QR: $project_root/runtime/pairing-qr.svg"
