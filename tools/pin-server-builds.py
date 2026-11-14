#!/usr/bin/env python3
"""Capture upstream server build IDs and checksums for reproducible integration runs."""
import concurrent.futures,json,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def read(url):
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'LazoDiscs/1.0.8 (https://github.com/eyecrasher0/LazoDiscs)'}),timeout=40) as response:return json.load(response)
def main():
    manifest=json.loads((ROOT/'compatibility.json').read_text())
    targets={(r['platform'],r['minecraft']) for r in manifest['entries'] if r['platform'] in ['paper','purpur','folia']}
    def resolve(pair):
        platform,mc=pair
        if platform=='purpur':
            row=read(f'https://api.purpurmc.org/v2/purpur/{mc}/latest');number=row['build']
            return f'{platform}/{mc}',{'build':str(number),'channel':'SUCCESS','url':f'https://api.purpurmc.org/v2/purpur/{mc}/{number}/download','algorithm':'md5','checksum':row['md5']}
        rows=read(f'https://fill.papermc.io/v3/projects/{platform}/versions/{mc}/builds')
        if not rows:raise RuntimeError(f'No builds: {platform}/{mc}')
        row=next((r for r in rows if r['channel']=='STABLE'),rows[0]);download=row['downloads']['server:default']
        return f'{platform}/{mc}',{'build':str(row['id']),'channel':row['channel'],'url':download['url'],'algorithm':'sha256','checksum':download['checksums']['sha256']}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:pins=dict(pool.map(resolve,sorted(targets)))
    (ROOT/'server-builds.json').write_text(json.dumps(pins,indent=2)+'\n');print('Pinned',len(pins),'server builds')
if __name__=='__main__':main()
