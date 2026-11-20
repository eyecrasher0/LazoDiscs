#!/usr/bin/env python3
"""Build the exact advertised mod/plugin artifacts and keep a hash-bound result ledger."""
import argparse,concurrent.futures,contextlib,fcntl,hashlib,json,os,shutil,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform',nargs='+',default=['fabric','forge','neoforge','paper','bukkit','legacy-client'])
    parser.add_argument('--versions',nargs='+')
    parser.add_argument('--jobs',type=int,default=2,choices=range(1,5))
    parser.add_argument('--output-root',type=Path,default=ROOT/'build-matrix')
    parser.add_argument('--compile-only',action='store_true')
    parser.add_argument('--resume',action='store_true',help='Reuse only artifacts whose source fingerprint and JAR hash still match')
    args=parser.parse_args();manifest=json.loads((ROOT/'compatibility.json').read_text());output=args.output_root.resolve();output.mkdir(parents=True,exist_ok=True)
    targets=[]
    for platform in ['fabric','forge','neoforge']:
        for entry in manifest['entries']:
            if entry['platform']==platform:targets.append((platform,entry['minecraft'],ROOT/platform/entry['minecraft']))
    for mc in json.loads((ROOT/'paper/targets.json').read_text()):targets.append(('paper',mc,ROOT/'paper/26.3'))
    targets.append(('bukkit','26.3',ROOT/'bukkit/26.3'))
    targets.extend(('legacy-client',p.name,p) for p in sorted((ROOT/'legacy-client').iterdir()) if (p/'build.gradle').exists())
    targets=[t for t in targets if t[0] in args.platform and (args.versions is None or t[1] in args.versions)]
    if not targets:raise SystemExit('No matching release target')
    results=[];logs=output/'logs'/('compile' if args.compile_only else 'build');logs.mkdir(parents=True,exist_ok=True)
    def inputs_digest(module, platform):
        roots=[module/'src',module/'build.gradle',module/'settings.gradle',module/'gradle.properties',module/'gradle',ROOT/'gradle',ROOT/'common',ROOT/'third-party-java8',ROOT/'LICENSE']
        if platform in ['paper','bukkit']: roots += [ROOT/'fabric/26.3/src',ROOT/'paper/26.3/src',ROOT/'paper/targets.json']
        digest=hashlib.sha256()
        files=set()
        for root in roots:
            if root.is_file(): files.add(root)
            elif root.is_dir(): files.update(p for p in root.rglob('*') if p.is_file() and '__pycache__' not in p.parts)
        for file in sorted(files):
            digest.update(str(file.relative_to(ROOT)).encode());digest.update(b'\0');digest.update(file.read_bytes());digest.update(b'\0')
        return digest.hexdigest()
    def run(target):
        platform,mc,module=target;row={'platform':platform,'minecraft':mc};start=time.monotonic()
        fingerprint=inputs_digest(module,platform);row['build_inputs_sha256']=fingerprint
        result=output/'results'/('compile' if args.compile_only else 'build')/platform/(mc+'.json')
        if args.resume and result.exists():
            prior=json.loads(result.read_text())
            artifact=Path(prior.get('artifact','/missing'))
            if prior.get('status')=='pass' and prior.get('build_inputs_sha256')==fingerprint and (args.compile_only or artifact.is_file() and hashlib.sha256(artifact.read_bytes()).hexdigest()==prior.get('sha256')):
                print(json.dumps(dict(prior,reused=True)),flush=True);return prior
        command=[str(module/'gradlew'),'-p',str(module),'--project-cache-dir',str(output/'project-cache'/platform/mc),f'-PlazoOutputRoot={output}','--console=plain','--max-workers=2']
        # ForgeGradle6 caches MCP services from a closed project when a daemon is reused.
        if platform=='forge' or platform=='legacy-client' and mc.startswith('forge'):command.append('--no-daemon')
        if platform=='paper':command.append(f'-PminecraftTarget={mc}')
        command+=['compileJava'] if args.compile_only else ['build']
        if not args.compile_only and platform in ['bukkit','paper']:command+=['integrationPlugin']
        log=logs/f'{platform}-{mc}.log';row['log']=str(log)
        try:
            # Loom 1.13's layered mapping artifact has a shared cache path across MC
            # versions. Cross-project parallel writes can corrupt its POM/JAR.
            loom = platform=='fabric' or (platform=='legacy-client' and mc.startswith('fabric'))
            with contextlib.ExitStack() as resources:
                if loom:
                    lockfile=Path(os.environ.get('GRADLE_USER_HOME', str(Path.home()/'.gradle'))).expanduser()/'lazodiscs-loom.lock'
                    lockfile.parent.mkdir(parents=True,exist_ok=True)
                    lock=resources.enter_context(lockfile.open('a'))
                    fcntl.flock(lock,fcntl.LOCK_EX)
                stream=resources.enter_context(log.open('w'))
                subprocess.run(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,check=True,timeout=900)
            if inputs_digest(module,platform)!=fingerprint:raise RuntimeError('Build inputs changed while Gradle was running; rebuild this target')
            folder=output/platform/mc/'libs'
            if platform=='legacy-client':
                loader=mc.split('-')[0]
                client_mc='1.16.5' if mc.endswith('1.16.5') else '1.19-1.19.1'
                expected=f'lazodiscs-voice1-client-{loader}-1.0.8+{client_mc}.jar'
            elif platform=='bukkit': expected='lazodiscs-bukkit-1.0.8.jar'
            else: expected=f'lazodiscs-{platform}-1.0.8+{mc}.jar'
            jars=[folder/expected] if (folder/expected).is_file() else []
            if not args.compile_only and len(jars)!=1:raise RuntimeError(f'Expected one final release JAR, found {jars}')
            if jars and not args.compile_only:
                row.update(artifact=str(jars[0]),sha256=hashlib.sha256(jars[0].read_bytes()).hexdigest())
                staged=output/'release'/jars[0].name;staged.parent.mkdir(exist_ok=True)
                shutil.copy2(jars[0],staged)
            row['status']='pass'
        except Exception as error:row.update(status='fail',error=str(error))
        row['seconds']=round(time.monotonic()-start,1)
        result=output/'results'/('compile' if args.compile_only else 'build')/platform/(mc+'.json')
        result.parent.mkdir(parents=True,exist_ok=True)
        temporary=result.with_suffix(f'.{os.getpid()}.tmp');temporary.write_text(json.dumps(row,indent=2)+'\n');temporary.replace(result)
        print(json.dumps(row),flush=True);return row
    report=output/('compile-results.json' if args.compile_only else 'build-results.json')
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for row in pool.map(run,targets):
            results.append(row);report.write_text(json.dumps(results,indent=2)+'\n')
    if any(row['status']!='pass' for row in results):raise SystemExit(1)
if __name__=='__main__':main()
