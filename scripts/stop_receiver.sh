#!/bin/zsh
set -euo pipefail

project_root=${0:A:h:h}
pid_file="$project_root/runtime/receiver.pid"
if [[ ! -f "$pid_file" ]]; then
  /bin/echo "Receiver is not running"
  exit 0
fi

receiver_pid=$(/bin/cat "$pid_file")
command_line=$(/bin/ps -p "$receiver_pid" -o command= 2>/dev/null || true)
if [[ "$command_line" != *"order_capture_receiver"* ]]; then
  /bin/echo "PID file is stale; refusing to stop unrelated process $receiver_pid" >&2
  exit 1
fi

/bin/kill "$receiver_pid"
for _ in {1..20}; do
  /bin/kill -0 "$receiver_pid" 2>/dev/null || break
  /bin/sleep 0.1
done
/bin/rm -f "$pid_file"
/bin/echo "Receiver stopped"
