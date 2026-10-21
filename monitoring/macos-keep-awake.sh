#!/bin/sh
# Keep the monitoring host awake on AC only; allow the display to sleep normally.
set -eu
while :; do
  if /usr/bin/pmset -g batt | /usr/bin/grep -q "'AC Power'"; then
    /usr/bin/caffeinate -i -s -t 60
  else
    /bin/sleep 30
  fi
done
