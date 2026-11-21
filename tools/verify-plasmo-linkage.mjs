import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync, unlinkSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = dirname(dirname(fileURLToPath(import.meta.url)));
const readJson = path => JSON.parse(readFileSync(path,'utf8'));
const outputRoot = process.env.LAZO_OUTPUT_ROOT ?? join(root,'build-matrix');
const prefix = /^su\/plo\/(?:voice|slib|lib)\//;
const activeTargets = matrix => matrix.entries.filter(t=>['fabric','forge','neoforge'].includes(t.platform)).map(t=>({
  loader:t.platform,minecraft:t.minecraft,generation:t.voice.generation,
  plasmo_voice:{channel:'release',url:t.voice.url,sha512:t.voice.sha512,version:t.voice.version}
}));

// Check actual runtime JARs, not just the newer Maven compile-time API. This catches
// missing methods on older stable PV releases without launching Minecraft.
if (process.argv[2] === '--self-test') {
  assert.equal(process.argv.length, 3, 'Usage: node tools/verify-plasmo-linkage.mjs --self-test');
  selfTest();
  process.exit(0);
}
const args = process.argv.slice(2);
assert.ok(args.every(arg => /^--(?:loader|minecraft)=[^=]+$/.test(arg)),
  'Usage: node tools/verify-plasmo-linkage.mjs [--loader=fabric|forge|neoforge] [--minecraft=1.21.1]');
assert.equal(new Set(args.map(arg => arg.split('=')[0])).size, args.length, 'Duplicate option');
const loader = args.find(arg => arg.startsWith('--loader='))?.slice(9);
const minecraft = args.find(arg => arg.startsWith('--minecraft='))?.slice(12);
const matrix = readJson(join(root, 'compatibility.json'));
const targets = activeTargets(matrix).filter(target =>
  (!loader || target.loader === loader) && (!minecraft || target.minecraft === minecraft));
assert.ok(targets.length > 0, 'No stable Plasmo Voice target matches this selection');
const javap = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', 'javap') : 'javap';
const cacheDirectory = join(outputRoot, 'verification-cache', 'plasmo-voice');
mkdirSync(cacheDirectory, { recursive: true });

function command(program, arguments_) {
  const result = spawnSync(program, arguments_, {
    encoding: 'utf8', timeout: 60000, maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, (result.stdout ?? '') + (result.stderr ?? ''));
  return result.stdout;
}

function withoutGenerics(text) {
  let depth = 0;
  let result = '';
  for (const character of text) {
    if (character === '<') depth++;
    else if (character === '>') depth--;
    else if (depth === 0) result += character;
  }
  return result;
}

function parseApi(output) {
  const types = new Map();
  let type, owner, member;
  for (const line of output.split('\n')) {
    const declaration = !/^\s/.test(line) && withoutGenerics(line).match(
      /\b(class|interface|enum)\s+([\w.$]+)([^{}]*?)(?:\s*\{)?$/);
    if (declaration) {
      owner = declaration[2].replaceAll('.', '/');
      type = { members: new Map(), parents: [], kind: declaration[1] };
      for (const parents of declaration[3].matchAll(/\b(?:extends|implements)\s+(.+?)(?=\s+implements\b|$)/g)) {
        type.parents.push(...parents[1].split(/\s*,\s*/)
          .map(parent => parent.trim().replaceAll('.', '/')));
      }
      types.set(owner, type);
      member = undefined;
      continue;
    }
    if (!type) continue;
    const method = line.match(/^\s+public .*?([^\s(]+)\(/);
    const field = !method && line.match(/^\s+public .*?([^\s;]+);$/);
    if (method || field) {
      const token = (method || field)[1];
      member = {
        name: token.replaceAll('.', '/') === owner ? '<init>' : token,
        static: /\bstatic\b/.test(line),
        abstract: /\babstract\b/.test(line),
        kind: method ? 'method' : 'field',
      };
      continue;
    }
    const descriptor = line.match(/^\s+descriptor: (\S+)/);
    if (descriptor && member) {
      type.members.set(member.name + ':' + descriptor[1], member);
      member = undefined;
    }
  }
  return types;
}

function references(output) {
  const result = new Map();
  for (const line of output.split('\n')) {
    const match = line.match(
      /^\s*\d+:\s+(invoke(?:static|virtual|special|interface)|(?:get|put)(?:static|field))\s+#\d+.*\/\/ (?:InterfaceMethod|Method|Field) (su\/plo\/(?:voice|slib|lib)\/[^.\s]+)\."?([^:\"]+)"?:(\S+)/)
      ?? line.match(
        /\bREF_(invokeStatic|invokeVirtual|invokeSpecial|invokeInterface|newInvokeSpecial|getStatic|putStatic|getField|putField)\s+(su\/plo\/(?:voice|slib|lib)\/[^.\s]+)\."?([^:\"]+)"?:(\S+)/);
    if (!match) continue;
    const [, instruction, owner, name, descriptor] = match;
    const opcode = instruction.toLowerCase();
    const key = `${owner}.${name}:${descriptor}`;
    const reference = { owner, name, descriptor, static: opcode.endsWith('static'),
      kind: opcode.includes('invoke') ? 'method' : 'field' };
    if (result.has(key)) assert.equal(result.get(key).static, reference.static, key);
    result.set(key, reference);
  }
  return result;
}

function loadApi(jar, owners, entries, cache) {
  let pending = [...new Set(owners)].filter(owner => !cache.has(owner));
  while (pending.length) {
    for (const owner of pending) assert.ok(entries.has(owner + '.class'), `Missing PV class: ${owner}`);
    const parsed = parseApi(command(javap,
      ['-s', '-public', '-classpath', jar, ...pending.map(owner => owner.replaceAll('/', '.'))]));
    for (const owner of pending) assert.ok(parsed.has(owner), `Unparsed PV class: ${owner}`);
    for (const [owner, type] of parsed) cache.set(owner, type);
    pending = [...new Set([...parsed.values()].flatMap(type => type.parents))]
      .filter(owner => prefix.test(owner) && !cache.has(owner));
  }
}

function resolve(owner, name, descriptor, cache, visited = new Set()) {
  if (visited.has(owner)) return undefined;
  visited.add(owner);
  const type = cache.get(owner);
  assert.ok(type, `Uninspected PV class: ${owner}`);
  const member = type.members.get(name + ':' + descriptor);
  if (member) return member;
  if (name === '<init>') return undefined;
  for (const parent of type.parents.filter(parent => prefix.test(parent))) {
    const inherited = resolve(parent, name, descriptor, cache, visited);
    if (inherited?.kind === 'method' && inherited.static && cache.get(parent).kind === 'interface') continue;
    if (inherited) return inherited;
  }
  return undefined;
}

function selfTest() {
  const fixture = `
public interface su.plo.voice.Fixture<T> {
  public static void factory();
    descriptor: ()V
  public abstract T get();
    descriptor: ()Ljava/lang/Object;
}
public class su.plo.voice.Child extends java.lang.Object implements su.plo.voice.Fixture<java.lang.String> {
  public su.plo.voice.Child();
    descriptor: ()V
  public static final int VALUE;
    descriptor: I
}
public class su.plo.voice.Grandchild extends su.plo.voice.Child {
}
`;
  const types = parseApi(fixture);
  assert.equal(types.size, 3);
  assert.deepEqual(types.get('su/plo/voice/Child').parents, ['java/lang/Object', 'su/plo/voice/Fixture']);
  assert.ok(types.get('su/plo/voice/Fixture').members.get('get:()Ljava/lang/Object;').abstract);
  assert.ok(resolve('su/plo/voice/Child', '<init>', '()V', types));
  assert.equal(resolve('su/plo/voice/Grandchild', '<init>', '()V', types), undefined);
  assert.ok(resolve('su/plo/voice/Child', 'get', '()Ljava/lang/Object;', types));
  assert.equal(resolve('su/plo/voice/Child', 'factory', '()V', types), undefined);
  assert.ok(resolve('su/plo/voice/Grandchild', 'VALUE', 'I', types).static);
  const verbose = parseApi(`public class su.plo.voice.Verbose
  minor version: 0
  major version: 52
{
  public static void call();
    descriptor: ()V
    flags: ACC_PUBLIC, ACC_STATIC
}`);
  assert.ok(verbose.get('su/plo/voice/Verbose').members.get('call:()V').static);
  const calls = references(`
  0: invokeinterface #21, 1 // InterfaceMethod su/plo/voice/Fixture.get:()Ljava/lang/Object;
  8: getstatic #24 // Field su/plo/voice/Child.VALUE:I
  #34 = MethodHandle 6:#22 // REF_invokeStatic su/plo/voice/Fixture.factory:()V
  #35 = MethodHandle 8:#23 // REF_newInvokeSpecial su/plo/voice/Child."<init>":()V
  #36 = MethodHandle 5:#21 // REF_invokeVirtual su/plo/voice/Fixture.get:()Ljava/lang/Object;
`);
  assert.equal(calls.size, 4);
  assert.ok(calls.get('su/plo/voice/Fixture.factory:()V').static);
  assert.equal(calls.get('su/plo/voice/Child.<init>:()V').kind, 'method');
  assert.equal(calls.get('su/plo/voice/Child.VALUE:I').kind, 'field');
  assert.throws(() => references(`
    0: invokevirtual #2 // Method su/plo/voice/Fixture.factory:()V
    4: invokestatic #2 // Method su/plo/voice/Fixture.factory:()V
  `), /factory/);
  console.log('PASS JVM linkage parser: generics, constructors, inherited members, interface-static exclusion, fields, method handles and conflicting opcodes');
}

async function getPlasmoVoice(target) {
  const pv = target.plasmo_voice;
  assert.equal(pv.channel, 'release', 'Only published stable Plasmo Voice releases are allowed');
  assert.equal(new URL(pv.url).hostname, 'cdn.modrinth.com', 'Untrusted artifact host');
  assert.match(pv.sha512, /^[a-f0-9]{128}$/, 'Missing upstream SHA-512');
  const destination = join(cacheDirectory, pv.sha512 + '.jar');
  if (!existsSync(destination)) {
    const response = await fetch(pv.url, { signal: AbortSignal.timeout(60000) });
    assert.ok(response.ok, `Plasmo Voice download HTTP ${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    assert.equal(createHash('sha512').update(bytes).digest('hex'), pv.sha512, 'PV checksum mismatch');
    const temporary = destination + '.' + process.pid + '.' + randomUUID() + '.download';
    try {
      writeFileSync(temporary, bytes, { flag: 'wx' });
      renameSync(temporary, destination);
    } finally {
      if (existsSync(temporary)) unlinkSync(temporary);
    }
  }
  assert.equal(createHash('sha512').update(readFileSync(destination)).digest('hex'), pv.sha512,
    'Cached PV checksum mismatch');
  return destination;
}

const results = [];
for (const target of targets) {
  const name = `${target.loader}/${target.minecraft}`;
  try {
    const directory = join(root, target.loader, target.minecraft);
    const version = readFileSync(join(directory, 'build.gradle'), 'utf8')
      .match(/^version = '([^']+)'/m)?.[1];
    assert.ok(version, `No build version for ${name}`);
    const artifact = join(outputRoot, 'release', `lazodiscs-${target.loader}-${version}.jar`);
    assert.ok(existsSync(artifact), `Build the final JAR first: ${artifact}`);
    const before = createHash('sha256').update(readFileSync(artifact)).digest('hex');
    const classes = command('unzip', ['-Z1', artifact]).trim().split(/\r?\n/)
      .filter(entry => entry.startsWith('com/eyecrasher/lazodiscs/') &&
        !entry.includes('/shadow/') && entry.endsWith('.class'))
      .map(entry => entry.slice(0, -6).replaceAll('/', '.'));
    assert.ok(classes.length, 'No mod-owned classes found');
    const bytecode = command(javap, ['-c', '-p', '-s', '-v', '-classpath', artifact, ...classes]);
    const used = references(bytecode);
    if (target.generation === 1) {
      assert.equal(used.size,0,'Voice 1 must not link the absent Voice 2 addon API');
      results.push({target:name,result:'pass',sha256:before,pv:target.plasmo_voice.version,member_references:0,scope:'Voice 2 class isolation; Voice 1 reflection tested separately'});
      console.log(`PASS ${name}: Voice 1 adapter has no Voice 2 member links`);
      continue;
    }
    assert.ok(used.size, 'No PV calls detected; ineffective verification');
    const pv = await getPlasmoVoice(target);
    const entries = new Set(command('unzip', ['-Z1', pv]).trim().split(/\r?\n/));
    const legacyApi = /-2\.0\./.test(target.plasmo_voice.version);
    const addonContract = 'su/plo/voice/api/addon/AddonInitializer';
    const providerContract = 'su/plo/voice/api/server/audio/provider/AudioFrameProvider';
    const contracts = [addonContract, 'su/plo/voice/api/addon/annotation/Addon'];
    if (!legacyApi) contracts.push('su/plo/voice/api/addon/InjectPlasmoVoice', providerContract);
    const cache = new Map();
    loadApi(pv, [...used.values()].map(reference => reference.owner).concat(contracts), entries, cache);
    for (const [key, reference] of used) {
      const actual = resolve(reference.owner, reference.name, reference.descriptor, cache);
      assert.ok(actual, `Missing stable PV member: ${key}`);
      assert.equal(actual.static, reference.static, `Static/instance mismatch: ${key}`);
      assert.equal(actual.kind, reference.kind, `Method/field mismatch: ${key}`);
    }
    const implementations = parseApi(bytecode);
    for (const contractName of legacyApi ? [addonContract] : [addonContract, providerContract]) {
      const implementors = [...implementations.values()]
        .filter(type => type.parents.includes(contractName));
      assert.ok(implementors.length, `No implementation of ${contractName}`);
      for (const [key, member] of cache.get(contractName).members) {
        if (!member.abstract || member.static || member.kind !== 'method') continue;
        for (const implementor of implementors) {
          const actual = implementor.members.get(key);
          assert.ok(actual && actual.kind === 'method' && !actual.static && !actual.abstract,
            `Missing concrete addon/provider implementation: ${key}`);
        }
      }
    }
    assert.equal(createHash('sha256').update(readFileSync(artifact)).digest('hex'), before,
      'Artifact changed during verification');
    results.push({ target: name, result: 'pass', sha256: before, pv: target.plasmo_voice.version,
      pv_sha512: target.plasmo_voice.sha512, member_references: used.size });
    console.log(`PASS ${name}: ${used.size} member references against stable ${target.plasmo_voice.version}`);
  } catch (error) {
    results.push({ target: name, result: 'fail', error: error.message });
    console.error(`FAIL ${name}: ${error.message}`);
  }
}
const output = join(outputRoot, 'verification');
mkdirSync(output, { recursive: true });
const selection = [loader, minecraft].filter(Boolean).join('-') || 'all';
writeFileSync(join(output, `plasmo-linkage-${selection}.json`), JSON.stringify({
  checked_at: new Date().toISOString(),
  scope: 'PV/SLib members, method handles, abstract addon/provider contracts; not a full JVM linker, reflective API check, or live game test',
  results,
}, null, 2) + '\n');
const passed = results.filter(result => result.result === 'pass').length;
console.log(`Stable Plasmo Voice linkage: ${passed}/${results.length} passed.`);
process.exitCode = passed === results.length ? 0 : 1;
