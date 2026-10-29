// All shared state comes from the dedicated loopback server. Devices remain synthetic.
import {PresetSyncClient,mountPresetConnections} from './sync.mjs';
const syncClient=new PresetSyncClient();
const $ = selector => document.querySelector(selector);
const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fanTitles = {keep:'현재 설정 유지',auto:'자동',low:'약하게',medium:'중간',high:'강하게'};
const feelingTitles = {usual:'평소대로',warm:'조금 더워요',tired:'오늘은 조금 지쳤어요',quiet:'조용히 쉬고 싶어요',cold:'조금 추워요',skip:'고르지 않을게요'};
const planTitles = {armed:'예약을 켜 두었어요',requested:'요청했어요 · 결과 확인 전',confirmed_demo:'예시 기기 상태를 확인했어요',failed:'준비 결과를 확인하지 못했어요',cancelled:'예약을 취소했어요',expired:'예정 시간이 지났어요',needs_review:'바뀐 설정을 다시 확인해 주세요'};
let lastReadAt = null;
let state = null, connected = false, busy = false, previewPlace = 'vehicle', previewId = null, editorPreset = null;
let dialogRevision = null, dialogEpoch = null, arrivalPresetID = null, deletionPresetID = null, commandEpoch = 0;
let dialogReturnFocus = null;
const time = value => value ? new Intl.DateTimeFormat('ko-KR',{hour:'numeric',minute:'2-digit',hour12:false}).format(new Date(value)) : '—';
const temp = value => value == null ? '현재 설정 유지' : `${value}°C`;
const selected = () => state?.presets.find(p=>p.id===state.selectedId) || state?.presets[0];
const seat = value => value === 'manual_comfort' ? '편안한 자세 선호 · 직접 조절' : '현재 설정 유지';
const arrow = comfort => ({cool_down:'↘',quiet_rest:'−',gentle_refresh:'↗'})[comfort] || '−';
function feedback(message, error=false) { $('#feedback').textContent=message; $('#feedback').classList.toggle('error',error); }
function connection() {
  connected=syncClient.connected;
  const message=syncClient.pending?'앞선 변경 결과를 확인해 주세요':busy?'변경을 저장하고 있어요':connected?'공유 서버 통신 확인됨':'서버 통신 확인 필요';
  if($('#connection').textContent!==message)$('#connection').textContent=message;
  $('.connection-note').dataset.state=syncClient.pending?'pending':connected?'connected':'offline';
  $('#connection-detail').textContent=syncClient.pending?'같은 요청으로 결과를 확인한 뒤 계속할 수 있어요.':connected?'마지막 서버 확인 '+time(lastReadAt)+' · 상대 앱 수신과 실물 동작은 별도로 확인해요.':'마지막 관측값을 표시해요. 다시 확인하기 전에는 새 변경을 보내지 않아요.';
  $('#app').setAttribute('aria-busy',String(busy));
  $('#dialog-body').setAttribute('aria-busy',String(busy));
  $('#sync-retry').hidden=!syncClient.pending;$('#sync-retry').disabled=busy;
  for(const element of document.querySelectorAll('#app [data-command],#app [data-action],#app input,#app select,#dialog button.primary,#dialog button[type=submit]')){
    const blocked=!connected||busy||!!syncClient.pending;
    if(blocked){if(!('syncDisabled' in element.dataset))element.dataset.syncDisabled=String(element.disabled);element.disabled=true;}
    else if('syncDisabled' in element.dataset){element.disabled=element.dataset.syncDisabled==='true';delete element.dataset.syncDisabled;}
  }
  for(const button of document.querySelectorAll('[data-sync-role]'))button.disabled=!connected;
}
const connections=mountPresetConnections(syncClient,{changed:connection,error:message=>feedback(message,true)});
async function poll(force=false) {
  if(busy||document.hidden)return;
  const epoch=commandEpoch;
  try {
    const next=await syncClient.read();
    if(epoch!==commandEpoch)return;
    if(next.demo!==true||!Array.isArray(next.presets))throw new Error('시연 상태를 확인하지 못했어요.');
    const changed=!state||state.revision!==next.revision;
    state=next; lastReadAt=new Date().toISOString(); if(changed||force)render();connection();await connections.refresh();
  } catch(error) {
    if(epoch!==commandEpoch)return;
    if(!syncClient.sync){state=null;$('#app').innerHTML='<div class="loading">연결이 만료되었거나 해제됐어요. 다시 확인을 눌러 연결해 주세요.</div>';}
    connection();
    if(!state&&syncClient.sync)$('#app').innerHTML='<div class="loading"><h2>프리셋 서버 연결을 확인해 주세요</h2><p class="support">연결되면 저장된 선호를 불러와요. 지금은 변경을 보내지 않아요.</p></div>';
  }
}
async function command(action,payload={},expectedRevision=state?.revision) {
  if(busy)return false;
  if(!connected||!state){feedback('서버 연결을 다시 확인한 뒤 선택해 주세요.',true);return false}
  const interaction=captureInteraction();
  busy=true;commandEpoch++;
  connection();
  feedback('변경을 저장하고 있어요. 결과를 확인할 때까지 잠시 기다려 주세요.');
  try {
    state=await syncClient.command(action,payload,expectedRevision,$('#dialog').open?dialogEpoch:syncClient.sync?.epoch);
    render();restoreInteraction(interaction);feedback('공유 선호를 저장했어요. 연결된 앱이 다음 확인 때 반영해요. 실제 기기는 바뀌지 않아요.');return true;
  } catch(error){
    state=syncClient.state;if(state){render();restoreInteraction(interaction);}
    feedback(error.status===409?'다른 화면에서 바뀌었어요. 최신 내용과 초안을 검토한 뒤 다시 저장해 주세요.':error.message,true);
    if(error.status===409&&$('#preset-form')){
      const latest=state?.presets.find(p=>p.id===editorPreset?.id);
      $('#dialog-error').innerHTML=`${escapeHTML(latest?`현재 서버값: ${latest.name} · 차 ${temp(latest.vehicle.tempC)} / ${fanTitles[latest.vehicle.fan]} · 집 ${temp(latest.home.tempC)} / ${fanTitles[latest.home.fan]}`:'현재 공유 목록이 바뀌었어요. 입력한 초안은 유지했어요.')}<br><button type="button" class="outline" data-action="review-latest">최신 값과 초안을 확인했어요 · 저장 다시 준비</button>`;
    }
    if(error.status===409&&$('#arrival-form')){
      $('#dialog-error').innerHTML=state?.plan?.requestedAt?'이미 요청한 예약은 다시 실행할 수 없어요. 창을 닫고 요청 결과를 확인해 주세요.':`입력한 시간은 유지했어요. 현재 대상과 예약 내용을 다시 확인하고 동의를 새로 선택해 주세요.<br><button type="button" class="outline" data-action="review-arrival-latest">최신 예약 내용으로 다시 검토</button>`;
    }
    return false;
  }
  finally {busy=false;connection();}
}
function presetCard(p) {
  return `<button class="preset-card" data-command="select_preset" data-id="${escapeHTML(p.id)}" data-testid="preset-${escapeHTML(p.id)}" aria-pressed="${state.selectedId===p.id}"><span class="direction" aria-hidden="true">${arrow(p.comfort)}</span><span class="copy"><strong>${escapeHTML(p.name)}${state.defaultId===p.id?' · 기본':''}</strong><small>차 ${temp(p.vehicle.tempC)} · 집 ${temp(p.home.tempC)}</small></span><i class="radio" aria-hidden="true"></i></button>`;
}
function render() {
  if(!state)return;
  const interaction=captureInteraction();
  const p=selected(), recommendation=state.recommendation, recommended=state.presets.find(x=>x.id===recommendation?.presetId);
  const proposal=state.devices.vehicle.proposal, v=state.devices.vehicle, h=state.devices.home, plan=state.plan;
  const shown=proposal||p;
  const custom=state.presets.filter(x=>x.kind==='custom');
  $('#app').innerHTML=`<div class="layout"><section aria-labelledby="phone-label"><div class="surface-label"><h2 id="phone-label">나의 폰</h2><span>MY COMFORT</span></div><article class="phone" data-testid="phone-surface">
  <header class="phone-head"><strong>Pit-In</strong><span class="pill">공유 선호 · 합성 체험</span></header>
  <div class="phone-body">
    <p class="eyebrow">내가 고르는 편안함</p>
    <h2 class="phone-title">나에게 편안한 방식</h2>
    <p class="support">함께 쓸 선호를 고르고, 차 안에서 직접 쉬어가요.</p>
    <h3 class="section-title">공유 선호 선택</h3>
    <div class="preset-list">${state.presets.filter(x=>x.kind==='builtin').map(presetCard).join('')}</div>
    ${custom.length?`<h3 class="section-title">내 커스텀 선호</h3><div class="preset-list">${custom.map(presetCard).join('')}</div>`:''}
    <div class="selection-summary">
      <p class="note">서버에 선택된 선호</p><strong>${escapeHTML(p.name)}</strong>
      <button class="text-button" data-action="preview" data-testid="preview-preset">차와 집의 세부 선호 살펴보기</button>
    </div>
    <details class="preference-details" id="feeling-options">
      <summary id="feeling-summary">느낌으로 추천받기 <span>선택사항</span></summary>
      <p class="note">지금의 느낌을 직접 골라요. 건강 상태를 진단하지 않아요.</p>
      <div class="feeling-list">${Object.entries(feelingTitles).map(([value,title])=>`<button data-command="set_feeling" data-value="${value}" aria-pressed="${state.feeling===value}" data-testid="feeling-${value}">${title}</button>`).join('')}</div>
      ${recommended?`<aside class="recommendation" data-testid="recommendation"><strong>지금은 ‘${escapeHTML(recommended.name)}’ 어떠세요?</strong><p>${escapeHTML(recommendation.reason)}</p><button class="text-button" data-command="select_preset" data-id="${escapeHTML(recommended.id)}">이 선호 선택하기</button></aside>`:''}
    </details>
    <details class="preference-details" id="preset-management">
      <summary id="management-summary">편집·기본 선호 관리</summary>
      <div class="inline-actions">
        <button class="text-button" data-action="create" data-testid="create-preset">새 커스텀 선호 만들기</button>
        <button class="text-button" data-action="edit">${p.kind==='builtin'?'기본 선호 복사해 수정':'선택한 선호 편집'}</button>
        ${p.kind==='custom'?'<button class="text-button danger" data-action="delete" data-testid="delete-preset">커스텀 선호 삭제</button>':''}
      </div>
      <div class="default-box">
        <label class="check"><input type="checkbox" id="auto-default" data-testid="auto-default" ${state.autoDefaultOnRest?'checked':''}><span>휴식 준비 때 기본 선호 자동 선택</span></label>
        <p class="note">기본: ${escapeHTML(state.presets.find(x=>x.id===state.defaultId)?.name||'선택 없음')}. 쉼 시작이나 기기 변경은 자동 실행하지 않아요.</p>
        <button class="text-button" data-command="set_default" data-testid="set-default">현재 선택을 기본값으로 저장</button>
      </div>
    </details>
  </div>
  <footer class="phone-bottom">
    <button class="primary full" data-command="begin_rest" data-testid="begin-rest">${state.autoDefaultOnRest?'기본 선호로 준비하기':'이 선호로 준비하기'} <span aria-hidden="true">↗</span></button>
    <p class="note">선호를 서버에 제안해요. 3분 쉼은 주차한 차량 화면에서 직접 시작해 주세요.</p>
  </footer>
</article></section><div class="right-column"><section aria-labelledby="vehicle-label"><div class="surface-label"><h2 id="vehicle-label">차 안의 공간</h2><span>GRAPHITE · 합성 차량</span></div><article class="vehicle" data-testid="vehicle-surface"><header class="vehicle-head"><strong>Pit-In</strong><span>${v.parked?'주차 상태 · 예시':'주차 확인 전 · 예시'}</span></header><div class="vehicle-main"><div><p class="eyebrow">${proposal?'폰에서 고른 취향':'내가 고르는 쉼'}</p><h3>${escapeHTML(shown.name)}</h3><p class="support">${proposal?'이 차에서 사용할 선호를 확인해 주세요.':'폰에서 휴식 준비를 선택하거나, 이 취향을 직접 골라보세요.'}</p><div class="vehicle-meta"><span>온도 선호 ${temp(shown.vehicle.tempC)}</span><span>바람 ${fanTitles[shown.vehicle.fan]}</span></div><p class="note" style="color:#a6aca8">시트: ${seat(shown.vehicle.seat)}<br>실제 공조·시트는 차량에서 직접 조절해 주세요.</p></div><svg class="vehicle-path" viewBox="0 0 180 100" aria-hidden="true"><path d="M5 85C65 85 115 15 175 15" fill="none" stroke="#ecedea" stroke-opacity=".4"/><circle cx="90" cy="50" r="3" fill="#ecedea"/></svg></div><footer class="vehicle-foot"><span>${v.confirmedPresetId?'합성 화면에서 선호 확인됨':'선호 확인 전'}</span><button class="primary" data-command="${proposal?'accept_vehicle':'propose_vehicle'}" data-testid="vehicle-confirm" ${proposal&&!v.parked?'disabled':''}>${proposal?'이 취향 확인 · 시연':'이 취향 제안하기'} <span>↗</span></button></footer><label class="check"><input type="checkbox" id="vehicle-parked" ${v.parked?'checked':''}><span>주차 상태 시연 · 실제 차량 신호가 아니에요</span></label><div class="inline-actions"><button class="text-button" data-action="vehicle-preview">차 안의 세부 선호 살펴보기</button><button class="text-button" data-command="cancel_arrival" ${!plan||!['armed','requested','needs_review'].includes(plan.status)?'disabled':''}>집 예약 취소 · 차량 화면</button></div></article></section><section aria-labelledby="home-label"><div class="surface-label"><h2 id="home-label">돌아갈 집</h2><span>HOME · 합성 기기</span></div><article class="home" data-testid="home-surface"><header class="home-heading"><div><p class="eyebrow">차에서 집으로, 같은 취향을</p><h3>${escapeHTML(h.name)}</h3></div><span class="pill">${h.online?'온라인 · 예시':'오프라인 · 예시'}</span></header><div class="home-values"><div><small>관측한 기기 설정 · 합성</small><strong>${h.tempC}°C</strong></div><span>바람 ${fanTitles[h.fan]}<br>전원 ${h.power==='on'?'켜짐':'꺼짐'}</span><span>시연 시계<br><strong style="font-size:20px" data-testid="demo-clock">${time(state.now)}</strong></span></div>${plan?planHTML(plan):'<div class="timeline"><h4>집에서도 익숙하게.</h4><p>차와 집의 온도·바람은 각각 저장해요. 같은 숫자가 같은 체감을 보장하지는 않아요.</p></div>'}<div class="inline-actions"><button class="outline" data-action="arm" data-testid="arm-arrival">${plan&&['armed','needs_review'].includes(plan.status)?'새 귀가 예약 만들기':'이번 귀가 준비하기'}</button><button class="text-button" data-action="home-preview">집에서의 선호 미리보기</button></div><details class="manual-controls"><summary id="manual-summary">시연 조건과 수동 변경</summary><p>다른 앱이나 리모컨에서 기기를 바꾼 상황을 시연해요. 기존 예약이 이 변경을 덮어쓰지 않아요.</p><form id="manual-form" class="manual-grid"><label>예시 온도<select name="tempC" data-testid="manual-temperature">${temperatureOptions(h.tempC,18,30,false)}</select></label><label>예시 바람<select name="fan">${options(fanTitles,h.fan,['auto','low','medium','high'])}</select></label><button class="outline" type="submit" data-testid="manual-change">수동 변경 시연</button></form><label class="check" style="margin-top:15px"><input type="checkbox" id="home-online" ${h.online?'checked':''}><span>예시 집 기기 온라인</span></label><div class="inline-actions"><button class="text-button" data-command="advance_time" data-minutes="5">시연 시계 ＋5분</button><button class="text-button" data-command="advance_time" data-minutes="65">기한이 지난 뒤 확인 · ＋65분</button></div></details><details class="providers"><summary id="providers-summary">연결 서비스와 현재 범위</summary><p>이 화면은 합성 기기만 제어해요. Apple Home은 iPhone 앱에서 명시적으로 허용한 집·장면의 읽기를 준비했으며 실제 장면 실행은 제공하지 않아요.</p>${state.providers.map(provider=>`<div class="provider-row"><strong>${escapeHTML(provider.name)}</strong><span>${provider.status==='demo'?'합성 시연':'별도 연결 준비 필요'}</span></div><p>${escapeHTML(provider.detail)}</p>`).join('')}</details><details class="events"><summary id="events-summary">세 화면의 최근 변경</summary><ul>${state.events.slice(0,8).map(e=>`<li><time>${time(e.at)}</time> · ${escapeHTML(e.origin)}<br>${escapeHTML(e.label)}</li>`).join('')}</ul></details></article></section></div></div>`;
  const arrivalButton=$('[data-testid="arm-arrival"]');
  if(plan?.status==='armed'){arrivalButton.dataset.action='update';arrivalButton.textContent='이번 예약 확인·수정'}
  if(plan?.status==='requested'){arrivalButton.disabled=true;arrivalButton.textContent='요청한 예약의 결과를 먼저 확인해 주세요'}
  // A sent request is never silently turned back into another execution by an edit.
  if(plan?.requestedAt)document.querySelector('[data-testid="update-arrival"]')?.remove();
  const observedLabel=document.querySelector('.home-values small');
  if(observedLabel)observedLabel.textContent='관측한 희망 온도 설정 · 합성';
  restoreInteraction(interaction);
}
function captureInteraction() {
  const manual=$('#manual-form');
  const focused=$('#app').contains(document.activeElement)?document.activeElement:null;
  return {
    manual:manual?Object.fromEntries(new FormData(manual)):null,
    open:[...document.querySelectorAll('#app details[open]')].map(el=>el.id?'#'+CSS.escape(el.id):'details.'+CSS.escape(el.className)),
    focus:focused?.id?'#'+CSS.escape(focused.id):focused?.dataset.testid?'#app [data-testid="'+CSS.escape(focused.dataset.testid)+'"]':focused?.name?'#manual-form [name="'+CSS.escape(focused.name)+'"]':focused?.dataset.action?'#app [data-action="'+CSS.escape(focused.dataset.action)+'"]':null,
    x:window.scrollX,y:window.scrollY
  };
}
function restoreInteraction(saved) {
  for(const selector of saved.open){const el=document.querySelector('#app '+selector);if(el)el.open=true}
  if(saved.manual)for(const [name,value] of Object.entries(saved.manual)){const el=document.querySelector('#manual-form [name="'+CSS.escape(name)+'"]');if(el)el.value=value}
  if(saved.focus)document.querySelector(saved.focus)?.focus({preventScroll:true});
  window.scrollTo(saved.x,saved.y);
}
function planHTML(plan) {
  const active=['armed','requested','needs_review'].includes(plan.status);
  return `<section class="timeline" data-testid="arrival-plan"><h4 data-testid="plan-status">${escapeHTML(planTitles[plan.status]||plan.status)}</h4><p>${escapeHTML(plan.presetName)} · 이번 귀가 한 번 · v${plan.version}</p><p>${escapeHTML(plan.message)}</p><ol class="timeline-list"><li><i class="timeline-dot"></i><div><time>${time(plan.createdAt)}</time><strong>대상과 선호를 확인했어요</strong><p>예약 당시 값을 보관해요. 프리셋 편집으로 바뀌지 않아요.</p></div></li><li class="${plan.status==='armed'?'current':''}"><i class="timeline-dot"></i><div><time>${time(plan.executeAt)} · 도착 ${plan.leadMinutes}분 전</time><strong>집 준비 요청 예정</strong><p>도착 예정 ${time(plan.arrivalAt)} · 직접 정한 시각</p></div></li><li class="${plan.status==='requested'||plan.status==='confirmed_demo'?'current':''}"><i class="timeline-dot"></i><div><strong>${plan.status==='confirmed_demo'?'합성 기기 값 확인':'요청과 기기 응답은 따로 확인해요'}</strong><p>실제 집 기기나 백그라운드 자동화가 아니에요.</p></div></li></ol><div class="inline-actions">${plan.status==='armed'?'<button class="outline" data-action="advance-due" data-testid="advance-due">예정 시각까지 · 시연</button>':''}${plan.status==='requested'?'<button class="outline" data-command="confirm_home" data-testid="confirm-home">예시 기기 상태 확인</button>':''}${active?'<button class="text-button" data-action="update" data-testid="update-arrival">예약 다시 확인·수정</button><button class="text-button danger" data-action="cancel" data-testid="cancel-arrival">이번 예약 취소</button>':''}</div>${plan.status==='cancelled'||plan.status==='confirmed_demo'?'<p class="note">이미 바뀐 기기 설정을 취소만으로 원복하거나 끄지 않아요.</p>':''}</section>`;
}
function options(titles,current,values=Object.keys(titles)) {return values.map(value=>`<option value="${value}" ${value===current?'selected':''}>${titles[value]}</option>`).join('')}
function temperatureOptions(current,min,max,keep=true) {let html=keep?`<option value="keep" ${current==null?'selected':''}>현재 설정 유지</option>`:'';for(let n=min;n<=max;n+=0.5)html+=`<option value="${n}" ${current===n?'selected':''}>${n}°C</option>`;return html}
function showDialog(title,body) {
  if(!$('#dialog').open)dialogReturnFocus=captureInteraction().focus;
  dialogRevision=state.revision;
  dialogEpoch=syncClient.sync?.epoch;
  $('#dialog-body').innerHTML=`<header class="dialog-head"><h2 id="dialog-title">${escapeHTML(title)}</h2><button data-action="close" aria-label="닫기">닫기</button></header>${body}<p id="dialog-error" class="dialog-error" role="status"></p>`;
  if(!$('#dialog').open)$('#dialog').showModal();
}
$('#dialog').addEventListener('close',()=>{
  const previous=dialogReturnFocus?document.querySelector(dialogReturnFocus):null;
  const target=previous&&!previous.disabled?previous:$('#app .preset-card[aria-pressed=true]')||$('#app');
  target?.focus({preventScroll:true});
  dialogReturnFocus=null;
});
function showPreview(place='vehicle') {
  previewId=selected().id;previewPlace=place;renderPreview();
}
function renderPreview() {
  const p=state.presets.find(x=>x.id===previewId)||selected(), v=p[previewPlace];
  showDialog(p.name,`<p class="support">같은 취향을 장소에 맞게 확인해 주세요. 아래는 선호 값의 미리보기예요.</p><div class="preview-switch"><button data-place="vehicle" aria-pressed="${previewPlace==='vehicle'}">차 안</button><button data-place="home" aria-pressed="${previewPlace==='home'}">집</button></div><dl class="value-rows"><div><dt>선호 온도</dt><dd>${temp(v.tempC)}</dd></div><div><dt>바람 선호</dt><dd>${fanTitles[v.fan]}</dd></div><div><dt>${previewPlace==='vehicle'?'시트':'전원'}</dt><dd>${previewPlace==='vehicle'?seat(v.seat):v.power==='on'?'켜기 선호':'현재 설정 유지'}</dd></div></dl><p class="note">${previewPlace==='vehicle'?'실제 차량 공조·시트는 직접 조절해 주세요.':'집 기기와 계정은 연결되지 않았어요. 예약은 합성 기기로 시연해요.'}</p><div class="dialog-actions"><button class="outline" data-action="edit">복사·편집하기</button><button class="primary" data-action="${previewPlace==='home'?'arm':'propose'}">${previewPlace==='home'?'이번 귀가 준비하기':'차량에 취향 제안하기'} <span>↗</span></button></div>`);
}
function showEditor(create=false,presetID=state.selectedId) {
  const source=state.presets.find(p=>p.id===presetID);
  if(!source){feedback('이 프리셋이 다른 화면에서 삭제됐어요. 최신 목록에서 다시 선택해 주세요.',true);return}
  editorPreset=structuredClone(source);
  if(create||editorPreset.kind==='builtin'){delete editorPreset.id;editorPreset.name=create?'나의 편안함':`${editorPreset.name} · 내 취향`}
  const p=editorPreset;
  showDialog(p.id?'내 프리셋 편집':'내 프리셋 만들기',`<p class="support">현재 설정 유지를 선택하면 그 항목을 바꾸지 않아요. 실제 기기 범위는 연결 뒤 따로 확인해야 해요.</p><form id="preset-form"><div class="form-grid"><label class="wide">프리셋 이름<input name="name" required maxlength="40" value="${escapeHTML(p.name)}" data-testid="preset-name"></label><label class="wide">기본 분위기<select name="comfort">${options({cool_down:'산뜻하게 식히기',quiet_rest:'조용히 쉬기',gentle_refresh:'부드럽게 기분 전환'},p.comfort)}</select></label></div><h3 class="form-section">차 안에서의 선호</h3><div class="form-grid"><label>선호 온도<select name="vehicleTemp">${temperatureOptions(p.vehicle.tempC,18,28)}</select></label><label>바람<select name="vehicleFan">${options(fanTitles,p.vehicle.fan,['keep','low','medium','high'])}</select></label><label class="wide">시트 선호<select name="seat">${options({keep:'현재 설정 유지',manual_comfort:'편안한 자세 선호 · 직접 조절'},p.vehicle.seat)}</select></label></div><p class="note">시트 이동·자동 공조는 지원하지 않아요. 차량에서 직접 조절해 주세요.</p><h3 class="form-section">집에서의 선호</h3><div class="form-grid"><label>선호 온도<select name="homeTemp">${temperatureOptions(p.home.tempC,18,30)}</select></label><label>바람<select name="homeFan">${options(fanTitles,p.home.fan)}</select></label><label class="wide">전원<select name="power">${options({keep:'현재 설정 유지',on:'켜기'},p.home.power)}</select></label></div><p class="note">온도와 바람 범위는 시제품의 예시예요. 건강·안전 권고가 아니에요.</p><div class="dialog-actions"><button class="outline" type="button" data-action="close">취소</button><button class="primary" type="submit" data-testid="save-preset">내 프리셋 저장 <span>↗</span></button></div></form>`);
}
function showArrival(update=false,presetID=state.selectedId) {
  const p=state.presets.find(item=>item.id===presetID)||selected(), plan=state.plan;
  arrivalPresetID=p.id;
  const eta=update&&plan?Math.max(2,Math.ceil((Date.parse(plan.arrivalAt)-Date.parse(state.now))/60000)):30;
  const lead=update&&plan?plan.leadMinutes:10;
  showDialog(update?'이번 예약 다시 확인':'이번 귀가 준비',`<p class="support">직접 정한 도착 시각을 기준으로, 이번 한 번만 준비해요. 실제 위치나 경로는 읽지 않아요.</p><p class="reason">${escapeHTML(update&&plan?plan.presetName:p.name)} · 예시 집 에어컨 한 대<br>집 선호 ${temp((update&&plan?plan.presetSnapshot:p).home.tempC)} · 바람 ${fanTitles[(update&&plan?plan.presetSnapshot:p).home.fan]}</p><form id="arrival-form" data-update="${update}"><div class="form-grid"><label>몇 분 뒤 도착하나요?<input name="eta" type="number" min="2" max="180" value="${eta}" required data-testid="arrival-eta"></label><label>몇 분 전에 준비할까요?<input name="lead" type="number" min="1" max="30" value="${lead}" required data-testid="arrival-lead"></label></div><p class="note">시연 시계 ${time(state.now)} 기준이에요. 준비 시점은 도착 시점보다 앞이어야 해요.</p><div class="rule"></div><label class="check"><input name="consent" type="checkbox" required data-testid="arrival-consent"><span>대상과 변경할 선호를 확인했어요. 이번 예약 시연을 켤게요.</span></label><p class="note">예약은 기본 꺼짐이에요. 수동으로 바꾼 기기 설정은 덮어쓰지 않아요. 앱 밖에서 실제 기기를 켜거나 자동 실행하지 않아요.</p><div class="dialog-actions"><button class="outline" type="button" data-action="close">취소</button><button class="primary" type="submit" data-testid="save-arrival">${update?'확인한 값으로 예약 수정':'이번 예약 시연 켜기'} <span>↗</span></button></div></form>`);
}
document.addEventListener('click',async event=>{
  const button=event.target.closest('button');if(!button)return;
  if(button.id==='refresh'){try{if(!syncClient.sync)await syncClient.connect();await poll(true);}catch(error){feedback(error.message,true);}return}
  if(button.id==='sync-retry'){
    if(busy)return;busy=true;commandEpoch++;connection();
    try{state=await syncClient.retry();render();feedback('앞선 변경 결과를 확인했어요. 같은 요청이 중복 적용되지는 않아요.');if($('#dialog').open)$('#dialog').close();}
    catch(error){state=syncClient.state;if(state)render();feedback(error.message,true);}finally{busy=false;connection();}return;
  }
  if(button.dataset.place){previewPlace=button.dataset.place;renderPreview();return}
  const a=button.dataset.action, c=button.dataset.command;
  if(a==='close')$('#dialog').close();
  if(a==='review-latest'){dialogRevision=state.revision;dialogEpoch=syncClient.sync?.epoch;$('#dialog-error').textContent='초안을 유지했어요. 저장 버튼을 눌러 확인한 값을 보내 주세요.';return;}
  if(a==='review-arrival-latest'){
    if(state.plan?.requestedAt){$('#dialog-error').textContent='이미 요청한 예약이에요. 창을 닫고 결과를 확인해 주세요.';return;}
    const draft=new FormData($('#arrival-form')),presetId=arrivalPresetID;
    const update=!!state.plan&&['armed','needs_review','failed','expired'].includes(state.plan.status);
    showArrival(update,presetId);$('#arrival-form [name="eta"]').value=draft.get('eta');$('#arrival-form [name="lead"]').value=draft.get('lead');return;
  }
  if(a==='create')showEditor(true);
  if(a==='edit')showEditor(false,button.closest('#dialog')?previewId:state.selectedId);
  if(a==='preview'||a==='vehicle-preview')showPreview('vehicle');
  if(a==='home-preview')showPreview('home');
  if(a==='arm'){
    if(state.plan?.status==='armed')showArrival(true);
    else if(state.plan?.status==='requested')feedback('이미 요청한 예약의 결과를 먼저 확인해 주세요.',true);
    else showArrival(false,button.closest('#dialog')?previewId:state.selectedId);
  }
  if(a==='update')showArrival(true);
  if(a==='delete'){deletionPresetID=selected().id;showDialog('내 프리셋을 지울까요?',`<p class="support">${escapeHTML(selected().name)}을 목록에서 지워요. 이미 켠 귀가 예약은 당시 확인한 값을 유지해요.</p><div class="dialog-actions"><button class="outline" data-action="close">취소</button><button class="primary" data-action="confirm-delete">프리셋 삭제</button></div>`)}
  if(a==='confirm-delete'&&await command('delete_preset',{id:deletionPresetID},dialogRevision))$('#dialog').close();
  if(a==='cancel')showDialog('이번 예약을 취소할까요?','<p class="support">남은 예약을 취소해요. 이미 바뀐 기기 설정을 원복하거나 전원을 끄지는 않아요.</p><div class="dialog-actions"><button class="outline" data-action="close">돌아가기</button><button class="primary" data-action="confirm-cancel">이번 예약 취소</button></div>');
  if(a==='confirm-cancel'&&await command('cancel_arrival',{},dialogRevision))$('#dialog').close();
  if(a==='propose'&&await command('propose_vehicle',{id:previewId},dialogRevision))$('#dialog').close();
  if(a==='advance-due'){
    const minutes=(Date.parse(state.plan.executeAt)-Date.parse(state.now))/60000;
    if(minutes<0.1)feedback('예정 시각이 지났어요. 예약을 다시 확인해 주세요.',true);
    else await command('advance_time',{minutes});
  }
  if(c){
    let payload={};
    if(c==='select_preset')payload={id:button.dataset.id};
    if(c==='set_feeling')payload={value:button.dataset.value};
    if(c==='set_default')payload={id:state.selectedId,autoDefaultOnRest:state.autoDefaultOnRest};
    if(c==='begin_rest')payload={origin:'phone'};
    if(c==='propose_vehicle')payload={id:state.selectedId};
    if(c==='accept_vehicle')payload={origin:'vehicle'};
    if(c==='advance_time')payload={minutes:Number(button.dataset.minutes)};
    await command(c,payload);
  }
});
document.addEventListener('change',async event=>{
  if(event.target.id==='auto-default')await command('set_default',{id:state.defaultId||state.selectedId,autoDefaultOnRest:event.target.checked});
  if(event.target.id==='vehicle-parked')await command('set_vehicle_gate',{parked:event.target.checked});
  if(event.target.id==='home-online')await command('set_target_online',{online:event.target.checked});
});
document.addEventListener('submit',async event=>{
  if(!['preset-form','arrival-form','manual-form'].includes(event.target.id))return;
  event.preventDefault();const data=new FormData(event.target);let ok=false;
  if(event.target.id==='preset-form'){
    const numberOrKeep=value=>value==='keep'?null:Number(value);
    const payload={name:data.get('name').trim(),comfort:data.get('comfort'),vehicle:{tempC:numberOrKeep(data.get('vehicleTemp')),fan:data.get('vehicleFan'),seat:data.get('seat')},home:{tempC:numberOrKeep(data.get('homeTemp')),fan:data.get('homeFan'),power:data.get('power')}};
    if(editorPreset.id)payload.id=editorPreset.id;
    ok=await command('save_preset',payload,dialogRevision);
  }
  if(event.target.id==='arrival-form'){
    const etaMinutes=Number(data.get('eta')),leadMinutes=Number(data.get('lead'));
    if(etaMinutes<=leadMinutes){$('#dialog-error').textContent='준비 시점은 도착 시점보다 앞이어야 해요. 도착까지 남은 시간을 더 길게 정해 주세요.';return}
    const update=event.target.dataset.update==='true';
    ok=await command(update?'update_arrival':'arm_arrival',{...(update?{}:{presetId:arrivalPresetID,target:'home-ac'}),etaMinutes,leadMinutes,consent:data.get('consent')==='on'},dialogRevision);
  }
  if(event.target.id==='manual-form'){await command('set_home_manual',{tempC:Number(data.get('tempC')),fan:data.get('fan')});return}
  if(ok)$('#dialog').close();else if($('#dialog-error')&&!$('#dialog-error [data-action="review-latest"],#dialog-error [data-action="review-arrival-latest"]'))$('#dialog-error').textContent=$('#feedback').textContent;
});
try{await syncClient.connect();await poll();}catch(error){feedback(error.message,true);connection();}
document.addEventListener('visibilitychange',()=>{if(!document.hidden)poll(true);});
setInterval(()=>poll(),2000);
