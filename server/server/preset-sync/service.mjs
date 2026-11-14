import { createServer as httpServer } from 'node:http';
import { createServer as httpsServer } from 'node:https';
import { randomBytes, randomInt, randomUUID, createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { ContinuityEngine, ContinuityError, inKorea, round4 } from '../continuity/engine.mjs';
import { openPreferenceStore } from './store.mjs';
import { reportAppearance, appearanceView } from './appearance.mjs';

const prefix='/api/preset-sync/v1';
const cookieName='pitin_preset_session';
const hash=value=>createHash('sha256').update(value).digest('hex');
const secret=()=>randomBytes(32).toString('base64url');
const fail=(condition,code,message,status=400)=>{if(!condition)throw new ContinuityError(code,message,status);};
function fields(value,allowed) {fail(value!==null && typeof value==='object' && !Array.isArray(value) && Object.keys(value).length===allowed.length && allowed.every(k=>Object.hasOwn(value,k)),'INVALID_FIELDS','요청 항목을 확인해 주세요.');}
const vehicleActions=new Set(['select_preset','set_default','propose_vehicle','begin_rest','accept_vehicle','clear_place']);
const phoneActions=new Set(['select_preset','set_feeling','set_default','save_preset','delete_preset','begin_rest','propose_vehicle','propose_rest','propose_place','arm_arrival','update_arrival','cancel_arrival','advance_time','confirm_home','set_home_manual','set_target_online']);
const placeKinds=['park','cafe','bookstore','sauna','other'];
// The place the phone sent is for the car: the local web page sees only that one exists, not its name or position.
const viewFor=(actor,state)=>{if(actor.role!=='web' || !state?.devices?.vehicle?.place)return state;return {...state,devices:{...state.devices,vehicle:{...state.devices.vehicle,place:{sent:true}}}};};
const kakaoSearch='https://dapi.kakao.com/v2/local/search/keyword.json';
// Upstream text is shortened in UTF-16 units (matching propose_place) without leaving half an emoji.
const cut=(value,max)=>{if(typeof value!=='string')return '';let t=value.replace(/[\u0000-\u001f\u007f]/g,'').trim().slice(0,max);if(/[\uD800-\uDBFF]$/.test(t))t=t.slice(0,-1);return t.trim();};
function placeOf(doc) {
  if(!doc || typeof doc!=='object' || typeof doc.id!=='string' || !/^[0-9A-Za-z_-]{1,40}$/.test(doc.id))return null;
  const name=cut(doc.place_name,40),lat=Number(doc.y),lon=Number(doc.x);
  if(!name || typeof doc.y!=='string' && typeof doc.y!=='number' || typeof doc.x!=='string' && typeof doc.x!=='number' || !inKorea(lat,lon))return null;
  return {id:'kakao:'+doc.id,name,address:cut(doc.road_address_name,120) || cut(doc.address_name,120),lat:round4(lat),lon:round4(lon),category:cut(doc.category_group_name,40)};
}
const commonHeaders={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Content-Security-Policy':"default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data:"};
function json(res,status,data) {res.writeHead(status,{...commonHeaders,'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify(data));}
async function body(req) {
  fail(/^application\/json(?:\s*;|$)/i.test(req.headers['content-type']??''),'CONTENT_TYPE','JSON 요청만 받을 수 있어요.',415);
  const chunks=[];let size=0;
  for await(const chunk of req){size+=chunk.length;fail(size<=16384,'BODY_LIMIT','요청이 너무 커요.',413);chunks.push(chunk);}
  try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw new ContinuityError('INVALID_JSON','JSON 요청을 확인해 주세요.');}
}
const staticFiles=new Map([['/',['web/continuity/index.html','text/html; charset=utf-8']],['/index.html',['web/continuity/index.html','text/html; charset=utf-8']],['/styles.css',['web/continuity/styles.css','text/css; charset=utf-8']],['/app.mjs',['web/continuity/app.mjs','text/javascript; charset=utf-8']],['/sync.mjs',['web/continuity/sync.mjs','text/javascript; charset=utf-8']],['/fonts/NotoSansKR.ttf',['web/fonts/NotoSansKR.ttf','font/ttf']],['/fonts/OFL.txt',['web/fonts/OFL.txt','text/plain']]]);
staticFiles.set('/rest-guide/', ['web/rest-guide/index.html', 'text/html; charset=utf-8']);
staticFiles.set('/rest-guide/styles.css', ['web/rest-guide/styles.css', 'text/css; charset=utf-8']);
const motionReference = 'reference/motion-handoff-2026-10-25/Pit-In/';
staticFiles.set('/rest-guide/assets/hybrid-motion.mp4', [motionReference + 'hybrid-motion/renders/pit-in-hybrid-motion.mp4', 'video/mp4']);
staticFiles.set('/rest-guide/assets/code-motion.mp4', [motionReference + 'code-motion/renders/pit-in-code-motion.mp4', 'video/mp4']);
for (const scene of ['graphite-guided', 'graphite-resume', 'pearl-guided', 'umber-freedom']) {
  const name = scene + '-loop-10s.mp4';
  staticFiles.set('/rest-guide/assets/' + name, [motionReference + 'grok-videos/loops/' + name, 'video/mp4']);
}
for (const phase of ['guided', 'resume']) {
  for (const [suffix, type] of [['.png', 'image/png'], ['-grok.mp4', 'video/mp4']]) {
    const name = `graphite-${phase}-v5${suffix}`;
    staticFiles.set('/rest-guide/assets/' + name, ['web/rest-guide/assets/' + name, type]);
  }
}

/** Separate synthetic preference channel. No health relay routes or credentials are shared. */
export function createPresetSyncService({storeFile=null,tls,now=Date.now,browserHandler,kakaoRestKey=process.env.KAKAO_REST_KEY ?? null,fetchImpl=globalThis.fetch}={}) {
  const engine=new ContinuityEngine({now:now()}), epoch=randomUUID();
  // Construct TLS first so configuration errors cannot strand the writer lock.
  const secureServer=httpsServer(tls), browserServer=httpServer();
  const store=openPreferenceStore(storeFile,engine),sessions=new Map(),pairings=new Map();
  let attempts=[];
  const searches=new WeakMap();let allSearches=[];
  const kakaoKey=typeof kakaoRestKey==='string' && kakaoRestKey.trim() ? kakaoRestKey.trim() : null;
  const sync=actor=>({epoch,deviceId:actor.deviceId,role:actor.role,expiresAt:new Date(actor.expiresAt).toISOString()});
  function cleanup(){const time=now();for(const [k,v] of sessions)if(v.expiresAt<=time)sessions.delete(k);for(const [k,v] of pairings)if(v.expiresAt<=time)pairings.delete(k);attempts=attempts.filter(t=>time-t<60000);}
  function authorize(req,secure,pending=false) {
    cleanup();
    let key;
    if(secure){const m=/^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.authorization??'');key=m?hash(m[1]):null;}
    else {const m=(req.headers.cookie??'').split(';').map(x=>x.trim()).find(x=>x.startsWith(cookieName+'='));key=m?hash(m.slice(cookieName.length+1)):null;}
    const actor=sessions.get(key);fail(actor && actor.transport===(secure?'tls':'http'),'UNAUTHENTICATED','프리셋을 다시 연결해 주세요.',401);
    fail(pending || actor.status==='approved','APPROVAL_REQUIRED','브라우저에서 비교 번호를 확인하고 승인해 주세요.',403);return {actor,key};
  }
  function web(actor){fail(actor.role==='web','ROLE_FORBIDDEN','이 작업은 로컬 브라우저에서만 가능해요.',403);}
  async function handler(req,res,secure) {
    let actor;
    try {
      cleanup();
      const allowed=(secure?['127.0.0.1','localhost','10.0.2.2']:['127.0.0.1','localhost']).map(h=>h+':'+req.socket.localPort);
      fail(allowed.includes(req.headers.host),'LOCAL_ONLY','허용된 로컬 주소로 연결해 주세요.',403);
      fail(!req.headers.origin || req.headers.origin===(secure?'https://':'http://')+req.headers.host,'ORIGIN_FORBIDDEN','같은 출처에서만 요청할 수 있어요.',403);
      fail(!['cross-site','same-site'].includes(req.headers['sec-fetch-site']),'ORIGIN_FORBIDDEN','같은 출처에서만 요청할 수 있어요.',403);
      fail(secure || !req.headers.authorization,'TLS_REQUIRED','네이티브 연결에는 HTTPS가 필요해요.',403);
      fail(req.url.startsWith('/') && !req.url.startsWith('//') && !req.url.includes('\\'),'INVALID_PATH','경로를 확인해 주세요.');
      const url=new URL(req.url,'http://localhost');fail(!url.search,'INVALID_QUERY','쿼리 항목은 허용하지 않아요.');const path=url.pathname;
      if(!path.startsWith(prefix+'/')) {
        fail(!secure,'NOT_FOUND','경로를 찾지 못했어요.',404);
        if(path==='/favicon.ico' && ['GET','HEAD'].includes(req.method)){res.writeHead(204,commonHeaders);return res.end();}
        if(browserHandler)return await browserHandler(req,res);
        const file=staticFiles.get(path);fail(file && ['GET','HEAD'].includes(req.method),'NOT_FOUND','경로를 찾지 못했어요.',404);
        const data=await readFile(new URL('../../'+file[0],import.meta.url));
        const headers={...commonHeaders,'Content-Type':file[1],'Content-Length':data.length};
        if(file[1]==='video/mp4') {
          headers['Accept-Ranges']='bytes';
          // Only explicitly listed reference assets are exposed; no filesystem route.
          if(req.method==='GET' && req.headers.range) {
            const match=/^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
            let start=match?.[1]?Number(match[1]):0;
            let end=match?.[2]?Number(match[2]):data.length-1;
            if(match && !match[1] && match[2]) {start=Math.max(0,data.length-Number(match[2]));end=data.length-1;}
            if(!match || (!match[1]&&!match[2]) || !Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start<0 || start>=data.length || end<start) {
              res.writeHead(416,{...commonHeaders,'Content-Range':`bytes */${data.length}`});return res.end();
            }
            end=Math.min(end,data.length-1);
            res.writeHead(206,{...headers,'Content-Length':end-start+1,'Content-Range':`bytes ${start}-${end}/${data.length}`});return res.end(data.subarray(start,end+1));
          }
        }
        res.writeHead(200,headers);return res.end(req.method==='HEAD'?undefined:data);
      }
      const route=path.slice(prefix.length),method=req.method;
      if(route==='/session' && method==='GET') {
        fail(!secure,'ROLE_FORBIDDEN','브라우저에서만 세션을 열 수 있어요.',403);
        try{actor=authorize(req,false).actor;}catch(e){if(e.status!==401)throw e;}
        if(!actor){fail(sessions.size<128,'SESSION_LIMIT','연결이 많아요. 잠시 뒤 다시 시도해 주세요.',429);const token=secret();actor={deviceId:randomUUID(),role:'web',status:'approved',transport:'http',expiresAt:now()+8*3600000};sessions.set(hash(token),actor);res.setHeader('Set-Cookie',`${cookieName}=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800`);}
        return json(res,200,{sync:sync(actor)});
      }
      if(route==='/pairings/claim' && method==='POST') {
        fail(secure,'TLS_REQUIRED','네이티브 연결에는 HTTPS가 필요해요.',403);
        fail(attempts.length<30,'CLAIM_LIMIT','연결 시도가 많아요. 잠시 기다려 주세요.',429);attempts.push(now());
        const p=await body(req);cleanup();fields(p,['role','code']);fail(['phone','vehicle'].includes(p.role) && typeof p.code==='string' && /^\d{6}$/.test(p.code),'INVALID_PAIRING','역할과 연결 번호를 확인해 주세요.');
        const pair=[...pairings.values()].find(x=>x.codeHash===hash(p.code) && x.role===p.role && !x.claimed);
        fail(pair,'PAIRING_INVALID','연결 번호가 만료되었거나 올바르지 않아요.',401);
        fail(sessions.size<128,'SESSION_LIMIT','연결이 많아요.',429);
        pair.claimed=true;delete pair.codeHash;
        const token=secret(),comparisonCode=String(randomInt(10000)).padStart(4,'0');
        const native={deviceId:randomUUID(),role:p.role,status:'pending',transport:'tls',pairingId:pair.pairingId,comparisonCode,expiresAt:pair.expiresAt};sessions.set(hash(token),native);
        return json(res,200,{pairingId:pair.pairingId,token,comparisonCode,expiresAt:new Date(native.expiresAt).toISOString()});
      }
      const auth=authorize(req,secure,route==='/pairings/status' || (route==='/session' && method==='DELETE'));actor=auth.actor;
      if(route==='/session' && method==='DELETE'){sessions.delete(auth.key);if(!secure)res.setHeader('Set-Cookie',`${cookieName}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`);return json(res,200,{deleted:true});}
      if(route==='/pairings/status' && method==='GET'){fail(actor.role!=='web','ROLE_FORBIDDEN','네이티브 연결 상태만 조회할 수 있어요.',403);return json(res,200,{status:actor.status,pairingId:actor.pairingId,comparisonCode:actor.comparisonCode,sync:sync(actor)});}
      if(route==='/appearance' && method==='GET')return json(res,200,{appearance:appearanceView(sessions,now()),sync:sync(actor)});
      if(route==='/appearance' && method==='POST') {
        fail(actor.role==='vehicle','ROLE_FORBIDDEN','차량 앱만 현재 화면 분위기를 알릴 수 있어요.',403);
        const p=await body(req);
        // A slow request cannot outlive approval, expiry, or revocation.
        actor=authorize(req,secure).actor;
        reportAppearance(actor,p,epoch,now());
        return json(res,200,{appearance:appearanceView(sessions,now()),sync:sync(actor),acknowledgedRequestId:p.requestId});
      }
      if(route==='/state' && method==='GET')return json(res,200,{state:viewFor(actor,engine.getState()),sync:sync(actor)});
      if(route==='/commands' && method==='POST') {
        const p=await body(req);actor=authorize(req,secure).actor;fields(p,['action','expectedEpoch','expectedRevision','requestId','payload']);
        fail(actor.role==='web' || (actor.role==='phone'?phoneActions:vehicleActions).has(p.action),'ROLE_FORBIDDEN','이 화면에서는 해당 작업을 할 수 없어요.',403);
        // A rest with its 차 안 설정 comes from the phone only (not the local web page, which may run other commands).
        if(p.action==='propose_rest')fail(actor.role==='phone','ROLE_FORBIDDEN','폰에서만 쉼을 차에 보낼 수 있어요.',403);
        if(p.action==='propose_place')fail(actor.role==='phone','ROLE_FORBIDDEN','폰에서만 쉬어 갈 곳을 차에 보낼 수 있어요.',403);
        // Only the car confirms a place: the local web page may not clear what the phone sent.
        if(p.action==='clear_place')fail(actor.role==='vehicle','ROLE_FORBIDDEN','차량 화면에서만 쉬어 갈 곳을 지울 수 있어요.',403);
        if(actor.role!=='web' && ['begin_rest','accept_vehicle'].includes(p.action))fail(p.payload?.origin===actor.role,'ROLE_FORBIDDEN','자신의 화면 역할로만 요청할 수 있어요.',403);
        fail(p.expectedEpoch===epoch,'EPOCH_CONFLICT','서버 세션이 바뀌었어요. 최신 내용을 확인해 주세요.',409);
        fail(typeof p.requestId==='string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(p.requestId),'INVALID_REQUEST_ID','요청 UUID를 확인해 주세요.');
        const {expectedEpoch,...command}=p;const state=engine.execute(command,{actor,commit:store.commit});
        return json(res,200,{state:viewFor(actor,state),sync:sync(actor),acknowledgedRequestId:p.requestId});
      }
      if(route==='/places/search' && method==='POST') {
        // Query text only: no coordinates are accepted or sent, and neither the query nor the key is ever logged.
        fail(secure,'TLS_REQUIRED','네이티브 연결에는 HTTPS가 필요해요.',403);
        fail(actor.role==='phone','ROLE_FORBIDDEN','폰에서만 장소를 찾을 수 있어요.',403);
        fail(kakaoKey,'PLACES_UNAVAILABLE','장소 검색이 아직 준비되지 않았어요.',503);
        const p=await body(req);actor=authorize(req,secure).actor;fields(p,['query','kind']);
        fail(typeof p.query==='string' && p.query.length<=120 && p.query.isWellFormed() && p.query.trim().length>=1 && p.query.trim().length<=40 && !/[\u0000-\u001f\u007f]/.test(p.query),'INVALID_SEARCH','검색어는 1~40자로 입력해 주세요.');
        fail(placeKinds.includes(p.kind),'INVALID_SEARCH','장소 종류를 확인해 주세요.');
        const time=now(),recent=(searches.get(actor)??[]).filter(t=>time-t<60000);
        fail(recent.length<30,'SEARCH_LIMIT','검색이 많아요. 잠시 뒤 다시 해 주세요.',429);
        // Re-pairing makes a new session, so the whole service also has a ceiling.
        allSearches=allSearches.filter(t=>time-t<60000);fail(allSearches.length<120,'SEARCH_LIMIT','검색이 많아요. 잠시 뒤 다시 해 주세요.',429);
        recent.push(time);searches.set(actor,recent);allSearches.push(time);
        let docs;
        try {
          const url=kakaoSearch+'?query='+encodeURIComponent(p.query.trim())+'&size=15'+(p.kind==='cafe'?'&category_group_code=CE7':'');
          const response=await fetchImpl(url,{headers:{Authorization:'KakaoAK '+kakaoKey},signal:AbortSignal.timeout(5000)});
          if(!response.ok)throw new Error('upstream');
          docs=(await response.json())?.documents;if(!Array.isArray(docs))throw new Error('upstream');
        } catch {throw new ContinuityError('PLACES_FAILED','장소를 찾지 못했어요. 잠시 뒤 다시 해 주세요.',502);}
        actor=authorize(req,secure).actor;
        return json(res,200,{places:docs.map(placeOf).filter(Boolean).slice(0,15),sync:sync(actor)});
      }
      if(route==='/pairings' && method==='POST') {
        web(actor);const p=await body(req);actor=authorize(req,secure).actor;web(actor);fields(p,['role']);fail(['phone','vehicle'].includes(p.role),'INVALID_ROLE','연결 역할을 확인해 주세요.');fail(pairings.size<32,'PAIRING_LIMIT','연결 번호가 많아요. 만료 후 다시 시도해 주세요.',429);
        let code;do{code=String(randomInt(1000000)).padStart(6,'0');}while([...pairings.values()].some(x=>x.codeHash===hash(code)));
        const pairingId=randomUUID(),expiresAt=now()+300000;pairings.set(pairingId,{pairingId,role:p.role,codeHash:hash(code),expiresAt,claimed:false});return json(res,200,{pairingId,code,expiresAt:new Date(expiresAt).toISOString()});
      }
      if(route==='/pairings/approve' && method==='POST') {
        web(actor);const p=await body(req);actor=authorize(req,secure).actor;web(actor);fields(p,['pairingId','comparisonCode','confirmed']);fail(p.confirmed===true && typeof p.pairingId==='string' && typeof p.comparisonCode==='string' && /^\d{4}$/.test(p.comparisonCode),'CONFIRM_REQUIRED','비교 번호를 확인하고 승인해 주세요.');
        const native=[...sessions.values()].find(x=>x.pairingId===p.pairingId && x.status==='pending');fail(native && native.comparisonCode===p.comparisonCode,'PAIRING_INVALID','비교 번호 또는 연결 상태를 확인해 주세요.');native.status='approved';native.expiresAt=now()+7200000;pairings.delete(native.pairingId);return json(res,200,{approved:true});
      }
      if(route==='/devices' && method==='GET'){web(actor);return json(res,200,{devices:[...sessions.values()].filter(x=>x.role!=='web').map(x=>({deviceId:x.deviceId,role:x.role,status:x.status,pairingId:x.pairingId,...(x.status==='pending'?{comparisonCode:x.comparisonCode}:{}),expiresAt:new Date(x.expiresAt).toISOString()}))});}
      if(route.startsWith('/devices/') && method==='DELETE'){web(actor);const id=route.slice('/devices/'.length);for(const [key,value] of sessions)if(value.deviceId===id && value.role!=='web'){sessions.delete(key);pairings.delete(value.pairingId);}return json(res,200,{deleted:true});}
      throw new ContinuityError('NOT_FOUND','경로를 찾지 못했어요.',404);
    } catch(e) {
      const known=e instanceof ContinuityError,status=known?e.status:500;
      json(res,status,{error:{code:known?e.code:'UNAVAILABLE',message:known?e.message:'프리셋 서버 상태를 확인해 주세요.'},...(actor?.status==='approved' && status===409?{state:engine.getState(),sync:sync(actor)}:{})});
    }
  }
  browserServer.on('request',(req,res)=>handler(req,res,false));secureServer.on('request',(req,res)=>handler(req,res,true));
  for(const server of [browserServer,secureServer]){server.requestTimeout=10000;server.headersTimeout=10000;}
  let closed=false;
  return {httpServer:browserServer,httpsServer:secureServer,engine,async close(){if(closed)return;closed=true;await Promise.all([browserServer,secureServer].map(s=>new Promise(r=>{s.closeAllConnections();s.close(()=>r());})));store.close();}};
}
