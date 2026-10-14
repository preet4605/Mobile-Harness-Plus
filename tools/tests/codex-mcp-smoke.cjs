'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const fixtureHome = path.resolve('.git/codex-mcp-smoke-home');
fs.mkdirSync(fixtureHome,{recursive:true});
const script = path.resolve('app/src/main/assets/harness/harness-tools.cjs');
const override = `mcp_servers.pocket_native_inspection={command=${JSON.stringify(process.execPath)},args=[${JSON.stringify(script)},"--mcp"],env_vars=["POCKET_INSPECTION_TOKEN","POCKET_WORKSPACE","PATH"],startup_timeout_sec=10,tool_timeout_sec=40,required=false}`;
const child = spawn('/usr/local/bin/codex',['app-server','--stdio','-c',override],{
  cwd:process.cwd(), env:{...process.env,CODEX_HOME:fixtureHome},stdio:['pipe','pipe','pipe']
});
let buffer=''; const waiting=new Map(); const replies=new Map();
child.stderr.resume();
child.stdout.setEncoding('utf8'); child.stdout.on('data',data=>{
 buffer+=data; let end;
 while((end=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,end);buffer=buffer.slice(end+1); const msg=JSON.parse(line);
  if(msg.id!==undefined){const callback=waiting.get(msg.id);callback ? callback(msg) : replies.set(msg.id,msg);}
 }
});
let sequence=0;
function rpc(method,params){const id=++sequence;return new Promise((resolve,reject)=>{
 const timer=setTimeout(()=>{waiting.delete(id);reject(Error(method+' timed out'));},20000);
 const accept=msg=>{clearTimeout(timer);waiting.delete(id);msg.error ? reject(Error(method+': '+JSON.stringify(msg.error))) : resolve(msg.result);};
 waiting.set(id,accept);child.stdin.write(JSON.stringify({id,method,params})+'\n');if(replies.has(id))accept(replies.get(id));
});}
(async()=>{
 try {
  await rpc('initialize',{clientInfo:{name:'harness-offline-smoke',version:'1.0.0'},capabilities:{experimentalApi:true}});
  child.stdin.write(JSON.stringify({method:'initialized',params:{}})+'\n');
  const thread=await rpc('thread/start',{cwd:process.cwd(),ephemeral:true,approvalPolicy:'never',sandbox:'danger-full-access'});
  const id=thread.thread.id;
  const status=await rpc('mcpServerStatus/list',{threadId:id,serverName:'pocket_native_inspection'});
  const server=status.data.find(s=>s.name==='pocket_native_inspection');
  if(!server)throw Error('Managed MCP server missing');
  const names=Object.keys(server.tools).sort(); if(names.length!==3)throw Error('Wrong tool inventory: '+names);
  const result=await rpc('mcpServer/tool/call',{threadId:id,server:'pocket_native_inspection',tool:'harness_doctor',arguments:{}});
  const text=result.content?.find(c=>c.type==='text')?.text;
  if(!text?.includes('certificates') || result.isError)throw Error('Doctor did not return usable data');
  console.log(JSON.stringify({status:'VERIFIED',codex:'0.161.0',tools:names,doctorCalled:true,liveModelRequest:false}));
 } catch(error){console.error(error.message);process.exitCode=1;}
 finally {child.stdin.end();const timer=setTimeout(()=>child.kill('SIGKILL'),1000);child.once('exit',()=>clearTimeout(timer));}
})();
