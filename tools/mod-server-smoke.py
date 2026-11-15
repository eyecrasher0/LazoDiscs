#!/usr/bin/env python3
"""Boot a final mod JAR on the real loader, persist a recorded jukebox and load local audio.

This exercises loader dependencies, Mixin application, item decoding and the server
Voice adapter. It is not a client listening or full gameplay test.
"""
import argparse,fcntl,hashlib,http.server,importlib.util,json,os,re,shutil,socket,subprocess,tempfile,threading,time,urllib.request,uuid,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('audio_fixture',ROOT/'paper/26.3/tools/integration.py');audio=importlib.util.module_from_spec(spec);spec.loader.exec_module(audio)
def fetch(url,path):
    if not path.exists():
        path.parent.mkdir(parents=True,exist_ok=True)
        request=urllib.request.Request(url,headers={'User-Agent':'LazoDiscs/1.0.8 (https://github.com/eyecrasher0/LazoDiscs)'})
        with urllib.request.urlopen(request,timeout=90) as r:path.write_bytes(r.read())
    return path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform',choices=['fabric','forge','neoforge'],required=True);parser.add_argument('--minecraft',required=True)
    parser.add_argument('--java',required=True);parser.add_argument('--installer-java',default=None)
    parser.add_argument('--output-root',type=Path,default=ROOT/'build-matrix');parser.add_argument('--work-root',type=Path,default=ROOT/'build-matrix/mod-servers')
    args=parser.parse_args();mc=args.minecraft;platform=args.platform
    target=next(r for r in json.loads((ROOT/'compatibility.json').read_text())['entries'] if (r['platform'],r['minecraft'])==(platform,mc))
    folder=(args.work_root/platform/mc).resolve();folder.mkdir(parents=True,exist_ok=True)
    lock=(folder/'test.lock').open('a')
    fcntl.flock(lock,fcntl.LOCK_EX | fcntl.LOCK_NB)
    if (folder/'result.json').exists():
        (folder/'result.json').replace(folder/'result-previous.json')
    module=ROOT/platform/mc;build=(module/'build.gradle').read_text();installer_java=args.installer_java or args.java
    run=folder/'server';run.mkdir(exist_ok=True)
    if platform=='fabric':
        version=re.search(r'net.fabricmc:fabric-loader:([^\']+)',build)[1]
        installer=fetch('https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar',folder/'installer.jar')
        marker=run/'fabric-server-launch.jar'
        command=[installer_java,'-jar',str(installer),'server','-mcversion',mc,'-loader',version,'-downloadMinecraft','-dir',str(run)]
        launch=['-jar',str(marker)]
    else:
        if platform=='forge':
            version=re.search(r"net.minecraftforge:forge:([^']+)",build)[1];group='net/minecraftforge/forge';url=f'https://maven.minecraftforge.net/{group}/{version}/forge-{version}-installer.jar'
        else:
            version=re.search(r"neoForge\s*\{.*?version\s*=\s*'([^']+)'",build,re.S)[1];group='net/neoforged/neoforge';url=f'https://maven.neoforged.net/releases/{group}/{version}/neoforge-{version}-installer.jar'
        installer=fetch(url,folder/'installer.jar');marker=run/'libraries'/group/version/'unix_args.txt'
        command=[installer_java,'-jar',str(installer),'--installServer',str(run)]
        launch=['@'+str(marker)]
        if mc=='1.16.5':marker=run/f'forge-{version}.jar';launch=['-jar',str(marker)]
    if not marker.exists():
        with (folder/'install.log').open('w') as log:subprocess.run(command,cwd=folder,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=600)
    if not marker.is_file():raise RuntimeError(f'Loader installation failed: {marker}')
    mods=run/'mods';mods.mkdir(exist_ok=True)
    artifact=args.output_root.resolve()/'release'/f'lazodiscs-{platform}-1.0.8+{mc}.jar'
    artifact_bytes=artifact.read_bytes();(mods/'LazoDiscs.jar').write_bytes(artifact_bytes);digest=hashlib.sha256(artifact_bytes).hexdigest()
    voice=target['voice'];audio.download(voice['url'],mods/'PlasmoVoice.jar','sha512',voice['sha512'])
    if platform=='fabric':
        api=json.loads((ROOT/'fabric-api-lock.json').read_text())[mc]
        audio.download(api['url'],mods/'FabricApi.jar','sha512',api['sha512'])
    (run/'eula.txt').write_text('eula=true\n')
    with socket.socket() as available:available.bind(('127.0.0.1',0));port=available.getsockname()[1]
    # A reused recorded world preloads mod classes and can hide first-load races.
    world='fixture-'+uuid.uuid4().hex
    (run/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nlevel-name={world}\nonline-mode=false\nenforce-secure-profile=false\nlevel-type=flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nallow-nether=false\npause-when-empty-seconds=-1\n')
    config=run/'config/lazodiscs';config.mkdir(parents=True,exist_ok=True)
    (config/'config.toml').write_text('[security]\nallowHttp = true\nallowHttps = true\nallowedDomains = []\n')
    if voice['generation']==1:
        pv=run/'config/PlasmoVoice';pv.mkdir(exist_ok=True)
        with zipfile.ZipFile(mods/'PlasmoVoice.jar') as archive:settings=archive.read('server.yml').decode().replace('0.0.0.0','127.0.0.1')
        (pv/'server.yml').write_text(settings)
    else:

        pv=run/'config/plasmovoice';pv.mkdir(exist_ok=True);(pv/'server.toml').write_text('[host]\nip = "127.0.0.1"\nport = 0\n')
    requested=[]
    class Fixture(audio.AudioFixture):
        def do_GET(self):requested.append(self.path);super().do_GET()
    fixture_http=http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture_http.serve_forever,daemon=True).start()
    url=f'http://127.0.0.1:{fixture_http.server_port}/test.wav';disc='{url:"'+url+'",title:"Runtime fixture",range:64,volume:1.0f,id:"'+str(uuid.uuid4())+'"}'
    legacy=mc.startswith(('1.16.','1.17','1.18','1.19')) or mc in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4']
    item='{id:"minecraft:music_disc_cat",Count:1b,tag:{lazodiscs:'+disc+'}}' if legacy else '{id:"minecraft:music_disc_cat",count:1,components:{"minecraft:custom_data":{lazodiscs:'+disc+'}}}'
    lines=[];ready=threading.Event();log=folder/'console.log'
    command=[args.java,'-XX:ActiveProcessorCount=2','-Xms512M','-Xmx1536M',*launch,'--nogui']
    print('Running',platform,mc,'log:',log,flush=True)
    process=None
    try:
        process=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        def collect():
            with log.open('w') as output:
                for line in process.stdout:
                    output.write(line);output.flush();lines.append(line)
                    if 'Done (' in line and 'For help' in line:ready.set()
        reader=threading.Thread(target=collect,daemon=True);reader.start()
        deadline=time.monotonic()+180
        while not ready.is_set() and process.poll() is None and time.monotonic()<deadline:ready.wait(.25)
        if not ready.is_set():raise RuntimeError(f'Loader did not reach ready state: {log}')
        def send(command):process.stdin.write(command+'\n');process.stdin.flush()
        send('forceload add 0 0')
        time.sleep(1)
        send('setblock 0 80 0 minecraft:jukebox')
        send('data merge block 0 80 0 {RecordItem:'+item+'}')
        deadline=time.monotonic()+50
        while '/test.wav' not in requested and process.poll() is None and time.monotonic()<deadline:time.sleep(.1)
        if '/test.wav' not in requested:raise RuntimeError(f'Recorded item did not request local audio: {log}')
        time.sleep(5);send('data get block 0 80 0');time.sleep(1);send('stop')
        code=process.wait(timeout=60);reader.join(timeout=5)
        if code:raise RuntimeError(f'Mod server exited {code}: {log}')
        result={'platform':platform,'minecraft':mc,'sha256':digest,'voice':voice['version'],'fresh_world':world,'ready':True,'audio_requests':requested,'exit':code,'log':str(log),'scope':'Real final-JAR loader/Mixin startup and first persisted jukebox HTTP audio load in a fresh world; no client listening assertion'}
        (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        fixture_http.shutdown();fixture_http.server_close()
if __name__=='__main__':main()
