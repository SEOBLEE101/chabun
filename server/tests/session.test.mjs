import test from 'node:test';
import assert from 'node:assert/strict';
import { Session, blockers } from '../web/session.mjs';

const safe = {gear:'P', speedKph:0, fresh:true, call:'idle', uxAllowed:true, visible:true, online:true};
function setup() { let now=0; const session=new Session(()=>now); session.setGates(safe); return {session, at(t){now=t; return session.view();}}; }

test('guided session settles at 176s and completes exactly once at 180s',()=>{
  const {session,at}=setup(); session.start();
  assert.equal(at(175999).segment,'guided');
  assert.equal(at(176000).segment,'settling'); assert.equal(session.view().expansion,0);
  assert.equal(at(179999).status,'running');
  assert.equal(at(180000).status,'completed'); assert.equal(at(190000).elapsed,180000);
  assert.equal(session.resume(),false); assert.equal(session.finish(),false);
});
test('pause freezes elapsed and geometry, then manual resume runs only remaining time',()=>{
  const {session,at}=setup(); session.start(); at(178000); session.pause();
  assert.equal(at(288000).elapsed,178000); assert.equal(session.view().remaining,2000);
  assert.equal(session.view().expansion,0); assert.equal(session.resume(),true);
  assert.equal(at(289999).status,'running'); assert.equal(at(290000).status,'completed');
});
test('unsafe gates pause and recovery never resumes automatically',()=>{
  const {session,at}=setup(); session.start(); at(2300);
  session.setGates({...safe,gear:'D',speedKph:12}); const paused=session.view();
  assert.equal(paused.status,'paused'); const e=paused.expansion;
  assert.equal(at(12000).elapsed,2300); assert.equal(session.view().expansion,e);
  session.setGates(safe); assert.equal(session.view().status,'paused');
  assert.equal(session.resume(),true);
});
test('unknown parking or call, stale state, hidden window and UX restriction deny starting',()=>{
  for(const bad of [{gear:'unknown'},{call:'unknown'},{fresh:false},{visible:false},{uxAllowed:false},{speedKph:1}]){
    const {session}=setup();session.setGates({...safe,...bad});assert.equal(session.start(),false);assert.equal(session.view().status,'idle');
  }
  assert.ok(blockers({...safe,call:'busy',uxAllowed:false}).length>=2);
});
test('offline transport does not prevent a local silent session with known-safe local gates',()=>{
  const {session,at}=setup();session.setGates({...safe,online:false});
  assert.equal(session.start(),true);assert.equal(at(8000).elapsed,8000);
});
test('early finish is terminal until a deliberate new session and keeps final geometry',()=>{
  const {session,at}=setup();session.start();const before=at(2000);session.finish();
  assert.equal(at(8000).status,'ended');assert.equal(session.view().elapsed,2000);assert.equal(session.view().expansion,before.expansion);
  assert.equal(session.resume(),false);assert.equal(session.start(),true);assert.equal(session.view().elapsed,0);
});
test('reduced motion is static at .5 in progress, settling and completion',()=>{
  const {session,at}=setup();session.setReduced(true);session.start();
  for(const t of [0,2000,4000,176000,180000])assert.equal(at(t).expansion,.5);
});
test('quintic expansion uses the same point outward and back',()=>{
  const {session,at}=setup();session.start();assert.equal(at(0).expansion,0);
  assert.equal(at(2000).expansion,.5);assert.equal(at(4000).expansion,1);
  assert.equal(at(6000).expansion,.5);assert.equal(at(8000).expansion,0);
});
