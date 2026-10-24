#!/usr/bin/env bash
# Keep only the newest YourTV apps visible on a television without destroying old data.
# Usage:
#   bash optimize/cleanup-old-versions.sh report   # inventory only
#   bash optimize/cleanup-old-versions.sh archive  # hide old app shortcuts (reversible)
#   DATA_MIGRATED_AND_CHECKED=YES bash optimize/cleanup-old-versions.sh remove
#
# Android cannot migrate private app data between package IDs just by reinstalling.
# Never remove an old package unless the new app actually shows the old information.
# "remove" uses -k to retain any legacy private app data as a recovery precaution.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
mode="${1:-report}"
case "$mode" in report|archive|remove) ;; *) echo "usage: $0 report|archive|remove"; exit 2 ;; esac
tv() { "$PY" -I optimize/tv.py "$1"; }
old=(sa.yourtv.screensaver sa.yourtv.home com.abdullah.tvsaver com.abdullah.tvhome)
saver=sa.urtv.screensaver
home=sa.urtv.home

# Never risk disabling an old HOME/screensaver until the replacements are both present.
check_new() {
  for p in "$saver" "$home"; do
    if ! tv "pm path $p" | grep -q '^package:'; then
      echo "STOP: latest app $p is not installed; keeping all older packages as they are."
      exit 1
    fi
  done
}
installed() { tv "pm path $1" | grep -q '^package:'; }

# Save a device inventory locally before any action. This is NOT a backup of private app data.
mkdir -p optimize/upgrade-audits
log="optimize/upgrade-audits/versions-$(date +%Y%m%d-%H%M%S).txt"
{
  printf 'Mode: %s\n' "$mode"
  printf 'Time: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo '=== installed packages ==='
  tv "pm list packages" || true
  echo '=== current home ==='
  tv "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN" || true
  echo '=== current screensaver ==='
  tv "settings get secure screensaver_components" || true
} > "$log"
echo "Device inventory saved: $log (package list only; no personal data)"

if [ "$mode" = report ]; then
  for p in "$saver" "$home" "${old[@]}"; do
    if installed "$p"; then echo "  installed: $p"; fi
  done
  echo 'Nothing removed or disabled.'
  exit 0
fi

check_new

if [ "$mode" = remove ] && [ "${DATA_MIGRATED_AND_CHECKED:-}" != YES ]; then
  echo 'STOP: legacy packages may contain personal settings stored under another app ID.'
  echo 'First confirm on THIS TV that favorites, city, teams, tasks, reminders and photos appear in the latest app.'
  echo 'If any information is missing, keep the older apps; migrate/export that data first.'
  echo 'After verification, run:'
  echo 'DATA_MIGRATED_AND_CHECKED=YES bash optimize/cleanup-old-versions.sh remove'
  exit 1
fi

current_home="$(tv "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN")"
current_saver="$(tv "settings get secure screensaver_components")"
for p in "${old[@]}"; do
  if ! installed "$p"; then continue; fi
  # Do not break the TV if an old launcher/dream is still in use.
  if [[ "$current_home" == *"$p"* || "$current_saver" == *"$p"* ]]; then
    echo "SKIP $p: still selected as a system home or screensaver; activate the new app first."
    continue
  fi
  if [ "$mode" = archive ]; then
    out="$(tv "pm disable-user --user 0 $p")"
    echo "Preserved old data and hid $p: $out"
  else
    # -k removes the old app for the primary user but retains its private data for recovery.
    out="$(tv "pm uninstall -k --user 0 $p")"
    if [[ "$out" != *Success* ]]; then
      echo "STOP: unable to remove $p: $out"
      exit 1
    fi
    echo "Removed legacy app $p for user 0; old private data kept for recovery (-k)."
  fi
done
echo 'Done. Your current sa.urtv.* apps and their data were never uninstalled or reset.'
