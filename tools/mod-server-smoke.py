#!/usr/bin/env python3
"""Boot a final mod JAR on the real loader, persist a recorded jukebox and load local audio.

This exercises loader dependencies, Mixin application, item decoding and the server
Voice adapter. It is not a client listening or full gameplay test.
"""
import argparse,fcntl,hashlib,http.server,importlib.util,io,json,os,re,shutil,socket,subprocess,tempfile,threading,time,urllib.request,uuid,wave,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
HARNESS_SHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
HTTP_FIXTURE_SHA256=hashlib.sha256((ROOT/'paper/26.3/tools/integration.py').read_bytes()).hexdigest()
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
    cancelled_requested=threading.Event();cancelled_release=threading.Event();cancelled_responses=[]
    cold_disc=str(uuid.uuid4());cancelled_disc=str(uuid.uuid4())
    playback_discs={path:str(uuid.uuid4()) for path in ('/first.wav','/second.wav','/short.wav')}
    returned_loaders=set();retired_discs={};observed_barriers=set();playback_events={}
    barrier_message='LazoDiscs-smoke-'+uuid.uuid4().hex
    replacement_barrier='LazoDiscs-replacement-'+uuid.uuid4().hex
    response_barriers={disc:'LazoDiscs-response-'+uuid.uuid4().hex for disc in (cold_disc,cancelled_disc)}
    barriers=(barrier_message,replacement_barrier,*response_barriers.values())
    with wave.open(io.BytesIO(audio.AudioFixture.audio)) as wav:
        fixture_seconds=wav.getnframes()/wav.getframerate()
        expected_timecode=round(fixture_seconds*1000)-20
        short_pcm=wav.readframes(wav.getframerate())
        short_format=wav.getparams()
    short_wav=io.BytesIO()
    with wave.open(short_wav,'wb') as wav:
        wav.setparams(short_format)
        wav.writeframes(short_pcm)
    class Fixture(audio.AudioFixture):
        def do_GET(self):
            requested.append(self.path)
            path=self.path
            if path=='/short.wav':
                self.audio=short_wav.getvalue()
            if path=='/cancelled.wav':
                cancelled_requested.set()
                if not cancelled_release.wait(30):
                    self.send_error(504)
                    return
            if path=='/cold.wav':
                cold_requested.set()
                if not cold_release.wait(30):
                    self.send_error(504)
                    return
            if path in ('/cold.wav','/first.wav','/second.wav','/short.wav','/cancelled.wav'):
                self.path='/test.wav'
            super().do_GET()
            if path=='/cold.wav':cold_responses.append(time.monotonic())
            if path=='/cancelled.wav':cancelled_responses.append(time.monotonic())
    fixture_http=http.server.ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture_http.serve_forever,daemon=True).start()
    legacy=mc.startswith(('1.16.','1.17','1.18','1.19')) or mc in ['1.20','1.20.1','1.20.2','1.20.3','1.20.4']
    def recording(path,disc_id):
        url=f'http://127.0.0.1:{fixture_http.server_port}{path}'
        disc='{url:"'+url+'",title:"Runtime fixture",range:64,volume:1.0f,id:"'+disc_id+'"}'
        return '{id:"minecraft:music_disc_cat",Count:1b,tag:{lazodiscs:'+disc+'}}' if legacy else '{id:"minecraft:music_disc_cat",count:1,components:{"minecraft:custom_data":{lazodiscs:'+disc+'}}}'
    lines=[];completed={};ready=threading.Event();log=folder/'console.log'
    command=[args.java,'-XX:ActiveProcessorCount=2','-Xms512M','-Xmx1536M','-Dlazodiscs.testPlayback=true',*launch,'--nogui']
    print('Running',platform,mc,'log:',log,flush=True)
    process=None
    try:
        process=subprocess.Popen(command,cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        def collect():
            with log.open('w') as output:
                for line in process.stdout:
                    output.write(line);output.flush();lines.append(line)
                    if 'Done (' in line and 'For help' in line:ready.set()
                    retired_match=re.search(r'LazoDiscs smoke retired ([0-9a-f-]{36}) from empty jukebox',line)
                    if retired_match:retired_discs.setdefault(retired_match[1],len(lines))
                    returned_match=re.search(r'LazoDiscs smoke loader returned ([0-9a-f-]{36})',line)
                    if returned_match:returned_loaders.add(returned_match[1])
                    for barrier in barriers:
                        if any(speaker+' '+barrier in line for speaker in ('[Server]','<Server>')):observed_barriers.add(barrier)
                    playback=re.search(r'LazoDiscs smoke (registered source|provided frame) ([0-9a-f-]{36})',line)
                    if playback:playback_events.setdefault(playback[2],[]).append((playback[1],len(lines)))
                    finished=re.search(r'LazoDiscs smoke completed (/\w+\.wav) at (\d+) ms',line)
                    if finished:completed.setdefault(finished[1],[]).append(int(finished[2]))
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
        def assert_retired_playback():
            for disc in (cold_disc,cancelled_disc):
                retired_at=retired_discs.get(disc)
                if retired_at is not None and any(kind=='provided frame' or line>retired_at
                        for kind,line in playback_events.get(disc,())):
                    raise RuntimeError(f'Retired disc {disc} registered a source or provided audio: {log}')
        def wait_for(condition,timeout,problem):
            deadline=time.monotonic()+timeout
            while not condition() and process.poll() is None and time.monotonic()<deadline:
                assert_clean_playback();time.sleep(.05)
            assert_clean_playback()
            if not condition():raise RuntimeError(f'{problem}: {log}')
        def insert(path,disc):
            send('data merge block 0 80 0 {RecordItem:'+recording(path,disc)+'}')
        def require_playback(path,timecode):
            disc=playback_discs[path]
            insert(path,disc)
            wait_for(lambda:path in completed,30,f'Decoded playback did not complete for {path}')
            if completed[path]!=[timecode]:
                raise RuntimeError(f'Unexpected decoded timecodes for {path}: {completed[path]}, expected {[timecode]}')
            wait_for(lambda:{kind for kind,_ in playback_events.get(disc,())}=={'registered source','provided frame'},
                     5,f'Playback observer missed registration or audio for {path}')
        def cancel_held(path,disc,requested,response_gate,responses,barrier):
            insert(path,disc)
            wait_for(requested.is_set,30,f'{path} never reached the HTTP fixture')
            # Old jukebox NBT readers retain their existing item if RecordItem is absent.
            # An explicit empty compound makes every generation actually clear the slot.
            send('data modify block 0 80 0 RecordItem set value {}')
            send('say '+barrier)
            wait_for(lambda:disc in retired_discs and disc in returned_loaders and barrier in observed_barriers,
                     10,f'Server did not acknowledge retiring {path} from an empty jukebox')
            if responses:raise RuntimeError(f'{path} responded before the server retired it: {log}')
            response_gate.set()
            wait_for(lambda:bool(responses),30,f'Retired HTTP response did not return for {path}')
            response_barrier=response_barriers[disc]
            send('say '+response_barrier)
            wait_for(lambda:response_barrier in observed_barriers,10,f'Server did not acknowledge released {path}')
            # Let the late opened decoder be disposed before any new playback can warm the archive.
            settle=time.monotonic()+2
            while time.monotonic()<settle:
                assert_clean_playback();assert_retired_playback();time.sleep(.05)
            assert_retired_playback()
        send('forceload add 0 0')
        time.sleep(1)
        send('setblock 0 80 0 minecraft:jukebox')
        # The very first decoder must be cancelled cold, not after a successful track
        # has already loaded every codec/control class from Forge's shared archive.
        cancel_held('/cold.wav',cold_disc,cold_requested,cold_release,cold_responses,barrier_message)
        require_playback('/first.wav',expected_timecode)
        cancel_held('/cancelled.wav',cancelled_disc,cancelled_requested,cancelled_release,cancelled_responses,replacement_barrier)
        require_playback('/second.wav',expected_timecode)
        send('data modify block 0 80 0 RecordItem set value {}')
        require_playback('/short.wav',980)
        send('data get block 0 80 0');time.sleep(1);send('stop')
        code=process.wait(timeout=60);reader.join(timeout=5)
        if code:raise RuntimeError(f'Mod server exited {code}: {log}')
        assert_clean_playback();assert_retired_playback()
        expected_completed={'/first.wav':[expected_timecode],'/second.wav':[expected_timecode],'/short.wav':[980]}
        if completed!=expected_completed:
            raise RuntimeError(f'Expected only the three complete control playbacks: {log}')
        if hashlib.sha256(Path(__file__).read_bytes()).hexdigest()!=HARNESS_SHA256:raise RuntimeError('Harness changed during verification; rerun')
        if hashlib.sha256((ROOT/'paper/26.3/tools/integration.py').read_bytes()).hexdigest()!=HTTP_FIXTURE_SHA256:raise RuntimeError('HTTP fixture changed during verification; rerun')
        result={'platform':platform,'minecraft':mc,'sha256':digest,'voice':voice['version'],'voice_sha512':hashlib.sha512((mods/'PlasmoVoice.jar').read_bytes()).hexdigest(),'fresh_world':world,'ready':True,'audio_requests':requested,
                'harness_sha256':HARNESS_SHA256,'http_fixture_sha256':HTTP_FIXTURE_SHA256,'console_sha256':hashlib.sha256(log.read_bytes()).hexdigest(),'playback_start_line':playback_start_line,
                'startup_external_error_events':external_startup_errors,'cold_cancelled':True,'cold_requests':requested.count('/cold.wav'),
                'cold_disc':cold_disc,'command_barrier':barrier_message,'cancelled_disc':cancelled_disc,'replacement_barrier':replacement_barrier,'cancelled_requests':requested.count('/cancelled.wav'),'retired_loaders':sorted(returned_loaders),'completed_playbacks':2,'fixture_seconds':fixture_seconds,
                'response_barriers':response_barriers,'playback_discs':playback_discs,
                'expected_timecode_ms':expected_timecode,'final_timecodes_ms':completed,'exit':code,'log':str(log),
                'scope':'Real final-JAR loader startup; observed empty-slot retirement and loader return while cold/replacement HTTP responses remain held; retired UUID source/frame observations after release with three positive source/frame controls; two full natural playbacks, one short duration control and clean shutdown'}
        (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
    finally:
        cold_release.set();cancelled_release.set()
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        fixture_http.shutdown();fixture_http.server_close()
if __name__=='__main__':main()
