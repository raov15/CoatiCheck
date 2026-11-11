(function(){
 const movementColumns=['Entrada','Salida','Comida','Regreso de comer','Descanso','Regreso del descanso'];
 const concepts=['Retardo','Ausentismo','Permiso médico justificado','Permiso médico no justificado','Falta justificada','Salida no autorizada','Salida autorizada','Tiempo extraordinario','Historial'];
 const finite=v=>v!==null&&v!==undefined&&v!==''&&Number.isFinite(Number(v));
 function localParts(ms){if(ms===null||ms===undefined||ms==='')return null;const d=new Date(Number.isFinite(Number(ms))?Number(ms):ms);if(!Number.isFinite(d.getTime()))return null;const p=Object.fromEntries(new Intl.DateTimeFormat('en-CA',{timeZone:'America/Mexico_City',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).formatToParts(d).map(p=>[p.type,p.value]));return {date:p.year+'-'+p.month+'-'+p.day,time:p.hour+':'+p.minute+':'+p.second};}
 function serial(day){return Date.parse(day+'T00:00:00Z')/86400000+25569;}
 function nameFor(worker,used){let raw=String(worker.rfc||worker.employee_code||'Sin RFC').replace(/[\\/?*\[\]:]/g,' ').replace(/^'+|'+$/g,'').trim()||'Trabajador';raw=raw.slice(0,31);let name=raw,n=1;while(used.has(name.toLowerCase())){const suffix=' ('+(++n)+')';name=raw.slice(0,31-suffix.length)+suffix;}used.add(name.toLowerCase());return name;}
 window.nexoraBuildWorkbook=function(data,filters,X){
  if(!X?.utils)throw Error('No se cargó la librería Excel. Comprueba la conexión y recarga la página.');
  const wb=X.utils.book_new(),used=new Set(),profiles=new Map((data.employees||[]).map(w=>[String(w.employee_code),w]));
  const groups=new Map([...profiles.keys()].map(k=>[k,[]]));
  for(const record of data.records||[]){const key=String(record.employee_code);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(record);if(!profiles.has(key))profiles.set(key,{employee_code:key,full_name:record.full_name});}
  if(!groups.size)throw Error('No hay trabajadores con esos filtros.');
  const from=filters.from||'',to=filters.to||'';
  const onePeriod=!!from&&!!to&&from<=to&&from.slice(0,7)===to.slice(0,7)&&(Number(from.slice(8))<=15)===(Number(to.slice(8))<=15);
  for(const [code,records] of groups){
   const worker=profiles.get(code);records.sort((a,b)=>(Number.isFinite(Number(a.occurred_at))?Number(a.occurred_at):Date.parse(a.occurred_at))-(Number.isFinite(Number(b.occurred_at))?Number(b.occurred_at):Date.parse(b.occurred_at)));
   const salary=finite(worker.daily_salary)?Number(worker.daily_salary):null;
   const headers=['Fecha','Hora','Calificación','Detalle del movimiento',...movementColumns,...concepts,'Valuación aplicada (días)','Observaciones'];
   const valueCol=headers.indexOf('Valuación aplicada (días)'),noteCol=headers.indexOf('Observaciones'),attendanceCol=noteCol+1;
   const rows=[[worker.full_name||code],['RFC',String(worker.rfc||'Sin RFC'),'CURP',String(worker.curp||''),'NSS',String(worker.nss||'')],['Código',code,'Corte',from||'','Hasta',to||''],['Sueldo diario',salary===null?'Falta sueldo diario':salary],[],[],headers];
   const valuations=(category,concept)=>{const exact=(data.valuations||[]).find(v=>String(v.category_id)===String(category)&&v.concept===concept);const fallback=(worker.valuations||[]).find(v=>v.concept===concept);return finite(exact?.value)?Number(exact.value):finite(fallback?.value)?Number(fallback.value):null;};
   const details=[];
   for(const r of records){const parts=localParts(r.occurred_at);if(!parts)continue;if(from&&parts.date<from||to&&parts.date>to)continue;
    const row=Array(headers.length).fill(null);row[0]=serial(parts.date);row[1]=parts.time;row[2]=r.concept||'';
    let movement=r.event_type==='CLOCK_IN'?'Entrada':r.event_type==='CLOCK_OUT'?'Salida':r.event_type==='MEAL_START'?(r.break_kind==='REST'?'Descanso':'Comida'):r.event_type==='MEAL_END'?(r.break_kind==='REST'?'Regreso del descanso':'Regreso de comer'):null;
    if(movement){row[headers.indexOf(movement)]=1;row[3]=movement+(['MEAL_START','MEAL_END'].includes(r.event_type)&&r.break_number?' '+r.break_number:'');}
    if(concepts.includes(r.concept))row[headers.indexOf(r.concept)]=1;
    if(r.weekly_absence_generated)row[headers.indexOf('Ausentismo')]=1;
    let value=finite(r.applied_value)?Number(r.applied_value):['Retardo','Historial'].includes(r.concept)?0:valuations(r.category_id,r.concept);
    if(!r.concept)value=0;
    row[valueCol]=value;row[noteCol]=(r.weekly_absence_generated?'Ausentismo generado por 3 retardos de la misma semana. ':'')+(r.notes||'');
    details.push({row,value,concept:r.concept,generated:!!r.weekly_absence_generated});rows.push(row);
   }
   const first=8,last=7+details.length,total=rows.length+1;
   rows.push(Array(headers.length).fill(null));rows[total-1][0]='Totales';
   const configured=rows.length+1;rows.push(Array(headers.length).fill(null));rows[configured-1][0]='Valuación configurada';
   const equivalent=rows.length+1;rows.push(Array(headers.length).fill(null));rows[equivalent-1][0]='Equivalencia aplicada (días)';
   const payStart=2;
   const summaryLabelCol=headers.length+1,summaryValueCol=headers.length+3;
   const payLabels=['Asistencia','Ausentismos (días)','Sueldo diario','Pago'];
   for(let i=0;i<payLabels.length;i++){const row=rows[payStart+i-1]||(rows[payStart+i-1]=[]);row[summaryLabelCol]=payLabels[i];}
   rows[0][summaryLabelCol]='RESUMEN';
   if(from&&to&&!onePeriod){rows[6][summaryLabelCol]='ESTÁ TOMANDO DÍAS DE LA SIGUIENTE QUINCENA';}


   const ws=X.utils.aoa_to_sheet(rows);
   const col=i=>X.utils.encode_col(i),address=(c,r)=>col(c)+r;
   const put=(c,r,f,v,z)=>{ws[address(c,r)]={t:typeof v==='string'?'s':'n',f,v,...(z?{z}:{})};};
   const range=c=>col(c)+first+':'+col(c)+last;
   for(let c=4;c<valueCol;c++){
    const count=details.reduce((n,d)=>n+(d.row[c]||0),0);put(c,total,details.length?'SUM('+range(c)+')':'0',count,'0');
    const concept=headers[c];if(concepts.includes(concept)){const val=valuations(worker.category_id,concept);if(val!==null)ws[address(c,configured)]={t:'n',v:val,z:'0.00'};
      const applied=details.reduce((sum,d)=>sum+((d.row[c]||0)&&(concept!=='Retardo'||!d.generated)?(d.value||0):0),0);
      let f=details.length?'SUMIFS('+range(valueCol)+','+range(c)+',1'+(concept==='Retardo'?','+range(headers.indexOf('Ausentismo'))+',0':'')+')':'0';
      put(c,equivalent,f,applied,'0.00');
    }
   }
   const absenceColumn=headers.indexOf('Ausentismo');
   const missingAbsenceValue=details.some(d=>d.row[absenceColumn]===1&&d.value===null);
   const deduction=details.reduce((n,d)=>n+(d.row[absenceColumn]===1?(d.value||0):0),0);
   const base=365/24,net=base-deduction;
   const cell=row=>address(summaryValueCol,row),absCell=address(absenceColumn,equivalent);
   put(summaryValueCol,payStart,'365/24',base,'0.0000');
   put(summaryValueCol,payStart+1,missingAbsenceValue?'"Falta valuación de Ausentismo"':absCell,missingAbsenceValue?'Falta valuación de Ausentismo':deduction,'0.00');
   const ready=!!from&&!!to&&from<=to&&!missingAbsenceValue&&details.length>0;
   const reason=missingAbsenceValue?'Falta valuación de ausentismo':!details.length?'':!from||!to?'Selecciona Desde y Hasta':'El corte abarca varias quincenas';
   put(summaryValueCol,payStart+2,'B4',salary===null?'Falta sueldo diario':salary,'"$"#,##0.00');
   const payrollReady=ready&&salary!==null;
   put(summaryValueCol,payStart+3,payrollReady?'('+cell(payStart)+'-'+cell(payStart+1)+')*'+cell(payStart+2):'"'+(salary===null?'Falta sueldo diario':reason)+'"',payrollReady?net*salary:salary===null?'Falta sueldo diario':reason,'"$"#,##0.00');
   for(let n=first;n<=last;n++)if(ws['A'+n])ws['A'+n].z='dd/mm/yyyy';
   ws['B4'].z='"$"#,##0.00';
   ws['!cols']=headers.map((h,i)=>({wch:i===0?19:i===1?19:i===2||i===3?38:i===noteCol?62:Math.min(38,Math.max(18,h.length+4))}));
   ws['!rows']=rows.map((_,i)=>({hpt:i===0?32:i===6?30:i>=payStart-1&&i<payStart+payLabels.length-1?26:24}));
   ws['!merges']=[{s:{r:0,c:0},e:{r:0,c:8}},{s:{r:0,c:summaryLabelCol},e:{r:0,c:summaryValueCol}},...payLabels.map((_,i)=>({s:{r:payStart+i-1,c:summaryLabelCol},e:{r:payStart+i-1,c:summaryValueCol-1}}))];
   ws['!cols'].push({wch:4},{wch:20},{wch:14},{wch:24});
   ws['!ref']='A1:'+col(summaryValueCol)+Math.max(rows.length,payStart+payLabels.length-1);
   ws['!margins']={left:.4,right:.4,top:.5,bottom:.5,header:.2,footer:.2};
   if(details.length)ws['!autofilter']={ref:'A7:'+col(noteCol)+last};
   X.utils.book_append_sheet(wb,ws,nameFor(worker,used));
  }
  wb.Workbook={...(wb.Workbook||{}),CalcPr:{calcMode:'auto',fullCalcOnLoad:true}};
  return wb;
 };
})();
