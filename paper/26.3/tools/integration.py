#!/usr/bin/env python3
"""Run the integration fixture on a disposable, loopback-only Paper server."""

import argparse
import hashlib
import http.server
import io
import json
import math
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request
import wave

MODULE = Path(__file__).resolve().parents[1]
TARGETS = json.loads((MODULE.parent / 'targets.json').read_text())
PV = {
    "stable": (
        "https://cdn.modrinth.com/data/1bZhdhsH/versions/DySKquus/PlasmoVoice-Paper-2.1.17.jar",
        "d9e21e86e52454833f6c22e7f20268c585f5ddde3e8d78ed6480668aed478e021d4e9726baf1d56e755c425885403e011f1db434b3ae1049f9daed7c0da34014",
    ),
    "beta": (
        "https://cdn.modrinth.com/data/1bZhdhsH/versions/xWPSi9tp/PlasmoVoice-Paper-2.2.0-beta.3.jar",
        "c10c6cf72e3594de6d436422fb0f29e31dcfd57d4e91f7d454c5237877bd3e85d2e03de5fb106bbfa60c448e3472aa6253bfd13d448aaa1ad6193c0dfc40cbd7",
    ),
}


def download(url, destination, algorithm, digest):
    if destination.exists() and hashlib.new(algorithm, destination.read_bytes()).hexdigest() == digest:
        return
    request = urllib.request.Request(url, headers={"User-Agent": "LazoDiscs-integration/1.0 (https://github.com/eyecrasher0/LazoDiscs)"})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read()
    if hashlib.new(algorithm, data).hexdigest() != digest:
        raise RuntimeError(f"Checksum mismatch for {url}")
    destination.write_bytes(data)


def wav_fixture():
    stream = io.BytesIO()
    with wave.open(stream, "wb") as audio:
        audio.setnchannels(2)
        audio.setsampwidth(2)
        audio.setframerate(48000)
        audio.writeframes(b"".join(struct.pack("<hh", sample, sample) for sample in
                                  (int(math.sin(i * 2 * math.pi * 440 / 48000) * 2000) for i in range(48000 * 3))))
    return stream.getvalue()


class AudioFixture(http.server.BaseHTTPRequestHandler):
    audio = wav_fixture()

    def do_GET(self):
        if self.path == "/missing.wav":
            # Keep the loading state observable even when a local 404 is extremely fast.
            time.sleep(0.5)
        if self.path == "/slow.wav":
            time.sleep(4)
        if self.path == "/stall.wav":
            time.sleep(20)
        if self.path not in ("/test.wav", "/slow.wav", "/stall.wav"):
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(self.audio)))
        self.end_headers()
        try:
            self.wfile.write(self.audio)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", choices=TARGETS, default="26.3")
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java")
    parser.add_argument("--work-dir", type=Path, default=None)
    parser.add_argument("--plasmo", choices=PV, default="stable")
    args = parser.parse_args()
    target = TARGETS[args.minecraft]
    cache = (args.work_dir or MODULE / "build" / args.minecraft / "integration").resolve()
    cache.mkdir(parents=True, exist_ok=True)
    download(target["download"]["url"], cache / "paper.jar", "sha256", target["download"]["checksums"]["sha256"])
    url, digest = PV[args.plasmo]
    download(url, cache / f"pv-{args.plasmo}.jar", "sha512", digest)
    release = MODULE / f"build/{args.minecraft}/libs/lazodiscs-paper-1.0.7+{args.minecraft}.jar"
    fixture = MODULE / f"build/{args.minecraft}/libs/lazodiscs-paper-integration.jar"
    if not release.is_file() or not fixture.is_file():
        raise RuntimeError("Run ./gradlew build integrationPlugin first")
    run = Path(tempfile.mkdtemp(prefix=f"run-{args.plasmo}-", dir=cache))
    plugins = run / "plugins"
    (plugins / "LazoDiscs").mkdir(parents=True)
    (plugins / "PlasmoVoice").mkdir()
    for source, name in ((release, "LazoDiscs.jar"), (fixture, "LazoDiscsIntegration.jar"), (cache / f"pv-{args.plasmo}.jar", "PlasmoVoice.jar")):
        shutil.copy2(source, plugins / name)
    tested_hash = hashlib.sha256((plugins / "LazoDiscs.jar").read_bytes()).hexdigest()
    shutil.copy2(cache / "paper.jar", run / "paper.jar")
    # Reuse Paper's downloaded vanilla server and libraries, never its worlds/configuration.
    for name in ("cache", "libraries", "versions"):
        if (cache / name).exists():
            (run / name).symlink_to(cache / name, target_is_directory=True)
    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]
    (run / "eula.txt").write_text("eula=true\n")
    generator = json.dumps({"layers": [{"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:dirt", "height": 2},
                                       {"block": "minecraft:grass_block", "height": 1}], "biome": "minecraft:plains"}, separators=(",", ":"))
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\n"
        f"level-type=minecraft:flat\ngenerator-settings={generator}\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\n"
        "spawn-protection=0\nmax-players=4\npause-when-empty-seconds=-1\n"
    )
    config = (MODULE / "src/main/resources/config.yml").read_text().replace("allowHttp: false", "allowHttp: true")
    (plugins / "LazoDiscs/config.yml").write_text(config)
    (plugins / "PlasmoVoice/config.toml").write_text('[host]\nip = "127.0.0.1"\nport = 0\n')
    http_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), AudioFixture)
    threading.Thread(target=http_server.serve_forever, daemon=True).start()
    result = run / "result.json"
    command = [args.java, "-Xms512M", "-Xmx1536M", "-Dterminal.jline=false", "-Dterminal.ansi=false",
               f"-Dlazodiscs.testAudioUrl=http://127.0.0.1:{http_server.server_port}/test.wav",
               f"-Dlazodiscs.testResult={result}", "-jar", "paper.jar", "--nogui"]
    print(f"Running Paper {args.minecraft} + Plasmo {args.plasmo}; log: {run / 'console.log'}", flush=True)
    try:
        with (run / "console.log").open("w") as log:
            server = subprocess.Popen(command, cwd=run, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
            try:
                code = server.wait(timeout=240)
            except subprocess.TimeoutExpired:
                server.terminate()
                try:
                    server.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
                raise RuntimeError(f"Integration timed out; inspect {run / 'console.log'}")
        for name in ("cache", "libraries", "versions"):
            if not (cache / name).exists() and (run / name).is_dir() and not (run / name).is_symlink():
                shutil.copytree(run / name, cache / name)
        if code != 0 or not result.exists():
            raise RuntimeError(f"Paper exited {code} without a test result; inspect {run / 'console.log'}")
        report = json.loads(result.read_text())
        if hashlib.sha256(release.read_bytes()).hexdigest() != tested_hash:
            raise RuntimeError("Release JAR changed while its copied test instance was running")
        report.update(paper=f"{args.minecraft}-{target['build']}", plasmo=args.plasmo, log=str(run / "console.log"), jar_sha256=tested_hash)
        (cache / f"result-{args.plasmo}.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))
        if report["failure"] is not None:
            raise SystemExit(1)
    finally:
        http_server.shutdown()
        http_server.server_close()


if __name__ == "__main__":
    main()
