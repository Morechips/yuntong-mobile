"""Create an explicit phone-importable test profile. No discovery or auto-connection."""
import argparse
import ipaddress
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', required=True, help='Computer LAN IPv4 address reachable from phone')
    parser.add_argument('--port', type=int, default=8766)
    parser.add_argument('--name', default='电脑测试端')
    parser.add_argument('--token-file', type=Path, default=Path('simulator-token.txt'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    host = ipaddress.IPv4Address(args.host)
    allowed = any(host in ipaddress.IPv4Network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8'))
    if not allowed or not 1 <= args.port <= 65535:
        parser.error('Use a LAN IPv4 address and port 1..65535')
    if not args.name.strip() or len(args.name) > 80:
        parser.error('Name must contain 1..80 characters')
    token = args.token_file.read_text(encoding='utf-8').strip()
    if not 16 <= len(token) <= 128 or any(c.isspace() for c in token):
        parser.error('Invalid token file')
    profile = dict(schema='yuntong-test-connection-v1', name=args.name.strip(),
                   address=f'ws://{host}:{args.port}/link', token=token)
    with args.output.open('x', encoding='utf-8') as f:
        json.dump(profile, f, ensure_ascii=False, indent=2)
    print('Connection file created. It contains the device token; import only on your phone.')


if __name__ == '__main__':
    main()
