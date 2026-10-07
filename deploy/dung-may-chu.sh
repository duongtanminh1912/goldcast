#!/usr/bin/env bash
#
# Dung may chu cho GoldCast tren Ubuntu 22.04 / 24.04 vua cai.
#
# Chay bang root tren VPS moi:
#   bash deploy/dung-may-chu.sh
#
# CHAY LAI DUOC. Moi buoc kiem truoc khi lam, nen chay hai lan khong hong gi.
# Tinh chat nay bat buoc: tuan 11 se xoa may chu va dung lai tu dau chi bang
# cac script trong repo. Mot script chi chay duoc mot lan thi khong phai ha
# tang dang ma, no chi la mot ghi chep ve nhung gi tung go bang tay.
#
# KHONG lam trong script nay:
#   - Tat dang nhap bang mat khau SSH -> xem deploy/siet-ssh.sh, va chi chay
#     SAU khi da xac nhan dang nhap bang khoa thanh cong. Lam sai thu tu la
#     tu khoa minh o ngoai may chu vua tra tien.
#   - Cai dat ung dung -> tuan 8 lam tay, tuan 10 Jenkins lam.
#   - apt-get upgrade toan bo -> cham, kho doan, co the doi restart.
#     Thay vao do bat unattended-upgrades de he thong tu va cac ban va bao mat.

set -euo pipefail

NGUOI_TRIEN_KHAI="${NGUOI_TRIEN_KHAI:-deploy}"
SWAP="${SWAP:-2G}"
MUI_GIO="Asia/Ho_Chi_Minh"

buoc() { printf '\n==> %s\n' "$1"; }

if [ "$(id -u)" -ne 0 ]; then
  echo "Phai chay bang root. Thu: sudo bash $0" >&2
  exit 1
fi

buoc "Mui gio va dong bo dong ho"
# Dong ho dung la dieu kien bat buoc cho tuan 9: viec cap va kiem chung chi
# TLS phu thuoc truc tiep vao thoi gian. May lech vai ngay se bi tu choi
# chung chi kem thong bao rat kho hieu -- "certificate not yet valid".
timedatectl set-timezone "$MUI_GIO"
timedatectl set-ntp true

buoc "Cai cong cu co ban"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg ufw unattended-upgrades
systemctl enable --now unattended-upgrades

buoc "Swap $SWAP"
# Khong phai vi thieu RAM: do duoc ba container chi dung ~370 MB. Swap la bao
# hiem -- no bien "bi OOM killer giet dot ngot" thanh "cham di mot luc".
if swapon --show 2>/dev/null | grep -q '/swapfile'; then
  echo "swapfile da bat, bo qua"
else
  if [ ! -f /swapfile ]; then
    fallocate -l "$SWAP" /swapfile 2>/dev/null \
      || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  fi
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
fi
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
# swappiness=10: uu tien RAM, chi dung swap khi that su can.
sysctl -q -w vm.swappiness=10
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' >> /etc/sysctl.conf

buoc "Docker Engine va Compose plugin"
# Cai tu kho chinh thuc cua Docker, khong dung `apt install docker.io`:
# ban trong kho Ubuntu cu hon nhieu va thieu plugin `docker compose`.
if command -v docker >/dev/null 2>&1; then
  echo "da co: $(docker --version)"
else
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' \
    "$(dpkg --print-architecture)" \
    "$(. /etc/os-release && echo "$VERSION_CODENAME")" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

buoc "Nguoi dung trien khai: $NGUOI_TRIEN_KHAI"
# Tuan 10 Jenkins se SSH vao bang nguoi dung nay, khong phai root.
# Luu y trung thuc: thanh vien nhom `docker` tuong duong quyen root, vi ai vao
# duoc Docker thi mount duoc / cua host. Nen day KHONG phai mot lop cach ly
# thuc su -- no chi giam pham vi loi do go nham lenh, va de lai dau vet ro hon
# trong log. Cach ly that can rootless Docker, ngoai pham vi do an.
if id "$NGUOI_TRIEN_KHAI" >/dev/null 2>&1; then
  echo "da co"
else
  adduser --disabled-password --gecos "" "$NGUOI_TRIEN_KHAI"
fi
usermod -aG docker "$NGUOI_TRIEN_KHAI"
install -d -m 700 -o "$NGUOI_TRIEN_KHAI" -g "$NGUOI_TRIEN_KHAI" \
  "/home/$NGUOI_TRIEN_KHAI/.ssh"
if [ -s /root/.ssh/authorized_keys ]; then
  install -m 600 -o "$NGUOI_TRIEN_KHAI" -g "$NGUOI_TRIEN_KHAI" \
    /root/.ssh/authorized_keys "/home/$NGUOI_TRIEN_KHAI/.ssh/authorized_keys"
  echo "da sao khoa SSH tu root sang $NGUOI_TRIEN_KHAI"
else
  echo "CANH BAO: /root/.ssh/authorized_keys rong hoac khong co."
  echo "Phai dan khoa cong khai vao /home/$NGUOI_TRIEN_KHAI/.ssh/authorized_keys"
  echo "truoc khi chay deploy/siet-ssh.sh."
fi

buoc "Tuong lua: chi mo 22, 80, 443"
# Thu tu quan trong: cho phep 22 TRUOC khi bat ufw. Bat truoc roi moi mo SSH
# la tu ngat ket noi dang dung.
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp  comment 'SSH'
ufw allow 80/tcp  comment 'HTTP'
ufw allow 443/tcp comment 'HTTPS'
ufw --force enable

buoc "Kiem lai"
docker --version
docker compose version
free -h | awk '/Swap/ {print "Swap: " $2 " tong, " $3 " dang dung"}'
timedatectl | grep -E 'Time zone|synchronized'
id "$NGUOI_TRIEN_KHAI"
ufw status | head -8

cat <<'CANH_BAO'

================================================================
 DOC KY: UFW KHONG CHAN DUOC CONG DO DOCKER MO
================================================================

Docker ghi rule truc tiep vao chain DOCKER-USER cua iptables, nam TRUOC rule
cua ufw. Nen mot container co `ports: - "5432:5432"` se mo cong 5432 ra
internet THAT, du `ufw status` bao deny.

Day la cai bay noi tieng, va no danh thang vao dieu da ghi tu tuan 2:
khong bao gio mo 5432, 8080, 3000 ra ngoai.

Tuong lua o tren KHONG du. Cach duy nhat chac chan la:

  - docker-compose tren may chu KHONG khai `ports` cho db, backend, frontend
  - chi Caddy publish 80 va 443
  - ba service kia noi voi nhau qua mang noi bo cua compose
  - neu that su can truy cap tu may minh, dung SSH tunnel chu khong mo cong

Sau khi dung stack len o tuan 8, kiem tu NGOAI may chu:

  nmap -Pn <ip-may-chu>

Chi duoc thay 22, 80, 443. Thay 5432 la phai sua compose ngay.
================================================================
CANH_BAO
