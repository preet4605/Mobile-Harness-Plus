const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
process.env.MH_MOD_SHIM = path.resolve('app/src/main/assets/mods/mh-shim.js');
const kit = require('../../app/src/main/assets/harness/mod-kit.cjs');
const base = path.resolve('.git/mod-kit-tests');
fs.mkdirSync(base, {recursive: true});
function folder(t) {
  const dir = fs.mkdtempSync(path.join(base, 'mod-'));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  return dir;
}
function writeMod(dir, code, extra = {}) {
  fs.mkdirSync(path.join(dir, 'hooks'), {recursive: true});
  fs.writeFileSync(path.join(dir, 'mod.json'), JSON.stringify({id: 'dev.test.kit', name: 'Kit', version: '1.0.0', apiVersion: 2, hooks: {module: 'hooks/register.js'}, permissions: [], ...extra}));
  fs.writeFileSync(path.join(dir, 'hooks', 'register.js'), code);
}
test('the module transform matches the app: exports kept, imports refused', () => {
  assert.match(kit.wrap('export function register(on) {}'), /exports\.register = register;/);
  assert.match(kit.wrap('export default function (on) {}'), /exports\.default = function/);
  assert.match(kit.wrap('function a() {}\nexport { a as register }'), /exports\.register = a;/);
  assert.throws(() => kit.wrap('\nimport x from "y"'), /Line 2: a mod can't import/);
});
test('validate names what the app would refuse', t => {
  const dir = folder(t);
  writeMod(dir, 'export function register(on) { on("run.end", () => eval("1")) }', {id: 'Bad', permissions: [{capability: 'files.write'}, {capability: 'root'}]});
  const problems = kit.validate(dir);
  assert.ok(problems.some(p => p.startsWith('"id"')));
  assert.ok(problems.some(p => p.includes('files.write needs')));
  assert.ok(problems.some(p => p.includes('unknown capability "root"')));
  assert.ok(problems.some(p => p.includes('eval() isn\'t available')));
  writeMod(dir, 'export function register(on) { on("run.end", (') ;
  assert.ok(kit.validate(dir).some(p => p.startsWith('hooks/register.js:')));
});
test('validate checks contributes like the app: menus, panels, schedules, guest programs', t => {
  const dir = folder(t);
  writeMod(dir, 'export function register(on) {}', {contributes: {
    slashCommands: [{name: 'lint', description: 'Lint'}],
    menuItems: [{id: 'lint', title: 'Lint', place: 'chat', command: '/lint'}, {id: 'x', title: 'X', place: 'toolbar', command: 'deploy'}],
    panels: [{id: 'board', title: 'Board', entry: 'web/missing.html'}],
    schedules: [{id: 'often', everyMinutes: 5}],
    guestTools: [{name: 'fmt', command: ['./fmt']}],
    widgets: [],
  }});
  const problems = kit.validate(dir);
  for (const fragment of ['Unknown field "contributes.widgets"', 'place must be chat, file or project', "runs /deploy, which isn't one",
    'entry must be a .html file', 'need the ui.webpane permission', 'everyMinutes must be 15 to 1440', 'need the schedules permission', 'must ask for guest.tools']) {
    assert.ok(problems.some(p => p.includes(fragment)), `${fragment} in ${JSON.stringify(problems)}`);
  }
  assert.ok(!problems.some(p => p.includes('Menu item lint')));
  fs.mkdirSync(path.join(dir, 'web'));
  fs.writeFileSync(path.join(dir, 'web', 'board.html'), '<p>hi</p>');
  writeMod(dir, 'export function register(on) {}', {
    permissions: [{capability: 'ui.webpane', reason: 'r'}, {capability: 'schedules', reason: 'r'}],
    contributes: {panels: [{id: 'board', title: 'Board', entry: 'web/board.html'}], schedules: [{id: 'hourly', everyMinutes: 60}]},
  });
  assert.deepEqual(kit.validate(dir), []);
});
test('a loaded mod answers events, gates, drawing and taps through the real shim', async t => {
  const dir = folder(t);
  writeMod(dir, `
    const count = atom('count', 0)
    export function register(on, options) {
      on('session.start', async ($, e, next) => { await $.command.register({ name: 'hi', description: 'Hi' }); return next(e) })
      on('command.run', { name: 'hi' }, async ($, e) => ({ text: options.greeting + ' ' + e.args + ' ' + (await $.agent.ask({ prompt: 'x' })).text }))
      on('prompt.submit', ($, e, next) => next({ ...e, text: e.text.toUpperCase() }))
      on('ui.render', { component: 'Pane' }, ($) => h('Button', { onPress: async () => { update($, count, n => n + 1); await $.store.set('pressed', true) } }, 'Count ' + read($, count)))
    }`);
  const mod = await kit.loadMod(dir, {options: {greeting: 'Hello'}, mocks: {'agent.ask': () => ({text: 'done'})}});
  assert.deepEqual(mod.commands, ['hi']);
  assert.deepEqual(await mod.emit('command.run', {name: 'hi', args: 'Love'}), {text: 'Hello Love done'});
  assert.deepEqual(await mod.gate('prompt.submit', {text: 'hi'}), {__mhPass: true, e: {text: 'HI'}});
  const tree = await mod.render('pane:main', {component: 'Pane'});
  assert.equal(tree.type, 'Button');
  await mod.press('pane:main', tree.props.onPress, null);
  assert.equal(mod.store.pressed, true);
  assert.equal((await mod.render('pane:main', {component: 'Pane'})).children[0].text, 'Count 1');
  await assert.rejects(kit.loadMod(dir, {}).then(m => m.emit('command.run', {name: 'hi', args: ''})), /agent\.ask isn't mocked/);
});
test('init writes a mod whose own test passes', async t => {
  const dir = folder(t);
  const target = kit.init('dev.test.greeter', path.join(dir, 'greeter'));
  assert.deepEqual(kit.validate(target), []);
  const log = []; const original = console.log; console.log = line => log.push(line);
  try { assert.equal(await kit.runTests(target), 0); } finally { console.log = original; }
  assert.ok(log.some(line => line.startsWith('ok   example.test.js')));
});
test('the kit knows the same capabilities as the app', () => {
  const source = fs.readFileSync('app/src/main/java/com/jarves/mh/mods/ModCapability.kt', 'utf8');
  const ids = [...source.matchAll(/^\s+[A-Z_]+\("([a-z.]+)", RiskLevel\./gm)].map(m => m[1]);
  assert.deepEqual([...kit.CAPABILITIES].sort(), ids.sort());
});
