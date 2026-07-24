#!/usr/bin/env bash
# Install the GPU crash recorder as a USER-level systemd service (no root, nothing system-wide).
# Copies the unit into ~/.config/systemd/user/ and reloads the user manager. It does NOT enable linger and does
# NOT run any privileged command — both are left to you (see the notes it prints).
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
UNIT_SRC="$REPO/scripts/systemd/softbox-gpu-crash-recorder.service"
UNIT_DIR="$HOME/.config/systemd/user"
UNIT_DST="$UNIT_DIR/softbox-gpu-crash-recorder.service"

[[ -f "$UNIT_SRC" ]] || { echo "missing unit file: $UNIT_SRC" >&2; exit 2; }
command -v systemctl >/dev/null 2>&1 || { echo "systemctl not found — use the manual recorder instead:"; echo "  $REPO/scripts/gpu-crash-recorder.sh"; exit 2; }

mkdir -p "$UNIT_DIR" "$HOME/gpu-crash-records"
# %h expands to the user's home, but ExecStart must point at THIS checkout — rewrite it if the repo lives elsewhere.
sed "s|%h/Code/SoftBox|$REPO|g" "$UNIT_SRC" > "$UNIT_DST"
echo "installed $UNIT_DST"

systemctl --user daemon-reload && echo "systemctl --user daemon-reload: ok"

cat <<EOF

Next (run these yourself):

  systemctl --user enable --now softbox-gpu-crash-recorder.service
  systemctl --user status softbox-gpu-crash-recorder.service

Stop / restart:

  systemctl --user stop softbox-gpu-crash-recorder.service
  systemctl --user restart softbox-gpu-crash-recorder.service

NOTE — survival after logout: user services stop when your last session ends unless lingering is enabled.
That is a privileged change and is NOT done automatically. If you want the recorder to keep running with no
session open, run it yourself:

  sudo loginctl enable-linger $USER

NOTE — kernel journal access: NVRM/Xid messages are only captured if this user can read the kernel journal.
Check with 'journalctl -k -n1'. If it is denied, the one-time privileged fix is:

  sudo usermod -aG systemd-journal $USER     # then log out and back in

Records: \$HOME/gpu-crash-records/<UTC-session>/  (symlink 'current' points at the live session)
EOF
