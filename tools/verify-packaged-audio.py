#!/usr/bin/env python3
"""Decode WAV, resample, encode/decode Opus and execute bundled libraries on the target JVM."""
import argparse,hashlib,json,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,required=True);p.add_argument('--java',type=Path,required=True)
    p.add_argument('--javac',default='javac');p.add_argument('--version',required=True)
    p.add_argument('--report',type=Path,required=True)
    args=p.parse_args();args.report.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='lazo-audio-') as temp:
        temp=Path(temp);snapshot=temp/args.jar.name;snapshot.write_bytes(args.jar.read_bytes())
        subprocess.run([args.javac,'--release',('8' if args.version=='1.8' else args.version),'-cp',str(snapshot),'-d',str(temp),str(ROOT/'tools/java/LegacyPackagedAudioSmoke.java')],check=True)
        run=subprocess.run([str(args.java),'-cp',f'{temp}:{snapshot}','LegacyPackagedAudioSmoke',args.jar.name,str(snapshot),args.version],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=90)
        args.report.with_suffix('.log').write_text(run.stdout)
        result={'jar':args.jar.name,'sha256':hashlib.sha256(snapshot.read_bytes()).hexdigest(),'java':args.version,'passed':run.returncode==0,'output':run.stdout}
        args.report.write_text(json.dumps(result,indent=2)+'\n');print(run.stdout)
        if run.returncode:raise SystemExit(run.returncode)
if __name__=='__main__':main()
