import pool from '../db/client';
let running=false;
export async function processMissingExits():Promise<void>{
 if(running)return;running=true;
 try{
 await pool.query(`DELETE FROM attendance_auto_incidents ai USING automatic_shift_closures cl,employees e
 WHERE ai.company_id=cl.company_id AND ai.employee_id=cl.employee_id AND ai.incident_date=cl.work_date
 AND ai.origin='MISSING_EXIT' AND ai.status='pending' AND e.id_remote=cl.employee_id
 AND EXISTS(SELECT 1 FROM attendance_records a WHERE a.company_id=cl.company_id AND a.employee_id IN(e.id_local,e.id_remote::text,e.employee_code)
 AND a.event_type='CLOCK_OUT' AND a.occurred_at<=cl.closed_at
 AND (to_timestamp(a.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date>=cl.work_date);`);
 await pool.query(`DELETE FROM automatic_shift_closures cl USING employees e WHERE e.id_remote=cl.employee_id
 AND NOT EXISTS(SELECT 1 FROM attendance_auto_incidents ai WHERE ai.company_id=cl.company_id AND ai.employee_id=cl.employee_id AND ai.incident_date=cl.work_date AND ai.origin='MISSING_EXIT' AND ai.status='reviewed')
 AND EXISTS(SELECT 1 FROM attendance_records a WHERE a.company_id=cl.company_id AND a.employee_id IN(e.id_local,e.id_remote::text,e.employee_code)
 AND a.event_type='CLOCK_OUT' AND a.occurred_at<=cl.closed_at
 AND (to_timestamp(a.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date>=cl.work_date);`);
 await pool.query(`WITH entries AS (
 SELECT e.company_id,e.id_remote AS employee_id,
 (to_timestamp(a.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date AS day,
 MIN(a.occurred_at) AS entered FROM attendance_records a JOIN employees e ON e.company_id=a.company_id
 AND a.employee_id IN(e.id_local,e.id_remote::text,e.employee_code)
 WHERE a.event_type='CLOCK_IN' AND a.occurred_at>=EXTRACT(EPOCH FROM (SELECT enabled_at FROM exit_policy_activation WHERE id=TRUE))*1000
 GROUP BY e.company_id,e.id_remote,(to_timestamp(a.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date
 ), scheduled AS (
 SELECT en.*,c.id AS category_id,EXTRACT(EPOCH FROM ((en.day+cw.end_time::time+CASE WHEN cw.end_time::time<=cw.start_time::time THEN INTERVAL '1 day' ELSE INTERVAL '0' END) AT TIME ZONE 'America/Mexico_City'))*1000 AS deadline
 FROM entries en JOIN employees e ON e.id_remote=en.employee_id
 LEFT JOIN LATERAL(SELECT category_id FROM employee_day_categories WHERE employee_id=e.id_remote AND weekday=EXTRACT(ISODOW FROM en.day)::int LIMIT 1) dc ON TRUE
 JOIN categories c ON c.id=COALESCE(dc.category_id,e.category_id) AND c.overtime_allowed=FALSE
 JOIN LATERAL(SELECT COALESCE(NULLIF(w.start_time,''),NULLIF(c.start_time,'')) AS start_time,COALESCE(NULLIF(w.end_time,''),NULLIF(c.end_time,'')) AS end_time
 FROM category_workdays w WHERE w.category_id=c.id AND w.weekday=EXTRACT(ISODOW FROM en.day)::int AND w.is_workday) cw ON TRUE
 WHERE cw.start_time~'^([01]?[0-9]|2[0-3]):[0-5][0-9]' AND cw.end_time~'^([01]?[0-9]|2[0-3]):[0-5][0-9]'
 AND NOT EXISTS(SELECT 1 FROM attendance_records x WHERE x.company_id=en.company_id AND x.employee_id IN(e.id_local,e.id_remote::text,e.employee_code) AND x.event_type='CLOCK_OUT' AND x.occurred_at>=en.entered
 AND x.occurred_at<=EXTRACT(EPOCH FROM ((en.day+cw.end_time::time+CASE WHEN cw.end_time::time<=cw.start_time::time THEN INTERVAL '1 day' ELSE INTERVAL '0' END) AT TIME ZONE 'America/Mexico_City'))*1000+3600000
 AND x.occurred_at<EXTRACT(EPOCH FROM ((en.day+INTERVAL '2 days') AT TIME ZONE 'America/Mexico_City'))*1000)
 ), closed AS (
 INSERT INTO automatic_shift_closures(company_id,employee_id,work_date,scheduled_exit_at,closed_at)
 SELECT company_id,employee_id,day,deadline,deadline+3600000 FROM scheduled WHERE EXTRACT(EPOCH FROM NOW())*1000>=deadline+3600000
 ON CONFLICT DO NOTHING RETURNING *
 ) INSERT INTO attendance_auto_incidents(company_id,employee_id,category_id,incident_date,concept,origin,notes)
 SELECT cl.company_id,cl.employee_id,e.category_id,cl.work_date,'Incidencia','MISSING_EXIT','No marcó salida. Jornada cerrada automáticamente una hora después de la salida programada; no representa una checada del trabajador.'
 FROM closed cl JOIN employees e ON e.id_remote=cl.employee_id ON CONFLICT DO NOTHING`);
 }finally{running=false;}
}
export function startMissingExitJob(){void processMissingExits().catch(console.error);const timer=setInterval(()=>{void processMissingExits().catch(console.error);},60000);timer.unref();}
