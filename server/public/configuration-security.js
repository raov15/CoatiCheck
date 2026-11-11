(function(){
  const baseFetch=authorizedFetch;
  const grants=new Map();let dialogPromise=null,setupBusy=false;
  const cid=()=>document.querySelector('#settings-company')?.value||document.querySelector('#devices-company')?.value||'';
  const notice='Guarda tus contraseñas en un lugar seguro. Cualquier renovación de credenciales tendrá un costo extra.';
  let gate,protectedNodes=[];
  function lock(){grants.clear();for(const n of protectedNodes)n.inert=true;if(gate)gate.querySelector('[role=status]').textContent='Configuración bloqueada. Introduce tu contraseña para habilitarla.';}
  function unlocked(id){const grant=grants.get(id);return grant&&grant.until>Date.now()&&grant.session===sessionStorage.getItem(tokenKey)?grant.token:null;}
  async function securityInfo(id){const r=await baseFetch('/api/admin/companies/'+encodeURIComponent(id)+'/configuration-security');const data=await r.json();if(!r.ok)throw Error(data.error||'No se pudo consultar la protección');return data;}
  function passwordDialog(id,setup){
    if(dialogPromise)return dialogPromise;
    dialogPromise=new Promise(resolve=>{
      const overlay=document.createElement('div');overlay.className='modal-backdrop';
      overlay.innerHTML='<form class="modal-card" style="max-width:420px"><h3></h3><p></p><label>Contraseña de configuración<input type="password" name="password" minlength="8" maxlength="128" autocomplete="'+(setup?'new-password':'current-password')+'" required></label>'+(setup?'<label>Confirma tu contraseña<input type="password" name="confirmation" minlength="8" maxlength="128" autocomplete="new-password" required></label>':'')+'<p role="status"></p><div class="modal-actions"><button type="button" class="btn btn-soft">Cancelar</button><button type="submit" class="btn btn-primary">'+(setup?'Crear contraseña':'Autorizar')+'</button></div></form>';
      overlay.querySelector('h3').textContent=setup?'Crea tu contraseña de configuración':'Autorizar con contraseña';overlay.querySelector('p').textContent=notice;
      document.body.append(overlay);const form=overlay.querySelector('form'),feedback=form.querySelector('[role=status]');
      const finish=result=>{form.reset();overlay.remove();dialogPromise=null;resolve(result);};
      form.querySelector('.modal-actions [type=button]').onclick=()=>finish(null);
      form.querySelectorAll('input[type="password"]').forEach(input=>{
        const wrapper=document.createElement('div');wrapper.style.cssText='display:flex;align-items:center;gap:6px';
        input.replaceWith(wrapper);wrapper.append(input);input.style.cssText='flex:1;min-width:0;margin:0';
        const toggle=document.createElement('button');toggle.type='button';toggle.className='btn btn-soft';
        toggle.textContent='Mostrar';toggle.setAttribute('aria-label',input.name==='confirmation'?'Mostrar confirmación de contraseña':'Mostrar contraseña de configuración');toggle.setAttribute('aria-pressed','false');
        toggle.style.cssText='width:auto;padding:7px 10px;font-size:12px;white-space:nowrap';
        toggle.onclick=()=>{const visible=input.type==='password';input.type=visible?'text':'password';toggle.textContent=visible?'Ocultar':'Mostrar';toggle.setAttribute('aria-pressed',String(visible));toggle.setAttribute('aria-label',(visible?'Ocultar':'Mostrar')+(input.name==='confirmation'?' confirmación de contraseña':' contraseña de configuración'));};
        wrapper.append(toggle);
      });
      form.onsubmit=async e=>{e.preventDefault();const button=form.querySelector('[type=submit]');button.disabled=true;
        try{const r=await baseFetch('/api/admin/companies/'+encodeURIComponent(id)+'/configuration-security',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:form.elements.password.value,confirm_password:setup?form.elements.confirmation.value:undefined})});const d=await r.json();if(!r.ok)throw Error(d.error||'No se pudo autorizar');finish(d.token);}
        catch(error){feedback.textContent=error.message;}finally{button.disabled=false;}
      };form.elements.password.focus();
    });return dialogPromise;
  }
  async function authorize(id,always=false){
    if(!always&&unlocked(id))return unlocked(id);
    const info=await securityInfo(id);
    if(!info.configured){const created=await passwordDialog(id,true);lock();if(!created)return null;setStatus('Contraseña creada. La configuración quedó bloqueada.');}
    const token=await passwordDialog(id,false);if(token){grants.set(id,{token,until:Date.now()+900000,session:sessionStorage.getItem(tokenKey)});if(id===cid()){for(const n of protectedNodes)n.inert=false;if(gate)gate.querySelector('[role=status]').textContent='Configuración habilitada durante 15 minutos.';}}return token;
  }
  window.authorizedFetch=async function(url,options={}){
    const match=String(url).match(/\/api\/admin\/companies\/([^/]+)\/(.+)/);const method=(options.method||'GET').toUpperCase();
    if(!match||['GET','HEAD','OPTIONS'].includes(method))return baseFetch(url,options);
    const id=decodeURIComponent(match[1]),path=match[2];let payload;
    try{if(typeof options.body==='string')payload=JSON.parse(options.body);}catch{}
    let token=unlocked(id);
    if(payload?.concept==='Tiempo extraordinario'){
      if(confirm('¿Deseas autorizar y asignar este tiempo extra?')){token=await authorize(id,true);if(!token)throw Error('Autorización de tiempo extra cancelada');}
      else{payload.concept='Historial';payload.notes='Tiempo extra sin autorizar. '+(payload.notes||'');options={...options,body:JSON.stringify(payload)};}
    }
    if(/^(categories(?:\/|$)|policies(?:\/|$))/.test(path)&&!token){token=await authorize(id);if(!token)throw Error('Configuración bloqueada');}
    const headers=new Headers(options.headers||{});if(token)headers.set('X-Configuration-Token',token);
    // El fetch original usa un objeto de cabeceras; convertir Headers antes de enviarlo.
    const response=await baseFetch(url,{...options,headers:Object.fromEntries(headers.entries())});
    if(response.status===403&&token){const d=await response.clone().json().catch(()=>({}));if(d.code==='CONFIGURATION_LOCKED'||d.code==='CONFIGURATION_SETUP_REQUIRED')lock();}
    if(response.ok&&/\/qualify$|\/review$/.test(path))queueMicrotask(()=>loadAlerts().catch(console.error));
    return response;
  };
  async function loadAlerts(){const id=document.querySelector('#report-company')?.value||cid();const box=document.querySelector('#nx-weekly-alerts');if(!id||!box||!sessionStorage.getItem(tokenKey))return;
    const r=await baseFetch('/api/admin/companies/'+encodeURIComponent(id)+'/weekly-late-alerts');if(!r.ok)return;const rows=await r.json();box.replaceChildren();for(const row of rows){const p=document.createElement('p');p.textContent=row.full_name+' llegó tarde '+row.late_days+' veces esta semana y acumula '+row.absences+' ausentismo(s) por retardos.';box.append(p);}box.hidden=!rows.length;
  }
  async function firstSetup(){if(setupBusy||!sessionStorage.getItem(tokenKey))return;const id=cid();if(!id)return;setupBusy=true;try{if(!(await securityInfo(id)).configured){await passwordDialog(id,true);lock();}}catch(e){if(!nexoraSessionExpired)setStatus(e.message,'error');}finally{setupBusy=false;}}
  function initialize(){
    const page=document.querySelector('#settings-page');if(!page)return;
    const companyCard=page.querySelector('#settings-company')?.closest('.card');
    protectedNodes=[...page.children].filter(n=>n!==companyCard);
    gate=document.createElement('div');gate.id='nx-config-security';gate.className='card';
    gate.innerHTML='<h3>Contraseña de configuración</h3><p></p><p role="status"></p><div style="display:flex;gap:8px"><button type="button" class="btn btn-primary">Desbloquear configuración</button><button type="button" class="btn btn-soft">Bloquear</button></div>';
    gate.querySelector('p').textContent=notice;page.append(gate);lock();
    gate.querySelectorAll('button')[0].onclick=async()=>{try{if(!cid()){setStatus('Selecciona una empresa');return;}await authorize(cid());}catch(e){setStatus(e.message,'error');}};gate.querySelectorAll('button')[1].onclick=lock;
    new MutationObserver(()=>{protectedNodes=[...page.children].filter(n=>n!==companyCard&&n!==gate);for(const n of protectedNodes)n.inert=!unlocked(cid());}).observe(page,{childList:true});
    document.querySelector('#settings-company')?.addEventListener('change',()=>{lock();firstSetup();});
    document.querySelector('#devices-company')?.addEventListener('change',lock);
    document.querySelectorAll('.menu button').forEach(b=>b.addEventListener('click',()=>{if(b.dataset.page!=='settings-page')lock();else firstSetup();}));
    document.querySelector('#logout')?.addEventListener('click',lock);
    const alertBox=document.createElement('div');alertBox.id='nx-weekly-alerts';alertBox.setAttribute('role','alert');alertBox.hidden=true;document.querySelector('#attendance')?.before(alertBox);
    document.querySelector('#report-company')?.addEventListener('change',()=>loadAlerts().catch(console.error));
    document.addEventListener('change',async event=>{
      if(event.target.id!=='bmc-overtime-enabled'||event.target.value!=='si')return;
      if(!confirm('¿Deseas habilitar la asignación de tiempo extra?')){event.target.value='no';return;}
      try{if(!await authorize(cid(),true))event.target.value='no';}catch(e){event.target.value='no';setStatus(e.message,'error');}
    });
    const attendanceList=document.querySelector('#attendance');
    const describe=()=>{for(const card of attendanceList?.children||[]){const parts=[...card.querySelectorAll('.data-item-title,.data-item-subtitle,.incidence-info-line')].map(n=>n.textContent.trim());card.title=parts.join(' · ');}};
    if(attendanceList)new MutationObserver(describe).observe(attendanceList,{childList:true});
    const old=refreshAll;window.refreshAll=async function(...args){await old.apply(this,args);await firstSetup();await loadAlerts();};
    setInterval(()=>{if(!unlocked(cid()))lock();},15000);
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',initialize);else initialize();
})();
