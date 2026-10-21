#!/usr/bin/env python3
"""Prepare private local configuration; never write credentials into the checkout."""
import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import re
import secrets
import urllib.request


def private_write(path, text):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        stream.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--secrets-dir', type=Path, default=Path.home() / '.config/goldcast-monitoring')
    parser.add_argument('--ssh-key', type=Path, required=True)
    parser.add_argument('--known-hosts', type=Path, default=Path.home() / '.ssh/known_hosts')
    parser.add_argument('--ssh-host', default='deploy@103.175.248.236')
    args = parser.parse_args()
    os.umask(0o077)
    directory = args.secrets_dir.expanduser().resolve()
    checkout = Path(__file__).resolve().parents[1]
    if directory == checkout or checkout in directory.parents:
        raise SystemExit('Secrets must be outside the Git checkout.')
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    directory.chmod(0o700)
    for name, prompt in [('telegram_bot_token', 'Telegram bot token (hidden): '),
                         ('telegram_chat_id', 'Telegram chat_id: ')]:
        if not (directory / name).exists():
            private_write(directory / name, getpass.getpass(prompt).strip() + '\n')
    chat = (directory / 'telegram_chat_id').read_text().strip()
    if not re.fullmatch(r'-?[0-9]+', chat):
        raise SystemExit('Invalid Telegram chat_id.')
    if not (directory / 'grafana_admin_password').exists():
        private_write(directory / 'grafana_admin_password', secrets.token_urlsafe(32) + '\n')
    auth_path = directory / 'jenkins_auth.json'
    if not auth_path.exists():
        username = input('Jenkins username: ').strip()
        token = getpass.getpass('Jenkins API token (hidden): ').strip()
        header = base64.b64encode((username + ':' + token).encode()).decode()
        request = urllib.request.Request('http://127.0.0.1:8081/whoAmI/api/json',
                                         headers={'Authorization': 'Basic ' + header})
        with urllib.request.urlopen(request, timeout=10) as response:
            if not json.load(response).get('authenticated'):
                raise SystemExit('Jenkins authentication failed.')
        private_write(auth_path, json.dumps({'username': username, 'token': token}) + '\n')
    for path in (args.ssh_key, args.known_hosts):
        if not path.expanduser().is_file():
            raise SystemExit('SSH key/known_hosts missing; verify the VPS host key first.')
    template = Path(__file__).with_name('alertmanager') / 'alertmanager.yml.template'
    private_write(directory / 'alertmanager.yml', template.read_text().replace('__CHAT_ID__', chat))
    # Host paths are local Compose interpolation data, never shell-sourced.
    env = {'MONITORING_SECRETS_DIR': str(directory), 'MONITORING_SSH_KEY': str(args.ssh_key.expanduser().resolve()),
           'MONITORING_KNOWN_HOSTS': str(args.known_hosts.expanduser().resolve()),
           'MONITORING_SSH_HOST': args.ssh_host}
    private_write(directory / 'runtime.env', ''.join(key + '=' + value + '\n' for key, value in env.items()))
    print('Private configuration ready in ' + str(directory) + '. No token was printed.')


if __name__ == '__main__':
    main()
