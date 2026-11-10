#!/usr/bin/env python3
"""Build, verify and run each advertised Paper target in a disposable local server."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

MODULE = Path(__file__).resolve().parents[1]
TARGETS = json.loads((MODULE.parent / "targets.json").read_text())


def java_home(version):
    candidates = [os.environ.get(f"JAVA_HOME_{version}_X64"), os.environ.get(f"JAVA_{version}_HOME")]
    if version == 17:
        candidates += ["/usr/lib/jvm/java-17-openjdk"]
    if version in (21, 25):
        name = "delta" if version == 21 else "epsilon"
        candidates += [str(Path.home() / f".local/share/PrismLauncher/java/java-runtime-{name}")]
    if version == 25:
        candidates += [os.environ.get("JAVA_HOME")]
    for candidate in candidates:
        if candidate and (Path(candidate) / "bin/java").is_file():
            return candidate
    raise RuntimeError(f"Set JAVA_{version}_HOME to a Java {version} installation")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--versions", nargs="+", choices=TARGETS, default=list(TARGETS))
    parser.add_argument("--plasmo", choices=("stable", "beta"), default="stable")
    parser.add_argument("--jobs", type=int, choices=range(1, 5), default=2)
    parser.add_argument("--test-only", action="store_true")
    args = parser.parse_args()
    output = MODULE / "build/matrix"
    output.mkdir(parents=True, exist_ok=True)
    build_env = dict(os.environ, JAVA_HOME=java_home(25))

    def run(version):
        row = {"minecraft": version, "plasmo": args.plasmo}
        try:
            if not args.test_only:
                with (output / f"build-{version}.log").open("w") as log:
                    subprocess.run([str(MODULE / "gradlew"), "-p", str(MODULE),
                                    "--project-cache-dir", str(MODULE / f"build/{version}/gradle-cache"), f"-PminecraftTarget={version}",
                                    "build", "integrationPlugin", "--console=plain"], env=build_env, stdout=log,
                                   stderr=subprocess.STDOUT, check=True, timeout=300)
            with (output / f"test-{version}-{args.plasmo}.log").open("w") as log:
                subprocess.run([sys.executable, str(MODULE / "tools/integration.py"), "--minecraft", version,
                                "--plasmo", args.plasmo, "--java", str(Path(java_home(TARGETS[version]["java"])) / "bin/java")],
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
            result = json.loads((MODULE / f"build/{version}/integration/result-{args.plasmo}.json").read_text())
            if result["failure"] is not None:
                raise RuntimeError(result["failure"])
            jar = MODULE / f"build/{version}/libs/lazodiscs-paper-1.0.7+{version}.jar"
            digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            if digest != result["jar_sha256"]:
                raise RuntimeError("Artifact changed after integration test")
            row.update(status="pass", passed=result["passed"], sha256=digest, artifact=str(jar))
        except Exception as error:
            row.update(status="fail", error=str(error))
        print(json.dumps(row), flush=True)
        return row

    rows = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for row in pool.map(run, args.versions):
            rows.append(row)
            (output / f"results-{args.plasmo}.json").write_text(json.dumps(rows, indent=2) + "\n")
    if any(row["status"] != "pass" for row in rows):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
