#!/usr/bin/env python3
"""Pin official Voice releases and exclude platform/version combinations without an upstream build."""
import json
from pathlib import Path
import urllib.request
ROOT = Path(__file__).resolve().parents[1]
MC = ['1.7.10','1.8.9','1.12.2','1.16.5','1.17','1.17.1','1.18','1.18.1','1.18.2'] + ['1.19'] + [f'1.19.{i}' for i in range(1,5)] + ['1.20'] + [f'1.20.{i}' for i in range(1,7)] + ['1.21'] + [f'1.21.{i}' for i in range(1,12)] + ['26.1','26.1.1','26.1.2','26.2','26.3']
def read(url):
    request=urllib.request.Request(url,headers={'User-Agent':'LazoDiscs/1.0.8 (https://github.com/eyecrasher0/LazoDiscs)'})
    with urllib.request.urlopen(request,timeout=45) as response:return json.load(response)
def resolve():
    voice=read('https://api.modrinth.com/v2/project/plasmo-voice/version')
    projects={name:read(f'https://fill.papermc.io/v3/projects/{name}') for name in ['paper','folia']}
    available={name:{v for group in project['versions'].values() for v in group} for name,project in projects.items()}
    available['purpur']=set(read('https://api.purpurmc.org/v2/purpur')['versions'])
    forge_promos=read('https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json')['promos']
    overrides=json.loads((ROOT/'compatibility-policy.json').read_text())['voiceOverrides']
    entries=[];excluded=[]
    for platform in ['fabric','forge','neoforge','paper','spigot','purpur','folia']:
        for mc in MC:
            loader=platform if platform in ['fabric','forge','neoforge'] else 'paper'
            candidates=[v for v in voice if loader in v['loaders'] and mc in v['game_versions'] and v['version_type']=='release']
            modern=[v for v in candidates if v['version_number'].split('-')[-1].startswith('2.')]
            if platform in ['paper','spigot','purpur'] and mc=='1.16.5':
                # Stock upstream 1.16.5 launchers cap the JVM at 16. The official
                # Voice 2 Bukkit artifact needs Java 17, while Voice 1 is compatible.
                candidates=[v for v in candidates if v['version_number'].split('-')[-1].startswith('1.')]
            elif modern:candidates=modern
            if not candidates:
                excluded.append({'platform':platform,'minecraft':mc,'reason':'No official Plasmo Voice release'});continue
            if platform=='forge' and mc+'-latest' not in forge_promos and mc+'-recommended' not in forge_promos:
                excluded.append({'platform':platform,'minecraft':mc,'reason':'No official Forge distribution'});continue
            if platform in available and mc not in available[platform]:
                excluded.append({'platform':platform,'minecraft':mc,'reason':'No official server distribution'});continue
            if platform=='spigot':
                try:read(f'https://hub.spigotmc.org/versions/{mc}.json')
                except Exception:
                    excluded.append({'platform':platform,'minecraft':mc,'reason':'No official BuildTools version'});continue
            override=overrides.get(platform+'/'+mc)
            row=next(v for v in candidates if v['id']==override['versionId']) if override else max(candidates,key=lambda v:v['date_published'])
            file=next((f for f in row['files'] if f.get('primary')), row['files'][0])
            generation=row['version_number'].split('-')[-1].split('.')[0]
            java=25 if mc.startswith('26.') else 21 if mc.startswith('1.21') or mc in ['1.20.5','1.20.6'] else 17
            if platform in ['fabric','forge'] and mc=='1.16.5': java=8
            if mc in ['1.17','1.17.1'] and (platform=='fabric' or generation=='1'): java=16
            if platform in ['paper','spigot','purpur'] and mc=='1.16.5': java=16
            entries.append({'platform':platform,'minecraft':mc,'java':java,'voice':{'id':row['id'],'version':row['version_number'],'generation':int(generation),'url':file['url'],'sha512':file['hashes']['sha512']},'clientLazoDiscsRequired':generation=='1'})
    result={'release':'1.0.8','asOf':'2026-11-14','requestedMinecraftVersions':MC,'entries':entries,'excluded':excluded}
    (ROOT/'compatibility.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print('Requested versions:',len(MC))
    for platform in ['fabric','forge','neoforge','paper','spigot','purpur','folia']:
        rows=[r for r in entries if r['platform']==platform]
        print(platform,len(rows),'Voice 1:',[r['minecraft'] for r in rows if r['voice']['generation']==1])
if __name__=='__main__':resolve()
