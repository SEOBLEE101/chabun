const API='/api/preset-sync/v1';
export class PresetSyncError extends Error {
  constructor(message,code='CONNECTION',status=0){super(message);this.code=code;this.status=status;}
}
/** Browser credentials are HttpOnly cookies; health credentials never enter this client. */
export class PresetSyncClient {
  constructor(fetcher=globalThis.fetch.bind(globalThis),requestId=()=>crypto.randomUUID()){
    this.fetcher=fetcher;this.requestId=requestId;this.state=null;this.sync=null;
    this.connected=false;this.pending=null;this.generation=0;this.readSequence=0;this.appliedRead=0;this.sending=false;
  }
  invalidate(){this.state=null;this.sync=null;this.pending=null;this.connected=false;this.generation++;}
  async request(path,method='GET',body){
    const generation=this.generation;
    const response=await this.fetcher(API+path,{method,credentials:'same-origin',cache:'no-store',redirect:'error',signal:AbortSignal.timeout(8000),...(body===undefined?{}:{headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})});
    if(generation===this.generation&&(response.status===401||response.status===403))this.invalidate();
    let data;try{data=await response.json();}catch{
      throw new PresetSyncError(response.status===401||response.status===403?'연결이 만료되었거나 해제됐어요.':'서버 응답을 확인하지 못했어요.',response.status===401||response.status===403?'UNAUTHORIZED':'INVALID_RESPONSE',response.status);
    }
    if(!response.ok){
      const error=new PresetSyncError(data.error?.message||'연결 결과를 확인하지 못했어요.',data.error?.code||'UNAVAILABLE',response.status);
      error.packet=data;throw error;
    }
    return data;
  }
  validSync(value){return value&&typeof value.epoch==='string'&&value.role==='web'&&typeof value.deviceId==='string'&&Number.isFinite(Date.parse(value.expiresAt));}
  apply(packet){
    if(!this.validSync(packet.sync)||packet.state?.demo!==true||!Array.isArray(packet.state.presets)||!Number.isSafeInteger(packet.state.revision))throw new PresetSyncError('프리셋 상태 형식을 확인하지 못했어요.','INVALID_RESPONSE');
    if(this.sync&&this.sync.epoch!==packet.sync.epoch){this.invalidate();throw new PresetSyncError('서버 세션이 바뀌었어요. 다시 연결하고 내용을 확인해 주세요.','SESSION_CHANGED',409);}
    if(!this.state||packet.state.revision>=this.state.revision){this.sync=packet.sync;this.state=packet.state;}
    this.connected=true;
  }
  async connect(){
    if(this.pending)throw new PresetSyncError('먼저 앞선 변경의 결과를 확인해 주세요.','UNCONFIRMED');
    const generation=++this.generation;this.connected=false;
    const packet=await this.request('/session');
    if(generation!==this.generation)throw new PresetSyncError('연결 상태가 바뀌어 이전 연결 응답을 버렸어요.','STALE_SESSION',401);
    if(!this.validSync(packet.sync))throw new PresetSyncError('연결 응답을 확인하지 못했어요.','INVALID_RESPONSE');
    if(this.sync?.epoch!==packet.sync.epoch)this.state=null;
    this.sync=packet.sync;
  }
  async read(){
    const generation=this.generation,sequence=++this.readSequence;
    try{
      const packet=await this.request('/state');
      if(generation!==this.generation||sequence<this.appliedRead)return this.state;
      this.apply(packet);this.appliedRead=sequence;return this.state;
    }catch(error){if(generation===this.generation)this.connected=false;throw error;}
  }
  async command(action,payload={},expectedRevision=this.state?.revision,expectedEpoch=this.sync?.epoch){
    if(this.pending)throw new PresetSyncError('앞선 변경의 결과 확인 후 다시 선택해 주세요.','UNCONFIRMED');
    if(!this.connected||!this.state)throw new PresetSyncError('먼저 연결 상태를 다시 확인해 주세요.');
    this.pending={action,payload:structuredClone(payload),expectedEpoch,expectedRevision,requestId:this.requestId()};
    return this.sendPending();
  }
  async retry(){if(!this.pending)throw new PresetSyncError('확인할 요청이 없어요.');return this.sendPending();}
  async sendPending(){
    if(this.sending)throw new PresetSyncError('변경 결과를 확인하고 있어요.','BUSY');
    if(!this.sync)throw new PresetSyncError('먼저 연결 상태를 다시 확인해 주세요.','UNAUTHORIZED',401);
    const pending=this.pending,sessionEpoch=this.sync.epoch,generation=++this.generation;this.sending=true;
    try{
      const packet=await this.request('/commands','POST',pending);
      if(generation!==this.generation||!this.sync||this.sync.epoch!==sessionEpoch)throw new PresetSyncError('연결이 해제되어 늦게 도착한 응답을 버렸어요.','STALE_SESSION',401);
      if(packet.acknowledgedRequestId!==pending.requestId)throw new PresetSyncError('이 변경의 접수 결과를 확인하지 못했어요.','INVALID_ACK');
      this.apply(packet);this.pending=null;return this.state;
    }catch(error){
      if(generation===this.generation&&(error.status===401||error.status===403))this.invalidate();
      else if(generation===this.generation&&this.sync&&this.sync.epoch===sessionEpoch&&[400,404,409,413,415,422,429,503].includes(error.status)){
        this.pending=null;
        if(error.packet?.state)this.apply(error.packet);
      }else if(generation===this.generation)this.connected=false;
      throw error;
    }finally{this.sending=false;}
  }
}

export function mountPresetConnections(client,{changed=()=>{},error=()=>{}}={}){
  const container=document.querySelector('#sync-connections');if(!container)return {refresh:async()=>{}};
  const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  let invitation=null,fetching=false,lastDevices='';
  container.innerHTML=`<summary>폰·차량 앱과 프리셋 연결</summary><p class="note">이 PC에서 승인한 앱끼리 선호를 공유해요. 건강 요약 연결과는 별개이며, 실제 집·차량을 조작하지 않아요.</p><div class="inline-actions"><button class="outline" data-sync-role="phone" data-testid="pair-phone">폰 연결 번호 만들기</button><button class="outline" data-sync-role="vehicle" data-testid="pair-vehicle">차량 연결 번호 만들기</button></div><p id="sync-invitation" role="status"></p><div id="sync-devices"></div><p class="note">프리셋과 기본 선호는 이 PC에 저장돼요. 앱 또는 서버를 다시 시작하면 재연결하며, 일회성 예약은 서버 재시작 뒤 복원되지 않아요.</p>`;
  async function refresh(){
    if(fetching||!client.connected)return;fetching=true;
    try{
      const response=await client.request('/devices');
      if(!Array.isArray(response.devices))throw new Error('연결 목록을 확인하지 못했어요.');
      const serialized=JSON.stringify(response.devices);
      if(serialized!==lastDevices){
        const checked=new Set([...container.querySelectorAll('[data-match]:checked')].map(el=>el.dataset.match));lastDevices=serialized;
        container.querySelector('#sync-devices').innerHTML=response.devices.map(device=>`<article class="sync-device" data-device="${escape(device.deviceId)}"><strong>${device.role==='phone'?'Android 폰':'차량 앱'}</strong><span>${device.status==='approved'?'승인됨':'번호 비교 후 승인 대기'}</span>${device.status==='pending'?`<p>양쪽 확인 번호 <b data-testid="comparison-${escape(device.role)}">${escape(device.comparisonCode)}</b></p><label class="check"><input type="checkbox" data-match="${escape(device.pairingId)}" ${checked.has(device.pairingId)?'checked':''}><span>앱에 표시된 번호와 일치해요.</span></label><button class="outline" data-approve="${escape(device.pairingId)}" data-code="${escape(device.comparisonCode)}" data-testid="approve-${escape(device.role)}">이 기기 승인</button>`:''}<button class="text-button danger" data-revoke="${escape(device.deviceId)}">이 기기 연결 해제</button></article>`).join('')||'<p class="note">아직 연결한 앱이 없어요.</p>';
      }
      if(invitation&&Date.parse(invitation.expiresAt)<=Date.now()){container.querySelector('#sync-invitation').textContent='연결 번호가 만료됐어요. 새 번호를 만들어 주세요.';invitation=null;}
    }catch(e){error(e.message);changed();}finally{fetching=false;}
  }
  container.addEventListener('click',async event=>{
    const button=event.target.closest('button');if(!button)return;
    button.disabled=true;
    try{
      if(button.dataset.syncRole){
        invitation=await client.request('/pairings','POST',{role:button.dataset.syncRole});
        container.querySelector('#sync-invitation').innerHTML=`<span>${button.dataset.syncRole==='phone'?'폰':'차량'} 앱의 ‘연결된 프리셋’에 입력할 번호</span> <strong data-testid="pairing-code">${escape(invitation.code)}</strong><br><small>5분 내 입력한 뒤, 아래 확인 번호를 대조하고 승인해 주세요.</small>`;
      }
      if(button.dataset.approve){
        if(!container.querySelector(`[data-match="${CSS.escape(button.dataset.approve)}"]`)?.checked)throw new Error('앱과 확인 번호를 직접 비교해 주세요.');
        await client.request('/pairings/approve','POST',{pairingId:button.dataset.approve,comparisonCode:button.dataset.code,confirmed:true});
        container.querySelector('#sync-invitation').textContent='승인했어요. 앱에서 공유 선호를 불러오고 있어요.';invitation=null;
      }
      if(button.dataset.revoke){await client.request('/devices/'+encodeURIComponent(button.dataset.revoke),'DELETE');}
      await refresh();
    }catch(e){error(e.message);changed();}finally{if(button.isConnected)button.disabled=false;}
  });
  return {refresh};
}
