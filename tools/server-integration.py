#!/usr/bin/env python3
"""Run the Bukkit fixture against checksum-pinned, disposable upstream servers."""
import argparse, hashlib, http.server, importlib.util, json, os
from pathlib import Path
import shutil, socket, subprocess, tempfile, threading, zipfile
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('paper_fixture', ROOT/'paper/26.3/tools/integration.py')
fixture_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture_module)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=['paper','purpur','folia','spigot'], required=True)
    parser.add_argument('--minecraft', required=True)
    parser.add_argument('--java', default=str(Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else 'java')
    parser.add_argument('--output-root', type=Path, default=ROOT/'build-matrix')
    parser.add_argument('--work-root', type=Path, default=ROOT/'.agents/server-tests')
    parser.add_argument('--server-jar', type=Path, help='Locally built Spigot server (BuildTools); required for Spigot')
    harness_sha=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    http_sha=hashlib.sha256((ROOT/'paper/26.3/tools/integration.py').read_bytes()).hexdigest()
    config_sha=hashlib.sha256((ROOT/'bukkit/26.3/src/main/resources/config.yml').read_bytes()).hexdigest()
    args = parser.parse_args()
    entry = next(r for r in json.loads((ROOT/'compatibility.json').read_text())['entries'] if (r['platform'],r['minecraft'])==(args.platform,args.minecraft))
    key = f'{args.platform}/{args.minecraft}'
    cache = (args.work_root/key).resolve(); cache.mkdir(parents=True,exist_ok=True)
    if args.platform=='spigot':
        if not args.server_jar: parser.error('--server-jar is required for Spigot')
        server_jar = args.server_jar.resolve(); pin = {'build':'local-BuildTools','checksum':hashlib.sha256(server_jar.read_bytes()).hexdigest()}
    else:
        pin = json.loads((ROOT/'server-builds.json').read_text())[key]
        server_jar = cache/'server.jar'
        fixture_module.download(pin['url'], server_jar, pin['algorithm'], pin['checksum'])
    voice = entry['voice']; voice_jar = args.work_root.resolve()/'.voice'/(voice['sha512']+'.jar')
    voice_jar.parent.mkdir(parents=True,exist_ok=True)
    fixture_module.download(voice['url'], voice_jar, 'sha512', voice['sha512'])
    release = args.output_root.resolve()/'bukkit/26.3/libs/lazodiscs-bukkit-1.0.8.jar'
    fixture = release.with_name('lazodiscs-bukkit-integration.jar')
    if not release.is_file() or not fixture.is_file(): raise RuntimeError('Build the Bukkit release and integrationPlugin first')
    fast_root=os.environ.get('LAZO_TEST_RUN_ROOT')
    if fast_root:Path(fast_root).mkdir(parents=True,exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='run-',dir=fast_root or cache)); plugins=run/'plugins'
    saved_run=cache/run.name if fast_root else run
    (plugins/'LazoDiscs').mkdir(parents=True); (plugins/'PlasmoVoice').mkdir()
    # Share immutable, content-addressed JAR pages across disposable servers.
    # This avoids repeatedly reading the same shaded library classes from disk.
    artifacts=args.work_root.resolve()/'.artifacts';artifacts.mkdir(exist_ok=True)
    for source,name in [(release,'LazoDiscs.jar'),(fixture,'LazoDiscsIntegration.jar'),(voice_jar,'PlasmoVoice.jar')]:
        content=source.read_bytes();digest=hashlib.sha256(content).hexdigest();snapshot=artifacts/(digest+'.jar')
        if not snapshot.exists():
            temporary=snapshot.with_suffix(f'.{os.getpid()}.tmp');temporary.write_bytes(content);temporary.replace(snapshot)
        if fast_root: shutil.copy2(snapshot,plugins/name)
        else: os.link(snapshot,plugins/name)
    tested_hash=hashlib.sha256((plugins/'LazoDiscs.jar').read_bytes()).hexdigest()
    tested_fixture_hash=hashlib.sha256((plugins/'LazoDiscsIntegration.jar').read_bytes()).hexdigest()
    shutil.copy2(server_jar,run/'server.jar')
    tested_server_hash=hashlib.sha256((run/'server.jar').read_bytes()).hexdigest()
    tested_voice_hash=hashlib.sha512((plugins/'PlasmoVoice.jar').read_bytes()).hexdigest()
    if tested_voice_hash!=voice['sha512']:raise RuntimeError('Staged Voice JAR failed its pinned checksum')
    for name in ['cache','libraries','versions']:
        if (cache/name).exists():
            if fast_root:shutil.copytree(cache/name,run/name)
            else:(run/name).symlink_to(cache/name,target_is_directory=True)
    with socket.socket() as available:
        available.bind(('127.0.0.1',0)); port=available.getsockname()[1]
    (run/'eula.txt').write_text('eula=true\n')
    generator=json.dumps({'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:dirt','height':2},{'block':'minecraft:grass_block','height':1}],'biome':'minecraft:plains','structures':{}},separators=(',',':'))
    (run/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nallow-nether=false\nlevel-type=flat\ngenerator-settings={generator}\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\nmax-players=4\npause-when-empty-seconds=-1\n')
    (run/'bukkit.yml').write_text('settings:\n  allow-end: false\n')
    config=(ROOT/'bukkit/26.3/src/main/resources/config.yml').read_text().replace('allowHttp: false','allowHttp: true')
    (plugins/'LazoDiscs/config.yml').write_text(config)
    if voice['generation']==1:
        with zipfile.ZipFile(voice_jar) as archive: config=archive.read('config.yml').decode().replace('0.0.0.0','127.0.0.1')
        (plugins/'PlasmoVoice/config.yml').write_text(config)
    else: (plugins/'PlasmoVoice/config.toml').write_text('[host]\nip = "127.0.0.1"\nport = 0\n')
    controls=run/'http-controls';controls.mkdir()
    fixture_module.AudioFixture.controls=controls
    http_server=http.server.ThreadingHTTPServer(('127.0.0.1',0),fixture_module.AudioFixture)
    threading.Thread(target=http_server.serve_forever,daemon=True).start()
    result=run/'result.json'
    command=[args.java,'-XX:ActiveProcessorCount=2','-Xms512M','-Xmx1536M','-Dterminal.jline=false','-Dterminal.ansi=false',f'-Dlazodiscs.testHttpControl={controls}',f'-Dlazodiscs.testAudioUrl=http://127.0.0.1:{http_server.server_port}/test.wav',f'-Dlazodiscs.testResult={result}','-jar','server.jar','--nogui']
    print(f'Running {key} build {pin["build"]}, PV {voice["version"]}: {run / "console.log"}',flush=True)
    try:
        with (run/'console.log').open('w') as log:
            process=subprocess.Popen(command,cwd=run,stdout=log,stderr=subprocess.STDOUT,stdin=subprocess.DEVNULL)
            try: code=process.wait(timeout=240)
            except subprocess.TimeoutExpired:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
                raise RuntimeError(f'Timed out: {run / "console.log"}')
        for name in ['cache','libraries','versions']:
            folder=run/name
            if not (cache/name).exists() and folder.is_dir() and not folder.is_symlink():
                shutil.move(str(folder),str(cache/name)); folder.symlink_to(cache/name,target_is_directory=True)
        if code!=0 or not result.is_file(): raise RuntimeError(f'Server exit {code}, no fixture result: {run / "console.log"}')
        report=json.loads(result.read_text())
        if hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=harness_sha or hashlib.sha256((ROOT/'paper/26.3/tools/integration.py').read_bytes()).hexdigest()!=http_sha:
            raise RuntimeError('Verifier inputs changed during integration; rerun')
        if hashlib.sha256(fixture.read_bytes()).hexdigest()!=tested_fixture_hash or hashlib.sha256((ROOT/'bukkit/26.3/src/main/resources/config.yml').read_bytes()).hexdigest()!=config_sha:
            raise RuntimeError('Fixture/config inputs changed during integration; rerun')
        if hashlib.sha256(release.read_bytes()).hexdigest()!=tested_hash: raise RuntimeError('Release JAR changed during integration')
        report.update(harness_sha256=harness_sha,http_fixture_sha256=http_sha,config_sha256=config_sha,console_sha256=hashlib.sha256((run/'console.log').read_bytes()).hexdigest(),platform=args.platform,minecraft=args.minecraft,build=pin['build'],voice=voice['version'],jar_sha256=tested_hash,fixture_sha256=tested_fixture_hash,server_sha256=tested_server_hash,voice_sha512=tested_voice_hash,log=str(saved_run/'console.log'))
        (cache/'result.json').write_text(json.dumps(report,indent=2)+'\n'); print(json.dumps(report,indent=2))
        if report['failure'] is not None: raise SystemExit(1)
    finally:
        http_server.shutdown(); http_server.server_close()
        if fast_root:
            saved_run.mkdir(exist_ok=True)
            for name in ['console.log','result.json','server.properties','bukkit.yml']:
                if (run/name).is_file():shutil.copy2(run/name,saved_run/name)
            shutil.rmtree(run)
if __name__=='__main__': main()
