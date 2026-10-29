import { openSync, closeSync, readFileSync, writeFileSync, renameSync, unlinkSync, fsyncSync, statSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { ContinuityError, validatePreset } from '../continuity/engine.mjs';

const LIMIT=128*1024;
const fields=['schemaVersion','presets','selectedId','defaultId','autoDefaultOnRest'];
/** Explicit operator recovery only: never infer that a live or inaccessible process is stale. */
export function recoverStalePreferenceLock(file) {
  const lock=file+'.lock',before=statSync(lock);
  if(before.size>20)throw new Error('Invalid writer lock; recovery refused.');
  const text=readFileSync(lock,'utf8');
  if(!/^[1-9][0-9]*$/.test(text) || !Number.isSafeInteger(Number(text)))throw new Error('Invalid writer PID; recovery refused.');
  try {process.kill(Number(text),0);throw new Error('Writer is still alive; recovery refused.');}
  catch(e){if(e.code!=='ESRCH')throw e;}
  const after=statSync(lock);
  if(before.ino!==after.ino || before.mtimeMs!==after.mtimeMs || readFileSync(lock,'utf8')!==text)throw new Error('Writer lock changed; recovery refused.');
  unlinkSync(lock);
  return {recovered:true};
}
export function preferences(state) {
  return {schemaVersion:1,presets:structuredClone(state.presets),selectedId:state.selectedId,defaultId:state.defaultId,autoDefaultOnRest:state.autoDefaultOnRest};
}
export function validatePreferences(data, defaults) {
  const fail=()=>{throw new Error('Invalid stored preset preferences; refusing to reset.');};
  if(!data || Array.isArray(data) || Object.keys(data).length!==fields.length || !fields.every(k=>Object.hasOwn(data,k)) || data.schemaVersion!==1 || typeof data.autoDefaultOnRest!=='boolean' || !Array.isArray(data.presets) || data.presets.length<3 || data.presets.length>53) fail();
  const ids=new Set();
  for(const p of data.presets) {
    if(!p || Object.keys(p).length!==6 || !['id','name','kind','comfort','vehicle','home'].every(k=>Object.hasOwn(p,k)) || ids.has(p.id)) fail();
    validatePreset(p);ids.add(p.id);
    if(p.kind==='builtin') { if(!isDeepStrictEqual(p,defaults.presets.find(x=>x.id===p.id))) fail(); }
    else if(p.kind!=='custom' || !/^custom-[a-zA-Z0-9_-]+$/.test(p.id) || p.name!==p.name.trim()) fail();
  }
  if(!defaults.presets.every(p=>data.presets.some(x=>isDeepStrictEqual(x,p))) || !ids.has(data.selectedId) || !ids.has(data.defaultId)) fail();
  return data;
}
export function openPreferenceStore(file, engine) {
  if(!file) return {commit(){},close(){}};
  const lock=file+'.lock';let lockFd;
  try { lockFd=openSync(lock,'wx',0o600);writeFileSync(lockFd,String(process.pid)); }
  catch { throw new Error('Preset preferences already locked or directory inaccessible.'); }
  let closed=false;
  function close(){if(closed)return;closed=true;closeSync(lockFd);unlinkSync(lock);}
  try {
    let data;
    try { if(statSync(file).size>LIMIT) throw new Error('Stored preferences exceed size limit.');data=JSON.parse(readFileSync(file,'utf8')); }
    catch(e){if(e.code!=='ENOENT')throw e;}
    if(data!==undefined) { const valid=validatePreferences(data,engine.getState());const {schemaVersion,...rest}=valid;Object.assign(engine.state,structuredClone(rest)); }
  } catch(e){close();throw e;}
  return {close,commit(state){
    const temp=file+'.'+randomUUID()+'.tmp';let fd;
    try {
      const text=JSON.stringify(preferences(state));if(Buffer.byteLength(text)>LIMIT)throw new Error('limit');
      fd=openSync(temp,'wx',0o600);writeFileSync(fd,text);fsyncSync(fd);closeSync(fd);fd=undefined;renameSync(temp,file);
    } catch {
      if(fd!==undefined)closeSync(fd);try{unlinkSync(temp);}catch{}
      throw new ContinuityError('STORAGE_UNAVAILABLE','선호를 저장하지 못했어요. 변경은 적용하지 않았어요.',503);
    }
  }};
}
