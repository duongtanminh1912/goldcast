#!/bin/sh
# Kiem playbook Ansible chi bang Docker, chay y het tren may dev, GitHub Actions va
# Jenkins (giong monitoring/check.sh: file vao container qua tar, khong bind mount).
# Khong bao gio ket noi toi may chu that: dich duy nhat la mot container Ubuntu 24.04
# dung mot lan tren mang Docker rieng.
#
#   1. ansible-lint va --syntax-check
#   2. Hai cong chan cua siet-ssh.yml tu choi khi thieu bien / user chua co khoa
#   3. --check --diff tren may moi chay duoc, bao co thay doi, nhung khong ghi gi
#   4. Ap dung that bang root, nhu lan dau tren VPS
#   5. sshd nap cau hinh moi: root bi tu choi, deploy vao duoc va sudo duoc
#   6. Chay lai bang deploy + sudo, roi --check: ca hai phai changed=0
set -eu
cd "$(dirname "$0")/.."
. ansible/images.env

docker build -q -t goldcast-ansible-tool \
  --build-arg PYTHON_IMAGE="$PYTHON_IMAGE" \
  --build-arg ANSIBLE_CORE_VERSION="$ANSIBLE_CORE_VERSION" \
  --build-arg ANSIBLE_LINT_VERSION="$ANSIBLE_LINT_VERSION" - < ansible/test/tool.Dockerfile > /dev/null
docker build -q -t goldcast-ansible-target \
  --build-arg UBUNTU_IMAGE="$UBUNTU_IMAGE" - < ansible/test/target.Dockerfile > /dev/null

may="goldcast-ansible-$$"
don_dep() { docker rm -f "$may" > /dev/null 2>&1 || true; docker network rm "$may" > /dev/null 2>&1 || true; }
trap don_dep EXIT
trap 'exit 1' INT TERM
docker network create "$may" > /dev/null
docker run -d --name "$may" --network "$may" goldcast-ansible-target > /dev/null

# Khoa dung mot lan cho root, thay cho ssh-copy-id o muc 3.3 cua docs/huong-dan.md.
docker exec "$may" sh -ec 'ssh-keygen -q -t ed25519 -N "" -C check -f /tmp/khoa
  install -m 600 /tmp/khoa.pub /root/.ssh/authorized_keys'
KHOA=$(docker exec "$may" sh -ec 'cat /tmp/khoa; rm -f /tmp/khoa /tmp/khoa.pub')
export KHOA

# Chay $1 trong container cong cu, tai ansible/, voi ham `chay_playbook`:
#   chay_playbook <changed: 0 | co> <tham so ansible-playbook...>
# In dong PLAY RECAP va ten cac task da doi; ansible-playbook loi thi in toan bo log.
trong_cong_cu() {
  COPYFILE_DISABLE=1 tar -cf - ansible |
    docker run --rm -i --network "$may" -e KHOA -e MAY_THU="$may" -e ANSIBLE_NOCOLOR=1 \
      goldcast-ansible-tool sh -ec '
        mkdir /src; cd /src; tar -xf -; cd ansible
        install -d -m 700 ~/.ssh; printf "%s\n" "$KHOA" > ~/.ssh/id_ed25519; chmod 600 ~/.ssh/id_ed25519
        SSH="ssh -o LogLevel=ERROR -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"
        chay_playbook() {
          muon=$1; shift
          echo "--> ansible-playbook $*"
          ansible-playbook -i test/inventory.yml "$@" > /tmp/log 2>&1 || { cat /tmp/log; echo "ansible-playbook that bai" >&2; exit 1; }
          recap=$(grep -E "^may-thu +: ok=" /tmp/log | tail -1)
          echo "    $recap"
          awk "/^TASK \[/{t=\$0} /^changed:/{print t}" /tmp/log | sed -E "s/^TASK \[(.*)\] \**$/      doi: \1/" | uniq
          doi=$(echo "$recap" | sed -E "s/.* changed=([0-9]+) .*/\1/")
          case "$muon:$doi" in
            0:0) ;;
            0:*) echo "Van doi $doi thu (liet ke tren): playbook khong idempotent" >&2; exit 1 ;;
            co:0) cat /tmp/log; echo "Lan dau phai thay doi may moi" >&2; exit 1 ;;
          esac
        }
        phai_hong() {
          thong_bao=$1; shift
          echo "--> phai bi tu choi: ansible-playbook $*"
          if ansible-playbook -i test/inventory.yml "$@" > /tmp/log 2>&1; then
            cat /tmp/log; echo "Le ra phai dung lai" >&2; exit 1
          fi
          grep -q "$thong_bao" /tmp/log || { cat /tmp/log; echo "Dung lai sai ly do, can: $thong_bao" >&2; exit 1; }
          echo "    dung lai: $thong_bao"
        }
        '"$1"
}

echo "==> Lint va cu phap"
trong_cong_cu '
  ansible-playbook -i test/inventory.yml --syntax-check site.yml
  ansible-lint --nocolor
  echo "--> cong chan cua siet-ssh.yml"
  phai_hong "DUNG LAI" siet-ssh.yml -u root
  phai_hong "KHONG CO KHOA" siet-ssh.yml -u root -e goldcast_toi_da_kiem_khoa=yes
  echo "==> --check --diff tren may moi"
  chay_playbook co dung-may-chu.yml -u root --check --diff'

docker exec "$may" sh -ec '! id deploy > /dev/null 2>&1; ! grep -q swapfile /etc/fstab; [ ! -e /etc/apt/sources.list.d/docker.list ]' ||
  { echo "--check da sua may thu" >&2; exit 1; }
echo "    --check khong ghi gi len may"

trong_cong_cu '
  echo "==> Ap dung lan 1 (root)"
  chay_playbook co site.yml -u root -e goldcast_toi_da_kiem_khoa=yes'

# Thay cho `systemctl reload ssh`: sshd la PID 1 va doc lai cau hinh khi nhan SIGHUP.
docker kill -s HUP "$may" > /dev/null
sleep 1
[ "$(docker inspect -f '{{.State.Running}}' "$may")" = true ] || { docker logs "$may"; exit 1; }

echo "==> Sau khi sshd nap cau hinh siet"
trong_cong_cu '
  if $SSH "root@$MAY_THU" true 2> /dev/null; then echo "root van SSH vao duoc" >&2; exit 1; fi
  echo "    root bi tu choi"
  $SSH "deploy@$MAY_THU" "sudo -n id -u" | grep -qx 0 || { echo "deploy khong sudo duoc qua SSH" >&2; exit 1; }
  echo "    deploy vao duoc bang khoa va sudo -n duoc"
  phai_hong "KHONG CO KHOA" siet-ssh.yml -u deploy -b -e goldcast_toi_da_kiem_khoa=yes -e goldcast_nguoi_trien_khai=nobody
  echo "==> Ap dung lan 2 (deploy + sudo): phai changed=0"
  chay_playbook 0 site.yml -u deploy -b -e goldcast_toi_da_kiem_khoa=yes
  echo "==> --check --diff sau khi da ap dung: phai changed=0"
  chay_playbook 0 site.yml -u deploy -b -e goldcast_toi_da_kiem_khoa=yes --check --diff'
echo "Ansible OK"
