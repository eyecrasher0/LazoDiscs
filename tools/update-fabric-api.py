#!/usr/bin/env python3
"""Pin Fabric API by official Minecraft compatibility, not the Maven version suffix."""
import concurrent.futures,json,re,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    request=urllib.request.Request('https://api.modrinth.com/v2/project/fabric-api/version',headers={'User-Agent':'LazoDiscs/1.0.8 (https://github.com/eyecrasher0/LazoDiscs)'})
    with urllib.request.urlopen(request,timeout=60) as response:versions=json.load(response)
    targets=[r['minecraft'] for r in json.loads((ROOT/'compatibility.json').read_text())['entries'] if r['platform']=='fabric']
    pins={}
    for mc in targets:
        candidates=[r for r in versions if mc in r['game_versions'] and r['version_type']=='release' and 'fabric' in r['loaders']]
        if not candidates:raise RuntimeError('No official Fabric API for '+mc)
        row=max(candidates,key=lambda r:r['date_published']);file=next((f for f in row['files'] if f.get('primary')),row['files'][0])
        pins[mc]={'id':row['id'],'version':row['version_number'],'url':file['url'],'sha512':file['hashes']['sha512']}
        path=ROOT/'fabric'/mc/'build.gradle';s=path.read_text();old=re.search(r'net.fabricmc.fabric-api:fabric-api:([^\']+)',s)[1]
        if old!=row['version_number']:
            print(mc,old,'->',row['version_number'],flush=True)
            path.write_text(s.replace('net.fabricmc.fabric-api:fabric-api:'+old,'net.fabricmc.fabric-api:fabric-api:'+row['version_number']))
    (ROOT/'fabric-api-lock.json').write_text(json.dumps(pins,indent=2)+'\n')
if __name__=='__main__':main()
