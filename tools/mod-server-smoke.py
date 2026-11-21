#!/usr/bin/env python3
"""Boot a final mod JAR on the real loader, persist a recorded jukebox and load local audio.

This exercises loader dependencies, Mixin application, item decoding and the server
Voice adapter. It is not a client listening or full gameplay test.
"""
import argparse,fcntl,hashlib,http.server,importlib.util,io,json,os,re,shutil,socket,subprocess,tempfile,threading,time,urllib.request,uuid,wave,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
HARNESS_SHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
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
    module=ROOT/platform/mc;build=(module/'build.gradle').read_text()
    installer_java=args.installer_java or (str(Path(os.environ['JAVA_17_HOME'])/'bin/java')
        if platform=='forge' and os.environ.get('JAVA_17_HOME') else args.java)
    run=folder/'server';run.mkdir(exist_ok=True)
    if platform=='fabric':
        version=re.search(r'net.fabricmc:fabric-loader:([^\']+)',build)[1]
        installer=fetch('https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar',folder/'fabric-installer-1.1.2.jar')
        marker=run/'fabric-server-launch.jar'
        command=[installer_java,'-jar',str(installer),'server','-mcversion',mc,'-loader',version,'-downloadMinecraft','-dir',str(run)]
        launch=['-jar',str(marker)]
    else:
        if platform=='forge':
            version=re.search(r"net.minecraftforge:forge:([^']+)",build)[1];group='net/minecraftforge/forge';url=f'https://maven.minecraftforge.net/{group}/{version}/forge-{version}-installer.jar'
        else:
            version=re.search(r"neoForge\s*\{.*?version\s*=\s*'([^']+)'",build,re.S)[1];group='net/neoforged/neoforge';url=f'https://maven.neoforged.net/releases/{group}/{version}/neoforge-{version}-installer.jar'
        installer=fetch(url,folder/('installer-'+version+'.jar'));marker=run/'libraries'/group/version/'unix_args.txt'
        command=[installer_java,'-jar',str(installer),'--installServer',str(run)]
        launch=['@'+str(marker)]
        if mc=='1.16.5':marker=run/f'forge-{version}.jar';launch=['-jar',str(marker)]
    receipt=folder/'install-success.json'
    expected_install={'platform':platform,'minecraft':mc,'loader':version,'installer_sha256':hashlib.sha256(installer.read_bytes()).hexdigest()}
    prior_install=json.loads(receipt.read_text()) if receipt.exists() else None
    # Launch arguments are extracted before the installer verifies/remaps its final JARs.
    if not marker.exists() or prior_install!=expected_install:
        with (folder/'install.log').open('w') as log:subprocess.run(command,cwd=folder,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=600)
        if marker.is_file():receipt.write_text(json.dumps(expected_install,indent=2)+'\n')
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
    requested=[];cold_requested=threading.Event();cold_release=threading.Event();cold_responses=[]
    class Fixture(audio.AudioFixture):
        def do_GET(self):
            requested.append(self.path)
            if self.path=='/cold.wav':
                cold_requested.set()
                if not cold_release.wait(30):self.send_error(504);return
                self.path='/test.wav'
                try:super().do_GET()
                finally:cold_responses.append(time.monotonic())
            else:super().do_GET()
    fixture_http=http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture_http.serve_forever,daemon=True).start()
    url=f'http://127.0.0.1:{fixture_http.server_port}/test.wav';disc='{url:"'+url+'",title:"Runtime fixture",range:64,volume:1.0f,id:"'+str(uuid.uuid4())+'"}'
    legacy=mc.startswith(('1.16.','1.17','1.18','1.19')) or mc in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4']
    item='{id:"minecraft:music_disc_cat",Count:1b,tag:{lazodiscs:'+disc+'}}' if legacy else '{id:"minecraft:music_disc_cat",count:1,components:{"minecraft:custom_data":{lazodiscs:'+disc+'}}}'
    with wave.open(io.BytesIO(Fixture.audio)) as wav:fixture_seconds=wav.getnframes()/wav.getframerate()
    lines=[];completed=[];ready=threading.Event();log=folder/'console.log'
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
                    if "Finished LazoDisc 'Runtime fixture'" in line and '(track-ended)' in line:completed.append(time.monotonic())
        reader=threading.Thread(target=collect,daemon=True);reader.start()
        deadline=time.monotonic()+180
        while not ready.is_set() and process.poll() is None and time.monotonic()<deadline:ready.wait(.25)
        if not ready.is_set():raise RuntimeError(f'Loader did not reach ready state: {log}')
        playback_start_line=len(lines)
        # Optional native-transport discovery can log host errors before playback. Reject
        # startup errors in our own audio/plugin code, without confusing Linux's expected
        # macOS KQueue unavailability with a failure to play a disc.
        failure_tokens=['Exception in thread','NoClassDefFoundError','NoSuchMethodError','Encountered an unexpected exception',
                        'Failed to start streaming LazoDisc','Failed to provide LazoDisc',"Could not play '",'FriendlyException:','[LAZO-DIAG]']
        startup=re.sub(r'\x1b\[[0-9;]*m','',''.join(lines[:playback_start_line]))
        events=re.split(r'(?m)(?=^(?:\[\d\d:\d\d:\d\d|\d{4}-\d\d-\d\dT|[A-Z][a-z]{2} \d{1,2}, \d{4}))',startup)
        external_startup_errors=0
        for event in events:
            if not any(token in event for token in failure_tokens):continue
            if any(owner in event for owner in ['com.eyecrasher.lazodiscs.','com/eyecrasher/lazodiscs/','com.sedmelluq.discord.lavaplayer.','dev.lavalink.youtube.']):
                raise RuntimeError(f'Plugin/audio startup emitted an error: {log}')
            external_startup_errors+=1
        def send(command):process.stdin.write(command+'\n');process.stdin.flush()
        def assert_clean_playback():
            output=''.join(lines[playback_start_line:])
            if any(token in output for token in failure_tokens):raise RuntimeError(f'Playback emitted a runtime error: {log}')
        def wait_for_completion(count,started):
            deadline=time.monotonic()+max(30,fixture_seconds+15)
            while len(completed)<count and process.poll() is None and time.monotonic()<deadline:
                assert_clean_playback();time.sleep(.05)
            assert_clean_playback()
            if len(completed)<count:raise RuntimeError(f'Decoded playback did not complete: {log}')
            if completed[count-1]-started<fixture_seconds*.75:raise RuntimeError(f'Playback ended before the fixture could be heard: {log}')
        send('forceload add 0 0')
        time.sleep(1)
        send('setblock 0 80 0 minecraft:jukebox')
        # The very first decoder must be cancelled cold, not after a successful track
        # has already loaded every codec/control class from Forge's shared archive.
        cold_item=item.replace('/test.wav','/cold.wav').replace('Runtime fixture','Cold cancelled fixture')
        cold_started=time.monotonic();send('data merge block 0 80 0 {RecordItem:'+cold_item+'}')
        deadline=time.monotonic()+30
        while not cold_requested.is_set() and process.poll() is None and time.monotonic()<deadline:
            assert_clean_playback();time.sleep(.05)
        if not cold_requested.is_set():raise RuntimeError(f'Cold audio load never reached the fixture: {log}')
        # Old jukebox NBT readers retain their existing item if RecordItem is absent.
        # An explicit empty compound makes every generation actually clear the slot.
        send('data modify block 0 80 0 RecordItem set value {}')
        # Allow the server to process removal while the resolver is still held in HTTP.
        time.sleep(.2);cold_cancelled=time.monotonic();cold_release.set()
        deadline=time.monotonic()+30
        while len(cold_responses)<2 and process.poll() is None and time.monotonic()<deadline:
            assert_clean_playback();time.sleep(.05)
        if len(cold_responses)<2:raise RuntimeError(f'Cancelled cold decoder did not finish its HTTP load: {log}')
        # Let the late opened decoder be disposed before any successful playback can
        # warm the class archive and hide an interruption-induced read failure.
        settle=time.monotonic()+2
        while time.monotonic()<settle:assert_clean_playback();time.sleep(.05)
        first_started=time.monotonic();send('data merge block 0 80 0 {RecordItem:'+item+'}')
        deadline=time.monotonic()+50
        while '/test.wav' not in requested and process.poll() is None and time.monotonic()<deadline:
            assert_clean_playback();time.sleep(.1)
        if '/test.wav' not in requested:raise RuntimeError(f'Recorded item did not request local audio: {log}')
        wait_for_completion(1,first_started)
        cancelled=item.replace('Runtime fixture','Cancelled fixture')
        send('data merge block 0 80 0 {RecordItem:'+cancelled+'}')
        send('data modify block 0 80 0 RecordItem set value {}')
        second_started=time.monotonic();send('data merge block 0 80 0 {RecordItem:'+item+'}')
        wait_for_completion(2,second_started)
        send('data get block 0 80 0');time.sleep(1);send('stop')
        code=process.wait(timeout=60);reader.join(timeout=5)
        if code:raise RuntimeError(f'Mod server exited {code}: {log}')
        assert_clean_playback()
        if hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=HARNESS_SHA256:raise RuntimeError('Harness changed during verification; rerun')
        result={'platform':platform,'minecraft':mc,'sha256':digest,'voice':voice['version'],'fresh_world':world,'ready':True,'audio_requests':requested,
                'harness_sha256':HARNESS_SHA256,'console_sha256':hashlib.sha256(log.read_bytes()).hexdigest(),'playback_start_line':playback_start_line,
                'startup_external_error_events':external_startup_errors,'cold_cancelled':cold_started<cold_cancelled<first_started,'cold_requests':requested.count('/cold.wav'),
                'completed_playbacks':len(completed),'fixture_seconds':fixture_seconds,'first_playback_seconds':completed[0]-first_started,
                'second_playback_seconds':completed[1]-second_started,'exit':code,'log':str(log),
                'scope':'Real final-JAR loader startup, in-flight cancellation of the first cold decoder before successful playback, two timed complete decoded jukebox playbacks with rapid replacement/extraction, no plugin/audio startup or playback errors, and clean shutdown; no client listening assertion'}
        (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
    finally:
        cold_release.set()
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        fixture_http.shutdown();fixture_http.server_close()
if __name__=='__main__':main()
