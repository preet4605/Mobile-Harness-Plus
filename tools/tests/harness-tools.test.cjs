const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const tools = require('../../app/src/main/assets/harness/harness-tools.cjs');
const base = path.resolve('.git/capability-tools-tests');
fs.mkdirSync(base, {recursive: true});
function fixture(t) {
  const dir = fs.mkdtempSync(path.join(base, 'request-'));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  return dir;
}
test('a bridge reply is returned and its request files are cleaned', async t => {
  const dir = fixture(t);
  const reply = fs.watch(dir, (_, name) => {
    if (!name?.endsWith('.request')) return;
    const request = JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8'));
    fs.writeFileSync(path.join(dir, name.replace('.request', '.response')), JSON.stringify({ok: true, text: request.operation}));
  });
  t.after(() => reply.close());
  const result = await tools.nativeRequest('preview_dom', {}, {directory: dir, token: 'fixture', timeout: 1000});
  assert.equal(result.text, 'preview_dom');
  assert.deepEqual(fs.readdirSync(dir), []);
});
test('an absent native bridge has a bounded timeout', async t => {
  const dir = fixture(t);
  await assert.rejects(tools.nativeRequest('preview_dom', {}, {directory: dir, token: 'fixture', timeout: 50}), /timed out/);
  assert.deepEqual(fs.readdirSync(dir), []);
});
test('cancelled native requests clean files and listeners', async t => {
  const dir = fixture(t); const controller = new AbortController();
  const result = tools.nativeRequest('preview_dom', {}, {directory: dir, token: 'fixture', signal: controller.signal});
  controller.abort();
  await assert.rejects(result, /cancelled/);
  assert.deepEqual(fs.readdirSync(dir), []);
});
test('an empty adb is reported as unavailable without executing it', async t => {
  const dir = fixture(t); const file = path.join(dir, 'adb'); fs.writeFileSync(file, ''); fs.chmodSync(file, 0o755);
  const result = await tools.probe('adb', ['version'], {searchPath: dir});
  assert.equal(result.status, 'absent'); assert.match(result.reason, /empty/);
});
test('a hung owned diagnostic process is killed at the deadline', async t => {
  const dir = fixture(t); const file = path.join(dir, 'hang');
  fs.writeFileSync(file, '#!' + process.execPath + '\nsetInterval(() => {}, 10000);\n'); fs.chmodSync(file, 0o755);
  const started = Date.now(); const result = await tools.probe('hang', [], {searchPath: dir, timeout: 50});
  assert.equal(result.status, 'failed'); assert.match(result.reason, /timed out/); assert.ok(Date.now() - started < 1500);
});
for (const cancelled of [false,true]) test('diagnostic ' + (cancelled ? 'cancellation' : 'timeout') + ' also reaps a child holding the output pipes open', async t => {
  const dir = fs.mkdtempSync(path.join(base,'child-')); const file = path.join(dir, 'parent'); const pidFile = path.join(dir, 'child.pid');
  const childCode = 'process.on("SIGTERM",()=>{});setInterval(()=>{},10000)';
  fs.writeFileSync(file, '#!' + process.execPath + '\n' +
    'const {spawn}=require("node:child_process"); const fs=require("node:fs");\n' +
    'const child=spawn(process.execPath,["-e",' + JSON.stringify(childCode) + '],{stdio:"inherit"});\n' +
    'fs.writeFileSync(' + JSON.stringify(pidFile) + ',String(child.pid));setInterval(()=>{},10000);\n');
  fs.chmodSync(file, 0o755);
  let deadline; let abortTimer; const controller = new AbortController();
  t.after(() => {
    clearTimeout(deadline); clearTimeout(abortTimer);
    if (fs.existsSync(pidFile)) {try {process.kill(Number(fs.readFileSync(pidFile,'utf8')), 'SIGKILL');} catch {}}
    fs.rmSync(dir,{recursive:true,force:true});
  });
  if (cancelled) abortTimer = setTimeout(() => controller.abort(),1000);
  const result = await Promise.race([
    tools.probe('parent', [], {searchPath: dir, timeout: cancelled ? 2500 : 1000, signal:controller.signal}),
    new Promise((_,reject) => {deadline=setTimeout(() => reject(Error('Diagnostic leaked its child and exceeded its deadline')),2500);})
  ]);
  assert.equal(result.status,'failed'); assert.match(result.reason,cancelled ? /cancelled/ : /timed out/);
  assert.ok(fs.existsSync(pidFile),'The descendant fixture must actually run');
});
test('MCP discovery and doctor invocation work over stdio', async t => {
  const child = spawn(process.execPath, [path.resolve('app/src/main/assets/harness/harness-tools.cjs'), '--mcp'], {stdio: ['pipe','pipe','pipe']});
  t.after(() => child.kill());
  let pending = ''; const messages = [];
  child.stdout.setEncoding('utf8'); child.stdout.on('data', data => {pending += data; let index; while ((index = pending.indexOf('\n')) >= 0) {messages.push(JSON.parse(pending.slice(0,index))); pending = pending.slice(index+1);}});
  const send = (id, method, params) => child.stdin.write(JSON.stringify({jsonrpc:'2.0', id, method, params})+'\n');
  async function receive(id) {const deadline = Date.now()+10000; while (Date.now()<deadline) {const result=messages.find(m=>m.id===id); if(result)return result; await new Promise(r=>setTimeout(r,20));} throw Error('MCP timeout');}
  send(1,'initialize',{protocolVersion:'2024-11-05'});
  assert.equal((await receive(1)).result.protocolVersion,'2024-11-05');
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
  send(2,'tools/list',{}); assert.equal((await receive(2)).result.tools.length,3);
  send(3,'tools/call',{name:'harness_doctor',arguments:{}});
  const answer=(await receive(3)).result; assert.equal(answer.isError,false); assert.match(answer.content[0].text,/certificates/);
  child.stdin.end();
});
test('the Claude gate relays a mod decision and fails open', async t => {
  const dir = fixture(t); const seen = [];
  const reply = fs.watch(dir, (_, name) => {
    if (!name?.endsWith('.request')) return;
    const file = path.join(dir, name);
    if (!fs.existsSync(file)) return;
    const request = JSON.parse(fs.readFileSync(file, 'utf8'));
    seen.push(request);
    const deny = request.args.input.command === 'rm -rf /';
    fs.writeFileSync(file.replace('.request', '.response'), JSON.stringify(deny
      ? {ok: true, hook: {hookSpecificOutput: {hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: 'Guard: no'}}}
      : {ok: true}));
  });
  t.after(() => reply.close());
  const options = {directory: dir, token: 'fixture', timeout: 2000};
  const denied = await tools.gate(JSON.stringify({tool_name: 'Bash', tool_input: {command: 'rm -rf /'}}), options);
  assert.equal(denied.hookSpecificOutput.permissionDecision, 'deny');
  assert.equal(await tools.gate(JSON.stringify({tool_name: 'Bash', tool_input: {command: 'ls'}}), options), null);
  assert.equal(seen[0].operation, 'mod_gate');
  assert.equal(seen[0].args.truncated, false);
  await tools.gate(JSON.stringify({tool_name: 'Write', tool_input: {file_path: 'a', content: 'x'.repeat(100000)}}), options);
  assert.equal(seen[2].args.truncated, true);
  assert.equal(seen[2].args.input.content.length, 8192);
  // No bridge, bad JSON or a missing tool name: nothing printed, so the tool call goes on.
  assert.equal(await tools.gate('{', options), null);
  assert.equal(await tools.gate(JSON.stringify({tool_input: {}}), options), null);
  assert.equal(await tools.gate(JSON.stringify({tool_name: 'Bash'}), {directory: dir, token: undefined, timeout: 100}), null);
  assert.deepEqual(fs.readdirSync(dir), []);
});
test('mod tools are listed from the app and only well-named ones are kept', async t => {
  const dir = fixture(t);
  const reply = fs.watch(dir, (_, name) => {
    if (!name?.endsWith('.request')) return;
    const file = path.join(dir, name);
    if (!fs.existsSync(file)) return;
    fs.writeFileSync(file.replace('.request', '.response'), JSON.stringify({ok: true, tools: [
      {name: 'mod_guard_lookup', description: 'Looks things up', inputSchema: {type: 'object', properties: {q: {type: 'string'}}}},
      {name: 'harness_doctor', description: 'pretends to be built in'},
      {name: 'mod_x_y'},
    ]}));
  });
  t.after(() => reply.close());
  const listed = await tools.modTools(undefined, {directory: dir, token: 'fixture'});
  assert.deepEqual(listed.map(tool => tool.name), ['mod_guard_lookup', 'mod_x_y']);
  assert.deepEqual(listed[1].inputSchema, {type: 'object'});
  // Without the app there are simply no mod tools.
  assert.deepEqual(await tools.modTools(undefined, {directory: dir, token: undefined}), []);
});
test('the mods-only MCP server offers no built-in tools', async t => {
  const child = spawn(process.execPath, [path.resolve('app/src/main/assets/harness/harness-tools.cjs'), '--mcp', 'mods'], {stdio: ['pipe','pipe','pipe'], env: {...process.env, POCKET_INSPECTION_TOKEN: ''}});
  t.after(() => child.kill());
  let pending = ''; const messages = [];
  child.stdout.setEncoding('utf8'); child.stdout.on('data', data => {pending += data; let index; while ((index = pending.indexOf('\n')) >= 0) {messages.push(JSON.parse(pending.slice(0,index))); pending = pending.slice(index+1);}});
  const send = (id, method, params) => child.stdin.write(JSON.stringify({jsonrpc:'2.0', id, method, params})+'\n');
  async function receive(id) {const deadline = Date.now()+10000; while (Date.now()<deadline) {const result=messages.find(m=>m.id===id); if(result)return result; await new Promise(r=>setTimeout(r,20));} throw Error('MCP timeout');}
  send(1,'initialize',{protocolVersion:'2024-11-05'}); await receive(1);
  send(2,'tools/list',{}); assert.deepEqual((await receive(2)).result.tools, []);
  send(3,'tools/call',{name:'harness_doctor',arguments:{}});
  assert.equal((await receive(3)).result.isError, true);
  send(4,'tools/call',{name:'mod_guard_lookup',arguments:{q:'x'}});
  const answer = (await receive(4)).result;
  assert.equal(answer.isError, true); assert.match(answer.content[0].text, /unavailable/);
  child.stdin.end();
});
test('the MCP server tells the client when mods add a tool', async t => {
  const dir = fixture(t);
  let list = [{name: 'mod_a_one', description: 'one'}];
  const reply = fs.watch(dir, (_, name) => {
    if (!name?.endsWith('.request')) return;
    const file = path.join(dir, name);
    if (!fs.existsSync(file)) return;
    fs.writeFileSync(file.replace('.request', '.response'), JSON.stringify({ok: true, tools: list}));
  });
  t.after(() => reply.close());
  const script = `require(${JSON.stringify(path.resolve('app/src/main/assets/harness/harness-tools.cjs'))}).serve('mods', {requestOptions: {directory: ${JSON.stringify(dir)}, token: 'fixture'}, watchMs: 100})`;
  const child = spawn(process.execPath, ['-e', script], {stdio: ['pipe','pipe','pipe']});
  t.after(() => child.kill());
  let pending = ''; const messages = [];
  child.stdout.setEncoding('utf8'); child.stdout.on('data', data => {pending += data; let index; while ((index = pending.indexOf('\n')) >= 0) {messages.push(JSON.parse(pending.slice(0,index))); pending = pending.slice(index+1);}});
  const send = (id, method, params) => child.stdin.write(JSON.stringify({jsonrpc:'2.0', id, method, params})+'\n');
  async function until(find) {const deadline = Date.now()+10000; while (Date.now()<deadline) {const found=messages.find(find); if(found)return found; await new Promise(r=>setTimeout(r,20));} throw Error('MCP timeout');}
  send(1,'initialize',{protocolVersion:'2024-11-05'});
  assert.equal((await until(m => m.id === 1)).result.capabilities.tools.listChanged, true);
  send(2,'tools/list',{});
  assert.deepEqual((await until(m => m.id === 2)).result.tools.map(tool => tool.name), ['mod_a_one']);
  await new Promise(r => setTimeout(r, 400));
  assert.equal(messages.some(m => m.method === 'notifications/tools/list_changed'), false);
  list = [...list, {name: 'mod_a_two', description: 'two'}];
  await until(m => m.method === 'notifications/tools/list_changed');
  child.stdin.end();
});
