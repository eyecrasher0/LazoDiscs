#!/usr/bin/env python3
"""Select current Forge/NeoForge releases within each advertised Minecraft version."""
import argparse,json,re,urllib.request,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def read(url):
    request=urllib.request.Request(url,headers={'User-Agent':'LazoDiscs/1.0.8 (https://github.com/eyecrasher0/LazoDiscs)'})
    with urllib.request.urlopen(request,timeout=45) as response:return response.read()
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    promos=json.loads(read('https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json'))['promos']
    versions=[e.text for e in ET.fromstring(read('https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml')).findall('versioning/versions/version')]
    entries=json.loads((ROOT/'compatibility.json').read_text())['entries']
    for row in entries:
        platform,mc=row['platform'],row['minecraft']
        if platform not in ['forge','neoforge']:continue
        path=ROOT/platform/mc/'build.gradle';source=path.read_text()
        if platform=='forge':
            chosen=promos.get(mc+'-latest',promos.get(mc+'-recommended'))
            pattern=r"(minecraft 'net.minecraftforge:forge:"+re.escape(mc)+r"-)([^']+)(')"
        else:
            prefix=(mc.removeprefix('1.') if mc.startswith('1.') else (mc+'.0' if mc.count('.')==1 else mc))
            if mc=='1.21':prefix='21.0'
            candidates=[v for v in versions if v.startswith(prefix+'.')]
            stable=[v for v in candidates if re.fullmatch(r'[\d.]+',v)]
            chosen=max(stable or candidates,key=lambda v:tuple(int(n) for n in re.findall(r'\d+',v)))
            pattern=r"(neoForge\s*\{.*?\bversion\s*=\s*')([^']+)(')"
        match=re.search(pattern,source,re.S)
        if not match or not chosen:raise RuntimeError(f'No pin for {platform}/{mc}')
        updated=source[:match.start(2)]+chosen+source[match.end(2):]
        if platform=='neoforge':
            updated=re.sub(r"(implementation 'net.neoforged:neoforge:)[^']+(')",lambda m:m[1]+chosen+m[2],updated)
        if source!=updated:
            print(f'{platform}/{mc}: align loader declarations to {chosen}',flush=True)
            if args.apply:path.write_text(updated)
if __name__=='__main__':main()
