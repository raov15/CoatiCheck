"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const client_1 = __importDefault(require("../db/client"));
const auth_1 = require("../middleware/auth");
const router = (0, express_1.Router)();
const VALID_EVENTS = new Set([
    'CLOCK_IN',
    'CLOCK_OUT',
    'MEAL_START',
    'MEAL_END',
]);
function getMexicoIsoWeekday(epochMs) {
    const date = new Date(epochMs);
    const weekday = new Intl.DateTimeFormat('en-US', {
        timeZone: 'America/Mexico_City',
        weekday: 'short',
    }).format(date);
    const map = {
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
function nullableNumber(value) {
    if (value === '' || value === undefined || value === null)
        return null;
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
}
function mexicoDay(epochMs) {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: 'America/Mexico_City', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(epochMs));
    const get = (type) => parts.find(p => p.type === type)?.value || '';
    return `${get('year')}-${get('month')}-${get('day')}`;
}
function minuteOfDay(epochMs) {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'America/Mexico_City', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(epochMs));
    return Number(parts.find(p => p.type === 'hour')?.value || 0) * 60 + Number(parts.find(p => p.type === 'minute')?.value || 0);
}
function timeMinutes(s) {
    if (typeof s !== 'string' || !/^\d{1,2}:\d{2}/.test(s))
        return null;
    const [h, m] = s.split(':').map(Number);
    return h < 24 && m < 60 ? h * 60 + m : null;
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
router.post('/sync', auth_1.authMiddleware, async (req, res) => {
    const records = req.body?.records;
    if (!Array.isArray(records) || records.length === 0) {
        res.status(400).json({ error: 'records debe ser un arreglo no vacío' });
        return;
    }
    if (!req.deviceId) {
        res.status(401).json({ error: 'No se pudo identificar el dispositivo' });
        return;
    }
    try {
        const deviceResult = await client_1.default.query(`
        SELECT id_remote, id_local, device_name, company_id, site_id
        FROM devices
        WHERE id_remote = $1
        LIMIT 1
        `, [req.deviceId]);
        if (deviceResult.rows.length === 0) {
            res.status(403).json({ error: 'El dispositivo no está registrado' });
            return;
        }
        const device = deviceResult.rows[0];
        if (!device.company_id) {
            res.status(403).json({ error: 'El dispositivo no tiene empresa asignada' });
            return;
        }
        const synced = [];
        const errors = [];
        for (const rawRecord of records) {
            const record = rawRecord ?? {};
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
                const employeeResult = await client_1.default.query(`
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
            `, [device.company_id, employeeId]);
                if (employeeResult.rows.length === 0) {
                    errors.push({
                        id_local: idLocal,
                        error: `Empleado no encontrado: ${employeeId}`,
                    });
                    continue;
                }
                const employee = employeeResult.rows[0];
                const weekday = getMexicoIsoWeekday(occurredAt);
                const scheduledSiteResult = await client_1.default.query(`
             SELECT ews.work_mode, ews.site_id, s.name AS site_name
             FROM employee_work_sites ews
             LEFT JOIN sites s
               ON s.id = ews.site_id
              AND s.company_id = $2
             WHERE ews.employee_id = $1
               AND ews.weekday = $3
             LIMIT 1
            `, [employee.id_remote, device.company_id, weekday]);
                const scheduledSite = scheduledSiteResult.rows[0] ?? null;
                const workMode = String(scheduledSite?.work_mode ?? 'SITE').trim().toUpperCase();
                const isForeign = workMode === 'FOREIGN';
                const expectedSiteId = isForeign ? null : (scheduledSite?.site_id ?? employee.site_id ?? null);
                /*
                 * Si existe centro esperado, comprobamos además que siga
                 * perteneciendo a la misma empresa. Así nunca se guarda
                 * accidentalmente un cargo, un centro eliminado o un UUID
                 * de otra empresa.
                 */
                if (expectedSiteId) {
                    const validSite = await client_1.default.query(`
              SELECT id
              FROM sites
              WHERE id = $1
                AND company_id = $2
              LIMIT 1
              `, [expectedSiteId, device.company_id]);
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
                if (!isForeign &&
                    device.site_id &&
                    expectedSiteId &&
                    String(device.site_id) !== String(expectedSiteId)) {
                    errors.push({
                        id_local: idLocal,
                        error: `El empleado ${employee.full_name} tiene otro centro de trabajo asignado para este día`,
                    });
                    continue;
                }
                // Idempotencia y secuencia de jornada, incluso con sincronización offline.
                // Se serializa por empleado/día para evitar movimientos simultáneos.
                const existingRecord = await client_1.default.query(`SELECT id_remote, site_id FROM attendance_records WHERE id_local=$1 AND company_id=$2 LIMIT 1`, [idLocal, device.company_id]);
                if (existingRecord.rows.length) {
                    synced.push({ id_local: idLocal, id_remote: existingRecord.rows[0].id_remote,
                        site_id: existingRecord.rows[0].site_id ?? null });
                    continue;
                }
                const day = mexicoDay(occurredAt);
                const eventsToday = await client_1.default.query(`SELECT event_type, occurred_at FROM attendance_records
             WHERE company_id=$1 AND employee_id=$2
               AND (to_timestamp(occurred_at / 1000.0) AT TIME ZONE 'America/Mexico_City')::date=$3::date
             ORDER BY occurred_at ASC`, [device.company_id, employee.id_local, day]);
                const events = eventsToday.rows.filter((r) => Number(r.occurred_at) <= occurredAt);
                const futureEvents = eventsToday.rows.some((r) => Number(r.occurred_at) > occurredAt);
                const last = events.length ? String(events[events.length - 1].event_type) : null;
                const allowed = last === null ? normalizedEvent === 'CLOCK_IN'
                    : last === 'CLOCK_IN' || last === 'MEAL_END'
                        ? (normalizedEvent === 'MEAL_START' || normalizedEvent === 'CLOCK_OUT')
                        : last === 'MEAL_START' ? normalizedEvent === 'MEAL_END' : false;
                if (!allowed || futureEvents) {
                    errors.push({ id_local: idLocal, error: 'Movimiento fuera de secuencia o fecha; sincroniza la jornada en orden' });
                    continue;
                }
                // La app mantiene los eventos MEAL_*; el servidor determina comida/descanso.
                const scheduleResult = await client_1.default.query(`SELECT c.meal_start_time,c.meal_end_time,c.rest_start_time,
                    c.rest_duration_minutes,c.meal_duration_minutes
             FROM categories c WHERE c.id=$1 AND c.company_id=$2 LIMIT 1`, [employee.category_id, device.company_id]);
                const schedule = scheduleResult.rows[0] ?? {};
                let breakKind = null;
                let lateReturnMinutes = 0;
                if (normalizedEvent === 'MEAL_START' || normalizedEvent === 'MEAL_END') {
                    const today = mexicoDay(occurredAt);
                    const previousBreak = await client_1.default.query(`SELECT break_kind FROM attendance_records
               WHERE company_id=$1 AND employee_id=$2 AND event_type='MEAL_START'
                 AND to_char(to_timestamp(occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City','YYYY-MM-DD')=$3
               ORDER BY occurred_at DESC LIMIT 1`, [device.company_id, employee.id_local, today]);
                    if (normalizedEvent === 'MEAL_END' && previousBreak.rows[0]?.break_kind) {
                        breakKind = previousBreak.rows[0].break_kind;
                    }
                    else {
                        const nowMin = minuteOfDay(occurredAt);
                        const meal = timeMinutes(schedule.meal_start_time);
                        const rest = timeMinutes(schedule.rest_start_time);
                        if (meal !== null && rest !== null)
                            breakKind = Math.abs(nowMin - meal) <= Math.abs(nowMin - rest) ? 'MEAL' : 'REST';
                        else
                            breakKind = meal !== null ? 'MEAL' : rest !== null ? 'REST' : null;
                        if (normalizedEvent === 'MEAL_START' && breakKind) {
                            const start = breakKind === 'MEAL' ? meal : rest;
                            if (start !== null && nowMin < start) {
                                errors.push({ id_local: idLocal, error: 'Aún no comienza el horario de comida/descanso' });
                                continue;
                            }
                        }
                    }
                    if (normalizedEvent === 'MEAL_END' && breakKind) {
                        const start = breakKind === 'MEAL' ? timeMinutes(schedule.meal_start_time) : timeMinutes(schedule.rest_start_time);
                        const configuredEnd = breakKind === 'MEAL' ? timeMinutes(schedule.meal_end_time) : null;
                        const duration = Number(breakKind === 'MEAL' ? schedule.meal_duration_minutes : schedule.rest_duration_minutes) || 0;
                        const expected = configuredEnd ?? (start === null ? null : start + duration);
                        if (expected !== null)
                            lateReturnMinutes = Math.max(0, minuteOfDay(occurredAt) - expected - 10);
                    }
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
                const result = await client_1.default.query(`
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
            `, [
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
                ]);
                if (lateReturnMinutes > 0) {
                    await client_1.default.query(`INSERT INTO attendance_auto_incidents
                 (company_id,employee_id,category_id,attendance_record_id,incident_date,concept,origin,notes)
               VALUES ($1,$2,$3,$4,$5::date,'Retardo','BREAK_RETURN',$6)
               ON CONFLICT DO NOTHING`, [device.company_id, employee.id_remote, employee.category_id, result.rows[0].id_remote,
                        mexicoDay(occurredAt), `Regreso de ${breakKind === 'REST' ? 'descanso' : 'comida'}: ${lateReturnMinutes} minuto(s) después de la tolerancia`]);
                }
                synced.push({
                    id_local: idLocal,
                    id_remote: result.rows[0].id_remote,
                    site_id: result.rows[0].site_id ?? null,
                });
                console.log(`Asistencia sincronizada: ${employee.full_name} - ${normalizedEvent} - día ${weekday}`);
            }
            catch (error) {
                console.error(`Error al sincronizar registro ${idLocal}:`, error);
                errors.push({
                    id_local: idLocal,
                    error: 'Error interno del servidor',
                });
            }
        }
        res.status(200).json({ synced, errors });
    }
    catch (error) {
        console.error('Error verificando dispositivo:', error);
        res.status(500).json({ error: 'Error interno del servidor' });
    }
});
/* =========================================================
   POST /api/attendance/location/sync
   Sincroniza puntos GPS de jornadas FOREIGN.
========================================================= */
router.post('/location/sync', auth_1.authMiddleware, async (req, res) => {
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
        const deviceResult = await client_1.default.query(`SELECT id_remote, id_local, device_name, company_id, site_id
         FROM devices
         WHERE id_remote = $1
         LIMIT 1`, [req.deviceId]);
        const device = deviceResult.rows[0];
        if (!device) {
            res.status(403).json({ error: 'El dispositivo no está registrado' });
            return;
        }
        if (!device.company_id) {
            res.status(403).json({ error: 'El dispositivo no tiene empresa asignada' });
            return;
        }
        const synced = [];
        const errors = [];
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
            const pointDeviceId = rawDeviceId === null || rawDeviceId === undefined || rawDeviceId === ''
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
                const employeeResult = await client_1.default.query(`SELECT id_remote, id_local, employee_code, full_name, company_id
             FROM employees
             WHERE company_id = $1
               AND is_active = TRUE
               AND (id_local = $2 OR id_remote::text = $2 OR employee_code = $2)
             LIMIT 1`, [device.company_id, employeeId]);
                const employee = employeeResult.rows[0];
                if (!employee) {
                    errors.push({ id_local: idLocal, error: `Empleado no encontrado: ${employeeId}` });
                    continue;
                }
                const weekday = getMexicoIsoWeekday(occurredAt);
                const assignmentResult = await client_1.default.query(`SELECT work_mode
             FROM employee_work_sites
             WHERE employee_id = $1 AND weekday = $2
             LIMIT 1`, [employee.id_remote, weekday]);
                const workMode = String(assignmentResult.rows[0]?.work_mode ?? 'SITE').trim().toUpperCase();
                if (workMode !== 'FOREIGN') {
                    errors.push({
                        id_local: idLocal,
                        error: `El trabajador no está configurado como FOREIGN para el día ${weekday}`,
                    });
                    continue;
                }
                const result = await client_1.default.query(`INSERT INTO employee_location_points (
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
             RETURNING id`, [
                    idLocal, device.company_id, employee.id_remote, pointDeviceId,
                    workDate, occurredAt, latitude, longitude, accuracyM, altitudeM,
                ]);
                synced.push({ id_local: idLocal, id_remote: String(result.rows[0].id) });
                console.log(`Punto FOREIGN sincronizado: ${employee.full_name} - ${workDate} - ${occurredAt}`);
            }
            catch (error) {
                console.error(`Error sincronizando punto FOREIGN ${idLocal}:`, error);
                errors.push({ id_local: idLocal, error: 'Error interno del servidor' });
            }
        }
        res.status(200).json({ synced, errors });
    }
    catch (error) {
        console.error('Error verificando dispositivo para puntos FOREIGN:', error);
        res.status(500).json({ error: 'Error interno del servidor' });
    }
});
exports.default = router;
