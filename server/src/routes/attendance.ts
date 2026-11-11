import { classifyBreak, processMissingBreaks, BreakKind } from '../services/break-policy';
import { Router, Response } from 'express';
import pool from '../db/client';
import { authMiddleware, AuthRequest } from '../middleware/auth';

const router = Router();
async function attendanceMetadata(companyId:string,ids:string[]) {
  if(!ids.length)return [];
  const result=await pool.query(`SELECT a.id_local,a.id_remote,a.break_kind,
    CASE WHEN a.event_type IN ('MEAL_START','MEAL_END') THEN (
      SELECT COUNT(*)::int FROM attendance_records b WHERE b.company_id=a.company_id AND b.employee_id=a.employee_id
        AND b.event_type='MEAL_START' AND b.break_kind IS NOT DISTINCT FROM a.break_kind
        AND (to_timestamp(b.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date=
            (to_timestamp(a.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date
        AND b.occurred_at<=a.occurred_at
    ) ELSE NULL END AS break_number
    FROM attendance_records a WHERE a.company_id=$1 AND a.id_local=ANY($2::text[])`,[companyId,ids]);
  return result.rows;
}
router.post('/metadata',authMiddleware,async(req:AuthRequest,res:Response):Promise<void>=>{
  const ids=Array.isArray(req.body?.ids)?req.body.ids.filter((x:unknown)=>typeof x==='string').slice(0,500):[];
  try {
    const device=await pool.query('SELECT company_id FROM devices WHERE id_remote=$1',[req.deviceId]);
    if(!device.rows[0]?.company_id){res.status(403).json({error:'Dispositivo sin empresa'});return;}
    res.json(await attendanceMetadata(device.rows[0].company_id,ids));
  }catch(error){res.status(500).json({error:'No se pudo actualizar el tipo de pausa'});}
});

interface AttendanceRecordInput {
  id_local: string;
  employee_id: string;
  event_type: string;
  occurred_at: number;
  latitude?: number | null;
  longitude?: number | null;
  accuracy_m?: number | null;
  altitude_m?: number | null;
  face_confidence?: number | null;
}

const VALID_EVENTS = new Set([
  'CLOCK_IN',
  'CLOCK_OUT',
  'MEAL_START',
  'MEAL_END',
]);

function getMexicoIsoWeekday(epochMs: number): number {
  const date = new Date(epochMs);
  const weekday = new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/Mexico_City',
    weekday: 'short',
  }).format(date);

  const map: Record<string, number> = {
    Mon: 1,
    Tue: 2,
    Wed: 3,
    Thu: 4,
    Fri: 5,
    Sat: 6,
    Sun: 7,
  };

  return map[weekday] ?? 1;
}

function nullableNumber(value: unknown): number | null {
  if (value === '' || value === undefined || value === null) return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function mexicoDay(epochMs: number): string {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone:'America/Mexico_City', year:'numeric', month:'2-digit', day:'2-digit' }).formatToParts(new Date(epochMs));
  const get=(type:string)=>parts.find(p=>p.type===type)?.value||'';
  return `${get('year')}-${get('month')}-${get('day')}`;
}
function minuteOfDay(epochMs: number): number {
  const parts = new Intl.DateTimeFormat('en-GB', {timeZone:'America/Mexico_City', hour:'2-digit', minute:'2-digit', hourCycle:'h23'}).formatToParts(new Date(epochMs));
  return Number(parts.find(p=>p.type==='hour')?.value||0)*60+Number(parts.find(p=>p.type==='minute')?.value||0);
}
function timeMinutes(s: unknown): number | null {
  if(typeof s !== 'string' || !/^\d{1,2}:\d{2}/.test(s)) return null;
  const [h,m]=s.split(':').map(Number); return h<24 && m<60 ? h*60+m : null;
}

/* =========================================================
   POST /api/attendance/sync

   REGLAS:
   - attendance_records.employee_id guarda employees.id_local.
   - El centro esperado se obtiene de employee_work_sites según
     el día del registro.
   - Si no existe asignación por día, se usa employees.site_id
     únicamente como compatibilidad.
   - Un cargo/departamento NUNCA se interpreta como centro.
   - attendance_records.site_id guarda el centro real esperado
     para ese registro.
   - Android no modifica categorías, horarios ni centros.
========================================================= */
router.post(
  '/sync',
  authMiddleware,
  async (req: AuthRequest, res: Response): Promise<void> => {
    const records: AttendanceRecordInput[] = req.body?.records;

    if (!Array.isArray(records) || records.length === 0) {
      res.status(400).json({ error: 'records debe ser un arreglo no vacío' });
      return;
    }

    if (!req.deviceId) {
      res.status(401).json({ error: 'No se pudo identificar el dispositivo' });
      return;
    }

    try {
      const deviceResult = await pool.query(
        `
        SELECT id_remote, id_local, device_name, company_id, site_id
        FROM devices
        WHERE id_remote = $1
        LIMIT 1
        `,
        [req.deviceId],
      );

      if (deviceResult.rows.length === 0) {
        res.status(403).json({ error: 'El dispositivo no está registrado' });
        return;
      }

      const device = deviceResult.rows[0];

      if (!device.company_id) {
        res.status(403).json({ error: 'El dispositivo no tiene empresa asignada' });
        return;
      }

      const synced: {
        id_local: string;
        id_remote: string;
        site_id: string | null;
      }[] = [];

      const errors: {
        id_local: string;
        error: string;
      }[] = [];

      for (const rawRecord of records) {
        const record = rawRecord ?? ({} as AttendanceRecordInput);

        const idLocal = String(record.id_local ?? '').trim();
        const employeeId = String(record.employee_id ?? '').trim();
        const normalizedEvent = String(record.event_type ?? '').trim().toUpperCase();
        const occurredAt = Number(record.occurred_at);

        if (!idLocal || !employeeId || !normalizedEvent || !Number.isFinite(occurredAt) || occurredAt <= 0) {
          errors.push({
            id_local: idLocal || 'desconocido',
            error: 'Campos requeridos faltantes o inválidos',
          });
          continue;
        }

        if (!VALID_EVENTS.has(normalizedEvent)) {
          errors.push({
            id_local: idLocal,
            error: `Tipo de evento inválido: ${record.event_type}`,
          });
          continue;
        }

        try {
          const employeeResult = await pool.query(
            `
            SELECT
              id_remote,
              id_local,
              employee_code,
              full_name,
              company_id,
              site_id,
              category_id
            FROM employees
            WHERE company_id = $1
              AND is_active = TRUE
              AND (
                id_local = $2
                OR id_remote::text = $2
                OR employee_code = $2
              )
            LIMIT 1
            `,
            [device.company_id, employeeId],
          );

          if (employeeResult.rows.length === 0) {
            errors.push({
              id_local: idLocal,
              error: `Empleado no encontrado: ${employeeId}`,
            });
            continue;
          }

          const employee = employeeResult.rows[0];
          const weekday = getMexicoIsoWeekday(occurredAt);

          const scheduledSiteResult = await pool.query(
            `
             SELECT ews.work_mode, ews.site_id, s.name AS site_name
             FROM employee_work_sites ews
             LEFT JOIN sites s
               ON s.id = ews.site_id
              AND s.company_id = $2
             WHERE ews.employee_id = $1
               AND ews.weekday = $3
             LIMIT 1
            `,
            [employee.id_remote, device.company_id, weekday],
          );

          const scheduledSite = scheduledSiteResult.rows[0] ?? null;
          const workMode = String(scheduledSite?.work_mode ?? 'SITE').trim().toUpperCase();
          const isForeign = workMode === 'FOREIGN';

          const expectedSiteId: string | null =
            isForeign ? null : (scheduledSite?.site_id ?? employee.site_id ?? null);

          /*
           * Si existe centro esperado, comprobamos además que siga
           * perteneciendo a la misma empresa. Así nunca se guarda
           * accidentalmente un cargo, un centro eliminado o un UUID
           * de otra empresa.
           */
          if (expectedSiteId) {
            const validSite = await pool.query(
              `
              SELECT id
              FROM sites
              WHERE id = $1
                AND company_id = $2
              LIMIT 1
              `,
              [expectedSiteId, device.company_id],
            );

            if (!validSite.rows[0]) {
              errors.push({
                id_local: idLocal,
                error: `El centro asignado a ${employee.full_name} ya no es válido`,
              });
              continue;
            }
          }

          /*
           * Si el dispositivo está ligado a un centro y el trabajador
           * tiene otro centro para ese día, se rechaza el registro.
           */
          if (
            !isForeign &&
            device.site_id &&
            expectedSiteId &&
            String(device.site_id) !== String(expectedSiteId)
          ) {
            errors.push({
              id_local: idLocal,
              error: `El empleado ${employee.full_name} tiene otro centro de trabajo asignado para este día`,
            });
            continue;
          }

          // Idempotencia y secuencia de jornada, incluso con sincronización offline.
          // Se serializa por empleado/día para evitar movimientos simultáneos.
          const existingRecord = await pool.query(
            `SELECT id_remote, site_id FROM attendance_records WHERE id_local=$1 AND company_id=$2 LIMIT 1`,
            [idLocal, device.company_id],
          );
          if (existingRecord.rows.length) {
            synced.push({id_local:idLocal,id_remote:existingRecord.rows[0].id_remote,
              site_id:existingRecord.rows[0].site_id ?? null});
            continue;
          }
          const day = mexicoDay(occurredAt);
          const eventsToday = await pool.query(
            `SELECT event_type, occurred_at, break_kind FROM attendance_records
             WHERE company_id=$1 AND employee_id=$2
               AND (to_timestamp(occurred_at / 1000.0) AT TIME ZONE 'America/Mexico_City')::date=$3::date
             ORDER BY occurred_at ASC`, [device.company_id, employee.id_local, day]);
          const events = eventsToday.rows.filter((r:any)=>Number(r.occurred_at)<=occurredAt);
          const futureEvents = eventsToday.rows.some((r:any)=>Number(r.occurred_at)>occurredAt);
          const last = events.length ? String(events[events.length-1].event_type) : null;
          const allowed = last === null ? normalizedEvent === 'CLOCK_IN'
            : last === 'CLOCK_IN' || last === 'MEAL_END'
              ? (normalizedEvent === 'MEAL_START' || normalizedEvent === 'CLOCK_OUT')
              : last === 'MEAL_START' ? normalizedEvent === 'MEAL_END' : false;
          if ((!allowed || futureEvents) && !['MEAL_START','MEAL_END'].includes(normalizedEvent)) {
            errors.push({id_local:idLocal,error:'Movimiento fuera de secuencia o fecha; sincroniza la jornada en orden'});
            continue;
          }

          const scheduleResult = await pool.query(
            `SELECT c.id AS category_id,c.meal_start_time,c.meal_end_time,c.rest_start_time,
                    c.rest_duration_minutes,c.meal_duration_minutes
             FROM employees e LEFT JOIN LATERAL (
               SELECT dc.category_id FROM employee_day_categories dc
               WHERE dc.employee_id=e.id_remote AND dc.weekday=$3 LIMIT 1
             ) daily ON TRUE
             JOIN categories c ON c.id=COALESCE(daily.category_id,e.category_id)
             WHERE e.id_remote=$1 AND e.company_id=$2 LIMIT 1`,
            [employee.id_remote, device.company_id, weekday],
          );
          const schedule = scheduleResult.rows[0] ?? {};
          let breakKind: BreakKind | null = null;
          let lateReturnMinutes = 0;
          const breakIssues: string[] = [];
          if (normalizedEvent === 'MEAL_START' || normalizedEvent === 'MEAL_END') {
            const departures = events.filter((r:any)=>r.event_type==='MEAL_START');
            const previous = last==='MEAL_START' ? departures[departures.length-1]?.break_kind : null;
            const classified = classifyBreak(schedule, minuteOfDay(occurredAt), normalizedEvent==='MEAL_END',
              departures.map((r:any)=>r.break_kind).filter(Boolean), previous);
            breakKind = classified.kind;
            lateReturnMinutes = classified.late;
            if (classified.issue) breakIssues.push(classified.issue);
            if (!allowed) breakIssues.push('Marca de pausa fuera de secuencia; requiere revisión del administrador');
          }

          const latitude = nullableNumber(record.latitude);
          const longitude = nullableNumber(record.longitude);
          const accuracyM = nullableNumber(record.accuracy_m);
          const altitudeM = nullableNumber(record.altitude_m);
          const faceConfidence = nullableNumber(record.face_confidence);

          if (latitude !== null && (latitude < -90 || latitude > 90)) {
            errors.push({ id_local: idLocal, error: 'Latitud inválida' });
            continue;
          }

          if (longitude !== null && (longitude < -180 || longitude > 180)) {
            errors.push({ id_local: idLocal, error: 'Longitud inválida' });
            continue;
          }

          const result = await pool.query(
            `
            INSERT INTO attendance_records (
              id_local,
              employee_id,
              event_type,
              occurred_at,
              latitude,
              longitude,
              accuracy_m,
              altitude_m,
              face_confidence,
              device_id,
              company_id,
              site_id,
              break_kind,
              late_return_minutes
            )
            VALUES (
              $1, $2, $3, $4, $5, $6,
              $7, $8, $9, $10, $11, $12, $13, $14
            )
            ON CONFLICT (id_local)
            DO UPDATE SET
              employee_id = EXCLUDED.employee_id,
              event_type = EXCLUDED.event_type,
              occurred_at = EXCLUDED.occurred_at,
              latitude = EXCLUDED.latitude,
              longitude = EXCLUDED.longitude,
              accuracy_m = EXCLUDED.accuracy_m,
              altitude_m = EXCLUDED.altitude_m,
              face_confidence = EXCLUDED.face_confidence,
              device_id = EXCLUDED.device_id,
              company_id = EXCLUDED.company_id,
              site_id = EXCLUDED.site_id,
              break_kind = EXCLUDED.break_kind,
              late_return_minutes = EXCLUDED.late_return_minutes
            RETURNING id_remote, id_local, employee_id, site_id
            `,
            [
              idLocal,
              employee.id_local,
              normalizedEvent,
              occurredAt,
              latitude,
              longitude,
              accuracyM,
              altitudeM,
              faceConfidence,
              String(device.id_remote),
              device.company_id,
              isForeign ? null : (expectedSiteId ?? device.site_id ?? null),
              breakKind,
              lateReturnMinutes,
            ],
          );

          if (breakIssues.length) {
            await pool.query(
              `INSERT INTO attendance_auto_incidents
                (company_id,employee_id,category_id,attendance_record_id,incident_date,concept,origin,notes)
               VALUES ($1,$2,$3,$4,$5::date,'Incidencia','BREAK_SCHEDULE',$6) ON CONFLICT DO NOTHING`,
              [device.company_id,employee.id_remote,schedule.category_id??employee.category_id,
               result.rows[0].id_remote,mexicoDay(occurredAt),breakIssues.join('. ')]
            );
          }

          synced.push({
            id_local: idLocal,
            id_remote: result.rows[0].id_remote,
            site_id: result.rows[0].site_id ?? null,
          });

          console.log(
            `Asistencia sincronizada: ${employee.full_name} - ${normalizedEvent} - día ${weekday}`,
          );
        } catch (error) {
          console.error(`Error al sincronizar registro ${idLocal}:`, error);
          errors.push({
            id_local: idLocal,
            error: 'Error interno del servidor',
          });
        }
      }

      try { await processMissingBreaks(); }
      catch (error) { console.error('No se pudieron reconciliar las pausas pendientes:', error); }

      // Un HTTP 200 puede contener rechazos individuales: hacerlos visibles en Docker.
      console.log('Asistencia: resultado de sincronización', JSON.stringify({
        device_id: req.deviceId,
        company_id: device.company_id,
        recibidos: records.length,
        confirmados: synced.length,
        rechazados: errors.length,
      }));
      for (const rejected of errors) {
        const original = records.find((item: AttendanceRecordInput) => String(item?.id_local ?? '').trim() === rejected.id_local);
        console.warn('Asistencia rechazada:', JSON.stringify({
          id_local: rejected.id_local,
          employee_id: original?.employee_id,
          event_type: original?.event_type,
          occurred_at: original?.occurred_at,
          motivo: rejected.error,
        }));
      }

      const metadata=await attendanceMetadata(device.company_id,synced.map(r=>r.id_local));
      const enriched=synced.map(r=>({...r,...metadata.find(m=>m.id_local===r.id_local)}));
      res.status(200).json({ synced:enriched, errors });
    } catch (error) {
      console.error('Error verificando dispositivo:', error);
      res.status(500).json({ error: 'Error interno del servidor' });
    }
  },
);


/* =========================================================
   POST /api/attendance/location/sync
   Sincroniza puntos GPS de jornadas FOREIGN.
========================================================= */
router.post(
  '/location/sync',
  authMiddleware,
  async (req: AuthRequest, res: Response): Promise<void> => {
    const points = req.body?.points;

    if (!Array.isArray(points) || points.length === 0) {
      res.status(400).json({ error: 'points debe ser un arreglo no vacío' });
      return;
    }

    if (!req.deviceId) {
      res.status(401).json({ error: 'No se pudo identificar el dispositivo' });
      return;
    }

    try {
      const deviceResult = await pool.query(
        `SELECT id_remote, id_local, device_name, company_id, site_id
         FROM devices
         WHERE id_remote = $1
         LIMIT 1`,
        [req.deviceId],
      );

      const device = deviceResult.rows[0];
      if (!device) {
        res.status(403).json({ error: 'El dispositivo no está registrado' });
        return;
      }
      if (!device.company_id) {
        res.status(403).json({ error: 'El dispositivo no tiene empresa asignada' });
        return;
      }

      const synced: { id_local: string; id_remote: string }[] = [];
      const errors: { id_local: string; error: string }[] = [];

      for (const rawPoint of points) {
        const point = rawPoint ?? {};
        const idLocal = String(point.id_local ?? point.idLocal ?? '').trim();
        const employeeId = String(point.employee_id ?? point.employeeId ?? '').trim();
        const workDate = String(point.work_date ?? point.workDate ?? '').trim();
        const occurredAt = Number(point.occurred_at ?? point.occurredAt);
        const latitude = nullableNumber(point.latitude);
        const longitude = nullableNumber(point.longitude);
        const accuracyM = nullableNumber(point.accuracy_m ?? point.accuracyM);
        const altitudeM = nullableNumber(point.altitude_m ?? point.altitudeM);
        const rawDeviceId = point.device_id ?? point.deviceId ?? null;
        const pointDeviceId =
          rawDeviceId === null || rawDeviceId === undefined || rawDeviceId === ''
            ? String(device.id_remote)
            : String(rawDeviceId);

        if (!idLocal || !employeeId || !workDate || !Number.isFinite(occurredAt) ||
            occurredAt <= 0 || latitude === null || longitude === null) {
          errors.push({ id_local: idLocal || 'desconocido', error: 'Campos requeridos faltantes o inválidos' });
          continue;
        }
        if (!/^\d{4}-\d{2}-\d{2}$/.test(workDate)) {
          errors.push({ id_local: idLocal, error: `work_date inválido: ${workDate}` });
          continue;
        }
        if (latitude < -90 || latitude > 90) {
          errors.push({ id_local: idLocal, error: 'Latitud inválida' });
          continue;
        }
        if (longitude < -180 || longitude > 180) {
          errors.push({ id_local: idLocal, error: 'Longitud inválida' });
          continue;
        }

        try {
          const employeeResult = await pool.query(
            `SELECT id_remote, id_local, employee_code, full_name, company_id
             FROM employees
             WHERE company_id = $1
               AND is_active = TRUE
               AND (id_local = $2 OR id_remote::text = $2 OR employee_code = $2)
             LIMIT 1`,
            [device.company_id, employeeId],
          );

          const employee = employeeResult.rows[0];
          if (!employee) {
            errors.push({ id_local: idLocal, error: `Empleado no encontrado: ${employeeId}` });
            continue;
          }

          const weekday = getMexicoIsoWeekday(occurredAt);
          const assignmentResult = await pool.query(
            `SELECT work_mode
             FROM employee_work_sites
             WHERE employee_id = $1 AND weekday = $2
             LIMIT 1`,
            [employee.id_remote, weekday],
          );

          const workMode = String(assignmentResult.rows[0]?.work_mode ?? 'SITE').trim().toUpperCase();
          if (workMode !== 'FOREIGN') {
            errors.push({
              id_local: idLocal,
              error: `El trabajador no está configurado como FOREIGN para el día ${weekday}`,
            });
            continue;
          }

          const result = await pool.query(
            `INSERT INTO employee_location_points (
               id, company_id, employee_id, device_id, work_date,
               recorded_at, latitude, longitude, accuracy_m, altitude_m, created_at
             )
             VALUES ($1::uuid,$2,$3,$4,$5::date,to_timestamp($6 / 1000.0),$7,$8,$9,$10,NOW())
             ON CONFLICT (employee_id, recorded_at)
             DO UPDATE SET
               company_id=EXCLUDED.company_id,
               device_id=EXCLUDED.device_id,
               work_date=EXCLUDED.work_date,
              latitude=EXCLUDED.latitude,
              longitude=EXCLUDED.longitude,
              accuracy_m=EXCLUDED.accuracy_m,
              altitude_m=EXCLUDED.altitude_m
             RETURNING id`,
            [
              idLocal, device.company_id, employee.id_remote, pointDeviceId,
              workDate, occurredAt, latitude, longitude, accuracyM, altitudeM,
            ],
          );

          synced.push({ id_local: idLocal, id_remote: String(result.rows[0].id) });
          console.log(`Punto FOREIGN sincronizado: ${employee.full_name} - ${workDate} - ${occurredAt}`);
        } catch (error) {
          console.error(`Error sincronizando punto FOREIGN ${idLocal}:`, error);
          errors.push({ id_local: idLocal, error: 'Error interno del servidor' });
        }
      }

      res.status(200).json({ synced, errors });
    } catch (error) {
      console.error('Error verificando dispositivo para puntos FOREIGN:', error);
      res.status(500).json({ error: 'Error interno del servidor' });
    }
  },
);

export default router;
