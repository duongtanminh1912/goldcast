#!/usr/bin/env bash
# Siet SSH: tat dang nhap root, tat mat khau, chi con khoa.
#
# CHAY SAU KHI da chung minh user trien khai vao duoc bang khoa.
# Chay truoc do la tu khoa minh ra khoi may chu.
set -euo pipefail

NGUOI_TRIEN_KHAI="${NGUOI_TRIEN_KHAI:-deploy}"
FILE_SIET=/etc/ssh/sshd_config.d/99-goldcast.conf
GOC=/etc/ssh/sshd_config

buoc() { printf '\n==> %s\n' "$1"; }

# Chot 1: bat nguoi chay phai xac nhan da kiem khoa tu may ca nhan.
if [ "${TOI_DA_KIEM_KHOA:-}" != "yes" ]; then
  cat <<'HUONG_DAN'
DUNG LAI.

Script nay tat dang nhap root va tat mat khau tren SSH. Sau do duong vao
duy nhat la user trien khai bang khoa. Khoa khong hoat dong thi may chi
con vao duoc qua console web cua nha cung cap.

Tu MAY CA NHAN (khong phai tu may chu nay), chay:

    ssh deploy@<ip> 'sudo -n id && echo SUDO_OK'

Ra uid=0(root) kem SUDO_OK thi chay lai script nay voi:

    TOI_DA_KIEM_KHOA=yes bash deploy/siet-ssh.sh

Va giu phien SSH hien tai MO trong luc chay.
HUONG_DAN
  exit 1
fi

[ "$(id -u)" -eq 0 ] || { echo "Can chay bang root."; exit 1; }

# Chot 2: authorized_keys cua nguoi trien khai phai co noi dung thuc.
buoc "Kiem khoa cua $NGUOI_TRIEN_KHAI"
NHA="$(getent passwd "$NGUOI_TRIEN_KHAI" | cut -d: -f6)"
FILE_KHOA="$NHA/.ssh/authorized_keys"
if [ ! -s "$FILE_KHOA" ]; then
  echo "KHONG CO KHOA: $FILE_KHOA rong hoac khong ton tai."
  echo "Day khoa len truoc: ssh-copy-id $NGUOI_TRIEN_KHAI@<ip>"
  exit 1
fi
echo "$(grep -cve '^[[:space:]]*$' -e '^#' "$FILE_KHOA") khoa trong $FILE_KHOA"

# sshd lay GIA TRI DAU TIEN cho moi tu khoa. Include nam o cuoi file thi
# cac gia tri trong sshd_config da doc truoc va se thang - cau hinh siet
# bi vo hieu mot cach im lang.
buoc "Dam bao Include o dong dau sshd_config"
DONG=$(grep -n '^Include /etc/ssh/sshd_config.d' "$GOC" | head -1 | cut -d: -f1 || true)
if [ "$DONG" != "1" ]; then
  cp -n "$GOC" "$GOC.truoc-goldcast"
  sed -i '\#^Include /etc/ssh/sshd_config.d#d' "$GOC"
  printf 'Include /etc/ssh/sshd_config.d/*.conf\n' | cat - "$GOC" > "$GOC.moi"
  mv "$GOC.moi" "$GOC"
  echo "da chuyen Include len dong 1, ban cu o $GOC.truoc-goldcast"
else
  echo "Include da o dong 1, bo qua"
fi

buoc "Ghi $FILE_SIET"
mkdir -p "$(dirname "$FILE_SIET")"
cat > "$FILE_SIET" <<'CAU_HINH'
# Do deploy/siet-ssh.sh tao. Sua o day, dung sua sshd_config goc.
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
CAU_HINH
chmod 644 "$FILE_SIET"
cat "$FILE_SIET"

# Chot 3: kiem cu phap TRUOC khi ap dung.
buoc "Kiem cu phap sshd"
sshd -t
echo "cu phap ok"

# Chot 4: reload giu cac phien dang mo. restart cat het, va neu cau hinh
# sai thi mat luon duong ve.
buoc "Reload sshd"
systemctl reload ssh
systemctl is-active ssh

buoc "Kiem lai gia tri thuc te sshd dang dung"
sshd -T | grep -E '^(permitrootlogin|passwordauthentication|kbdinteractiveauthentication|pubkeyauthentication) '

cat <<'XONG'

=========================================================
 CHUA XONG - CON MOT BUOC O MAY CA NHAN
=========================================================

Dung dong phien SSH nay.

Tu may ca nhan, cua so khac:

    ssh deploy@<ip> 'echo VAO_DUOC'

Ra VAO_DUOC thi moi dong phien nay.

Khong vao duoc thi phien nay van con, hoan tac bang:

    rm /etc/ssh/sshd_config.d/99-goldcast.conf
    systemctl reload ssh

=========================================================
XONG
