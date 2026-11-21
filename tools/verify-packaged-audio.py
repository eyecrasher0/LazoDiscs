#!/usr/bin/env python3
"""Decode WAV, resample, encode/decode Opus and execute bundled libraries on the target JVM."""
import argparse,hashlib,json,os,subprocess,tempfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,required=True);p.add_argument('--java',type=Path,required=True)
    p.add_argument('--javac',default='javac');p.add_argument('--version',required=True)
    p.add_argument('--report',type=Path,required=True)
    p.add_argument('--cache-root',type=Path,default=ROOT/'build-matrix/verification-cache/audio-host')
    args=p.parse_args();args.report.parent.mkdir(parents=True,exist_ok=True)
    # Minecraft supplies Log4j. The standalone probe supplies that same public
    # host API so Java 8 can resolve AudioLogs' legacy logger method signatures.
    host=args.cache_root/'log4j-api-2.26.1.jar';host.parent.mkdir(parents=True,exist_ok=True)
    expected='f1810a4704ccce019d02bba029dc02a4f2ac0c997f647b465e7d409c1927f822'
    if not host.is_file() or hashlib.sha256(host.read_bytes()).hexdigest()!=expected:
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/apache/logging/log4j/log4j-api/2.26.1/log4j-api-2.26.1.jar',timeout=60) as response:content=response.read()
        if hashlib.sha256(content).hexdigest()!=expected:raise RuntimeError('Log4j host API checksum mismatch')
        host.write_bytes(content)
    with tempfile.TemporaryDirectory(prefix='lazo-audio-') as temp:
        temp=Path(temp);snapshot=temp/args.jar.name;snapshot.write_bytes(args.jar.read_bytes())
        subprocess.run([args.javac,'--release',('8' if args.version=='1.8' else args.version),'-cp',str(snapshot),'-d',str(temp),str(ROOT/'tools/java/LegacyPackagedAudioSmoke.java')],check=True)
        classpath=os.pathsep.join(map(str,[temp,snapshot,host.resolve()]))
        run=subprocess.run([str(args.java),'-cp',classpath,'LegacyPackagedAudioSmoke',args.jar.name,str(snapshot),args.version],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
        args.report.with_suffix('.log').write_text(run.stdout)
        result={'jar':args.jar.name,'sha256':hashlib.sha256(snapshot.read_bytes()).hexdigest(),'java':args.version,'passed':run.returncode==0,'host_log4j_api_sha256':expected,'output':run.stdout}
        args.report.write_text(json.dumps(result,indent=2)+'\n');print(run.stdout)
        if run.returncode:raise SystemExit(run.returncode)
if __name__=='__main__':main()
