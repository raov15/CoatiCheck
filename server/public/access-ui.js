(function(){
  const eye='<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>';
  window.nexoraAddPasswordEye=function(input){
    if(!input||input.dataset.nexoraEye)return;
    input.dataset.nexoraEye='1';const wrap=document.createElement('div');wrap.style.cssText='display:flex;align-items:center;gap:8px;margin-top:4px';
    input.replaceWith(wrap);wrap.append(input);input.style.cssText+=';flex:1;min-width:0;margin:0';
    const toggle=document.createElement('button');toggle.type='button';toggle.innerHTML=eye;toggle.className='btn btn-soft';toggle.style.cssText='display:flex;align-items:center;justify-content:center;width:48px;min-width:48px;height:48px;padding:10px';
    toggle.setAttribute('aria-label','Mostrar contraseña');toggle.setAttribute('aria-pressed','false');
    toggle.onclick=()=>{const visible=input.type==='password';input.type=visible?'text':'password';toggle.setAttribute('aria-label',visible?'Ocultar contraseña':'Mostrar contraseña');toggle.setAttribute('aria-pressed',String(visible));};wrap.append(toggle);
  };
  async function jsonRequest(url,options){const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),25000);try{const response=await fetch(url,{...options,signal:controller.signal});const data=await response.json().catch(()=>({}));if(!response.ok)throw Error(data.error||'No se pudo completar la solicitud');return data;}catch(e){if(e.name==='AbortError')throw Error('El servidor está tardando en responder. Intenta nuevamente.');throw e;}finally{clearTimeout(timer);}}
  function changeRequiredPassword(token,currentPassword){return new Promise(resolve=>{
    const overlay=document.createElement('div');overlay.className='modal-backdrop';overlay.setAttribute('role','dialog');overlay.setAttribute('aria-modal','true');overlay.setAttribute('aria-label','Cambia tu contraseña de acceso');
    overlay.innerHTML='<form class="modal-card" style="max-width:560px"><h3>Cambia tu contraseña de acceso</h3><label>Nueva contraseña<input type="password" name="password" minlength="8" maxlength="128" autocomplete="new-password" required></label><label>Confirma tu nueva contraseña<input type="password" name="confirmation" minlength="8" maxlength="128" autocomplete="new-password" required></label><p role="status"></p><div class="modal-actions"><button type="button" class="btn btn-soft" data-cancel>Cancelar</button><button type="submit" class="btn btn-primary">Guardar contraseña</button></div></form>';
    document.body.append(overlay);const form=overlay.querySelector('form'),feedback=form.querySelector('[role=status]'),save=form.querySelector('[type=submit]'),cancel=form.querySelector('[data-cancel]');let busy=false;
    form.querySelectorAll('input').forEach(nexoraAddPasswordEye);
    const finish=result=>{form.reset();overlay.remove();resolve(result);};cancel.onclick=()=>{if(!busy)finish(false);};
    form.onsubmit=async e=>{e.preventDefault();if(busy)return;feedback.textContent='';if(form.elements.password.value!==form.elements.confirmation.value){feedback.textContent='Las contraseñas no coinciden.';return;}busy=true;save.disabled=cancel.disabled=true;save.textContent='Guardando…';
      try{await jsonRequest('/api/admin/change-password',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({current_password:currentPassword,password:form.elements.password.value})});finish(true);}catch(error){feedback.textContent=error.message;}finally{busy=false;save.disabled=cancel.disabled=false;save.textContent='Guardar contraseña';}
    };form.elements.password.focus();
  });}
  let loginBusy=false;
  window.nexoraStartLogin=async function(event){
    event.preventDefault();if(loginBusy)return;loginBusy=true;
    const form=document.querySelector('#login'),button=form.querySelector('[type=submit]'),message=document.querySelector('#message'),previous=button.textContent;
    button.disabled=true;button.textContent='Entrando…';form.setAttribute('aria-busy','true');message.textContent='Verificando tus datos…';
    try{
      const password=document.querySelector('#password').value;
      const data=await jsonRequest('/api/admin/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:document.querySelector('#username').value.trim(),password})});
      sessionStorage.setItem(tokenKey,data.token);nexoraSessionExpired=false;
      if(data.must_change_password&&!await changeRequiredPassword(data.token,password)){sessionStorage.removeItem(tokenKey);message.textContent='Debes cambiar tu contraseña para continuar.';return;}
      document.querySelector('#password').value='';message.textContent='';loginView.classList.add('hidden');appView.classList.remove('hidden');setStatus('Sesión administrativa activa.');
      // Mostrar el portal de inmediato; la carga de datos no bloquea el botón de acceso.
      Promise.resolve().then(()=>refreshAll()).catch(error=>{console.error(error);setStatus('Entraste al portal, pero no se pudieron actualizar todos los datos. Usa Actualizar.','error');});startAutomaticRefresh();
    }catch(error){message.textContent=error.message||'No fue posible conectar con el servidor.';}finally{loginBusy=false;button.disabled=false;button.textContent=previous;form.removeAttribute('aria-busy');}
  };
})();
