import pool from '../db/client';

export type BreakKind = 'MEAL' | 'REST';
export type Schedule = {
  meal_start_time?: string; meal_end_time?: string; meal_duration_minutes?: number;
  rest_start_time?: string; rest_duration_minutes?: number;
};
export function minutes(value: unknown): number | null {
  if (typeof value !== 'string' || !/^([01]?\d|2[0-3]):[0-5]\d(:[0-5]\d)?$/.test(value)) return null;
  const [h, m] = value.split(':').map(Number); return h * 60 + m;
}
export function windows(schedule: Schedule): Array<{ kind: BreakKind; start: number; end: number }> {
  const result: Array<{ kind: BreakKind; start: number; end: number }> = [];
  const meal = minutes(schedule.meal_start_time), rest = minutes(schedule.rest_start_time);
  if (meal !== null) result.push({kind: 'MEAL', start: meal,
    end: minutes(schedule.meal_end_time) ?? meal + Number(schedule.meal_duration_minutes ?? 60)});
  if (rest !== null) result.push({kind: 'REST', start: rest, end: rest + Number(schedule.rest_duration_minutes ?? 30)});
  return result.filter(w => w.end > w.start && w.end <= 1440);
}
export function classifyBreak(schedule: Schedule, at: number, returning: boolean,
  used: BreakKind[] = [], previous?: BreakKind | null): {kind: BreakKind | null; issue: string | null; late: number} {
  const all = windows(schedule);
  if (!returning) {
    const matching = all.filter(w=>at>=w.start && at<=w.end)
      .sort((a,b)=>Math.abs(at-a.start)-Math.abs(at-b.start))[0];
    if (matching) return {kind:matching.kind,issue:null,late:0};
    return {kind:'REST',issue:'Descanso fuera de los horarios establecidos de comida y descanso',late:0};
  }
  // El regreso conserva el tipo de su propia salida, aunque esa salida esté fuera de rango.
  const window = previous ? all.find(w=>w.kind===previous) : undefined;
  if (!previous) return {kind:'REST',issue:'Regreso de descanso sin salida identificada',late:0};
  if (!window) return {kind:previous,issue:'Regreso de descanso fuera de un horario configurado',late:0};
  const name = window.kind === 'MEAL' ? 'comida' : 'descanso';
  let issue: string | null = null;
  if (at < window.start || at > window.end) issue = `Regreso de ${name} fuera del horario configurado`;
  return {kind:window.kind, issue, late:Math.max(0, at-window.end)};
}

// Recalcula ausencias de pausa de jornadas con entrada. Las alertas pendientes
// se reconcilian cuando llegan checadas offline; una calificación previa se conserva.
export async function processMissingBreaks(): Promise<void> {
  const days = await pool.query(`
    WITH worked AS (
      SELECT DISTINCT company_id, employee_id,
        (to_timestamp(occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date AS day
      FROM attendance_records WHERE event_type='CLOCK_IN'
        AND occurred_at >= EXTRACT(EPOCH FROM (NOW()-INTERVAL '7 days'))*1000
    )
    SELECT w.day::text, w.company_id, e.id_remote AS employee_id, c.id AS category_id,
      c.meal_start_time,c.meal_end_time,c.meal_duration_minutes,c.rest_start_time,c.rest_duration_minutes,
      EXTRACT(EPOCH FROM (w.day::timestamp AT TIME ZONE 'America/Mexico_City'))*1000 AS day_ms,
      COALESCE((SELECT json_agg(json_build_object('event_type',a.event_type,'break_kind',a.break_kind,'occurred_at',a.occurred_at) ORDER BY a.occurred_at)
        FROM attendance_records a WHERE a.company_id=w.company_id
          AND a.employee_id IN (e.id_local,e.id_remote::text,e.employee_code)
          AND (to_timestamp(a.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date=w.day),'[]'::json) AS records
    FROM worked w JOIN employees e ON e.company_id=w.company_id
      AND w.employee_id IN (e.id_local,e.id_remote::text,e.employee_code)
    LEFT JOIN LATERAL (SELECT dc.category_id FROM employee_day_categories dc
      WHERE dc.employee_id=e.id_remote AND dc.weekday=EXTRACT(ISODOW FROM w.day)::int LIMIT 1) dc ON TRUE
    JOIN categories c ON c.id=COALESCE(dc.category_id,e.category_id)
    JOIN category_workdays cw ON cw.category_id=c.id AND cw.weekday=EXTRACT(ISODOW FROM w.day)::int AND cw.is_workday
    WHERE NOT EXISTS (SELECT 1 FROM category_rest_days rd WHERE rd.category_id=c.id AND rd.weekday=EXTRACT(ISODOW FROM w.day)::int)
      AND NOT EXISTS (SELECT 1 FROM employee_vacations v WHERE v.employee_id=e.id_remote AND w.day BETWEEN v.start_date AND v.end_date)
  `);
  for (const day of days.rows) {
    const records = day.records as Array<{event_type:string;break_kind:BreakKind|null;occurred_at:number}>;
    const dayMs = Number(day.day_ms), now = Date.now();
    const used: BreakKind[] = [];
    let open: BreakKind | null = null;
    for (const record of records) {
      if (!['MEAL_START','MEAL_END'].includes(record.event_type)) continue;
      const returning = record.event_type==='MEAL_END';
      const at = Math.floor((Number(record.occurred_at)-dayMs)/60000);
      record.break_kind ??= classifyBreak(day, at, returning, used, open).kind;
      if (returning) open=null;
      else { open=record.break_kind; if (open) used.push(open); }
    }
    for (const window of windows(day)) {
      const starts = records.filter(r => r.event_type==='MEAL_START' && r.break_kind===window.kind);
      const ends = records.filter(r => r.event_type==='MEAL_END' && r.break_kind===window.kind);
      const deadline = dayMs+(window.end+10)*60000;
      const closed = records.some(r => r.event_type==='CLOCK_OUT');
      for (const direction of ['START','END']) {
        const origin = `MISSING_${window.kind}_${direction}`;
        const unreturned = records.filter(r=>r.break_kind===window.kind).reduce((open,r)=>
          r.event_type==='MEAL_START'?open+1:r.event_type==='MEAL_END'?Math.max(0,open-1):open,0);
        const missing = direction==='START' ? starts.length===0 : unreturned>0;
        if ((now>=deadline || closed) && missing) {
          await pool.query(`INSERT INTO attendance_auto_incidents
            (company_id,employee_id,category_id,incident_date,concept,origin,notes)
            VALUES ($1,$2,$3,$4::date,'Incidencia',$5,$6) ON CONFLICT DO NOTHING`,
            [day.company_id,day.employee_id,day.category_id,day.day,origin,
             `No se registró ${direction==='START'?'salida a':'regreso de'} ${window.kind==='MEAL'?'comida':'descanso'} al concluir su horario`]);
        } else {
          await pool.query(`DELETE FROM attendance_auto_incidents WHERE company_id=$1 AND employee_id=$2
            AND incident_date=$3::date AND origin=$4 AND status='pending'`,
            [day.company_id,day.employee_id,day.day,origin]);
        }
      }
    }
  }
}
