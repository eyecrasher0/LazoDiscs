#!/usr/bin/env python3
"""Exercise release-driver cache decisions with disposable artifacts and upstream records."""
import contextlib
import copy
import hashlib
import http.server
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import concurrent.futures

ROOT = Path(__file__).resolve().parents[1]


def module(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ReleaseVerificationTest(unittest.TestCase):
    def test_build_resume_requires_current_fixture_launcher_and_packaged_json_verifier(self):
        build = module('build_receipt_test', 'tools/build-matrix.py')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            build.ROOT = root
            target = root / 'bukkit/26.3'
            target.mkdir(parents=True)
            (root / 'paper').mkdir()
            (root / 'paper/targets.json').write_text('{}')
            (root / 'legacy-client').mkdir()
            (root / 'compatibility.json').write_text('{"entries":[]}')
            (root / 'tools/java').mkdir(parents=True)
            probe = root / 'tools/java/PackagedJsonSmoke.java'
            probe.write_text('disposable verifier input')
            wrapper = target / 'gradlew'
            wrapper.write_text('''#!/usr/bin/env python3
import sys
from pathlib import Path
out=Path(next(a.split('=',1)[1] for a in sys.argv if a.startswith('-PlazoOutputRoot=')))
folder=out/'bukkit/26.3/libs';folder.mkdir(parents=True,exist_ok=True)
(folder/'lazodiscs-bukkit-1.0.8.jar').write_bytes(b'disposable release archive')
(folder/'lazodiscs-bukkit-integration.jar').write_bytes(b'disposable integration archive')
with (out/'invocations').open('a') as calls:calls.write('build\\n')
''')
            wrapper.chmod(0o755)
            out = root / 'output'
            fixture = out / 'bukkit/26.3/libs/lazodiscs-bukkit-integration.jar'
            argv = ['build-matrix', '--platform', 'bukkit', '--versions', '26.3', '--jobs', '1',
                    '--output-root', str(out), '--resume']

            def run(expected_calls):
                with patch('sys.argv', argv), contextlib.redirect_stdout(io.StringIO()):
                    build.main()
                self.assertEqual(expected_calls, len((out / 'invocations').read_text().splitlines()))
                receipt = json.loads((out / 'results/build/bukkit/26.3.json').read_text())
                self.assertEqual(sha(fixture), receipt['fixture_sha256'])
                self.assertEqual(receipt['sha256'], sha(out / 'release/lazodiscs-bukkit-1.0.8.jar'))

            run(1)
            run(1)
            fixture.unlink()
            run(2)
            fixture.write_bytes(b'replaced integration archive')
            run(3)
            wrapper.write_text(wrapper.read_text() + '\n# Updated launcher bytes\n')
            run(4)
            probe.write_text('updated disposable verifier input')
            run(5)

    def test_voice_audit_rejects_every_consumed_field_mismatch(self):
        audit = module('voice_record_test', 'tools/check-voice-pins.py').audit
        selected = {'id': 'official-release', 'version_number': 'fabric-1.20.6-2.0.10',
                    'version_type': 'release', 'date_published': '2026-01-01',
                    'loaders': ['fabric'], 'game_versions': ['1.20.6'],
                    'files': [{'primary': True, 'url': 'https://cdn.modrinth.com/official.jar',
                               'hashes': {'sha512': '1' * 128}}]}
        record = {'platform': 'fabric', 'minecraft': '1.20.6', 'clientLazoDiscsRequired': False,
                  'voice': {'id': selected['id'], 'version': selected['version_number'], 'generation': 2,
                            'url': selected['files'][0]['url'], 'sha512': '1' * 128}}
        self.assertTrue(audit({'entries': [record]}, {}, [selected])[0]['matches'])
        for field, wrong in [('id', 'other-release'), ('version', 'wrong-label'), ('generation', 1),
                             ('url', 'https://cdn.modrinth.com/other.jar'), ('sha512', '2' * 128)]:
            with self.subTest(field=field):
                target = copy.deepcopy(record)
                target['voice'][field] = wrong
                row = audit({'entries': [target]}, {}, [selected])[0]
                self.assertFalse(row['matches'])
                self.assertIn(field, row['differences'])
        target = copy.deepcopy(record)
        target['clientLazoDiscsRequired'] = True
        self.assertFalse(audit({'entries': [target]}, {}, [selected])[0]['matches'])

    def test_server_resume_rejects_changed_verifier_http_fixture_and_plugin_fixture(self):
        matrix = module('server_receipt_test', 'tools/run-server-matrix.py')
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            matrix.ROOT = root
            out, work = root / 'output', root / 'servers'
            release = out / 'bukkit/26.3/libs/lazodiscs-bukkit-1.0.8.jar'
            release.parent.mkdir(parents=True)
            release.write_bytes(b'release')
            fixture = release.with_name('lazodiscs-bukkit-integration.jar')
            fixture.write_bytes(b'fixture')
            inputs = {'harness_sha256': root / 'tools/server-integration.py',
                      'http_fixture_sha256': root / 'paper/26.3/tools/integration.py',
                      'config_sha256': root / 'bukkit/26.3/src/main/resources/config.yml'}
            for path in inputs.values():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('disposable verifier input')
            server = work / 'paper/26.3/server.jar'
            server.parent.mkdir(parents=True)
            server.write_bytes(b'server')
            voice_bytes = b'official voice bytes'
            voice_hash = hashlib.sha512(voice_bytes).hexdigest()
            voice = work / '.voice' / (voice_hash + '.jar')
            voice.parent.mkdir()
            voice.write_bytes(voice_bytes)
            (root / 'server-builds.json').write_text(json.dumps({'paper/26.3': {
                'build': '1', 'algorithm': 'sha256', 'checksum': sha(server)}}))
            (root / 'compatibility.json').write_text(json.dumps({'entries': [{
                'platform': 'paper', 'minecraft': '26.3', 'java': 25,
                'voice': {'version': '2.1.17', 'sha512': voice_hash}}]}))
            argv = ['server-matrix', '--output-root', str(out), '--work-root', str(work), '--resume',
                    *[arg for java in [16, 17, 21, 25] for arg in ['--java' + str(java), 'unused-java']]]
            calls = []

            def execute(*args, **kwargs):
                calls.append(True)
                console=server.parent/'console.log';console.write_text('disposable actual verification console')
                result = {'failure': None, 'passed': 1, 'build': '1', 'voice': '2.1.17',
                          'log':str(console),'console_sha256':sha(console),
                          'jar_sha256': sha(release), 'fixture_sha256': sha(fixture),
                          'server_sha256': sha(server), 'voice_sha512': voice_hash,
                          **{key: sha(path) for key, path in inputs.items()}}
                (server.parent / 'result.json').write_text(json.dumps(result))
                return type('Completed', (), {'returncode': 0})()

            def run(count):
                with patch('sys.argv', argv), patch.object(matrix.subprocess, 'run', execute), \
                        contextlib.redirect_stdout(io.StringIO()):
                    matrix.main()
                self.assertEqual(count, len(calls))

            run(1)
            run(1)
            inputs['harness_sha256'].write_text('changed verifier')
            run(2)
            inputs['http_fixture_sha256'].write_text('changed HTTP fixture')
            run(3)
            fixture.write_bytes(b'replaced fixture')
            run(4)
            (server.parent/'console.log').unlink()
            run(5)
            (server.parent/'console.log').write_text('replacement console with no pass evidence')
            run(6)

    def test_transient_or_invalid_buildtools_metadata_never_overwrites_the_manifest(self):
        resolver=module('spigot_resolution_test','tools/resolve-compatibility.py')
        from urllib.error import HTTPError,URLError
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);resolver.ROOT=root;resolver.MC=['1.16.5']
            (root/'compatibility-policy.json').write_text('{"voiceOverrides":{}}')
            manifest=root/'compatibility.json'
            official={'id':'legacy','version_number':'spigot-1.0.11','version_type':'release',
                      'date_published':'2026-01-01','loaders':['paper'],'game_versions':['1.16.5'],
                      'files':[{'primary':True,'url':'https://cdn.modrinth.com/official.jar','hashes':{'sha512':'1'*128}}]}
            valid_refs = {key: digit * 40 for key, digit in zip(('BuildData', 'Bukkit', 'CraftBukkit', 'Spigot'), 'aB3d')}
            build_response = {'refs': valid_refs}
            def read(url):
                if 'modrinth.com' in url:return [official]
                if 'fill.papermc.io' in url:return {'versions':{'1.16':['1.16.5']}}
                if 'purpurmc.org' in url:return {'versions':['1.16.5']}
                if 'promotions_slim.json' in url:return {'promos':{}}
                if 'hub.spigotmc.org' in url:
                    if isinstance(build_response, Exception):
                        raise build_response
                    return build_response
                raise AssertionError(url)

            previous = b'previous complete release manifest'
            manifest.write_bytes(previous)
            with patch.object(resolver, 'read', read), contextlib.redirect_stdout(io.StringIO()):
                for failure in (URLError('transport timeout'), HTTPError('fixture', 500, 'temporary', None, None),
                                json.JSONDecodeError('invalid JSON', '{', 1)):
                    with self.subTest(failure=failure):
                        build_response = failure
                        with self.assertRaises(type(failure)):
                            resolver.resolve()
                        self.assertEqual(previous, manifest.read_bytes())
                        if isinstance(failure, HTTPError):
                            failure.close()

                missing = object()
                invalid_refs = {'missing': missing, 'non-string': 42, 'empty': '', 'whitespace': ' ' * 40,
                                'invalid hex': 'g' * 40, 'short': 'a' * 39}
                for key in valid_refs:
                    for case, value in invalid_refs.items():
                        with self.subTest(ref=key, case=case):
                            refs = valid_refs.copy()
                            if value is missing:
                                del refs[key]
                            else:
                                refs[key] = value
                            build_response = {'refs': refs}
                            with self.assertRaisesRegex(ValueError, 'Invalid BuildTools metadata for Spigot 1.16.5'):
                                resolver.resolve()
                            self.assertEqual(previous, manifest.read_bytes())

                build_response = HTTPError('fixture', 404, 'definitive absence', None, None)
                resolver.resolve()
                build_response.close()
                result = json.loads(manifest.read_text())
                self.assertFalse(any(row['platform'] == 'spigot' for row in result['entries']))
                self.assertIn({'platform': 'spigot', 'minecraft': '1.16.5', 'reason': 'No official BuildTools version'},
                              result['excluded'])
                build_response = {'refs': valid_refs}
                resolver.resolve()
                result = json.loads(manifest.read_text())
                self.assertEqual(['1.16.5'], [row['minecraft'] for row in result['entries'] if row['platform'] == 'spigot'])

    def test_concurrent_verified_downloads_publish_complete_shared_artifacts(self):
        download = module('download_race_test', 'paper/26.3/tools/integration.py').download
        content = bytes(range(256)) * 8192

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Length', str(len(content)))
                self.end_headers()
                self.wfile.write(content)

            def log_message(self, *_):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                target = Path(temp) / 'shared-voice.jar'
                expected = hashlib.sha512(content).hexdigest()

                def fetch(_):
                    download('http://127.0.0.1:' + str(server.server_port), target, 'sha512', expected)
                    self.assertEqual(content, target.read_bytes())

                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                    list(pool.map(fetch, range(16)))
                self.assertEqual([target], list(target.parent.iterdir()))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(5)

    def test_paper_beta_matrix_selects_only_263_and_rejects_older_explicit_targets(self):
        matrix = module('paper_beta_selection_test', 'paper/26.3/tools/matrix.py')
        matrix.TARGETS = {'1.17.1': {'java': 17}, '26.3': {'java': 25}}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            calls = []

            def execute(command, **_):
                self.assertIn('--minecraft', command)
                version = command[command.index('--minecraft') + 1]
                generation = command[command.index('--plasmo') + 1]
                calls.append((version, generation))
                folder = Path(command[command.index('--work-dir') + 1])
                folder.mkdir(parents=True, exist_ok=True)
                jar = root / 'paper' / version / f'libs/lazodiscs-paper-1.0.8+{version}.jar'
                jar.parent.mkdir(parents=True, exist_ok=True)
                jar.write_bytes(b'disposable candidate')
                console = folder / 'console.log'
                console.write_text('disposable server verification output')
                receipt = dict(failure=None, passed=1, jar_sha256=sha(jar), log=str(console),
                               console_sha256=sha(console), **{key: 'disposable external identity' for key in
                               ['fixture_sha256', 'harness_sha256', 'server_sha256', 'voice_sha512', 'config_sha256']})
                (folder / f'result-{generation}.json').write_text(json.dumps(receipt))

            base = ['paper-matrix', '--test-only', '--output-root', str(root), '--work-root', str(root / 'servers')]
            with patch.object(matrix, 'java_home', lambda _: '/unused-java'), \
                    patch.object(matrix.subprocess, 'run', execute), contextlib.redirect_stdout(io.StringIO()):
                with patch('sys.argv', base + ['--plasmo', 'beta']):
                    matrix.main()
                self.assertEqual([('26.3', 'beta')], calls)
                self.assertEqual(['26.3'], [r['minecraft'] for r in json.loads(
                    (root / 'verification/native-paper/results-beta.json').read_text())])
                calls.clear()
                with patch('sys.argv', base + ['--plasmo', 'beta', '--versions', '1.17.1']), \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as refused:
                    matrix.main()
                self.assertEqual(2, refused.exception.code)
                self.assertEqual([], calls, 'Unsupported beta selection must fail before server execution')
                with patch('sys.argv', base + ['--plasmo', 'stable']):
                    matrix.main()
                self.assertCountEqual([('1.17.1', 'stable'), ('26.3', 'stable')], calls)


if __name__ == '__main__':
    unittest.main()
