#!/usr/bin/env python3
"""Compare the release's Voice pins with current official stable releases."""
import argparse
import datetime
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def audit(manifest, policy, versions):
    results = []
    for target in manifest['entries']:
        platform, mc = target['platform'], target['minecraft']
        loader = platform if platform in ('fabric', 'forge', 'neoforge') else 'paper'
        candidates = [v for v in versions if v['version_type'] == 'release'
                      and loader in v['loaders'] and mc in v['game_versions']]
        modern = [v for v in candidates if v['version_number'].split('-')[-1].startswith('2.')]
        reason = None
        if platform in ('paper', 'spigot', 'purpur') and mc == '1.16.5':
            candidates = [v for v in candidates if v['version_number'].split('-')[-1].startswith('1.')]
            reason = 'Stock 1.16.5 launchers require Java <=16; the official Voice 2 Bukkit plugin requires Java 17.'
        elif modern:
            candidates = modern
        newest = max(candidates, key=lambda v: v['date_published'])
        override = policy.get(platform + '/' + mc)
        selected = next(v for v in candidates if v['id'] == override['versionId']) if override else newest
        if override:
            reason = override['reason']
        file = next((f for f in selected['files'] if f.get('primary')), selected['files'][0])
        generation = int(selected['version_number'].split('-')[-1].split('.')[0])
        expected = {'id': selected['id'], 'version': selected['version_number'], 'generation': generation,
                    'url': file['url'], 'sha512': file['hashes']['sha512']}
        differences = [key for key, value in expected.items() if target['voice'].get(key) != value]
        if target.get('clientLazoDiscsRequired') != (generation == 1): differences.append('clientLazoDiscsRequired')
        results.append({'platform': platform, 'minecraft': mc, 'selected': target['voice']['version'],
                        'newestCompatible': selected['version_number'], 'versionId': selected['id'],
                        'matches': not differences, 'differences': differences, 'expectedVoice': expected, 'constraint': reason})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'compatibility.json').read_text())
    policy = json.loads((ROOT / 'compatibility-policy.json').read_text())['voiceOverrides']
    request = urllib.request.Request('https://api.modrinth.com/v2/project/1bZhdhsH/version',
                                     headers={'User-Agent': 'LazoDiscs/1.0.8 compatibility audit'})
    with urllib.request.urlopen(request, timeout=60) as response:
        versions = json.load(response)
    results = audit(manifest, policy, versions)
    report = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'scope': 'Official stable release metadata with reviewed runtime constraints; binary linkage and real loader tests are separate.',
              'results': results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('Current compatible official Voice pins: %d/%d' % (sum(r['matches'] for r in results), len(results)))
    for row in results:
        if not row['matches']:
            print('UPDATE REQUIRED:', row)
    if not all(r['matches'] for r in results):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
