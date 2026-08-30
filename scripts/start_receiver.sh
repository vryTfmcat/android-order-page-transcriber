#!/bin/zsh
set -euo pipefail

project_root=${0:A:h:h}
runtime_dir="$project_root/runtime"
pid_file="$runtime_dir/receiver.pid"

if [[ ! -x "$project_root/.venv/bin/python" || ! -f "$runtime_dir/config.json" ]]; then
  /bin/echo "Run scripts/setup_receiver.sh first" >&2
  exit 1
fi

if [[ -f "$pid_file" ]]; then
  old_pid=$(/bin/cat "$pid_file")
  if /bin/kill -0 "$old_pid" 2>/dev/null; then
    /bin/echo "Receiver is already running (PID $old_pid)"
    exit 0
  fi
  /bin/rm -f "$pid_file"
fi

/bin/mkdir -p "$runtime_dir"
cd "$project_root"
/usr/bin/nohup /usr/bin/env PYTHONPATH="$project_root/receiver" \
  "$project_root/.venv/bin/python" -m order_capture_receiver \
  --config "$runtime_dir/config.json" serve \
  >> "$runtime_dir/receiver.log" 2>> "$runtime_dir/receiver-error.log" &
receiver_pid=$!
/bin/echo "$receiver_pid" > "$pid_file"
/bin/chmod 600 "$pid_file"
/bin/sleep 1

if ! /bin/kill -0 "$receiver_pid" 2>/dev/null; then
  /bin/echo "Receiver failed to start; inspect runtime/receiver-error.log" >&2
  exit 1
fi
/bin/echo "Receiver started (PID $receiver_pid)"
