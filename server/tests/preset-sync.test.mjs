import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { readFileSync, mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createPresetSyncService } from '../server/preset-sync/service.mjs';
import { preferences, validatePreferences, recoverStalePreferenceLock } from '../server/preset-sync/store.mjs';
import { ContinuityEngine } from '../server/continuity/engine.mjs';
import { createTestTlsFixture } from './fixtures/ephemeral-tls.mjs';

const { key, cert } = createTestTlsFixture();
const tls = { key, cert };

test('Grok reference media stays in the browser allowlist and supports bounded video seeking', async t => {
  const f=await fixture(t);
  async function asset(path, {method='GET',range,secure=false}={}) {
    const server=secure?f.service.httpsServer:f.service.httpServer;
    return new Promise((resolve,reject)=>{
      const req=(secure?https:http).request({hostname:'127.0.0.1',port:server.address().port,path,method,ca:tls.cert,headers:range?{Range:range}:{}},res=>{
        const chunks=[];res.on('data',c=>chunks.push(c));res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,bytes:Buffer.concat(chunks)}));
      });req.on('error',reject);req.end();
    });
  }
  assert.equal((await asset('/rest-guide/')).status,200);
  assert.equal((await asset('/rest-guide/assets/graphite-resume-v5.png')).headers['content-type'],'image/png');
  const path='/rest-guide/assets/graphite-guided-v5-grok.mp4';
  const all=await asset(path),head=await asset(path,{method:'HEAD'});
  assert.equal(all.status,200);assert.equal(head.bytes.length,0);assert.equal(Number(head.headers['content-length']),all.bytes.length);
  const range=await asset(path,{range:'bytes=10-29'});
  assert.equal(range.status,206);assert.deepEqual(range.bytes,all.bytes.subarray(10,30));
  assert.equal(range.headers['content-range'],`bytes 10-29/${all.bytes.length}`);
  assert.deepEqual((await asset(path,{range:'bytes=-10'})).bytes,all.bytes.subarray(-10));
  assert.equal((await asset(path,{range:'bytes=999999999-'})).status,416);
  assert.equal((await asset(path,{range:'bytes=0-1,3-4'})).status,416);
  assert.equal((await asset(path,{range:'bytes=-0'})).status,416);
  assert.equal((await asset(path,{secure:true})).status,404);
  assert.equal((await asset('/rest-guide/assets/manifest.json')).status,404);
  assert.equal((await asset('/rest-guide/assets/prompts.md')).status,404);
  for (const name of ['hybrid-motion', 'code-motion', 'graphite-guided-loop-10s', 'graphite-resume-loop-10s', 'pearl-guided-loop-10s', 'umber-freedom-loop-10s']) {
    const url = '/rest-guide/assets/' + name + '.mp4';
    const response = await asset(url, {method:'HEAD'});
    assert.equal(response.status,200);assert.equal(response.headers['content-type'],'video/mp4');
    assert.ok(Number(response.headers['content-length'])>1000);
    assert.equal((await asset(url,{secure:true})).status,404);
  }
  assert.equal((await asset('/reference/motion-handoff-2026-10-25/Chabun/hybrid-motion/AGENTS.md')).status,404);
});
async function fixture(t, options={}) {
  let time = Date.now();
  const service=createPresetSyncService({tls,now:()=>time,...options});
  await Promise.all([service.httpServer,service.httpsServer].map(s=>new Promise(r=>s.listen(0,'127.0.0.1',r))));
  t.after(()=>service.close());
  async function call(path, {secure=false,method='GET',body,cookie,token,headers={}}={}) {
    const server=secure?service.httpsServer:service.httpServer;
    return new Promise((resolve,reject)=>{
      const req=(secure?https:http).request({hostname:'127.0.0.1',port:server.address().port,path:'/api/preset-sync/v1'+path,method,ca:tls.cert,headers:{...(body?{'Content-Type':'application/json'}:{}),...(cookie?{Cookie:cookie}:{}),...(token?{Authorization:'Bearer '+token}:{}),...headers}},res=>{
        const chunks=[];res.on('data',c=>chunks.push(c));res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:JSON.parse(Buffer.concat(chunks))}));
      }); req.on('error',reject);req.end(body?JSON.stringify(body):undefined);
    });
  }
  const session=await call('/session');
  const cookie=session.headers['set-cookie'][0].split(';')[0];
  async function pair(role='phone',approve=true) {
    const p=await call('/pairings',{method:'POST',cookie,body:{role}});
    const c=await call('/pairings/claim',{method:'POST',secure:true,body:{role,code:p.body.code}});
    assert.equal(c.status,200);
    if(approve) assert.equal((await call('/pairings/approve',{method:'POST',cookie,body:{pairingId:c.body.pairingId,comparisonCode:c.body.comparisonCode,confirmed:true}})).status,200);
    return c.body;
  }
  async function command(action,payload,extra={}) {
    const current=(await call('/state',{cookie})).body;
    return call('/commands',{method:'POST',cookie,body:{action,payload,expectedEpoch:current.sync.epoch,expectedRevision:current.state.revision,requestId:randomUUID(),...extra}});
  }
  return {service,call,cookie,pair,command,session,advance:ms=>{time+=ms;}};
}

async function delayedPost(f,path,payload,{token,cookie,secure=Boolean(token)}={}) {
  const server=secure?f.service.httpsServer:f.service.httpServer;
  const started=new Promise(resolve=>server.once('request',req=>req.once('data',resolve)));
  const text=JSON.stringify(payload);let request;
  const response=new Promise((resolve,reject)=>{
    request=(secure?https:http).request({hostname:'127.0.0.1',port:server.address().port,path:'/api/preset-sync/v1'+path,method:'POST',ca:tls.cert,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(cookie?{Cookie:cookie}:{})}},res=>{
      const chunks=[];res.on('data',c=>chunks.push(c));res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(Buffer.concat(chunks))}));
    });request.on('error',reject);request.write(text.slice(0,1));
  });
  await started;
  return ()=>{request.end(text.slice(1));return response;};
}

for(const mode of ['revoked','expired'])test(`partially received command cannot mutate after actor ${mode}`,async t=>{
  const f=await fixture(t),native=await f.pair();
  const before=f.service.engine.getState();
  const finish=await delayedPost(f,'/commands',{action:'select_preset',payload:{id:'cool_down'},expectedEpoch:f.session.body.sync.epoch,expectedRevision:0,requestId:randomUUID()},{token:native.token});
  if(mode==='revoked')assert.equal((await f.call('/session',{method:'DELETE',secure:true,token:native.token})).status,200);
  else f.advance(7200001);
  const result=await finish();assert.equal(result.status,401);assert.equal(result.body.state,undefined);assert.deepEqual(f.service.engine.getState(),before);
});

test('delayed web mutations cannot outlive web session or resurrect expired pending native',async t=>{
  const f=await fixture(t);
  let finish=await delayedPost(f,'/pairings',{role:'phone'},{cookie:f.cookie});
  await f.call('/session',{method:'DELETE',cookie:f.cookie});assert.equal((await finish()).status,401);
  const g=await fixture(t),pending=await g.pair('phone',false);
  finish=await delayedPost(g,'/pairings/approve',{pairingId:pending.pairingId,comparisonCode:pending.comparisonCode,confirmed:true},{cookie:g.cookie});
  g.advance(300001);assert.equal((await finish()).status,400);
  assert.equal((await g.call('/pairings/status',{secure:true,token:pending.token})).status,401);
});

test('delayed approval rejects revoked web and delayed pairing creation rejects expired web',async t=>{
  const f=await fixture(t),pending=await f.pair('phone',false);
  let finish=await delayedPost(f,'/pairings/approve',{pairingId:pending.pairingId,comparisonCode:pending.comparisonCode,confirmed:true},{cookie:f.cookie});
  await f.call('/session',{method:'DELETE',cookie:f.cookie});assert.equal((await finish()).status,401);
  assert.equal((await f.call('/pairings/status',{secure:true,token:pending.token})).body.status,'pending');
  const g=await fixture(t);finish=await delayedPost(g,'/pairings',{role:'phone'},{cookie:g.cookie});
  g.advance(8*3600000+1);assert.equal((await finish()).status,401);
});

test('delayed unauthenticated claim does not issue token for expired pairing',async t=>{
  const f=await fixture(t),pairing=await f.call('/pairings',{method:'POST',cookie:f.cookie,body:{role:'phone'}});
  const finish=await delayedPost(f,'/pairings/claim',{role:'phone',code:pairing.body.code},{secure:true});
  f.advance(300001);const result=await finish();assert.equal(result.status,401);assert.equal(result.body.token,undefined);
  assert.deepEqual((await f.call('/devices',{cookie:f.cookie})).body.devices,[]);
});

test('pairing requires TLS and approval; roles, origin, expiry and revocation are enforced',async t=>{
  const f=await fixture(t);
  assert.equal((await f.call('/state')).status,401);
  assert.equal((await f.call('/session',{headers:{Origin:'https://evil.example'}})).status,403);
  assert.equal((await f.call('/state',{cookie:f.cookie,headers:{'Sec-Fetch-Site':'cross-site'}})).status,403);
  assert.equal((await f.call('/pairings/claim',{method:'POST',body:{role:'phone',code:'000000'}})).status,403);
  const p=await f.pair('phone',false);
  assert.equal((await f.call('/state',{secure:true,token:p.token})).status,403);
  assert.equal((await f.call('/state',{token:p.token})).status,403);
  assert.equal((await f.call('/state',{secure:true,token:'health-token-independent'})).status,401);
  assert.equal((await f.call('/pairings/approve',{method:'POST',cookie:f.cookie,body:{pairingId:p.pairingId,comparisonCode:p.comparisonCode,confirmed:true}})).status,200);
  const status=await f.call('/pairings/status',{secure:true,token:p.token});
  assert.equal(status.body.status,'approved');
  assert.equal((await f.call('/pairings',{method:'POST',secure:true,token:p.token,body:{role:'vehicle'}})).status,403);
  assert.equal((await f.call('/devices/'+status.body.sync.deviceId,{method:'DELETE',cookie:f.cookie})).status,200);
  assert.equal((await f.call('/state',{secure:true,token:p.token})).status,401);
  const q=await f.pair();f.advance(7200001);
  assert.equal((await f.call('/state',{secure:true,token:q.token})).status,401);
});

test('web phone vehicle share revisions; actor idempotency conflicts and strict schemas',async t=>{
  const f=await fixture(t),phone=await f.pair(),vehicle=await f.pair('vehicle');
  const initial=(await f.call('/state',{cookie:f.cookie})).body;
  const body={action:'select_preset',payload:{id:'cool_down'},expectedEpoch:initial.sync.epoch,expectedRevision:0,requestId:randomUUID()};
  const sent=await f.call('/commands',{method:'POST',secure:true,token:phone.token,body});
  assert.equal(sent.status,200);assert.equal(sent.body.state.events[0].origin,'phone');
  assert.equal((await f.call('/state',{secure:true,token:vehicle.token})).body.state.selectedId,'cool_down');
  assert.equal((await f.call('/commands',{method:'POST',secure:true,token:phone.token,body})).body.state.revision,1);
  assert.equal((await f.call('/commands',{method:'POST',secure:true,token:phone.token,body:{...body,payload:{id:'quiet_rest'}}})).status,409);
  assert.equal((await f.call('/commands',{method:'POST',secure:true,token:vehicle.token,body})).status,409);
  assert.equal((await f.command('set_feeling',{value:'tired'},{expectedEpoch:randomUUID()})).status,409);
  assert.equal((await f.call('/commands',{method:'POST',secure:true,token:vehicle.token,body:{...body,action:'set_vehicle_gate',payload:{parked:false},expectedRevision:1,requestId:randomUUID()}})).status,403);
  assert.equal((await f.call('/commands',{method:'POST',secure:true,token:phone.token,body:{...body,action:'begin_rest',payload:{origin:'vehicle'},expectedRevision:1,requestId:randomUUID()}})).status,403);
  assert.equal((await f.command('select_preset',{id:'quiet_rest',health:{}})).status,400);
  assert.equal((await f.command('select_preset',{id:'gentle_refresh'})).body.state.events[0].origin,'web');
});

test('only the phone may send a rest with its cabin settings to the car (propose_rest)',async t=>{
  const f=await fixture(t),phone=await f.pair(),vehicle=await f.pair('vehicle');
  const cabin={seatDriver:{position:'keep',comfort:'vent',level:2},seatPassenger:{position:'keep',comfort:'keep',level:1},
    climDriver:{mode:'keep',temp:24,fan:'auto',dir:'keep',ac:'keep'},climPassenger:{mode:'keep',temp:24,fan:'auto',dir:'keep',ac:'keep'},
    light:{mode:'custom',color:'sage',bright:60}};
  const payload={preset:{name:'조용히 쉬기',comfort:'quiet_rest',vehicle:{tempC:24,fan:'low',seat:'keep'},cabin}};
  const current=async()=>(await f.call('/state',{cookie:f.cookie})).body;
  const as=async(token,s)=>f.call('/commands',{method:'POST',secure:true,token,body:{action:'propose_rest',payload,expectedEpoch:s.sync.epoch,expectedRevision:s.state.revision,requestId:randomUUID()}});
  let s=await current();
  assert.equal((await f.command('propose_rest',payload)).status,403,'local web page refused');
  assert.equal((await as(vehicle.token,s)).status,403,'car refused');
  assert.equal((await current()).state.devices.vehicle.proposal,null,'nothing proposed by refused roles');
  s=await current();
  const sent=await as(phone.token,s);
  assert.equal(sent.status,200);
  const proposal=sent.body.state.devices.vehicle.proposal;
  assert.match(proposal.id,/^phone-/);assert.deepEqual(proposal.cabin,cabin);assert.equal(sent.body.state.events[0].origin,'phone');
  assert.equal(sent.body.state.presets.length,3,'a proposal is not saved as a preset');
  const seen=(await f.call('/state',{secure:true,token:vehicle.token})).body.state.devices.vehicle.proposal;
  assert.deepEqual(seen,proposal,'the car reads the same proposal');
});

test('atomic preferences survive restart only; corrupt files and duplicate writers fail closed',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'chabun-sync-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
  const storeFile=join(dir,'preferences.json');const f=await fixture(t,{storeFile});
  const native=await f.pair();
  assert.throws(()=>createPresetSyncService({storeFile,tls}));
  assert.equal((await f.command('set_default',{id:'cool_down',autoDefaultOnRest:true})).status,200);
  await f.command('set_feeling',{value:'tired'});
  const disk=JSON.parse(readFileSync(storeFile));assert.deepEqual(Object.keys(disk).sort(),['autoDefaultOnRest','defaultId','presets','schemaVersion','selectedId']);
  await f.service.close();const g=await fixture(t,{storeFile});
  assert.equal((await g.call('/state',{secure:true,token:native.token})).status,401);
  assert.equal((await g.call('/state',{cookie:f.cookie})).status,401);
  assert.equal(g.service.engine.getState().defaultId,'cool_down');assert.equal(g.service.engine.getState().feeling,'skip');assert.equal(g.service.engine.getState().revision,0);
  assert.notEqual(g.session.body.sync.epoch,f.session.body.sync.epoch);
  await g.service.close();disk.health={};writeFileSync(storeFile,JSON.stringify(disk));assert.throws(()=>createPresetSyncService({storeFile,tls}));
  writeFileSync(storeFile,'x'.repeat(128*1024+1));assert.throws(()=>createPresetSyncService({storeFile,tls}));
  writeFileSync(storeFile,'{broken');assert.throws(()=>createPresetSyncService({storeFile,tls}));
});

test('failed durable commit rolls memory revision and idempotency back, then same request succeeds',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'chabun-sync-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
  const storeFile=join(dir,'preferences.json'),f=await fixture(t,{storeFile});
  mkdirSync(storeFile);const id=randomUUID();
  const failed=await f.command('select_preset',{id:'cool_down'},{requestId:id});assert.equal(failed.status,503);assert.equal(f.service.engine.getState().revision,0);
  rmSync(storeFile,{recursive:true});const retry=await f.command('select_preset',{id:'cool_down'},{requestId:id});assert.equal(retry.status,200);assert.equal(retry.body.state.revision,1);
  await f.service.close();
});

test('arrival snapshot, requested vs observed, cancellation do not mutate preferences',async t=>{
  const f=await fixture(t);
  const saved=await f.command('save_preset',{name:'Test',comfort:'quiet_rest',vehicle:{tempC:23,fan:'low',seat:'keep'},home:{tempC:22,fan:'low',power:'on'}});
  const id=saved.body.state.selectedId;
  await f.command('arm_arrival',{presetId:id,target:'home-ac',etaMinutes:10,leadMinutes:5,consent:true});
  await f.command('delete_preset',{id});
  const requested=await f.command('advance_time',{minutes:5});
  assert.equal(requested.body.state.plan.presetSnapshot.id,id);assert.equal(requested.body.state.plan.status,'requested');assert.equal(requested.body.state.devices.home.tempC,27);
  assert.equal((await f.command('confirm_home',{})).body.state.plan.status,'confirmed_demo');
  const cancelled=await f.command('cancel_arrival',{});assert.equal(cancelled.body.state.devices.home.tempC,22);
});

test('strict durable schema rejects corruption, builtin drift, duplicate IDs and dangling references',()=>{
  const defaults=new ContinuityEngine().getState(),valid=preferences(defaults);
  for(const mutate of [x=>x.schemaVersion=2,x=>x.presets[0].vehicle.tempC=99,x=>x.presets[0].name='changed',x=>x.presets.push(x.presets[0]),x=>x.selectedId='absent',x=>x.defaultId=null,x=>x.autoDefaultOnRest='true',x=>x.presets[0].home.location='secret',x=>x.events=[],x=>x.presets=[]]) {
    const broken=structuredClone(valid);mutate(broken);assert.throws(()=>validatePreferences(broken,defaults));
  }
  assert.deepEqual(validatePreferences(valid,defaults),valid);
});

test('pairing expiry, claim rate bounds, cookie isolation, UUID and JSON type checks',async t=>{
  const f=await fixture(t);
  assert.match(f.session.headers['set-cookie'][0],/HttpOnly; SameSite=Strict; Path=\//);
  assert.equal(f.session.headers['cache-control'],'no-store');
  assert.equal((await f.call('/state',{secure:true,cookie:f.cookie})).status,401);
  assert.equal((await f.command('select_preset',{id:'quiet_rest'},{requestId:'not-a-uuid'})).status,400);
  assert.equal((await f.call('/pairings/claim',{secure:true,method:'POST',body:{role:'phone',code:123456}})).status,400);
  const p=await f.pair('phone',false);f.advance(300001);
  assert.equal((await f.call('/pairings/status',{secure:true,token:p.token})).status,401);
  for(let i=0;i<30;i++)await f.call('/pairings/claim',{secure:true,method:'POST',body:{role:'phone',code:'000000'}});
  assert.equal((await f.call('/pairings/claim',{secure:true,method:'POST',body:{role:'phone',code:'000000'}})).status,429);
  f.advance(8*3600000);assert.equal((await f.call('/state',{cookie:f.cookie})).status,401);
});

test('explicit stale lock recovery refuses live and malformed PIDs, recovers dead PID without touching preferences',async t=>{
  const dir=mkdtempSync(join(tmpdir(),'chabun-lock-'));t.after(()=>rmSync(dir,{recursive:true,force:true}));
  const file=join(dir,'preferences.json');writeFileSync(file,'preserve exactly');writeFileSync(file+'.lock',String(process.pid));
  assert.throws(()=>recoverStalePreferenceLock(file));assert.equal(readFileSync(file+'.lock','utf8'),String(process.pid));
  writeFileSync(file+'.lock','not a pid');assert.throws(()=>recoverStalePreferenceLock(file));
  const child=spawn(process.execPath,['-e','process.exit(0)'],{windowsHide:true,stdio:'ignore'});
  const pid=child.pid;await new Promise((resolve,reject)=>{child.on('exit',resolve);child.on('error',reject);});
  writeFileSync(file+'.lock',String(pid));assert.deepEqual(recoverStalePreferenceLock(file),{recovered:true});
  assert.equal(readFileSync(file,'utf8'),'preserve exactly');
});

// Appearance reports use preset credentials, while health and preset state DTOs stay unchanged.
const appearanceReport = (epoch, expectedRevision=0, requestId=randomUUID()) => ({schemaVersion:1,themeId:'graphite',paletteVersion:1,expectedEpoch:epoch,expectedRevision,requestId});
test('appearance requires approval and only vehicle actors may report their rendered theme', async t=>{
  const f=await fixture(t),phone=await f.pair(),pending=await f.pair('vehicle',false);
  assert.equal((await f.call('/appearance')).status,401);
  assert.equal((await f.call('/appearance',{secure:true,token:pending.token})).status,403);
  const body=appearanceReport(f.session.body.sync.epoch);
  assert.equal((await f.call('/appearance',{method:'POST',secure:true,token:phone.token,body})).status,403);
  assert.equal((await f.call('/appearance',{method:'POST',cookie:f.cookie,body})).status,403);
  assert.equal((await f.call('/appearance',{cookie:f.cookie})).body.appearance.vehicles.length,0);
});
test('appearance report reaches phone independently of health and synthetic preset state', async t=>{
  const f=await fixture(t),vehicle=await f.pair('vehicle'),phone=await f.pair();
  const before=f.service.engine.getState(),body=appearanceReport(f.session.body.sync.epoch);
  const sent=await f.call('/appearance',{method:'POST',secure:true,token:vehicle.token,body});
  assert.equal(sent.status,200);assert.equal(sent.body.acknowledgedRequestId,body.requestId);
  const read=await f.call('/appearance',{secure:true,token:phone.token});
  assert.equal(read.status,200);assert.equal(read.body.sync.role,'phone');
  const report=read.body.appearance.vehicles[0];
  assert.equal(report.themeId,'graphite');assert.equal(report.paletteVersion,1);assert.equal(report.revision,1);
  assert.equal(Date.parse(report.expiresAt)-Date.parse(report.reportedAt),120000);
  assert.equal(report.deviceId,sent.body.sync.deviceId);
  assert.equal(read.body.appearance.schemaVersion,1);assert.ok(Date.parse(read.body.appearance.serverTime));
  assert.equal(read.body.state,undefined);assert.deepEqual(f.service.engine.getState(),before);
});
test('appearance retry is idempotent, revision checked, and does not refresh an old report', async t=>{
  const f=await fixture(t),v=await f.pair('vehicle');
  const send=body=>f.call('/appearance',{method:'POST',secure:true,token:v.token,body});
  const body=appearanceReport(f.session.body.sync.epoch),first=await send(body);assert.equal(first.status,200);
  f.advance(10000);
  const repeat=await send(body);assert.equal(repeat.status,200);
  assert.deepEqual(repeat.body.appearance.vehicles,first.body.appearance.vehicles);
  assert.equal((await send({...body,expectedRevision:1})).status,409);
  assert.equal((await send(appearanceReport(f.session.body.sync.epoch,0))).status,409);
  assert.equal((await send(appearanceReport(randomUUID(),1))).status,409);
  const next=await send(appearanceReport(f.session.body.sync.epoch,1));assert.equal(next.status,200);assert.equal(next.body.appearance.vehicles[0].revision,2);
  assert.equal((await send({...appearanceReport(f.session.body.sync.epoch,2),themeId:'pearl'})).status,400);
  assert.equal((await send({...appearanceReport(f.session.body.sync.epoch,2),health:{}})).status,400);
});
test('appearance accepts exactly graphite, paper (Pearl) and umber and follows each theme change', async t=>{
  const f=await fixture(t),v=await f.pair('vehicle'),phone=await f.pair(),epoch=f.session.body.sync.epoch;
  const send=body=>f.call('/appearance',{method:'POST',secure:true,token:v.token,body});
  let revision=0;
  for(const themeId of ['umber','paper','graphite','umber']) {
    const body={...appearanceReport(epoch,revision),themeId},sent=await send(body);
    assert.equal(sent.status,200);assert.equal(sent.body.acknowledgedRequestId,body.requestId);
    revision++;
    const read=await f.call('/appearance',{secure:true,token:phone.token});
    assert.equal(read.status,200);assert.equal(read.body.appearance.vehicles.length,1);
    assert.equal(read.body.appearance.vehicles[0].themeId,themeId);assert.equal(read.body.appearance.vehicles[0].revision,revision);
    assert.equal(read.body.appearance.vehicles[0].paletteVersion,1);
  }
  for(const themeId of ['neon','pearl','Paper','GRAPHITE',' umber','',null,1,['graphite'],{id:'graphite'}]) {
    const rejected=await send({...appearanceReport(epoch,revision),themeId});
    assert.equal(rejected.status,400);assert.equal(rejected.body.error.code,'UNSUPPORTED_APPEARANCE');
  }
  const missing=appearanceReport(epoch,revision);delete missing.themeId;
  assert.equal((await send(missing)).body.error.code,'INVALID_FIELDS');
  for(const change of [{schemaVersion:2},{paletteVersion:2}])
    assert.equal((await send({...appearanceReport(epoch,revision),themeId:'paper',...change})).body.error.code,'UNSUPPORTED_APPEARANCE');
  const retry={...appearanceReport(epoch,revision),themeId:'paper'};assert.equal((await send(retry)).status,200);
  const changedRetry=await send({...retry,themeId:'graphite'});
  assert.equal(changedRetry.status,409);assert.equal(changedRetry.body.error.code,'REQUEST_CONFLICT');
  const latest=(await f.call('/appearance',{secure:true,token:phone.token})).body.appearance.vehicles[0];
  assert.equal(latest.themeId,'paper');assert.equal(latest.revision,revision+1);
});
test('appearance keeps distinct vehicle sources and removes revoked reports', async t=>{
  const f=await fixture(t),a=await f.pair('vehicle'),b=await f.pair('vehicle');
  for(const v of [a,b])assert.equal((await f.call('/appearance',{method:'POST',secure:true,token:v.token,body:appearanceReport(f.session.body.sync.epoch)})).status,200);
  const reports=(await f.call('/appearance',{cookie:f.cookie})).body.appearance.vehicles;
  assert.equal(reports.length,2);assert.notEqual(reports[0].deviceId,reports[1].deviceId);
  await f.call('/session',{method:'DELETE',secure:true,token:a.token});
  assert.equal((await f.call('/appearance',{cookie:f.cookie})).body.appearance.vehicles.length,1);
  f.advance(7200001);
  assert.equal((await f.call('/appearance',{cookie:f.cookie})).body.appearance.vehicles.length,0);
});
test('appearance freshness expires without inventing a new observed value', async t=>{
  const f=await fixture(t),v=await f.pair('vehicle');
  const first=await f.call('/appearance',{method:'POST',secure:true,token:v.token,body:appearanceReport(f.session.body.sync.epoch)});
  assert.equal(first.status,200);f.advance(120001);
  const read=(await f.call('/appearance',{cookie:f.cookie})).body.appearance;
  assert.deepEqual(read.vehicles,first.body.appearance.vehicles);
  assert.ok(Date.parse(read.vehicles[0].expiresAt)<Date.parse(read.serverTime));
});
test('slow appearance body cannot publish after its credential has been revoked', async t=>{
  const f=await fixture(t),v=await f.pair('vehicle');
  const finish=await delayedPost(f,'/appearance',appearanceReport(f.session.body.sync.epoch),{token:v.token});
  await f.call('/session',{method:'DELETE',secure:true,token:v.token});
  assert.equal((await finish()).status,401);
  assert.equal((await f.call('/appearance',{cookie:f.cookie})).body.appearance.vehicles.length,0);
});
