#!/usr/bin/env python3
"""Create a separate monitoring SSH key with forced-command and forwarding restrictions."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deploy-key', type=Path, required=True)
    parser.add_argument('--host', default='deploy@103.175.248.236')
    args = parser.parse_args()
    os.umask(0o077)
    directory = Path.home() / '.config/goldcast-monitoring'
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    key = directory / 'id_ed25519'
    if not key.exists():
        subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-C',
                        'goldcast-monitoring-forward-only', '-f', str(key)], check=True)
    public = key.with_suffix('.pub').read_text().strip()
    if not public.startswith('ssh-ed25519 ') or '\n' in public:
        raise SystemExit('Invalid public key')
    # -N creates no command channel; forced false rejects shell/exec/SFTP with this key.
    # restrict disables PTY, agent, X11 and user rc; only forwarding is re-enabled.
    line = ('restrict,port-forwarding,permitopen="127.0.0.1:9091",'
            'permitopen="127.0.0.1:9100",command="/bin/false" ' + public + '\n')
    remote = '''
from pathlib import Path
import os,sys
line=sys.stdin.read()
if len(line.splitlines())!=1 or 'goldcast-monitoring-forward-only' not in line:
 raise SystemExit('Invalid monitoring key')
directory=Path.home()/'.ssh'
directory.mkdir(mode=0o700,exist_ok=True)
path=directory/'authorized_keys'
previous=path.read_text() if path.exists() else ''
if line.strip() not in previous.splitlines():
 with path.open('a') as output:
  if previous and not previous.endswith('\\n'):output.write('\\n')
  output.write(line)
path.chmod(0o600)
print('Restricted monitoring public key installed; existing keys retained.')
'''
    subprocess.run(['ssh', '-i', str(args.deploy_key.expanduser()), '-o', 'IdentitiesOnly=yes',
                    '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=10',
                    args.host, 'python3 -c ' + shlex.quote(remote)], input=line, text=True, check=True)
    print('Use --ssh-key ' + str(key) + ' with monitoring/configure.py.')
