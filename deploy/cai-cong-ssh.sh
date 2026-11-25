#!/usr/bin/env bash
# Cai cong SSH cho khoa Jenkins (deploy/goldcast-gate.py) tren VPS. Chay bang sudo.
#
#   sudo bash cai-cong-ssh.sh cai               # chi cai /usr/local/bin/goldcast-gate
#   sudo bash cai-cong-ssh.sh khoa <khoa.pub>   # gioi han khoa Jenkins vao cong
#   sudo bash cai-cong-ssh.sh mo   <khoa.pub>   # go gioi han (quay lai nhu cu)
#
# Thu tu an toan (docs/huong-dan.md): `cai` -> merge ban Jenkinsfile goi cong -> mot lan
# deploy xanh -> `khoa`. `khoa` tu choi neu user khong con khoa nao KHAC chua bi gioi han:
# do la duong vao quan tri duy nhat sau khi khoa Jenkins chi con chay duoc cong.
set -euo pipefail

NGUOI_TRIEN_KHAI="${NGUOI_TRIEN_KHAI:-deploy}"
CONG=/usr/local/bin/goldcast-gate
THU_MUC=$(cd "$(dirname "$0")" && pwd)
NGUON="$THU_MUC/goldcast-gate.py"
TUY_CHON="restrict,command=\"$CONG\""

chet() { echo "LOI: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || chet "phai chay bang sudo"
NHA=$(getent passwd "$NGUOI_TRIEN_KHAI" | cut -d: -f6) || chet "khong co user $NGUOI_TRIEN_KHAI"
FILE_KHOA="$NHA/.ssh/authorized_keys"

cai() {
    [ -f "$NGUON" ] || chet "khong thay $NGUON (chep ca thu muc deploy/ len may chu)"
    python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' || chet "can Python >= 3.9"
    python3 -m py_compile "$NGUON"
    install -o root -g root -m 0755 "$NGUON" "$CONG"
    # Goi thu: lenh rong phai bi tu choi voi ma 2, khong mo shell.
    set +e
    SSH_ORIGINAL_COMMAND='' "$CONG" </dev/null >/dev/null 2>&1
    ma=$?
    set -e
    [ "$ma" -eq 2 ] || chet "cong khong tu choi lenh rong (ma $ma)"
    echo "Da cai $CONG (root:root 0755). Khoa Jenkins chua bi gioi han."
}

khoa_cong_khai() {
    [ -n "${1:-}" ] && [ -f "$1" ] || chet "can file khoa cong khai cua Jenkins, vd jenkins_deploy.pub"
    read -r loai blob _ < "$1" || true
    case "$loai" in ssh-ed25519|ssh-rsa|ecdsa-sha2-*) ;; *) chet "$1 khong phai khoa cong khai SSH";; esac
    [ -n "$blob" ] || chet "$1 thieu noi dung khoa"
    printf '%s %s' "$loai" "$blob"
}

viet_lai() {
    # $1: khoa (loai blob), $2: tien to moi ('' de go gioi han)
    local khoa=$1 tien_to=$2 tam
    [ -f "$FILE_KHOA" ] || chet "khong co $FILE_KHOA"
    grep -qF "$khoa" "$FILE_KHOA" || chet "khoa Jenkins khong co trong $FILE_KHOA"
    cp -p "$FILE_KHOA" "$FILE_KHOA.bak-$(date -u +%Y%m%dT%H%M%SZ)"
    tam=$(mktemp "$FILE_KHOA.XXXXXX")
    while IFS= read -r dong || [ -n "$dong" ]; do
        case "$dong" in
            *"$khoa"*)
                # Bo moi tuy chon cu truoc khoa, giu chu thich phia sau.
                phan_khoa=${dong#*"$khoa"}
                if [ -n "$tien_to" ]; then printf '%s %s%s\n' "$tien_to" "$khoa" "$phan_khoa"
                else printf '%s%s\n' "$khoa" "$phan_khoa"; fi;;
            *) printf '%s\n' "$dong";;
        esac
    done < "$FILE_KHOA" > "$tam"
    chown "$NGUOI_TRIEN_KHAI:" "$tam"
    chmod 600 "$tam"
    mv "$tam" "$FILE_KHOA"
}

khoa() {
    local khoa_jenkins
    khoa_jenkins=$(khoa_cong_khai "${1:-}")
    [ -x "$CONG" ] || chet "chua cai cong; chay '$0 cai' truoc"
    # Phai con it nhat mot khoa khac khong co command=: duong vao quan tri.
    if ! grep -vF "$khoa_jenkins" "$FILE_KHOA" | grep -E '^(ssh-|ecdsa-)' >/dev/null; then
        chet "khong con khoa quan tri nao khac chua bi gioi han trong $FILE_KHOA. Them khoa ca nhan truoc, kiem dang nhap bang no, roi chay lai."
    fi
    viet_lai "$khoa_jenkins" "$TUY_CHON"
    echo "Da gioi han khoa Jenkins vao $CONG. Khoa quan tri van vao shell binh thuong."
}

mo() {
    viet_lai "$(khoa_cong_khai "${1:-}")" ''
    echo "Da go gioi han khoa Jenkins (ban sao cu: $FILE_KHOA.bak-*)."
}

case "${1:-}" in
    cai) cai;;
    khoa) khoa "${2:-}";;
    mo) mo "${2:-}";;
    *) sed -n '2,10p' "$0"; exit 2;;
esac
