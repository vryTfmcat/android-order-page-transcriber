#!/bin/zsh
set -euo pipefail

project_root=${0:A:h:h}
runtime_dir="$project_root/runtime"
tailscale_bin=${TAILSCALE_BIN:-/usr/local/bin/tailscale}
before="$runtime_dir/tailscale-serve-before.json"
after="$runtime_dir/tailscale-serve-after.json"

if [[ ! -x "$tailscale_bin" ]]; then
  /bin/echo "Tailscale CLI not found: $tailscale_bin" >&2
  exit 1
fi
/bin/mkdir -p "$runtime_dir"
"$tailscale_bin" serve status --json > "$before"
/bin/chmod 600 "$before"

before_root=$(/usr/bin/python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(next((h.get("Proxy","") for w in d.get("Web",{}).values() for p,h in w.get("Handlers",{}).items() if p=="/"),""))' "$before")

"$tailscale_bin" serve --bg --https=443 --set-path=/order-capture http://127.0.0.1:43118
"$tailscale_bin" serve status --json > "$after"
/bin/chmod 600 "$after"

after_root=$(/usr/bin/python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(next((h.get("Proxy","") for w in d.get("Web",{}).values() for p,h in w.get("Handlers",{}).items() if p=="/"),""))' "$after")
order_proxy=$(/usr/bin/python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(next((h.get("Proxy","") for w in d.get("Web",{}).values() for p,h in w.get("Handlers",{}).items() if p=="/order-capture"),""))' "$after")

if [[ "$before_root" != "$after_root" || "$order_proxy" != "http://127.0.0.1:43118" ]]; then
  "$tailscale_bin" serve set-config "$before"
  /bin/echo "Verification failed; previous Serve configuration was restored" >&2
  exit 1
fi

/bin/echo "Added /order-capture without changing root proxy: ${after_root:-<none>}"
