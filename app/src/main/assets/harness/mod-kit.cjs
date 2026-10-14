'use strict';
// mh-mod: the mod authoring kit in the guest (mod platform spec v2 section 6.5).
//   mh-mod init <id> [dir]   scaffold a mod with a hooks module and a test
//   mh-mod validate [dir]    check mod.json and the hooks module the way the app loads them
//   mh-mod test [dir]        run tests/*.test.js against the app's own shim, with a mocked host
//   mh-mod pack [dir]        compile hooks/register.jsx|tsx|ts to plain JS when esbuild is installed
// The app's checks on install stay the final word; this kit only catches problems sooner.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {spawnSync} = require('node:child_process');

const SHIM = process.env.MH_MOD_SHIM || path.join(__dirname, '..', 'mods', 'mh-shim.js');
const CAPABILITIES = ['app.info', 'project.info', 'ui.toast', 'storage', 'notify', 'chat.read', 'chat.post', 'prompt.draft',
  'prompt.send', 'events.subscribe', 'files.read', 'files.write', 'git.read', 'browser.open', 'terminal.run', 'net.fetch',
  'secrets', 'memory.read', 'agent.ask', 'prompt.transform', 'ui.status', 'ui.band', 'ui.pane', 'ui.webpane',
  'prompt.compose', 'files.gate', 'changes.read', 'changes.undo', 'terminal.gate', 'tools.gate', 'tools.provide',
  'model.complete', 'agent.chain', 'mods.call', 'git.write', 'build.run', 'guest.tools', 'tools.approve', 'engine.memory'];
const ID = /^[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*){1,4}$/;
const VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const MAX_MODULE = 512 * 1024;

// ---- the app's module transform (ModModuleSource.kt), so line numbers and errors match ----------
const IMPORT = /^[ \t]*import\s*(?:[\w{*$]|["'])/m;
const EXPORT_DEFAULT = /^([ \t]*)export\s+default\s+/gm;
const EXPORT_DECLARATION = /^([ \t]*)export\s+((?:async\s+)?function\s*\*?\s*|class\s+|const\s+|let\s+|var\s+)([A-Za-z_$][\w$]*)/gm;
const EXPORT_LIST = /^([ \t]*)export\s*\{([^}]*)}\s*;?/gm;
function lineOf(text, index) { return text.slice(0, index).split('\n').length; }
function wrap(source) {
  const imported = IMPORT.exec(source);
  if (imported) throw Error(`Line ${lineOf(source, imported.index)}: a mod can't import other files. Put all of its code in the hooks module.`);
  const exported = [];
  let body = source.replace(EXPORT_DECLARATION, (_, indent, kind, name) => { exported.push(name); return indent + kind + name; });
  body = body.replace(EXPORT_DEFAULT, (_, indent) => indent + 'exports.default = ');
  body = body.replace(EXPORT_LIST, (match, indent, list, offset) => indent + list.split(',').map(s => s.trim()).filter(Boolean).map(item => {
    const [local, name = local] = item.split(/\s+as\s+/).map(s => s.trim());
    if (!/^[A-Za-z_$][\w$]*$/.test(local) || !/^[A-Za-z_$][\w$]*$/.test(name)) throw Error(`Line ${lineOf(source, offset)}: can't read the export list "${match.trim()}".`);
    return `exports.${name} = ${local};`;
  }).join(' '));
  return "__mh.define(function (exports, module) { 'use strict'; " + body + '\n;' +
    [...new Set(exported)].map(name => `exports.${name} = ${name};`).join('') + "\n});\n'ok'";
}

// ---- reading a mod folder ------------------------------------------------------------------------
function readManifest(dir) {
  const file = path.join(dir, 'mod.json');
  if (!fs.existsSync(file)) throw Error(`No mod.json in ${dir}`);
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (error) { throw Error(`mod.json is not valid JSON: ${error.message}`); }
}
function hooksFile(dir, manifest) {
  const module = manifest.hooks && manifest.hooks.module;
  return typeof module === 'string' ? path.join(dir, module) : null;
}

/** Problems the app would refuse, as plain sentences; an empty list means none were found. */
function validate(dir) {
  const problems = [];
  let manifest;
  try { manifest = readManifest(dir); } catch (error) { return [error.message]; }
  if (typeof manifest.id !== 'string' || !ID.test(manifest.id)) problems.push('"id" must look like dev.yourname.mod-name (lowercase, 2 to 5 parts).');
  if (typeof manifest.name !== 'string' || !manifest.name.trim() || manifest.name.length > 40) problems.push('"name" must be 1 to 40 characters.');
  if (typeof manifest.version !== 'string' || !VERSION.test(manifest.version)) problems.push('"version" must be like 1.0.0.');
  if (!Number.isInteger(manifest.apiVersion)) problems.push('"apiVersion" is required.');
  const permissions = Array.isArray(manifest.permissions) ? manifest.permissions : [];
  if (manifest.permissions !== undefined && !Array.isArray(manifest.permissions)) problems.push('"permissions" must be a list.');
  for (const [i, permission] of permissions.entries()) {
    if (!permission || !CAPABILITIES.includes(permission.capability)) problems.push(`permissions[${i}]: unknown capability ${JSON.stringify(permission && permission.capability)}.`);
    if (permission && permission.capability === 'files.write' && !(permission.scope && Array.isArray(permission.scope.paths) && permission.scope.paths.length)) {
      problems.push('files.write needs "scope.paths" naming the files it may change.');
    }
    if (permission && permission.capability === 'net.fetch' && !(permission.scope && Array.isArray(permission.scope.hosts) && permission.scope.hosts.length)) {
      problems.push('net.fetch needs "scope.hosts" naming the hosts it may reach.');
    }
  }
  const file = hooksFile(dir, manifest);
  if (manifest.hooks !== undefined && !file) problems.push('"hooks" must be { "module": "hooks/register.js" }.');
  if (file) {
    if (!fs.existsSync(file)) return [...problems, `The hooks module ${manifest.hooks.module} is missing.`];
    const source = fs.readFileSync(file, 'utf8');
    if (Buffer.byteLength(source) > MAX_MODULE) problems.push('The hooks module is larger than 512 KB.');
    for (const [pattern, what] of [[/\brequire\s*\(/, 'require()'], [/\bimport\s*\(/, 'import()'], [/\beval\s*\(/, 'eval()'], [/\bnew\s+Function\b|\bFunction\s*\(/, 'Function()']]) {
      const hit = pattern.exec(source);
      if (hit) problems.push(`Line ${lineOf(source, hit.index)}: ${what} isn't available to mods.`);
    }
    try {
      new vm.Script(wrap(source), {filename: manifest.hooks.module});
    } catch (error) {
      problems.push(error instanceof SyntaxError ? `${manifest.hooks.module}: ${error.message}` : error.message);
    }
  }
  return problems;
}

// ---- running a mod against the shim, for tests -----------------------------------------------------
/**
 * Loads the mod in [dir] the way the app does and returns a handle tests drive:
 * `emit(event, e)` runs its hooks and resolves to what they answered, `gate(event, e)` runs a gate,
 * `render(surface, e)` draws, `press(surface, key, value)` taps. Host calls are answered by `mocks`
 * (`{ 'agent.ask': (args) => ({ status: 'completed', text: 'ok' }) }`), an in-memory store, and
 * records: `toasts`, `statuses`, `logs`, `calls`, `commands`, `tools`, `posts` (`$.ui.post` to web panels).
 */
async function loadMod(dir = process.cwd(), {options = {}, mocks = {}, store = {}} = {}) {
  const manifest = readManifest(dir);
  const file = hooksFile(dir, manifest);
  if (!file) throw Error('This mod has no hooks module to test.');
  const pending = new Map();
  const record = {toasts: [], statuses: [], logs: [], calls: [], commands: [], tools: [], posts: [], store: {...store}};
  let context;
  let nextD = 1;
  const port = {postMessage: text => setImmediate(() => onMessage(JSON.parse(text)))};
  context = vm.createContext({android: {getNamedPort: () => Promise.resolve(port)}, console});
  const run = code => vm.runInContext(code, context, {timeout: 2000});
  const answer = (id, ok, value) => run(`__mh.answer(${id}, ${ok}, ${JSON.stringify(value === undefined ? null : value)})`);
  async function host(op, args) {
    record.calls.push({op, args});
    if (Object.prototype.hasOwnProperty.call(mocks, op)) return typeof mocks[op] === 'function' ? mocks[op](args) : mocks[op];
    switch (op) {
      case 'store.get': return record.store[args[0]] === undefined ? null : record.store[args[0]];
      case 'store.set': record.store[args[0]] = args[1]; return null;
      case 'store.delete': delete record.store[args[0]]; return null;
      case 'store.keys': return Object.keys(record.store);
      case 'clock.sleep': await new Promise(resolve => setTimeout(resolve, Math.min(Number(args[0]) || 0, 2000))); return null;
      case 'clock.after': case 'clock.every': case 'clock.cancel': return true;
      case 'ui.toast': record.toasts.push(String(args[0])); return true;
      case 'ui.status': case 'ui.summary': record.statuses.push(args[0]); return null;
      case 'ui.redraw': case 'ui.open': case 'ui.close': return true;
      case 'ui.post': record.posts.push({panel: args[0], data: args[1]}); return null;
      case 'command.register': record.commands.push(args[0] && args[0].name); return true;
      case 'tool.register': record.tools.push(args[0] && args[0].name); return `mod_${manifest.id.split('.').pop().replace(/[^a-z0-9]+/g, '_')}_${args[0] && args[0].name}`;
      case 'project.info': return {id: 'p1', name: 'Test project', harness: 'claude'};
      default: throw Error(`$.${op} isn't mocked in this test; pass mocks: { '${op}': ... }`);
    }
  }
  function onMessage(message) {
    if (message.t === 'log') record.logs.push(`${message.level}: ${message.text}`);
    else if (message.t === 'op') {
      Promise.resolve().then(() => host(message.op, message.args))
        .then(value => answer(message.id, true, value), error => answer(message.id, false, error.message));
    } else if (message.t === 'done' || message.t === 'error') {
      const waiter = pending.get(message.d);
      if (!waiter) return;
      pending.delete(message.d);
      if (message.t === 'error' || message.error !== undefined) waiter.reject(Error(String(message.error)));
      else waiter.resolve(message.value === undefined ? null : message.value);
    }
  }
  const call = (entry, args) => new Promise((resolve, reject) => {
    const d = nextD++;
    pending.set(d, {resolve, reject});
    run(`__mh.${entry}(${[d, ...args].map(a => JSON.stringify(a === undefined ? null : a)).join(', ')})`);
  });
  run(fs.readFileSync(SHIM, 'utf8'));
  await run('__mh.connect()');
  run(wrap(fs.readFileSync(file, 'utf8')));
  const started = JSON.parse(run(`__mh.start(${JSON.stringify(options)})`));
  await call('dispatch', ['session.start', {}]);
  return {
    ...record,
    events: started.events,
    emit: (event, e = {}) => call('dispatch', [event, e]),
    gate: (event, e = {}) => call('gate', [event, e]),
    render: (surface, e = {}) => call('render', [surface, e]),
    press: (surface, key, value) => call('press', [surface, key, value]),
  };
}

async function runTests(dir) {
  const folder = path.join(dir, 'tests');
  const files = fs.existsSync(folder) ? fs.readdirSync(folder).filter(name => name.endsWith('.test.js')).sort() : [];
  if (!files.length) { console.log('No tests: add tests/*.test.js (mh-mod init writes an example).'); return 0; }
  let failed = 0; let passed = 0;
  for (const name of files) {
    const tests = [];
    // Tests run in this realm, so assert.deepEqual compares the mod's answers by value.
    const body = vm.runInThisContext('(function (test, loadMod, assert, require) {' + fs.readFileSync(path.join(folder, name), 'utf8') + '\n})', {filename: name});
    body((title, fn) => tests.push({title, fn}), options => loadMod(dir, options), require('node:assert/strict'), require);
    for (const {title, fn} of tests) {
      try {
        await Promise.race([fn(), new Promise((_, reject) => setTimeout(() => reject(Error('Timed out after 10 s')), 10000).unref())]);
        passed++; console.log(`ok   ${name} > ${title}`);
      } catch (error) {
        failed++; console.log(`FAIL ${name} > ${title}\n     ${String(error && error.message || error).split('\n').join('\n     ')}`);
      }
    }
  }
  console.log(`${passed} passed, ${failed} failed`);
  return failed;
}

function init(id, dir) {
  if (!ID.test(id || '')) throw Error('Usage: mh-mod init <id> [dir], with an id like dev.yourname.mod-name');
  const target = path.resolve(dir || id.split('.').pop());
  if (fs.existsSync(path.join(target, 'mod.json'))) throw Error(`${target} already has a mod.json`);
  fs.mkdirSync(path.join(target, 'hooks'), {recursive: true});
  fs.mkdirSync(path.join(target, 'tests'), {recursive: true});
  const name = id.split('.').pop().replace(/-/g, ' ').replace(/^\w/, c => c.toUpperCase());
  fs.writeFileSync(path.join(target, 'mod.json'), JSON.stringify({
    id, name, version: '0.1.0', apiVersion: 2, description: 'What this mod does, in one line.',
    hooks: {module: 'hooks/register.js'},
    permissions: [{capability: 'ui.toast', reason: 'Says hello when a task ends'}],
    contributes: {},
  }, null, 2) + '\n');
  fs.writeFileSync(path.join(target, 'hooks', 'register.js'), `export function register(on, options) {
  on('run.end', async ($, e, next) => {
    if (e.status === 'completed') await $.ui.toast('Task done')
    return next(e)
  })
}
`);
  fs.writeFileSync(path.join(target, 'tests', 'example.test.js'), `test('says when a task is done', async () => {
  const mod = await loadMod()
  await mod.emit('run.end', { status: 'completed' })
  assert.deepEqual(mod.toasts, ['Task done'])
})
`);
  return target;
}

function pack(dir) {
  const source = ['register.tsx', 'register.jsx', 'register.ts'].map(name => path.join(dir, 'hooks', name)).find(file => fs.existsSync(file));
  if (!source) return 'Nothing to pack: hooks/register.js is already plain JavaScript.';
  const esbuild = spawnSync('esbuild', ['--version'], {encoding: 'utf8'});
  if (esbuild.status !== 0) {
    throw Error('esbuild is not installed, so JSX/TS can\'t be compiled. Write hooks/register.js in plain JavaScript and build trees with h(): h("Text", { style: "title2" }, "Hello").');
  }
  const out = path.join(dir, 'hooks', 'register.js');
  const result = spawnSync('esbuild', [source, '--format=esm', '--jsx-factory=h', '--jsx-fragment=Fragment', '--target=es2020', `--outfile=${out}`], {encoding: 'utf8'});
  if (result.status !== 0) throw Error(result.stderr.trim().slice(0, 4000));
  return `Wrote ${path.relative(process.cwd(), out)}`;
}

module.exports = {wrap, validate, loadMod, runTests, init, pack, CAPABILITIES};

if (require.main === module) {
  const [command, ...rest] = process.argv.slice(2);
  const fail = error => { console.error(error.message || String(error)); process.exitCode = 1; };
  try {
    if (command === 'init') console.log(`Created ${init(rest[0], rest[1])}`);
    else if (command === 'validate') {
      const problems = validate(path.resolve(rest[0] || '.'));
      if (problems.length) { problems.forEach(problem => console.log(`- ${problem}`)); process.exitCode = 1; } else console.log('No problems found.');
    } else if (command === 'test') runTests(path.resolve(rest[0] || '.')).then(failed => { if (failed) process.exitCode = 1; }, fail);
    else if (command === 'pack') console.log(pack(path.resolve(rest[0] || '.')));
    else console.log('Usage: mh-mod init <id> [dir] | validate [dir] | test [dir] | pack [dir]');
  } catch (error) { fail(error); }
}
