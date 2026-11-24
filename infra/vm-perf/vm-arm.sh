#!/usr/bin/env bash
# The dead-man timer: after <hours> the VM restores its live layout by itself, in case the workstation runner died, slept
# or lost the network before its own restore. Arming again replaces the earlier timer; vm-restore.sh stops it.
#
#   bash ~/perf-test/vm-arm.sh <hours>
source "$(dirname "$0")/common.sh"
hours=${1:-}
[[ $hours =~ ^[1-9][0-9]?$ ]] && (( hours <= 24 )) || die "usage: vm-arm.sh <hours, 1 to 24>"
# Each arming has its own ID in deadman.id. The timer restores only if its ID is still there when it wakes, so a
# timer from an earlier run, or one whose run already restored (vm-restore.sh removes the file), does nothing.
idfile=$PERF_HOME/deadman.id
mkdir -p "$OUT"
id="$(date +%s)-$$-$RANDOM"
echo "$id" > "$idfile"
detached nohup bash -c "sleep $(( hours * 3600 )); [ \"\$(cat '$idfile' 2> /dev/null)\" = '$id' ] && bash '$(dirname "$0")/vm-restore.sh' deadman"   >> "$OUT/deadman.log" 2>&1 < /dev/null &
log "dead-man timer $id armed: the live layout returns by itself at $(date -u -d "+$hours hours" +%Y-%m-%dT%H:%M:%SZ) unless vm-restore.sh runs first"
