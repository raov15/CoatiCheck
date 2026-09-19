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

/**
 * occurred_at se guarda como epoch en milisegundos.
 * Esta función obtiene el día ISO usando la zona horaria de México central.
 * 1 = lunes ... 7 = domingo.
 */
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

/* =========================================================
   POST /api/attendance/sync

   Sincroniza registros creados por Android.

   REGLAS IMPORTANTES:
   - attendance_records.employee_id guarda employees.id_local.
   - El centro esperado se obtiene de employee_work_sites según
     el día del registro.
   - Si ese día no tiene centro específico, se conserva como
     compatibilidad employees.site_id.
   - Si el dispositivo tiene un centro y no coincide con el
     centro esperado de ese día, el registro se rechaza.
   - attendance_records.site_id guarda el centro esperado/usado
     para ese registro, no simplemente el centro fijo antiguo.
========================================================= */
router.post(
  '/sync',
  authMiddleware,
  async (req: AuthRequest, res: Response): Promise<void> => {
    const records: AttendanceRecordInput[] = req.body?.records;

    if (!Array.isArray(records) || records.length === 0) {
      res.status(400).json({
        error: 'records debe ser un arreglo no vacío',
      });
      return;
    }

    if (!req.deviceId) {
      res.status(401).json({
        error: 'No se pudo identificar el dispositivo',
      });
      return;
    }

    try {
      const deviceResult = await pool.query(
        `
        SELECT
          id_remote,
          id_local,
          device_name,
          company_id,
          site_id
        FROM devices
        WHERE id_remote = $1
        LIMIT 1
        `,
        [req.deviceId],
      );

      if (deviceResult.rows.length === 0) {
        res.status(403).json({
          error: 'El dispositivo no está registrado',
        });
        return;
      }

      const device = deviceResult.rows[0];

      if (!device.company_id) {
        res.status(403).json({
          error: 'El dispositivo no tiene empresa asignada',
        });
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
          errors.push({
            id_local: id_local ?? 'desconocido',
            error: 'Campos requeridos faltantes',
          });
          continue;
        }

        const normalizedEvent = String(event_type).trim().toUpperCase();
        const validEvents = [
          'CLOCK_IN',
          'CLOCK_OUT',
          'MEAL_START',
          'MEAL_END',
        ];

        if (!validEvents.includes(normalizedEvent)) {
          errors.push({
            id_local,
            error: `Tipo de evento inválido: ${event_type}`,
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
            [device.company_id, employee_id],
          );

          if (employeeResult.rows.length === 0) {
            console.warn(`Empleado no encontrado: ${employee_id}`);
            errors.push({
              id_local,
              error: `Empleado no encontrado: ${employee_id}`,
            });
            continue;
          }

          const employee = employeeResult.rows[0];
          const weekday = getMexicoIsoWeekday(Number(occurred_at));

          /* ===================================================
             CENTRO DE TRABAJO SEGÚN EL DÍA
          =================================================== */
          const scheduledSiteResult = await pool.query(
            `
            SELECT
              ews.site_id,
              s.name AS site_name
            FROM employee_work_sites ews
            JOIN sites s
              ON s.id = ews.site_id
             AND s.company_id = $2
            WHERE ews.employee_id = $1
              AND ews.weekday = $3
            LIMIT 1
            `,
            [employee.id_remote, device.company_id, weekday],
          );

          const scheduledSite = scheduledSiteResult.rows[0] ?? null;

          // Compatibilidad con trabajadores todavía no configurados por día.
          const expectedSiteId: string | null =
            scheduledSite?.site_id ?? employee.site_id ?? null;

          if (
            device.site_id &&
            expectedSiteId &&
            device.site_id !== expectedSiteId
          ) {
            errors.push({
              id_local,
              error:
                `El empleado ${employee.full_name} tiene otro centro de trabajo asignado para este día`,
            });
            continue;
          }

          /* ===================================================
             GUARDAR ASISTENCIA
          =================================================== */
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
              site_id
            )
            VALUES (
              $1, $2, $3, $4, $5, $6,
              $7, $8, $9, $10, $11, $12
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
              site_id = EXCLUDED.site_id
            RETURNING
              id_remote,
              id_local,
              employee_id,
              site_id
            `,
            [
              id_local,
              employee.id_local,
              normalizedEvent,
              occurred_at,
              latitude,
              longitude,
              accuracy_m,
              altitude_m,
              face_confidence,
              device.id_remote.toString(),
              device.company_id,
              expectedSiteId ?? device.site_id ?? null,
            ],
          );

          synced.push({
            id_local,
            id_remote: result.rows[0].id_remote,
            site_id: result.rows[0].site_id ?? null,
          });

          console.log(
            `Asistencia sincronizada: ${employee.full_name} - ${normalizedEvent} - día ${weekday}`,
          );
        } catch (err) {
          console.error(`Error al sincronizar registro ${id_local}:`, err);
          errors.push({
            id_local,
            error: 'Error interno del servidor',
          });
        }
      }

      res.status(200).json({
        synced,
        errors,
      });
    } catch (err) {
      console.error('Error verificando dispositivo:', err);
      res.status(500).json({
        error: 'Error interno del servidor',
      });
    }
  },
);

export default router;
