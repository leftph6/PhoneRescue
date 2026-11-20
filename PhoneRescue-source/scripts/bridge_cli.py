"""PhoneRescue CLI for people and agents. JSON output; credentials never printed."""
import argparse
import http.client
import json
import os
from pathlib import Path
import sys
import time
import urllib.parse
import uuid

MAX_BYTES = 100 * 1024 * 1024


class Bridge:
    def __init__(self, session):
        data = json.loads(Path(session).read_text(encoding='utf-8-sig'))
        uri = urllib.parse.urlsplit(data['url'])
        if uri.scheme != 'http' or uri.hostname != '127.0.0.1' or not uri.fragment:
            raise ValueError('CLI requires a local bridge session URL')
        self.host, self.port, self.token = uri.hostname, uri.port, uri.fragment

    def call(self, method, path, payload=None, source=None, destination=None):
        headers = {'X-Rescue-Token': self.token}
        body = None
        if payload is not None:
            body = json.dumps(payload).encode('utf-8')
            headers['Content-Type'] = 'application/json'
        if source is not None:
            headers['Content-Type'] = 'application/octet-stream'
            headers['Content-Length'] = str(os.fstat(source.fileno()).st_size)
            body = source
        connection = http.client.HTTPConnection(self.host, self.port, timeout=60)
        try:
            connection.request(method, '/api/' + path, body=body, headers=headers)
            response = connection.getresponse()
            if response.status >= 300:
                raise RuntimeError('HTTP ' + str(response.status) + ': ' + response.read(2048).decode('utf-8', errors='replace'))
            if destination is not None:
                total = 0
                while chunk := response.read(65536):
                    total += len(chunk)
                    if total > MAX_BYTES:
                        raise ValueError('File exceeds 100 MB')
                    destination.write(chunk)
                return {'bytes': total}
            return json.loads(response.read() or b'{}')
        finally:
            connection.close()


def emit(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default = Path(os.environ.get('LOCALAPPDATA', str(Path.home()))) / 'PhoneRescue/session.json'
    parser.add_argument('--session', default=str(default), help='Local session.json (do not upload or print its contents)')
    subs = parser.add_subparsers(dest='action', required=True)
    subs.add_parser('status'); subs.add_parser('files')
    up = subs.add_parser('upload'); up.add_argument('file', type=Path)
    down = subs.add_parser('download'); down.add_argument('remote_path'); down.add_argument('destination', type=Path)
    run = subs.add_parser('run', help='Execute an authorized PowerShell script; no implicit confirmation dialog')
    run.add_argument('--script', type=Path, required=True); run.add_argument('--cwd'); run.add_argument('--timeout', type=int, default=60)
    for action in ('job', 'cancel'):
        sub = subs.add_parser(action); sub.add_argument('id')
    args = parser.parse_args()
    bridge = Bridge(args.session)
    if args.action in ('status', 'files'):
        emit(bridge.call('GET', args.action))
    elif args.action == 'upload':
        if args.file.stat().st_size > MAX_BYTES:
            raise ValueError('File exceeds 100 MB')
        with args.file.open('rb') as source:
            emit(bridge.call('POST', 'files/upload?name=' + urllib.parse.quote(args.file.name), source=source))
    elif args.action == 'download':
        if args.destination.exists():
            raise FileExistsError('Destination already exists; choose another path')
        part = args.destination.with_name(args.destination.name + '.' + uuid.uuid4().hex + '.part')
        try:
            with part.open('xb') as output:
                result = bridge.call('GET', 'files/download?path=' + urllib.parse.quote(args.remote_path, safe=''), destination=output)
            # On Windows rename refuses to overwrite an existing destination.
            part.rename(args.destination)
            emit(dict(result, saved=str(args.destination)))
        finally:
            part.unlink(missing_ok=True)
    elif args.action == 'job':
        uuid.UUID(args.id); emit(bridge.call('GET', 'jobs/' + args.id))
    elif args.action == 'cancel':
        uuid.UUID(args.id); emit(bridge.call('POST', 'jobs/' + args.id + '/cancel', {}))
    elif args.action == 'run':
        if not 1 <= args.timeout <= 300:
            raise ValueError('Timeout must be 1..300 seconds')
        script = args.script.read_text(encoding='utf-8-sig')
        if not script.strip() or len(script) > 16000:
            raise ValueError('Script must contain 1..16000 characters')
        cwd = args.cwd or bridge.call('GET', 'status')['workspace']
        job = str(uuid.uuid4())
        emit({'id': job, 'state': 'submitting', 'note': 'On connection failure, query this id; do not blindly resubmit.'})
        bridge.call('POST', 'jobs', {'id': job, 'command': script, 'workingDirectory': cwd, 'timeoutSeconds': args.timeout})
        deadline = time.monotonic() + args.timeout + 30
        while time.monotonic() < deadline:
            result = bridge.call('GET', 'jobs/' + job)
            if result['state'] != 'running':
                emit(result)
                return 0 if result['state'] == 'completed' and result['exitCode'] == 0 else 1
            time.sleep(.5)
        raise TimeoutError('Polling deadline reached; query job ' + job)
    return 0


if __name__ == '__main__':
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
