#!/usr/bin/env python3
"""Exercise the logging boundaries in a final JAR on its supported JVM."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SUPPORT = [
    ('org/slf4j/slf4j-api/2.0.20/slf4j-api-2.0.20.jar',
     '7e1446499b359675d8aabe6af2de86b85e72ae8a2a932c0b40d0e5ba57097439'),
    ('org/slf4j/slf4j-jdk14/2.0.20/slf4j-jdk14-2.0.20.jar',
     'f02f2e6015daea6bac93ab68dfa1af0c6601b33f5a2bd934275626c55a0332c7'),
    ('org/apache/logging/log4j/log4j-api/2.26.1/log4j-api-2.26.1.jar',
     'f1810a4704ccce019d02bba029dc02a4f2ac0c997f647b465e7d409c1927f822'),
]
LEGACY_SLF4J = [
    ('org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar',
     'd3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0'),
    ('org/slf4j/slf4j-jdk14/1.7.36/slf4j-jdk14-1.7.36.jar',
     '5bf64690af4e59876b8902bb0db3dd39c686a40abfed97d3837eeeec7a2922ac'),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--cache-root', type=Path, default=ROOT / 'build-matrix/logging-support')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--legacy-slf4j', action='store_true', help='Exercise the SLF4J 1.7 host used by old Paper')
    args = parser.parse_args()
    args.cache_root.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    support = []
    for coordinate, digest in (LEGACY_SLF4J + SUPPORT[2:] if args.legacy_slf4j else SUPPORT):
        path = args.cache_root / Path(coordinate).name
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + coordinate, timeout=90) as response:
                content = response.read()
            if hashlib.sha256(content).hexdigest() != digest:
                raise RuntimeError('Logging test support checksum mismatch: ' + coordinate)
            path.write_bytes(content)
        support.append(path.resolve())
    with tempfile.TemporaryDirectory(prefix='lazo-logging-') as temp:
        folder = Path(temp)
        snapshot = folder / args.jar.name
        snapshot.write_bytes(args.jar.read_bytes())
        subprocess.run([args.javac, '--release', '8', '-encoding', 'UTF-8', '-d', str(folder),
                        str(ROOT / 'tools/java/PackagedLoggingSmoke.java')], check=True)
        run = subprocess.run([args.java, '-cp', str(folder), 'PackagedLoggingSmoke', str(snapshot),
                              *map(str, support)], capture_output=True, text=True, timeout=60)
        report = {'jar': args.jar.name, 'sha256': hashlib.sha256(snapshot.read_bytes()).hexdigest(),
                  'passed': run.returncode == 0, 'output': run.stdout + run.stderr,
                  'host_slf4j': '1.7.36' if args.legacy_slf4j else '2.0.20',
                  'scope': 'Executed final-JAR LavaPlayer and Apache HTTP logging factories and their owned output boundaries'}
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(report['output'], end='')
        if run.returncode:
            raise SystemExit(run.returncode)


if __name__ == '__main__':
    main()
