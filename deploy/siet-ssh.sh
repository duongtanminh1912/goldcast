#!/usr/bin/env bash
# Siet SSH: tat dang nhap root, tat mat khau, chi con khoa.
#
# CHAY SAU KHI da chung minh user trien khai vao duoc bang khoa.
# Chay truoc do la tu khoa minh ra khoi may chu.
set -euo pipefail

NGUOI_TRIEN_KHAI="${NGUOI_TRIEN_KHAI:-deploy}"

# Tien to 01 chu khong phai 99. Cac file trong sshd_config.d doc theo thu tu
# ten, va sshd lay GIA TRI DAU TIEN cho moi tu khoa. Image Ubuntu co san
# 50-cloud-init.conf dat PasswordAuthentication yes - file 99 doc sau no nen
# se thua. Phai doc TRUOC no.
FILE_SIET=/etc/ssh/sshd_config.d/01-goldcast.conf
GOC=/etc/ssh/sshd_config

buoc() { printf '\n==> %s\n' "$1"; }

if [ "${TOI_DA_KIEM_KHOA:-}" != "yes" ]; then
  cat <<'HUONG_DAN'
DUNG LAI.

Script nay tat dang nhap root va tat mat khau tren SSH. Sau do duong vao
duy nhat la user trien khai bang khoa. Khoa khong hoat dong thi may chi
con vao duoc qua console web cua nha cung cap.

Tu MAY CA NHAN (khong phai tu may chu nay), chay:

    ssh deploy@<ip> 'sudo -n id && echo SUDO_OK'

Ra uid=0(root) kem SUDO_OK thi chay lai script nay voi:

    TOI_DA_KIEM_KHOA=yes sudo -E bash deploy/siet-ssh.sh

Va giu phien SSH hien tai MO trong luc chay.
HUONG_DAN
  exit 1
fi

[ "$(id -u)" -eq 0 ] || { echo "Can chay bang root."; exit 1; }

buoc "Kiem khoa cua $NGUOI_TRIEN_KHAI"
NHA="$(getent passwd "$NGUOI_TRIEN_KHAI" | cut -d: -f6)"
FILE_KHOA="$NHA/.ssh/authorized_keys"
if [ ! -s "$FILE_KHOA" ]; then
  echo "KHONG CO KHOA: $FILE_KHOA rong hoac khong ton tai."
  echo "Day khoa len truoc: ssh-copy-id $NGUOI_TRIEN_KHAI@<ip>"
  exit 1
fi
echo "$(grep -cve '^[[:space:]]*$' -e '^#' "$FILE_KHOA") khoa trong $FILE_KHOA"

# Include phai nam dong dau. Neu no o cuoi file, cac gia tri trong
# sshd_config da duoc doc truoc va se thang - cau hinh siet bi vo hieu
# mot cach im lang.
buoc "Dam bao Include o dong dau sshd_config"
DONG=$(grep -n '^Include /etc/ssh/sshd_config.d' "$GOC" | head -1 | cut -d: -f1 || true)
if [ "$DONG" != "1" ]; then
  cp -n "$GOC" "$GOC.truoc-goldcast" || true
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

buoc "Kiem cu phap sshd"
sshd -t
echo "cu phap ok"

# reload giu cac phien dang mo. restart cat het, va neu cau hinh sai
# thi mat luon duong ve.
buoc "Reload sshd"
systemctl reload ssh
systemctl is-active ssh

# Khong chi IN RA gia tri - kiem that su va fail neu sai. In ra roi di
# tiep chinh la cach bo sot loi da gap o swap va o dong ho.
buoc "Kiem gia tri thuc te sshd dang dung"
LOI=0
for CAN in "permitrootlogin no" \
           "passwordauthentication no" \
           "kbdinteractiveauthentication no" \
           "pubkeyauthentication yes"; do
  if sshd -T | grep -qx "$CAN"; then
    echo "  ok  $CAN"
  else
    echo "  SAI $CAN  ->  thuc te: $(sshd -T | grep "^${CAN%% *} " || echo khong-co)"
    LOI=1
  fi
done

if [ "$LOI" -ne 0 ]; then
  cat <<KHONG_DAT

KHONG DAT. Mot file trong /etc/ssh/sshd_config.d/ doc TRUOC $FILE_SIET
da dat gia tri khac, hoac Include khong duoc doc.

Xem thu tu: ls /etc/ssh/sshd_config.d/

Hoan tac: rm $FILE_SIET && systemctl reload ssh
KHONG_DAT
  exit 1
fi

cat <<XONG

=========================================================
 CHUA XONG - CON MOT BUOC O MAY CA NHAN
=========================================================

Dung dong phien SSH nay.

Tu may ca nhan, cua so khac:

    ssh deploy@<ip> 'echo VAO_DUOC'

Ra VAO_DUOC thi moi dong phien nay.

Khong vao duoc thi phien nay van con, hoan tac bang:

    rm $FILE_SIET
    systemctl reload ssh

=========================================================
XONG
