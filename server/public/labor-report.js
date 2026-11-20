(function(){
 const detailedBuilder=window.nexoraBuildWorkbook;
 const dayKey=t=>new Intl.DateTimeFormat('en-CA',{timeZone:'America/Mexico_City',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(Number(t)));
 const time=t=>t==null?'':new Intl.DateTimeFormat('es-MX',{timeZone:'America/Mexico_City',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(new Date(Number(t)));
 const add=(d,n)=>{const x=new Date(d+'T12:00:00Z');x.setUTCDate(x.getUTCDate()+n);return x.toISOString().slice(0,10);};
 const weekday=d=>(new Date(d+'T12:00:00Z').getUTCDay()||7);
 const monday=d=>add(d,1-weekday(d));
 const isoWeek=day=>{const d=new Date(day+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+4-(d.getUTCDay()||7));const year=d.getUTCFullYear(),jan=new Date(Date.UTC(year,0,1,12));return year+'-W'+String(Math.ceil((((d-jan)/86400000)+1)/7)).padStart(2,'0');};
 const minutes=v=>/^\d\d?:\d\d/.test(v||'')?Number(v.split(':')[0])*60+Number(v.split(':')[1]):null;
 const known=v=>v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v));
 const limit=y=>y<=2026?48:y===2027?46:y===2028?44:y===2029?42:40;
 const doubles=y=>y<=2027?9:y===2028?10:y===2029?11:12;
 window.nexoraLaborRules=day=>({weekly:limit(Number(day.slice(0,4))),double:doubles(Number(day.slice(0,4))),dailyExtra:4,extraDays:4,maxDay:12,maxTriple:4,available:day>='2026-05-01'});
 window.nexoraBuildDailyRows=function(worker,events,records,from,to){
   const days=new Map();const get=d=>{if(!days.has(d))days.set(d,{day:d,entry:null,exit:null,breakMs:0,open:null,issues:[]});return days.get(d);};let shiftDay=null;
   for(const e of [...events].sort((a,b)=>Number(a.occurred_at)-Number(b.occurred_at))){const d=dayKey(e.occurred_at),r=get(shiftDay||d),stamp=Number(e.occurred_at);
    if(e.event_type==='CLOCK_IN'){if(shiftDay)r.issues.push('Entrada adicional sin salida previa');shiftDay=d;get(d).entry??=stamp;}
    else if(e.event_type==='MEAL_START'){if(r.open!==null)r.issues.push('Salida de pausa sin regreso previo');r.open=stamp;}
    else if(e.event_type==='MEAL_END'){if(r.open===null)r.issues.push('Regreso sin salida de pausa');else{r.breakMs+=stamp-r.open;r.open=null;}}
    else if(e.event_type==='CLOCK_OUT'){r.exit=stamp;if(r.open!==null)r.issues.push('Pausa sin regreso');shiftDay=null;}
   }
   for(let d=from;d<=to;d=add(d,1)){const r=get(d),schedule=(worker.schedules||[]).find(s=>Number(s.weekday)===weekday(d)),c=schedule?.category,w=schedule?.workday;const start=minutes(w?.start_time||c?.start_time),end=minutes(w?.end_time||c?.end_time);
    r.planned=null;if(w?.is_workday===false)r.planned=0;else if(w?.is_workday===true&&start!==null&&end!==null){let duration=(end-start+1440)%1440;let ranges=[];for(const [v,length] of [[c.meal_start_time,c.meal_duration_minutes??60],[c.rest_start_time,c.rest_duration_minutes??30]]){const p=minutes(v);if(p!==null){const pos=(p-start+1440)%1440;if(pos<duration)ranges.push([pos,Math.min(duration,pos+Number(length))]);}}ranges.sort((a,b)=>a[0]-b[0]);let removed=0,last=-1;for(const [a,b]of ranges){removed+=Math.max(0,b-Math.max(a,last));last=Math.max(last,b);}r.planned=(duration-removed)/60;}
    r.hours=r.entry!==null&&r.exit!==null&&r.open===null&&!r.issues.length?(r.exit-r.entry-r.breakMs)/3600000:null;if(r.hours!==null&&r.hours<0){r.hours=null;r.issues.push('Duración inconsistente');}
    r.ordinary=r.hours!==null&&r.planned!==null?Math.min(r.hours,r.planned):null;r.extra=r.hours!==null&&r.planned!==null?Math.max(0,r.hours-r.planned):null;
    r.incidents=records.filter(x=>dayKey(x.occurred_at)===d);r.shift=worker.shift_type||c?.shift_type||'';r.dailyLimit=r.shift==='Nocturna'?7:r.shift==='Mixta'?7.5:['Matutina','Diurna'].includes(r.shift)?8:null;
   }return [...days.values()].filter(r=>r.day>=from&&r.day<=to).sort((a,b)=>a.day.localeCompare(b.day));
 };
 window.nexoraBuildWorkbook=function(data,filters,X){
   const wb=detailedBuilder(data,filters,X);if(!Array.isArray(data.events)||!filters.from||!filters.to)return wb;
   const profile=data.company?.details?.profile||{},company=data.company||{};for(const name of wb.SheetNames)delete wb.Sheets[name];wb.SheetNames=[];const names=[],used=new Set();
   const unique=base=>{base=base.replace(/[\\/?*\[\]:]/g,' ').slice(0,31);let n=base,i=1;while(used.has(n.toLowerCase()))n=base.slice(0,26)+'_'+(++i);used.add(n.toLowerCase());return n;};
   for(const worker of data.employees||[]){const events=data.events.filter(r=>r.employee_code===worker.employee_code),records=(data.records||[]).filter(r=>r.employee_code===worker.employee_code);const start=monday(filters.from),end=add(monday(filters.to),6);
    const daily=nexoraBuildDailyRows(worker,events,records,start,end),selected=daily.filter(r=>r.day>=filters.from&&r.day<=filters.to),weeks=new Map();for(const row of daily){const key=monday(row.day);if(!weeks.has(key))weeks.set(key,[]);weeks.get(key).push(row);}
    const rows=[],merges=[],bars=[];const section=text=>{bars.push(rows.length);rows.push([text]);merges.push({s:{r:rows.length-1,c:0},e:{r:rows.length-1,c:11}});};
    section('REGISTRO ELECTRÓNICO DE JORNADA LABORAL — REPORTE DEL CORTE');
    rows.push([],['Empleador',profile.business_name||profile.name||company.name||'No registrado','','','','RFC empleador',profile.rfc||''],['Registro patronal IMSS',profile.employer_registration||'','','','','Centro de trabajo',worker.worksite_name||''],['Trabajador',worker.full_name,'','','','Tipo de jornada',worker.shift_type||''],['RFC',worker.rfc||'','','','','CURP',worker.curp||''],['NSS',worker.nss||'','','','','Sueldo diario',known(worker.daily_salary)?Number(worker.daily_salary):''],['Puesto',worker.department||'','','','','Semana ISO',isoWeek(filters.from)],['Periodo',filters.from+' al '+filters.to],[]);
    const header=rows.length;rows.push(['Fecha','Día','Jornada pactada (h)','Hora inicio','Hora fin','Descanso (min)','Horas trabajadas','Horas ordinarias','Horas extra','Incidencia','Método de registro','Observaciones / correcciones']);
    const first=rows.length+1;
    for(const r of selected)rows.push([new Date(r.day+'T00:00:00Z'),['','Lunes','Martes','Miércoles','Jueves','Viernes','Sábado','Domingo'][weekday(r.day)],r.planned,time(r.entry),time(r.exit),r.breakMs/60000,r.hours,r.ordinary,r.extra,[...new Set(r.incidents.map(i=>i.concept).filter(Boolean))].join('; '),[...new Set(events.filter(e=>dayKey(e.occurred_at)===r.day).map(e=>e.record_origin==='ADMINISTRATIVE'?'Registro administrativo':e.record_origin==='MOBILE'?'Registro móvil':e.record_origin==='SHARED'?'Registro compartido':'Origen no registrado'))].join(' / '),r.issues.concat(r.incidents.map(i=>i.notes).filter(Boolean)).join('; ')]);
    const last=rows.length;rows.push(['TOTAL DEL CORTE']);const totals=rows.length;
    rows.push([]);section('RESUMEN DE PAGO');merges[merges.length-1].e.c=1;
    const absences=records.filter(x=>x.concept==='Ausentismo'||x.weekly_absence_generated).reduce((n,x)=>n+(known(x.applied_value)?Number(x.applied_value):Number((worker.valuations||[]).find(v=>v.concept==='Ausentismo')?.value||0)),0);
    const payFirst=rows.length+1;
    rows.push(['Asistencia',365/24],['Ausentismos (días)',absences],['Sueldo diario',known(worker.daily_salary)?Number(worker.daily_salary):'Sin sueldo configurado'],['Pago',known(worker.daily_salary)?(365/24-absences)*Number(worker.daily_salary):'Sin sueldo configurado']);
    for(let r=2;r<=8;r++){merges.push({s:{r,c:1},e:{r,c:4}},{s:{r,c:6},e:{r,c:11}});}
    const ws=X.utils.aoa_to_sheet(rows);ws['!cols']=[20,20,20,18,18,20,20,20,20,32,24,76].map(wch=>({wch}));ws['!rows']=rows.map((_,i)=>({hpt:bars.includes(i)?34:i===header?58:54}));ws['!merges']=merges;ws['!margins']={left:.35,right:.35,top:.45,bottom:.45,header:.2,footer:.2};
    const numeric={};for(const c of [2,5,6,7,8]){const cell=X.utils.encode_col(c)+(totals);const v=selected.reduce((s,r)=>s+(c===2?r.planned||0:c===5?r.breakMs/60000:c===6?r.hours||0:c===7?r.ordinary||0:r.extra||0),0);ws[cell]={t:'n',f:last>=first?'SUM('+X.utils.encode_col(c)+first+':'+X.utils.encode_col(c)+last+')':'0',v,z:'0.00'};}
    for(let r=0;r<rows.length;r++)for(let c=0;c<12;c++){const key=X.utils.encode_col(c)+(r+1);if(!rows[r].length||((r>=payFirst-2)&&c>1)){delete ws[key];continue;}if(!ws[key])ws[key]={t:'s',v:''};const bar=bars.includes(r)||r===header;ws[key].s={font:{name:'Calibri',sz:r===4?16:13,bold:bar||r===totals-1||c===0||r===4,color:{rgb:bar?'FFFFFF':'20354D'}},fill:{patternType:'solid',fgColor:{rgb:bar?'20385D':r%2?'FFFFFF':'F3F6FA'}},border:Object.fromEntries(['top','bottom','left','right'].map(side=>[side,{style:'thin',color:{rgb:'A7B4C4'}}])),alignment:{vertical:'center',wrapText:true}};if(typeof ws[key].v==='number')ws[key].z='0.00';if(r>=first-1&&r<=last-1&&c===0)ws[key].z='dd/mm/yyyy';}
    if(ws.G7&&known(worker.daily_salary)){ws.G7.z='"$"#,##0.00';ws.G7.s.alignment.horizontal='left';}
    ws['B'+payFirst]={...(ws['B'+payFirst]||{}),t:'n',f:'365/24',v:365/24,z:'0.0000'};
    if(known(worker.daily_salary)){ws['B'+(payFirst+2)].z='"$"#,##0.00';ws['B'+(payFirst+3)]={...(ws['B'+(payFirst+3)]||{}),t:'n',f:'(B'+payFirst+'-B'+(payFirst+1)+')*B'+(payFirst+2),v:(365/24-absences)*Number(worker.daily_salary),z:'"$"#,##0.00'};}
    for(let n=payFirst;n<=payFirst+3;n++)if(ws['B'+n]?.s)ws['B'+n].s.alignment.horizontal='left';
    for(let n=0;n<rows.length;n++)if(rows[n][3]==='Cumple'||rows[n][3]==='Revisar'){const c=ws['D'+(n+1)];c.s.fill.fgColor.rgb=rows[n][3]==='Cumple'?'DCFCE7':'FEE2E2';c.s.font.bold=true;}
    if(last>=first)ws['!autofilter']={ref:'A'+(header+1)+':L'+last};
    X.utils.book_append_sheet(wb,ws,unique(worker.rfc||worker.employee_code));
   }
   wb.SheetNames=[...wb.SheetNames.filter(n=>!names.includes(n)),...names];return wb;
 };
})();
