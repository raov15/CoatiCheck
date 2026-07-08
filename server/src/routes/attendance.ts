import { Router, Response } from 'express';
import pool from '../db/client';
import { authMiddleware, AuthRequest } from '../middleware/auth';

const router = Router();

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

// POST /api/attendance/sync
router.post('/sync', authMiddleware, async (req: AuthRequest, res: Response): Promise<void> => {
  const records: AttendanceRecordInput[] = req.body?.records;

  if (!Array.isArray(records) || records.length === 0) {
    res.status(400).json({ error: 'records debe ser un arreglo no vacio' });
    return;
  }

  const synced: { id_local: string; id_remote: string }[] = [];
  const errors: { id_local: string; error: string }[] = [];

  for (const record of records) {
    const {
      id_local,
      employee_id,
      event_type,
      occurred_at,
      latitude = null,
      longitude = null,
      accuracy_m = null,
      altitude_m = null,
      face_confidence = null,
    } = record ?? {};

    if (!id_local || !employee_id || !event_type || !occurred_at) {
      errors.push({ id_local: id_local ?? 'desconocido', error: 'Campos requeridos faltantes' });
      continue;
    }

    try {
      const result = await pool.query(
        `INSERT INTO attendance_records
          (id_local, employee_id, event_type, occurred_at, latitude, longitude, accuracy_m, altitude_m, face_confidence, device_id)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
         ON CONFLICT (id_local) DO UPDATE SET id_local = EXCLUDED.id_local
         RETURNING id_remote`,
        [
          id_local,
          employee_id,
          event_type,
          occurred_at,
          latitude,
          longitude,
          accuracy_m,
          altitude_m,
          face_confidence,
          req.deviceId ?? null,
        ]
      );
      synced.push({ id_local, id_remote: result.rows[0].id_remote });
    } catch (err) {
      console.error(`Error al sincronizar registro ${id_local}:`, err);
      errors.push({ id_local, error: 'Error interno del servidor' });
    }
  }

  res.status(200).json({ synced, errors });
});

export default router;
