#!/usr/bin/env python3
"""Update the release guide's dependency table from its reviewed compatibility manifest."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'docs/compatibility-1.0.8.md';text=p.read_text();manifest=json.loads((ROOT/'compatibility.json').read_text())
platforms=['paper','spigot','purpur','folia','fabric','forge','neoforge'];rows={(r['minecraft'],r['platform']):r for r in manifest['entries']}
start=text.index('| Minecraft |');end=text.index('\n## Choosing',start)
table='| Minecraft | Paper | Spigot | Purpur | Folia | Fabric | Forge | NeoForge |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n'
for mc in manifest['requestedMinecraftVersions']:
    cells=[]
    for platform in platforms:
        row=rows.get((mc,platform))
        if row:
            voice=row['voice'];cells.append(f'[{voice["version"].split("-")[-1]}](https://modrinth.com/plugin/plasmo-voice/version/{voice["id"]})')
        else:cells.append('—')
    table+='| '+mc+' | '+' | '.join(cells)+' |\n'
p.write_text(text[:start]+table+text[end:])
