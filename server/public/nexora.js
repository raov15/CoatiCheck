(function(){
  const q=id=>document.getElementById(id), debtCodes=new Set(), catalogs=new Map();
  const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const company=()=>q('devices-company')?.value||q('report-company')?.value||'';
  const catalogCompany=()=>q('code-company')?.value||company();
  window.nexoraBreakLabel=function(record){
    const number=record.break_number?' '+record.break_number:'';
    const event=record.attendance_event_type||record.event_type;
    if(event==='MEAL_END')return (record.break_kind==='MEAL'?'Regreso de comer':record.break_kind==='REST'?'Regreso del descanso':'Regreso de pausa')+number;
    return (record.break_kind==='MEAL'?'Comida':record.break_kind==='REST'?'Descanso':'Pausa')+number;
  };
  async function request(url,options){
    const response=await authorizedFetch(url,options);const data=await response.json().catch(()=>({}));
    if(!response.ok){const error=Error(data.error||'No se pudo guardar');error.data=data;throw error;}return data;
  }
  async function catalog(id,refresh=false){
    if(!id)return [];
    if(refresh||!catalogs.has(id))catalogs.set(id,request('/api/admin/companies/'+encodeURIComponent(id)+'/positions').catch(error=>{catalogs.delete(id);throw error;}));
    return catalogs.get(id);
  }
  window.nexoraFillPositions=async function(select,id){
    if(!select)return;select.dataset.positionsCompany=id;const old=select.value;
    try{
      const rows=await catalog(id);if(select.dataset.positionsCompany!==id)return;
      select.replaceChildren(new Option('Selecciona un cargo',''));
      for(const row of rows)select.add(new Option(row.name,row.name));
      if(rows.some(r=>r.name===old))select.value=old;
      else if(old&&select.id==='worker-edit-role'){const option=new Option(old+' (archivado)',old);option.disabled=true;select.add(option);select.value=old;}
    }catch(error){setStatus(error.message);}
  };
  window.nexoraFillFormPositions=async function(form,id){
    let field=form?.querySelector('[name="department"]');if(!field)return;
    if(field.tagName!=='SELECT'){const select=document.createElement('select');select.name='department';select.required=true;field.replaceWith(select);field=select;}
    await window.nexoraFillPositions(field,id);
  };
  async function refreshSelectors(){
    await Promise.all([
      window.nexoraFillPositions(q('code-site'),q('code-company')?.value||company()),
      window.nexoraFillPositions(q('user-site'),q('user-company')?.value||company()),
      window.nexoraFillPositions(q('worker-edit-role'),company()),
      window.nexoraFillFormPositions(q('worker-create-form'),q('worker-create-company')?.value||company())
    ]);
  }
  async function renderCatalog(){
    const container=q('nexora-positions-list');if(!container)return;
    const id=catalogCompany();container.replaceChildren();if(!id){container.textContent='Selecciona una empresa en Conectar dispositivos.';return;}
    try{
      for(const role of await catalog(id)){
        const row=document.createElement('div');row.className='nexora-position-row';
        const name=document.createElement('span');name.textContent=role.name;
        const edit=document.createElement('button');edit.type='button';edit.className='btn btn-soft btn-small';edit.textContent='Editar';
        edit.onclick=async()=>{const value=prompt('Nombre del cargo',role.name);if(!value?.trim()||value.trim()===role.name)return;
          try{await request(`/api/admin/companies/${encodeURIComponent(id)}/positions/${role.id}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:value.trim()})});catalogs.delete(id);await renderCatalog();await refreshSelectors();if(typeof loadDevices==='function')await loadDevices();}catch(error){setStatus(error.message);}};
        const remove=document.createElement('button');remove.type='button';remove.className='btn btn-soft btn-small';remove.textContent='Eliminar';
        remove.onclick=async()=>{if(!confirm('¿Retirar este cargo de las opciones disponibles? Los trabajadores ya asignados conservan su registro.'))return;
          try{await request(`/api/admin/companies/${encodeURIComponent(id)}/positions/${role.id}`,{method:'DELETE'});catalogs.delete(id);await renderCatalog();await refreshSelectors();}catch(error){setStatus(error.message);}};
        row.append(name,edit,remove);container.append(row);
      }
    }catch(error){container.textContent=error.message;}
  }
  window.nexoraApplyDebtStyles=function(){
    document.querySelectorAll('#devices tr').forEach(row=>{
      const code=row.querySelector('td')?.textContent.trim();row.classList.toggle('nexora-vacation-debt',debtCodes.has(code));
    });
  };
  window.nexoraVacationSummary=async function(){
    const box=q('worker-vacation-summary'),id=company();if(!box||!id)return;
    const generation=(window.__nexoraVacationGeneration||0)+1;window.__nexoraVacationGeneration=generation;
    box.textContent='Cargando vacaciones…';const workers=mergedWorkers().filter(w=>!w.company_id||String(w.company_id)===id);
    const cards=await Promise.all(workers.map(async worker=>{
      const code=bmcEmployeeCode(worker);
      try{const balance=await request(`/api/admin/companies/${encodeURIComponent(id)}/employees/${encodeURIComponent(code)}/vacations`);return {worker,code,balance};}
      catch(error){return {worker,code,error:error.message};}
    }));
    if(window.__nexoraVacationGeneration!==generation)return;
    debtCodes.clear();box.replaceChildren();
    for(const {worker,code,balance,error} of cards){
      const card=document.createElement('div');card.className='vacation-worker';
      if(error){card.textContent=fullWorkerName(worker)+': '+error;box.append(card);continue;}
      if(balance.available<0){debtCodes.add(code);card.classList.add('nexora-vacation-debt');}
      else if(balance.available>0)card.classList.add('nexora-vacation-available');
      const title=document.createElement('strong');title.textContent=fullWorkerName(worker);
      const stats=document.createElement('span');stats.className='nexora-vacation-stats';stats.textContent=`Generados: ${balance.accrued} · Tomados: ${balance.taken} · Disponibles: ${balance.available}`;
      const add=document.createElement('button');add.type='button';add.className='btn btn-primary btn-small';add.textContent='Registrar vacaciones';
      add.onclick=()=>window.nexoraVacationModal(code,fullWorkerName(worker),balance.available);
      const history=document.createElement('div');history.className='nexora-vacation-history hidden';
      const toggle=document.createElement('button');toggle.type='button';toggle.className='btn btn-soft btn-small';toggle.textContent='Ver historial';toggle.setAttribute('aria-expanded','false');
      toggle.onclick=()=>{const open=history.classList.toggle('hidden')===false;toggle.textContent=open?'Ocultar historial':'Ver historial';toggle.setAttribute('aria-expanded',String(open));};
      for(const item of balance.vacations){const line=document.createElement('p');line.textContent=`${String(item.start_date).slice(0,10)} a ${String(item.end_date).slice(0,10)} · ${item.days_taken} días${item.advance_confirmed?' · Incluye adelanto':''}${item.notes?' · '+item.notes:''}`;history.append(line);}
      if(!balance.vacations.length)history.textContent='Sin vacaciones registradas.';
      const info=document.createElement('div');info.className='nexora-vacation-info';info.append(title,stats);
      const actions=document.createElement('div');actions.className='nexora-vacation-actions';actions.append(add,toggle);
      const row=document.createElement('div');row.className='nexora-vacation-row';row.append(info,actions);
      card.append(row,history);box.append(card);
    }
    box.closest('.card')?.classList.add('nexora-vacation-section');
    window.nexoraApplyDebtStyles();
  };
  window.nexoraVacationModal=function(code,name,available){
    q('vacation-register-modal')?.remove();const overlay=document.createElement('div');overlay.id='vacation-register-modal';overlay.className='modal-backdrop';
    overlay.innerHTML=`<div class="modal-card"><h3>Registrar vacaciones</h3><p>${esc(name)} · ${available} días disponibles</p><form id="nexora-vacation-form"><div class="modal-grid">
      <label>Días tomados<input name="days" type="number" min="1" step="1" required></label>
      <label>Desde<input name="start" type="date" required></label><label>Hasta<input name="end" type="date" required></label>
      <label>Observaciones<textarea name="notes" maxlength="4000"></textarea></label></div>
      <p class="nexora-vacation-feedback" role="status"></p><button type="button" class="btn btn-soft nexora-cancel">Cancelar</button><button class="btn btn-primary" type="submit">Guardar vacaciones</button></form></div>`;
    document.body.append(overlay);overlay.querySelector('.nexora-cancel').onclick=()=>overlay.remove();
    const form=q('nexora-vacation-form');form.onsubmit=async event=>{
      event.preventDefault();const feedback=form.querySelector('[role="status"]'),button=form.querySelector('[type="submit"]');
      const fields=new FormData(form);const body={start_date:fields.get('start'),end_date:fields.get('end'),days_taken:Number(fields.get('days')),notes:String(fields.get('notes')||''),confirm_advance:false};
      const url=`/api/admin/companies/${encodeURIComponent(company())}/employees/${encodeURIComponent(code)}/vacations`;
      if(body.days_taken>available){if(!confirm(`Solo tienes ${available} días disponibles. ¿Deseas tomar más días por adelantado?`)){form.elements.days.focus();return;}body.confirm_advance=true;}
      button.disabled=true;
      try{
        let result;
        try{result=await request(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});}
        catch(error){if(error.data?.code!=='CONFIRM_ADVANCE')throw error;
          if(!confirm(error.data.error)){form.elements.days.focus();return;}
          body.confirm_advance=true;result=await request(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});}
        overlay.remove();await window.nexoraVacationSummary();setStatus('Vacaciones guardadas. Saldo disponible: '+result.available);
      }catch(error){feedback.textContent=error.message;}finally{button.disabled=false;}
    };
  };
  document.addEventListener('DOMContentLoaded',()=>{
    const style=document.createElement('style');style.textContent='.nexora-position-row{display:flex;gap:8px;align-items:center;margin:8px 0}.nexora-position-row span{flex:1}.nexora-vacation-debt,.nexora-vacation-debt>td{background:#fce7f3!important}.nexora-no-entry{border-left:5px solid #dc2626!important}.nexora-no-entry .incidence-type-badge{color:#991b1b!important;background:#fee2e2!important}.nexora-no-entry-title{color:#b91c1c;font-weight:800}';document.head.append(style);
    const host=q('connect-page');if(host){const panel=document.createElement('details');panel.id='nexora-positions-panel';panel.className='card';panel.innerHTML='<summary>Administrar cargos</summary><form id="nexora-position-form"><input name="name" maxlength="120" placeholder="Nombre del cargo" aria-label="Nombre del cargo" required><button class="btn btn-primary" type="submit">Agregar cargo</button></form><div id="nexora-positions-list"></div>';host.append(panel);panel.addEventListener('toggle',()=>{if(panel.open)renderCatalog();});
      q('nexora-position-form').onsubmit=async event=>{event.preventDefault();const id=catalogCompany();if(!id){setStatus('Selecciona una empresa');return;}const form=event.currentTarget;try{await request(`/api/admin/companies/${encodeURIComponent(id)}/positions`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:form.elements.name.value})});form.reset();catalogs.delete(id);await renderCatalog();await refreshSelectors();}catch(error){setStatus(error.message);}};}
    const edit=q('worker-edit-role');if(edit&&edit.tagName!=='SELECT'){const select=document.createElement('select');select.id=edit.id;edit.replaceWith(select);}
    ['devices-company','code-company','user-company','worker-create-company'].forEach(id=>q(id)?.addEventListener('change',()=>{refreshSelectors();renderCatalog();if(id==='devices-company')window.nexoraVacationSummary();}));
    document.addEventListener('click',event=>{if(event.target.closest('.worker-edit-btn'))setTimeout(refreshSelectors,0);});
    const old=window.renderDevicesTable;if(typeof old==='function')window.renderDevicesTable=function(...args){const result=old.apply(this,args);window.nexoraApplyDebtStyles();return result;};
    refreshSelectors();renderCatalog();
  });
})();
