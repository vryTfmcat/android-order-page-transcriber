#!/bin/zsh
set -euo pipefail

project_root=${0:A:h:h}
"$project_root/scripts/start_receiver.sh"
