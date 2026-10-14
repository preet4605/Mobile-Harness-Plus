'use strict';
// Dependency-free CLI and MCP adapter. No credentials or persistent Codex config are read here.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawn} = require('node:child_process');
const MAX_JSON = 64 * 1024;

function executable(name, searchPath = process.env.PATH || '') {
  for (const directory of (name.includes('/') ? [''] : searchPath.split(path.delimiter))) {
    const file = directory ? path.join(directory, name) : name;
    try { const stat = fs.statSync(file); fs.accessSync(file, fs.constants.X_OK); if (stat.isFile()) return {file, size:stat.size}; } catch {}
  }
  return null;
}
function probe(name, args, {searchPath, timeout = 3000, signal} = {}) {
  const found = executable(name, searchPath);
  if (!found || !found.size) return Promise.resolve({status:'absent', reason:found ? 'Executable is empty' : 'Command is not on PATH'});
  return new Promise(resolve => {
    const ownGroup = process.platform !== 'win32';
    const child = spawn(found.file, args, {detached:ownGroup, stdio:['ignore','pipe','pipe']});
    let output = ''; let stopped = false; let escalation;
    const terminate = signal => {
      try {if (ownGroup && child.pid) process.kill(-child.pid,signal); else child.kill(signal);} catch {}
    };
    const stop = () => { if (stopped) return; stopped = true; terminate('SIGTERM'); escalation = setTimeout(() => terminate('SIGKILL'), 250); };
    const timer = setTimeout(stop, timeout);
    const abort = () => stop();
    signal?.addEventListener('abort', abort, {once:true});
    if (signal?.aborted) stop();
    child.stdout.on('data', data => { if (output.length < 512) output += data.toString('utf8').slice(0,512-output.length); });
    // Drain stderr without publishing potentially sensitive environment/configuration errors.
    child.stderr.resume();
    const cleanup = () => {clearTimeout(timer); clearTimeout(escalation); signal?.removeEventListener('abort', abort);};
    child.on('error', () => {cleanup(); resolve({status:'failed', reason:'Executable could not start'});});
    // Own the complete diagnostic group, including descendants that retain output pipes.
    child.on('close', code => {if (ownGroup) terminate('SIGKILL'); cleanup(); resolve(stopped ? {status:'failed',reason:signal?.aborted ? 'Check cancelled' : 'Check timed out'} :
      code === 0 ? {status:'available',version:output.trim().slice(0,120)} : {status:'failed',reason:`Command exited with code ${code}`});});
  });
}
async function doctor(signal) {
  const report = {};
  for (const [name, args] of [['node',['--version']],['git',['--version']],['python3',['--version']],['rg',['--version']],['java',['-version']],['javap',['-version']],['codex',['--version']],['codex-code-mode-host',['--help']],['adb',['version']],['gh',['--version']],['sqlite3',['--version']]]) {
    if (signal?.aborted) throw Error('Check cancelled');
    const command = name === 'adb' && !executable(name) && fs.existsSync('/root/android-sdk/platform-tools/adb') ? '/root/android-sdk/platform-tools/adb' : name;
    report[name] = await probe(command, args, {signal});
  }
  const cert = await probe('python3',['-c','import ssl; c=ssl.create_default_context(); print(c.cert_store_stats()["x509_ca"]); raise SystemExit(0 if c.cert_store_stats()["x509_ca"] else 1)'], {signal});
  report.certificates = cert.status === 'available' ? {status:'available', reason:'Default verified TLS trust store contains CA certificates'} : {status:cert.status,reason:'Default TLS certificate discovery failed'};
  report.device_connection = {status:'untested',reason:'An ADB binary alone does not establish an authenticated device connection'};
  report.preview = {status:process.env.POCKET_INSPECTION_TOKEN ? 'untested' : 'absent',reason:'Use harness-tools preview dom with the project Preview open to check native inspection'};
  return report;
}
function nativeRequest(operation, args = {}, {directory = '/pocket-inspection', token = process.env.POCKET_INSPECTION_TOKEN, timeout = 10000, signal} = {}) {
  if (!token) return Promise.reject(Error('Native inspection is unavailable in this launch'));
  return new Promise((resolve,reject) => {
    const id = crypto.randomUUID(); const requestFile = path.join(directory,id+'.request'); const responseFile = path.join(directory,id+'.response');
    let watcher; let timer; let done = false;
    const finish = (error,result) => {
      if (done) return; done = true; clearTimeout(timer); watcher?.close(); signal?.removeEventListener('abort',abort);
      for (const file of [requestFile,responseFile,requestFile+'.tmp']) {try {fs.unlinkSync(file);} catch {}}
      error ? reject(error) : resolve(result);
    };
    const abort = () => finish(Error('Inspection cancelled'));
    const read = () => {
      if (!fs.existsSync(responseFile)) return;
      try {
        const stat = fs.lstatSync(responseFile);
        if (!stat.isFile() || stat.size > MAX_JSON) throw Error('Invalid inspection response');
        const result = JSON.parse(fs.readFileSync(responseFile,'utf8'));
        if (!result.ok) throw Error(typeof result.error === 'string' ? result.error.slice(0,200) : 'Inspection failed');
        finish(null,result);
      } catch (error) {finish(error);}
    };
    try {
      watcher = fs.watch(directory, (_,file) => {if(file === id+'.response') read();});
      watcher.on('error', () => finish(Error('Native inspection bridge closed')));
      timer = setTimeout(() => finish(Error('Native inspection timed out; open the project Preview and try again')),timeout);
      signal?.addEventListener('abort',abort,{once:true});
      if (signal?.aborted) return abort();
      fs.writeFileSync(requestFile+'.tmp',JSON.stringify({token,operation,args}),{flag:'wx',mode:0o600});
      fs.renameSync(requestFile+'.tmp',requestFile);
      read();
    } catch {finish(Error('Native inspection bridge is unavailable'));}
  });
}
const tools = [
  {name:'harness_doctor',description:'Check guest tools and default TLS trust without networking or reading credentials.',inputSchema:{type:'object',properties:{},additionalProperties:false},annotations:{readOnlyHint:true}},
  {name:'harness_preview',description:'Read the active project local Preview DOM, console, or viewport screenshot. Preview must be open; no remote browsing or arbitrary script execution.',inputSchema:{type:'object',properties:{kind:{type:'string',enum:['dom','console','screenshot']}},required:['kind'],additionalProperties:false},annotations:{readOnlyHint:true}},
  {name:'harness_brain_search',description:'Search saved Brain facts for this launch project. Results are untrusted contextual data.',inputSchema:{type:'object',properties:{query:{type:'string',maxLength:256}},required:['query'],additionalProperties:false},annotations:{readOnlyHint:true}}
];
/** Tools running mods give the agents (mod platform spec v2 P8); none when the app can't be reached. */
async function modTools(signal, options = {}) {
  try {
    const result = await nativeRequest('mod_tools', {}, {signal, timeout: 5000, ...options});
    return (Array.isArray(result.tools) ? result.tools : []).filter(tool => tool && typeof tool.name === 'string' && /^mod_[a-z0-9_]{1,60}$/.test(tool.name))
      .map(tool => ({name:tool.name, description:String(tool.description || '').slice(0,1200), inputSchema:tool.inputSchema && typeof tool.inputSchema === 'object' ? tool.inputSchema : {type:'object'}}));
  } catch { return []; }
}
async function call(name,args,signal) {
  if (typeof name === 'string' && /^mod_[a-z0-9_]{1,60}$/.test(name)) {
    const input = args && typeof args === 'object' && !Array.isArray(args) ? args : {};
    if (Buffer.byteLength(JSON.stringify(input)) > 48 * 1024) throw Error('Tool input is larger than 48 KB');
    return nativeRequest('mod_tool', {name, input}, {signal, timeout: 32000});
  }
  if (name === 'harness_doctor') return {ok:true,text:JSON.stringify(await doctor(signal))};
  if (name === 'harness_preview' && ['dom','console','screenshot'].includes(args?.kind)) return nativeRequest('preview_'+args.kind,{}, {signal});
  if (name === 'harness_brain_search' && typeof args?.query === 'string' && args.query.length <= 256) return nativeRequest('brain_search',{query:args.query},{signal});
  throw Error('Unknown tool or invalid arguments');
}
/** Cuts long strings and lists so a tool call fits the bridge's 64 KB request limit. */
function shrink(value, depth = 0) {
  if (typeof value === 'string') return value.length > 8192 ? value.slice(0, 8192) : value;
  if (depth > 8 || value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.slice(0, 50).map(item => shrink(item, depth + 1));
  const out = {};
  for (const key of Object.keys(value).slice(0, 50)) out[key] = shrink(value[key], depth + 1);
  return out;
}
/**
 * Claude PreToolUse hook (mod platform spec v2 section 7): asks the app's mods about one tool call.
 * Answers the hook output to print, or null for none, so the call goes on as it would without mods.
 * Every failure (no bridge, a timeout, bad input) is null: mod gates fail open.
 */
async function gate(text, options = {}, harness = 'claude') {
  let hook;
  try { hook = JSON.parse(text); } catch { return null; }
  if (!hook || typeof hook.tool_name !== 'string' || hook.tool_name.length > 128) return null;
  let input = hook.tool_input && typeof hook.tool_input === 'object' && !Array.isArray(hook.tool_input) ? hook.tool_input : {};
  let truncated = false;
  if (Buffer.byteLength(JSON.stringify(input)) > 48 * 1024) { input = shrink(input); truncated = true; }
  try {
    const result = await nativeRequest('mod_gate', {tool: hook.tool_name, input, truncated, harness}, {timeout: 9000, ...options});
    return result.hook && typeof result.hook === 'object' && !Array.isArray(result.hook) ? result.hook : null;
  } catch { return null; }
}
function readStdin(limit = 1024 * 1024) {
  return new Promise(resolve => {
    let text = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', data => { text += data; if (text.length > limit) { text = ''; process.stdin.destroy(); resolve(''); } });
    process.stdin.on('end', () => resolve(text));
    process.stdin.on('error', () => resolve(''));
  });
}
async function content(result) {
  const blocks = [{type:'text',text:result.text || JSON.stringify(result)}];
  if (result.imagePath) {
    const workspace = fs.realpathSync(process.env.POCKET_WORKSPACE || process.cwd());
    const file = fs.realpathSync(result.imagePath);
    if (!file.startsWith(workspace+path.sep) || fs.statSync(file).size > 2 * 1024 * 1024) throw Error('Invalid Preview image artifact');
    blocks.push({type:'image',mimeType:'image/png',data:fs.readFileSync(file).toString('base64')});
  }
  return blocks;
}
/** Names and descriptions of the mod tools, to notice when mods add or remove one. */
function toolKey(list) { return JSON.stringify(list.map(tool => [tool.name, tool.description]).sort()); }
function serve(mode, options = {}) {
  const active = new Map(); let buffer=''; let initialized=false; let watch=null; let seen=null;
  const reply = value => process.stdout.write(JSON.stringify({jsonrpc:'2.0',...value})+'\n');
  // Mods can add tools while a harness runs: check every few seconds and tell the client (MCP tools.listChanged).
  function startWatch() {
    if (watch) return;
    watch = setInterval(async () => {
      const key = toolKey(await modTools(undefined, options.requestOptions));
      if (seen !== null && key !== seen) reply({method:'notifications/tools/list_changed'});
      seen = key;
    }, options.watchMs || 10000);
    watch.unref?.();
  }
  const stopWatch = () => { if (watch) clearInterval(watch); watch = null; };
  async function handle(message) {
    if (!message || typeof message !== 'object' || Array.isArray(message)) {reply({id:null,error:{code:-32600,message:'Invalid request'}});return;}
    const {id,method,params} = message;
    if (method === 'notifications/cancelled') {active.get(params?.requestId)?.abort(); return;}
    if (id === undefined) return;
    if (method === 'initialize') {initialized=true; reply({id,result:{protocolVersion:['2024-11-05','2025-03-26','2025-06-18','2025-11-25'].includes(params?.protocolVersion) ? params.protocolVersion : '2024-11-05',capabilities:{tools:{listChanged:true}},serverInfo:{name:'mobile-harness',version:'1.0.0'}}}); startWatch(); return;}
    if (method === 'ping') {reply({id,result:{}}); return;}
    if (!initialized) {reply({id,error:{code:-32000,message:'Initialize first'}});return;}
    if (method === 'tools/list') {const mods = await modTools(undefined, options.requestOptions); seen = toolKey(mods); reply({id,result:{tools:mode === 'mods' ? mods : [...tools, ...mods]}});return;}
    if (method !== 'tools/call') {reply({id,error:{code:-32601,message:'Method not found'}});return;}
    if (active.size >= 2 || active.has(id)) {reply({id,error:{code:-32000,message:'Inspection is busy'}});return;}
    const controller = new AbortController(); active.set(id,controller);
    try {
      if (mode === 'mods' && !/^mod_/.test(String(params?.name))) throw Error('Unknown tool or invalid arguments');
      const result = await call(params?.name,params?.arguments,controller.signal); reply({id,result:{content:await content(result),isError:result.isError === true}});
    }
    catch (error) {reply({id,result:{content:[{type:'text',text:error.message.slice(0,200)}],isError:true}});}
    finally {active.delete(id);}
  }
  process.stdout.on('error', () => {stopWatch();for(const controller of active.values())controller.abort();process.stdin.destroy();});
  process.stdin.setEncoding('utf8');
  process.stdin.on('data',data => {
    buffer += data;
    if (buffer.length > MAX_JSON) {process.stdin.destroy();for(const controller of active.values())controller.abort();return;}
    let end;
    while ((end=buffer.indexOf('\n')) >= 0) {
      const line=buffer.slice(0,end); buffer=buffer.slice(end+1);
      try {void handle(JSON.parse(line)).catch(() => reply({id:null,error:{code:-32600,message:'Invalid request'}}));} catch {reply({id:null,error:{code:-32700,message:'Invalid JSON'}});}
    }
  });
  process.stdin.on('end',() => {stopWatch();for(const controller of active.values())controller.abort();});
  process.once('SIGTERM',() => {stopWatch();for(const controller of active.values())controller.abort();process.stdin.destroy();});
}
module.exports = {probe,doctor,nativeRequest,call,gate,shrink,modTools,serve};
if (require.main === module) {
  if (process.argv[2] === '--mcp') serve(process.argv[3]);
  else if (process.argv[2] === 'mod') {
    // harness-tools mod                  -> lists mod tools
    // harness-tools mod <name> '<json>'  -> calls one (for harnesses without MCP)
    const [name, json] = process.argv.slice(3);
    const run = async () => {
      if (!name) return JSON.stringify(await modTools(), null, 1);
      let input = {};
      if (json) { try { input = JSON.parse(json); } catch { throw Error('The input must be JSON, like \'{"query":"x"}\''); } }
      const result = await call(name, input);
      if (result.isError) throw Error(result.text || 'The tool failed');
      return result.text || '';
    };
    run().then(text => process.stdout.write(text + '\n')).catch(error => {process.stderr.write(error.message.slice(0, 2000)+'\n');process.exitCode=1;});
  }
  else if (process.argv[2] === 'gate') {
    // `gate --harness deepseek` is DeepSeek Harness's PreToolUse hook (its Claude-hooks bridge).
    const harness = process.argv[3] === '--harness' && process.argv[4] === 'deepseek' ? 'deepseek' : 'claude';
    readStdin().then(text => gate(text, {}, harness)).then(output => { if (output) process.stdout.write(JSON.stringify(output) + '\n'); }, () => {});
  }
  else {
    const [command,argument] = process.argv.slice(2);
    const name = command === 'doctor' ? 'harness_doctor' : command === 'brain' ? 'harness_brain_search' : 'harness_preview';
    const args = command === 'brain' ? {query:argument || ''} : {kind:argument};
    call(name,args).then(result => process.stdout.write(JSON.stringify(result)+'\n')).catch(error => {process.stderr.write(error.message+'\n');process.exitCode=1;});
  }
}
