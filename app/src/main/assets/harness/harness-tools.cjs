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
async function call(name,args,signal) {
  if (name === 'harness_doctor') return {ok:true,text:JSON.stringify(await doctor(signal))};
  if (name === 'harness_preview' && ['dom','console','screenshot'].includes(args?.kind)) return nativeRequest('preview_'+args.kind,{}, {signal});
  if (name === 'harness_brain_search' && typeof args?.query === 'string' && args.query.length <= 256) return nativeRequest('brain_search',{query:args.query},{signal});
  throw Error('Unknown tool or invalid arguments');
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
function serve() {
  const active = new Map(); let buffer=''; let initialized=false;
  const reply = value => process.stdout.write(JSON.stringify({jsonrpc:'2.0',...value})+'\n');
  async function handle(message) {
    if (!message || typeof message !== 'object' || Array.isArray(message)) {reply({id:null,error:{code:-32600,message:'Invalid request'}});return;}
    const {id,method,params} = message;
    if (method === 'notifications/cancelled') {active.get(params?.requestId)?.abort(); return;}
    if (id === undefined) return;
    if (method === 'initialize') {initialized=true; reply({id,result:{protocolVersion:['2024-11-05','2025-03-26','2025-06-18','2025-11-25'].includes(params?.protocolVersion) ? params.protocolVersion : '2024-11-05',capabilities:{tools:{}},serverInfo:{name:'mobile-harness',version:'1.0.0'}}}); return;}
    if (method === 'ping') {reply({id,result:{}}); return;}
    if (!initialized) {reply({id,error:{code:-32000,message:'Initialize first'}});return;}
    if (method === 'tools/list') {reply({id,result:{tools}});return;}
    if (method !== 'tools/call') {reply({id,error:{code:-32601,message:'Method not found'}});return;}
    if (active.size >= 2 || active.has(id)) {reply({id,error:{code:-32000,message:'Inspection is busy'}});return;}
    const controller = new AbortController(); active.set(id,controller);
    try {const result = await call(params?.name,params?.arguments,controller.signal); reply({id,result:{content:await content(result),isError:false}});}
    catch (error) {reply({id,result:{content:[{type:'text',text:error.message.slice(0,200)}],isError:true}});}
    finally {active.delete(id);}
  }
  process.stdout.on('error', () => {for(const controller of active.values())controller.abort();process.stdin.destroy();});
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
  process.stdin.on('end',() => {for(const controller of active.values())controller.abort();});
  process.once('SIGTERM',() => {for(const controller of active.values())controller.abort();process.stdin.destroy();});
}
module.exports = {probe,doctor,nativeRequest,call};
if (require.main === module) {
  if (process.argv[2] === '--mcp') serve();
  else {
    const [command,argument] = process.argv.slice(2);
    const name = command === 'doctor' ? 'harness_doctor' : command === 'brain' ? 'harness_brain_search' : 'harness_preview';
    const args = command === 'brain' ? {query:argument || ''} : {kind:argument};
    call(name,args).then(result => process.stdout.write(JSON.stringify(result)+'\n')).catch(error => {process.stderr.write(error.message+'\n');process.exitCode=1;});
  }
}
