#!/usr/bin/env python3
"""Execute the Bukkit integration on each advertised, pinned Paper/Purpur/Folia target."""
import argparse,concurrent.futures,hashlib,json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def current_console(prior):
    log=prior.get('log')
    if not isinstance(log,str) or not log:return False
    try:
        path=Path(log)
        return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==prior.get('console_sha256')
    except OSError:
        return False

def current_artifacts(prior,server,server_pin,voice,voice_pin):
    if server_pin is None or not server.is_file() or not voice.is_file():return False
    server_bytes=server.read_bytes()
    return (prior.get('build')==server_pin['build']
            and hashlib.new(server_pin['algorithm'],server_bytes).hexdigest()==server_pin['checksum']
            and hashlib.sha256(server_bytes).hexdigest()==prior.get('server_sha256')
            and hashlib.sha512(voice.read_bytes()).hexdigest()==voice_pin['sha512']==prior.get('voice_sha512'))

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output-root',type=Path,default=ROOT/'build-matrix')
    p.add_argument('--work-root',type=Path,default=ROOT/'build-matrix/servers')
    p.add_argument('--java16',required=True);p.add_argument('--java17',required=True);p.add_argument('--java21',required=True);p.add_argument('--java25',required=True)
    p.add_argument('--jobs',type=int,default=2,choices=range(1,5));p.add_argument('--platform',nargs='+',default=['paper','purpur','folia'])
    p.add_argument('--versions',nargs='+');p.add_argument('--resume',action='store_true')
    args=p.parse_args();out=args.output_root.resolve();work=args.work_root.resolve()
    release=out/'bukkit/26.3/libs/lazodiscs-bukkit-1.0.8.jar';fixture=release.with_name('lazodiscs-bukkit-integration.jar')
    sha=hashlib.sha256(release.read_bytes()).hexdigest();fixture_sha=hashlib.sha256(fixture.read_bytes()).hexdigest()
    verifier_inputs={'harness_sha256':ROOT/'tools/server-integration.py',
                     'http_fixture_sha256':ROOT/'paper/26.3/tools/integration.py',
                     'config_sha256':ROOT/'bukkit/26.3/src/main/resources/config.yml'}
    verifier_inputs={key:hashlib.sha256(path.read_bytes()).hexdigest() for key,path in verifier_inputs.items()}
    server_pins=json.loads((ROOT/'server-builds.json').read_text())
    rows=[r for r in json.loads((ROOT/'compatibility.json').read_text())['entries'] if r['platform'] in args.platform and (not args.versions or r['minecraft'] in args.versions)]
    def run(row):
        platform,mc=row['platform'],row['minecraft'];result=work/platform/mc/'result.json'
        if args.resume and result.exists():
            prior=json.loads(result.read_text())
            voice=row['voice']
            cached_voice=work/'.voice'/(voice['sha512']+'.jar')
            if ('failure' in prior and prior['failure'] is None and prior.get('jar_sha256')==sha
                    and all(prior.get(key)==value for key,value in verifier_inputs.items())
                    and prior.get('fixture_sha256')==fixture_sha and prior.get('voice')==voice['version']
                    and current_console(prior)
                    and current_artifacts(prior,result.with_name('server.jar'),server_pins.get(f'{platform}/{mc}'),cached_voice,voice)):
                print(f'SKIP verified {platform}/{mc}',flush=True);return prior
        log=out/'logs/server'/f'{platform}-{mc}.log';log.parent.mkdir(parents=True,exist_ok=True)
        command=[sys.executable,str(ROOT/'tools/server-integration.py'),'--platform',platform,'--minecraft',mc,'--java',getattr(args,'java'+str(row['java'])),'--output-root',str(out),'--work-root',str(work)]
        with log.open('w') as stream:code=subprocess.run(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,timeout=360).returncode
        report=json.loads(result.read_text()) if result.exists() else {'failure':'No server fixture result'}
        if code:report['failure']=report.get('failure') or f'Driver exit {code}; inspect {log}'
        report.update(platform=platform,minecraft=mc,driver_log=str(log))
        print(json.dumps({k:report.get(k) for k in ['platform','minecraft','passed','failure','driver_log']}),flush=True);return report
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        reports=list(pool.map(run,rows))
    path=out/'verification/bukkit-server-matrix.json';path.parent.mkdir(exist_ok=True);path.write_text(json.dumps(reports,indent=2)+'\n')
    if any(r.get('failure') is not None for r in reports):raise SystemExit(1)
if __name__=='__main__':main()
