import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { Session } from '../web/session.mjs';

// Run the browser controller with only its DOM/network/scheduler boundaries replaced.
function browser() {
  const nodes=new Map(),steps=[];
  const node=id=>{
    if(!nodes.has(id))nodes.set(id,{value:'',checked:false,textContent:'',hidden:false,style:{},dataset:{},handlers:{},classList:{toggle(){}},setAttribute(){},addEventListener(name,fn){this.handlers[name]=fn;},getTotalLength(){return 100;},getPointAtLength(x){return {x,y:0};}});
    return nodes.get(id);
  };
  const context=vm.createContext({Session,location:{pathname:'/'},document:{body:{dataset:{}},hidden:false,getElementById:node,querySelectorAll:()=>[],addEventListener(){}},window:{addEventListener(){}},navigator:{onLine:true},matchMedia:()=>({matches:false,addEventListener(){}}),setTimeout:()=>1,clearTimeout(){},setInterval(){},requestAnimationFrame:()=>1,cancelAnimationFrame(){},AbortController,performance:{now:()=>0},fetch:async()=>{assert.ok(steps.length,'Unexpected browser request');const step=steps.shift();return typeof step==='function'?step():step;}});
  vm.runInContext(readFileSync(new URL('../web/app.mjs',import.meta.url),'utf8').replace("import { Session } from './session.mjs';",''),context);
  return {node,steps,fire:id=>node(id).handlers.click(),evaluate:source=>vm.runInContext(source,context)};
}
const reply=(data,status=200)=>new Response(JSON.stringify(data),{status});
const car={pairingId:'a'.repeat(32),code:'123456',vehicleToken:'synthetic-test-token',expiresAt:'2030-01-01T00:00:00Z'};
async function pairedCar(f){f.steps.push(reply(car));await f.fire('create-pair');}

test('non-JSON 401 retains HTTP status so browser polling clears rejected credentials',async()=>{
  const f=browser();await pairedCar(f);
  f.steps.push(new Response('Unauthorized',{status:401}));await f.evaluate('poll()');
  assert.equal(f.evaluate('state.vehicle'),null);
  assert.match(f.node('relay-status').textContent,/만료|해제/);
});
test('disconnect does not claim server deletion from an unconfirmed success body',async()=>{
  const f=browser();await pairedCar(f);
  f.steps.push(reply({deleted:false}));await f.fire('disconnect');
  assert.notEqual(f.evaluate('state.vehicle'),null);
  assert.match(f.node('relay-status').textContent,/확인하지 못/);
});
test('disconnect distinguishes already absent from denied deletion and retains 403 retry state',async()=>{
  const f=browser();await pairedCar(f);
  f.steps.push(reply({error:{code:'WRONG_ROLE'}},403));await f.fire('disconnect');
  assert.notEqual(f.evaluate('state.vehicle'),null);
  f.steps.push(reply({error:{code:'PAIRING_NOT_FOUND'}},404));await f.fire('disconnect');
  assert.equal(f.evaluate('state.vehicle'),null);
  assert.match(f.node('relay-status').textContent,/이미|만료/);
});
test('late old disconnect cannot clear a connection created while phone deletion was pending',async()=>{
  const f=browser();f.node('pair-code').value='123456';
  f.steps.push(reply({pairingId:car.pairingId,phoneToken:'synthetic-phone',confirmation:'1234'}),reply({status:'paired',confirmation:'1234',expiresAt:car.expiresAt}));
  await f.fire('claim-pair');let release;
  f.steps.push(()=>new Promise(resolve=>{release=resolve;}));const deleting=f.fire('disconnect');
  f.steps.push(reply({...car,pairingId:'b'.repeat(32)}));await f.fire('create-pair');
  release(reply({deleted:true}));await deleting;
  assert.equal(f.evaluate('state.vehicle.pairingId'),'b'.repeat(32));
});
