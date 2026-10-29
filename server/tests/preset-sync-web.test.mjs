import test from 'node:test';
import assert from 'node:assert/strict';
import { PresetSyncClient } from '../web/continuity/sync.mjs';

const metadata={epoch:'server-one',deviceId:'web-one',role:'web',expiresAt:'2030-01-01T00:00:00Z'};
const packet=(revision=0,extra={})=>({state:{demo:true,revision,presets:[],selectedId:'quiet_rest'},sync:metadata,...extra});
const reply=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});
function setup(){
  const steps=[],calls=[];
  const client=new PresetSyncClient(async(url,options={})=>{calls.push({url,...options});assert.ok(steps.length,'Unexpected fetch');const next=steps.shift();return typeof next==='function'?next():next;},()=> 'test-command-0001');
  return {client,steps,calls};
}
async function connect(fixture){fixture.steps.push(reply({sync:metadata}),reply(packet()));await fixture.client.connect();await fixture.client.read();}

test('browser session uses the dedicated cookie API and never a health bearer',async()=>{
  const f=setup();await connect(f);
  assert.equal(f.client.connected,true);assert.equal(f.client.state.revision,0);
  assert.equal(f.calls[0].url,'/api/preset-sync/v1/session');
  for(const call of f.calls){assert.equal(call.credentials,'same-origin');assert.equal(call.headers?.Authorization,undefined);assert.ok(!call.url.includes('token'));}
});
test('lost command response retains exact request; fresh read does not silently unlock another write',async()=>{
  const f=setup();await connect(f);
  f.steps.push(()=>{throw new TypeError('Connection lost after send');});
  await assert.rejects(f.client.command('select_preset',{id:'cool_down'}));
  const first=JSON.parse(f.calls.at(-1).body);assert.ok(f.client.pending);
  f.steps.push(reply(packet(1)));await f.client.read();
  await assert.rejects(f.client.command('select_preset',{id:'quiet_rest'}),e=>e.code==='UNCONFIRMED');
  f.steps.push(reply(packet(1,{acknowledgedRequestId:first.requestId})));await f.client.retry();
  assert.deepEqual(JSON.parse(f.calls.at(-1).body),first);assert.equal(f.client.pending,null);assert.equal(f.client.state.revision,1);
});
test('a poll issued before a command cannot restore older selection state',async()=>{
  const f=setup();await connect(f);let release;
  f.steps.push(()=>new Promise(resolve=>{release=resolve;}));const old=f.client.read();
  f.steps.push(reply(packet(1,{acknowledgedRequestId:'test-command-0001'})));await f.client.command('select_preset',{id:'cool_down'});
  release(reply(packet(0)));await old;assert.equal(f.client.state.revision,1);
});
test('later completed older revision does not overwrite newest read',async()=>{
  const f=setup();await connect(f);let release;
  f.steps.push(()=>new Promise(resolve=>{release=resolve;}));const old=f.client.read();
  f.steps.push(reply(packet(3)));await f.client.read();release(reply(packet(2)));await old;
  assert.equal(f.client.state.revision,3);
});
test('revision conflict adopts authoritative state but never retries an editor draft',async()=>{
  const f=setup();await connect(f);
  f.steps.push(reply(packet(4,{error:{code:'REVISION_CONFLICT',message:'Review changes'}}),409));
  await assert.rejects(f.client.command('save_preset',{name:'Draft'},0,'server-one'),e=>e.status===409);
  assert.equal(f.client.state.revision,4);assert.equal(f.client.pending,null);assert.equal(f.calls.length,3);
  assert.equal(JSON.parse(f.calls[2].body).expectedRevision,0);
});
test('revocation clears connected cached state and pending intent',async()=>{
  const f=setup();await connect(f);f.steps.push(reply({error:{code:'AUTH_REQUIRED',message:'Expired'}},401));
  await assert.rejects(f.client.read(),e=>e.status===401);assert.equal(f.client.state,null);assert.equal(f.client.sync,null);assert.equal(f.client.pending,null);assert.equal(f.client.connected,false);
});
test('malformed success or mismatched acknowledgement cannot confirm a write',async()=>{
  const f=setup();await connect(f);f.steps.push(reply(packet(1,{acknowledgedRequestId:'another-request'})));
  await assert.rejects(f.client.command('select_preset',{id:'cool_down'}));assert.ok(f.client.pending);assert.equal(f.client.connected,false);
});
test('server error keeps the same uncertain request for explicit retry',async()=>{
  const f=setup();await connect(f);f.steps.push(reply({error:{code:'UNAVAILABLE',message:'Unknown result'}},500));
  await assert.rejects(f.client.command('select_preset',{id:'cool_down'}),e=>e.status===500);assert.ok(f.client.pending);
});
test('late command 200 response after 401 disconnect cannot resurrect state (R02)',async()=>{
  const f=setup();await connect(f);let releaseCommand;
  f.steps.push(()=>new Promise(resolve=>{releaseCommand=resolve;}));
  const pendingCommand=f.client.command('select_preset',{id:'cool_down'});
  f.steps.push(reply({error:{code:'AUTH_REQUIRED',message:'Expired'}},401));
  await assert.rejects(f.client.read(),e=>e.status===401);
  assert.equal(f.client.connected,false);assert.equal(f.client.state,null);
  releaseCommand(reply(packet(1,{acknowledgedRequestId:'test-command-0001'})));
  await assert.rejects(pendingCommand,e=>e.status===401);
  assert.equal(f.client.connected,false);assert.equal(f.client.state,null);assert.equal(f.client.sync,null);
});
test('non-JSON 401 response still invalidates client session (R03)',async()=>{
  const f=setup();await connect(f);
  f.steps.push(new Response('Unauthorized',{status:401,headers:{'Content-Type':'text/plain'}}));
  await assert.rejects(f.client.read(),e=>e.status===401);
  assert.equal(f.client.connected,false);assert.equal(f.client.state,null);assert.equal(f.client.sync,null);
});

test('late connect success cannot restore an explicitly invalidated session',async()=>{
  const f=setup();let release;
  f.steps.push(()=>new Promise(resolve=>{release=resolve;}));
  const opening=f.client.connect();f.client.invalidate();
  release(reply({sync:metadata}));
  await assert.rejects(opening,e=>e.code==='STALE_SESSION');
  assert.equal(f.client.sync,null);assert.equal(f.client.connected,false);
});

test('old read 401 cannot revoke a newly established browser session',async()=>{
  const f=setup();await connect(f);let release;
  f.steps.push(()=>new Promise(resolve=>{release=resolve;}));const old=f.client.read();
  f.client.invalidate();await connect(f);
  release(reply({error:{code:'UNAUTHENTICATED'}},401));
  await assert.rejects(old,e=>e.status===401);
  assert.equal(f.client.connected,true);assert.equal(f.client.state.revision,0);
});

test('late command result cannot erase a newer session after explicit reconnection',async()=>{
  const f=setup();await connect(f);let release;
  f.steps.push(()=>new Promise(resolve=>{release=resolve;}));const old=f.client.command('select_preset',{id:'cool_down'});
  f.client.invalidate();await connect(f);
  release(reply(packet(1,{acknowledgedRequestId:'test-command-0001'})));
  await assert.rejects(old,e=>e.code==='STALE_SESSION');
  assert.equal(f.client.connected,true);assert.equal(f.client.state.revision,0);
});
