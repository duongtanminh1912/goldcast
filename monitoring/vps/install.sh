#!/bin/sh
# Copy monitoring/ to /opt/goldcast/monitoring, then run as the deploy user.
set -eu
root=/opt/goldcast/monitoring
test "$(id -un)" = deploy
test -f "$root/images.env"
python3 --version
mkdir -p "$root/textfile"
chmod 755 "$root/textfile"
python3 "$root/vps/collect.py"
docker compose --env-file "$root/images.env" -f "$root/vps/compose.yml" config --quiet
docker compose --env-file "$root/images.env" -f "$root/vps/compose.yml" up -d
sudo -n install -m 644 "$root/vps/goldcast-monitoring.service" /etc/systemd/system/goldcast-monitoring.service
sudo -n install -m 644 "$root/vps/goldcast-monitoring.timer" /etc/systemd/system/goldcast-monitoring.timer
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now goldcast-monitoring.timer
sudo -n systemctl start goldcast-monitoring.service
systemctl is-active goldcast-monitoring.timer
