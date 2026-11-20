#!/usr/bin/env python3
"""Execute private Gson/Jackson round trips from a final JAR on the selected JVM."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='lazo-json-') as directory:
        directory = Path(directory)
        snapshot = directory / args.jar.name
        snapshot.write_bytes(args.jar.read_bytes())
        subprocess.run([args.javac, '--release', '8', '-encoding', 'UTF-8', '-d', str(directory),
                        str(ROOT / 'tools/java/PackagedJsonSmoke.java')], check=True)
        run = subprocess.run([args.java, '-cp', str(directory) + ':' + str(snapshot),
                              'PackagedJsonSmoke', str(snapshot)], capture_output=True, text=True, timeout=60)
        report = {'jar': args.jar.name, 'sha256': hashlib.sha256(snapshot.read_bytes()).hexdigest(),
                  'passed': run.returncode == 0, 'output': run.stdout + run.stderr}
        args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(report['output'], end='')
        if run.returncode:
            raise SystemExit(run.returncode)


if __name__ == '__main__':
    main()
