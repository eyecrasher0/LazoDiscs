#!/usr/bin/env python3
"""Describe the pinned Bukkit server/Voice combinations for integration CI."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'compatibility.json').read_text())
rows=[{'platform':r['platform'],'minecraft':r['minecraft'],'runtime':str(r['java'])} for r in manifest['entries'] if r['platform'] in ['paper','purpur','folia']]
print(json.dumps({'include':rows},separators=(',',':')))
