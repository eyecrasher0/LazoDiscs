#!/usr/bin/env python3
"""Describe only the exact mod and client-helper targets advertised by this release."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'compatibility.json').read_text())
rows=[{'platform':r['platform'],'minecraft':r['minecraft'],'runtime':str(r['java'])} for r in manifest['entries'] if r['platform'] in ['fabric','forge','neoforge']]
rows += [{'platform':'legacy-client','minecraft':p.name,'runtime':'8' if p.name.endswith('1.16.5') else '17'} for p in sorted((root/'legacy-client').iterdir()) if (p/'build.gradle').exists()]
print(json.dumps({'include':rows},separators=(',',':')))
