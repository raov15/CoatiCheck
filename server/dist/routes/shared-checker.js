"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const crypto_1 = __importDefault(require("crypto"));
const client_1 = __importDefault(require("../db/client"));
const router = (0, express_1.Router)();
const tokenPattern = /^[a-f0-9]{64}$/i;
const uuidPattern = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const hashToken = (token) => crypto_1.default.createHash('sha256').update(token).digest('hex');
async function checkerFor(token) {
    if (typeof token !== 'string' || !tokenPattern.test(token))
        return null;
    const result = await client_1.default.query(`
    SELECT sc.id, sc.company_id, sc.site_id, sc.device_name
    FROM shared_checkers sc
    JOIN companies c ON c.id=sc.company_id AND c.is_active=TRUE
    WHERE sc.device_token_hash=$1 AND sc.is_active=TRUE LIMIT 1`, [hashToken(token)]);
    return result.rows[0] ?? null;
}
router.post('/employees', async (req, res) => {
    try {
        const checker = await checkerFor(req.body?.device_token);
        if (!checker) {
            res.status(401).json({ error: 'Tablet no autorizada' });
            return;
        }
        const result = await client_1.default.query(`
      SELECT e.id_remote AS employee_id, e.employee_code, e.full_name
      FROM shared_checker_employees sce
      JOIN employees e ON e.id_remote=sce.employee_id AND e.company_id=sce.company_id
      WHERE sce.checker_id=$1 AND sce.company_id=$2 AND sce.enabled=TRUE AND e.is_active=TRUE
      ORDER BY e.full_name`, [checker.id, checker.company_id]);
        res.json({ checker_id: checker.id, employees: result.rows });
    }
    catch (error) {
        console.error('shared-checker employees:', error);
        res.status(500).json({ error: 'No se pudieron consultar los trabajadores' });
    }
});
// NOTA: La verificación facial en JavaScript no puede certificarse desde Express.
// Este endpoint confía en un kiosco administrado; NO usar como prueba biométrica de alta seguridad.
router.post('/attendance', async (req, res) => {
    const { device_token, employee_id, event_type } = req.body ?? {};
    if (!uuidPattern.test(String(employee_id ?? '')) || !['CLOCK_IN', 'CLOCK_OUT'].includes(event_type)) {
        res.status(400).json({ error: 'Trabajador o tipo de marcaje inválido' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const checkerResult = await client.query(`
      SELECT sc.id, sc.company_id, sc.site_id FROM shared_checkers sc
      JOIN companies c ON c.id=sc.company_id AND c.is_active=TRUE
      WHERE sc.device_token_hash=$1 AND sc.is_active=TRUE FOR UPDATE OF sc`, [typeof device_token === 'string' && tokenPattern.test(device_token) ? hashToken(device_token) : '']);
        const checker = checkerResult.rows[0];
        if (!checker) {
            await client.query('ROLLBACK');
            res.status(401).json({ error: 'Tablet no autorizada' });
            return;
        }
        const found = await client.query(`
      SELECT e.id_remote, e.id_local, e.full_name, e.site_id
      FROM shared_checker_employees sce
      JOIN employees e ON e.id_remote=sce.employee_id AND e.company_id=sce.company_id
      WHERE sce.checker_id=$1 AND sce.company_id=$2 AND sce.enabled=TRUE
        AND e.is_active=TRUE AND e.id_remote=$3 LIMIT 1`, [checker.id, checker.company_id, employee_id]);
        const employee = found.rows[0];
        if (!employee) {
            await client.query('ROLLBACK');
            res.status(403).json({ error: 'Empleado no asignado a esta tablet' });
            return;
        }
        const weekday = new Intl.DateTimeFormat('en-US', { timeZone: 'America/Mexico_City', weekday: 'short' }).format(new Date());
        const day = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 }[weekday];
        const schedule = await client.query(`SELECT work_mode, site_id FROM employee_work_sites
      WHERE employee_id=$1 AND weekday=$2 LIMIT 1`, [employee.id_remote, day]);
        const workMode = String(schedule.rows[0]?.work_mode ?? 'SITE').toUpperCase();
        const expectedSite = schedule.rows[0]?.site_id ?? employee.site_id;
        if (workMode === 'FOREIGN' || !expectedSite || String(expectedSite) !== String(checker.site_id)) {
            await client.query('ROLLBACK');
            res.status(403).json({ error: 'El trabajador no está asignado al centro de esta tablet para hoy' });
            return;
        }
        // Evita marcajes accidentales repetidos durante 60 segundos.
        const now = Date.now();
        const recent = await client.query(`SELECT 1 FROM attendance_records
      WHERE company_id=$1 AND employee_id=$2 AND event_type=$3
        AND occurred_at >= $4 AND occurred_at <= $5 LIMIT 1`, [checker.company_id, employee.id_local, event_type, now - 60000, now]);
        if (recent.rowCount) {
            await client.query('ROLLBACK');
            res.status(409).json({ error: 'Este marcaje ya se registró hace menos de un minuto' });
            return;
        }
        const idLocal = crypto_1.default.randomUUID();
        await client.query(`INSERT INTO attendance_records
      (id_local, employee_id, event_type, occurred_at, face_confidence, device_id, company_id, site_id)
      VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`, [idLocal, employee.id_local, event_type, now, null, String(checker.id), checker.company_id, checker.site_id]);
        await client.query('COMMIT');
        res.status(201).json({ saved: true, employee_name: employee.full_name, event_type, occurred_at: now });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('shared-checker attendance:', error);
        res.status(500).json({ error: 'No se pudo guardar la asistencia' });
    }
    finally {
        client.release();
    }
});
exports.default = router;
