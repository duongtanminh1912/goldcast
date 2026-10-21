#!/usr/bin/env python3
"""Read-only Jenkins exporter. Intentionally failing rehearsal jobs are excluded."""
import base64
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
import time
import urllib.request

JOBS = ('goldcast-ci', 'goldcast-backup')
TREE = 'lastCompletedBuild[result,timestamp,duration],lastSuccessfulBuild[timestamp,duration],lastBuild[building,timestamp,number]'


def render(job, data, now):
    labels = '{job_name="' + job + '"}'
    completed = data.get('lastCompletedBuild') or {}
    successful = data.get('lastSuccessfulBuild') or {}
    latest = data.get('lastBuild') or {}
    running = bool(latest.get('building'))
    values = {
        'goldcast_jenkins_scrape_success': 1,
        'goldcast_jenkins_job_has_completed_build': bool(completed),
        'goldcast_jenkins_job_last_result_success': completed.get('result') == 'SUCCESS',
        'goldcast_jenkins_job_last_success_timestamp_seconds': (successful.get('timestamp', 0) + successful.get('duration', 0)) / 1000,
        'goldcast_jenkins_job_building': running,
        'goldcast_jenkins_job_running_seconds': max(0, now - latest.get('timestamp', now * 1000) / 1000) if running else 0,
    }
    return ''.join(f'{name}{labels} {float(value):.17g}\n' for name, value in values.items())


def collect(fetch, now=None):
    now = time.time() if now is None else now
    def one(job):
        try:
            return render(job, fetch(job), now)
        except Exception:
            return f'goldcast_jenkins_scrape_success{{job_name="{job}"}} 0\n'
    with ThreadPoolExecutor(max_workers=2) as pool:
        return ''.join(pool.map(one, JOBS))


def main():
    auth = json.loads(Path('/run/secrets/jenkins_auth').read_text())
    header = 'Basic ' + base64.b64encode((auth['username'] + ':' + auth['token']).encode()).decode()
    base = os.environ.get('JENKINS_URL', 'http://host.docker.internal:8081').rstrip('/')
    def fetch(job):
        request = urllib.request.Request(base + '/job/' + job + '/api/json?tree=' + TREE,
                                         headers={'Authorization': header})
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)
    state = {'body': '# Waiting for the first Jenkins poll\n'}
    def poll():
        while True:
            state['body'] = collect(fetch)
            time.sleep(30)
    threading.Thread(target=poll, daemon=True).start()
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/metrics':
                self.send_error(404)
                return
            body = state['body'].encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; version=0.0.4')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *args):
            pass
    ThreadingHTTPServer(('0.0.0.0', 9110), Handler).serve_forever()


if __name__ == '__main__':
    main()
