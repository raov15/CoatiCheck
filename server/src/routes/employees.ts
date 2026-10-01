import { Router, Response } from 'express';
import pool from '../db/client';
import {
  authMiddleware,
  AuthRequest,
} from '../middleware/auth';

const router = Router();

/* =========================================================
   TIPOS
========================================================= */

interface EmployeeSyncInput {
  id_local: string;

  employee_code?: string | null;

  first_name?: string | null;
  last_name_paternal?: string | null;
  last_name_maternal?: string | null;
  full_name?: string | null;

  rfc?: string | null;
  curp?: string | null;
  nss?: string | null;

  /**
   * IMPORTANTE:
   * department representa el CARGO.
   *
   * Ejemplo:
   * Desarrollador web
   *
   * NO representa el centro de trabajo.
   */
  department?: string | null;

  is_active?: boolean;
}

/* =========================================================
   POST /api/employees/sync

   Sincroniza empleados creados desde la app móvil.

   REGLAS:

   - El código EMP001, EMP002, etc. se genera aquí.
   - Android NO asigna el código definitivo.
   - Nombre y apellidos se guardan por separado.
   - RFC, CURP y NSS se reciben desde Android.
   - department = cargo.

   CAMPOS ADMINISTRADOS DESDE LA WEB Y QUE ANDROID NO DEBE
   SOBRESCRIBIR:

   - site_id
   - category_id
   - hire_date
   - phone
   - start_time
   - end_time
   - employee_work_sites (centros por día)

   De esta forma una nueva sincronización desde Android no borra
   la configuración laboral que hizo el administrador.
========================================================= */

router.post(
  '/sync',
  authMiddleware,
  async (
    req: AuthRequest,
    res: Response
  ): Promise<void> => {

    const employees: EmployeeSyncInput[] =
      req.body?.employees;

    /* =====================================================
       VALIDAR LISTA
    ===================================================== */

    if (
      !Array.isArray(employees) ||
      employees.length === 0
    ) {

      res.status(400).json({
        error:
          'employees debe ser un arreglo no vacío',
      });

      return;
    }

    /* =====================================================
       VALIDAR DISPOSITIVO
    ===================================================== */

    if (!req.deviceId) {

      res.status(401).json({
        error:
          'No se pudo identificar el dispositivo',
      });

      return;
    }

    try {

      /* ===================================================
         BUSCAR EMPRESA DEL DISPOSITIVO
      =================================================== */

      const deviceResult =
        await pool.query(
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
          [
            req.deviceId,
          ]
        );

      if (
        deviceResult.rows.length === 0
      ) {

        res.status(403).json({
          error:
            'El dispositivo no está registrado',
        });

        return;
      }

      const device =
        deviceResult.rows[0];

      if (!device.company_id) {

        res.status(403).json({
          error:
            'El dispositivo no tiene empresa asignada',
        });

        return;
      }

      /* ===================================================
         RESULTADOS
      =================================================== */

      const synced: {
        id_local: string;
        id_remote: string;
        employee_code: string;
        full_name: string;
        category_id: string | null;
        hire_date: string | null;
      }[] = [];

      const errors: {
        id_local: string;
        error: string;
      }[] = [];

      /* ===================================================
         PROCESAR EMPLEADOS
      =================================================== */

      for (
        const employee of employees
      ) {

        const {
          id_local,
          first_name,
          last_name_paternal,
          last_name_maternal,
          full_name,
          rfc,
          curp,
          nss,
          department,
          is_active = true,
        } = employee ?? {};

        /* =================================================
           NORMALIZAR DATOS
        ================================================= */

        const firstName =
          first_name?.trim() || '';

        const lastNamePaternal =
          last_name_paternal?.trim() || '';

        const lastNameMaternal =
          last_name_maternal?.trim() || '';

        const generatedFullName =
          [
            firstName,
            lastNamePaternal,
            lastNameMaternal,
          ]
            .filter(Boolean)
            .join(' ')
            .trim();

        const normalizedFullName =
          generatedFullName ||
          full_name?.trim() ||
          '';

        const normalizedRfc =
          rfc
            ?.trim()
            .toUpperCase() ||
          null;

        const normalizedCurp =
          curp
            ?.trim()
            .toUpperCase() ||
          null;

        const normalizedNss =
          nss
            ?.trim() ||
          null;

        /*
         * department es únicamente el CARGO.
         */
        const normalizedDepartment =
          department
            ?.trim() ||
          null;

        /* =================================================
           VALIDACIÓN
        ================================================= */

        if (!id_local?.trim()) {

          errors.push({
            id_local:
              id_local ??
              'desconocido',

            error:
              'Falta id_local',
          });

          continue;
        }

        if (!firstName) {

          errors.push({
            id_local,

            error:
              'Falta el nombre del empleado',
          });

          continue;
        }

        if (!normalizedFullName) {

          errors.push({
            id_local,

            error:
              'No se pudo generar el nombre completo',
          });

          continue;
        }

        /* =================================================
           CONEXIÓN / TRANSACCIÓN
        ================================================= */

        const client =
          await pool.connect();

        try {

          await client.query(
            'BEGIN'
          );

          /* ===============================================
             BLOQUEAR GENERACIÓN DE CÓDIGO
          =============================================== */

          await client.query(
            `
            SELECT pg_advisory_xact_lock(
              hashtext($1)
            )
            `,
            [
              String(
                device.company_id
              ),
            ]
          );

          /* ===============================================
             COMPROBAR SI YA EXISTE

             También leemos la configuración administrativa
             únicamente para conservarla y devolverla.
          =============================================== */

          const existingResult =
            await client.query(
              `
              SELECT
                id_remote,
                id_local,
                employee_code,
                full_name,
                site_id,
                category_id,
                hire_date,
                phone,
                start_time,
                end_time
              FROM employees
              WHERE id_local = $1
              LIMIT 1
              `,
              [
                id_local,
              ]
            );

          let employeeCode:
            string;

          /* ===============================================
             CONSERVAR CÓDIGO EXISTENTE
          =============================================== */

          if (
            existingResult.rows.length > 0
          ) {

            employeeCode =
              existingResult
                .rows[0]
                .employee_code;

          } else {

            /* =============================================
               GENERAR SIGUIENTE CÓDIGO
            ============================================= */

            const codeResult =
              await client.query(
                `
                SELECT
                  COALESCE(
                    MAX(
                      CASE
                        WHEN employee_code
                          ~ '^EMP[0-9]+$'
                        THEN
                          SUBSTRING(
                            employee_code
                            FROM 4
                          )::INTEGER
                        ELSE 0
                      END
                    ),
                    0
                  ) + 1 AS next_number
                FROM employees
                WHERE company_id = $1
                `,
                [
                  device.company_id,
                ]
              );

            const nextNumber =
              Number(
                codeResult
                  .rows[0]
                  .next_number
              );

            employeeCode =
              `EMP${String(
                nextNumber
              ).padStart(
                3,
                '0'
              )}`;
          }

          /* ===============================================
             INSERTAR / ACTUALIZAR EMPLEADO

             IMPORTANTE:

             Los siguientes campos NO aparecen en
             DO UPDATE SET:

             - site_id
             - category_id
             - hire_date
             - phone
             - start_time
             - end_time

             Por lo tanto, una sincronización desde Android
             nunca elimina ni reemplaza lo configurado desde
             el panel administrativo.
          =============================================== */

          const result =
            await client.query(
              `
              INSERT INTO employees (
                id_local,
                employee_code,

                first_name,
                last_name_paternal,
                last_name_maternal,
                full_name,

                rfc,
                curp,
                nss,

                department,

                is_active,

                company_id,

                site_id,

                updated_at
              )

              VALUES (
                $1,
                $2,
                $3,
                $4,
                $5,
                $6,
                $7,
                $8,
                $9,
                $10,
                $11,
                $12,
                $13,
                NOW()
              )

              ON CONFLICT (id_local)

              DO UPDATE SET

                /*
                 * Los datos editados desde el panel administrativo
                 * tienen prioridad. Android solo completa campos que
                 * todavía están vacíos en PostgreSQL.
                 */
                first_name =
                  COALESCE(NULLIF(TRIM(employees.first_name), ''), EXCLUDED.first_name),

                last_name_paternal =
                  COALESCE(NULLIF(TRIM(employees.last_name_paternal), ''), EXCLUDED.last_name_paternal),

                last_name_maternal =
                  COALESCE(NULLIF(TRIM(employees.last_name_maternal), ''), EXCLUDED.last_name_maternal),

                full_name =
                  COALESCE(NULLIF(TRIM(employees.full_name), ''), EXCLUDED.full_name),

                rfc =
                  COALESCE(NULLIF(TRIM(employees.rfc), ''), EXCLUDED.rfc),

                curp =
                  COALESCE(NULLIF(TRIM(employees.curp), ''), EXCLUDED.curp),

                nss =
                  COALESCE(NULLIF(TRIM(employees.nss), ''), EXCLUDED.nss),

                department =
                  COALESCE(NULLIF(TRIM(employees.department), ''), EXCLUDED.department),

                is_active =
                  EXCLUDED.is_active,

                company_id =
                  EXCLUDED.company_id,

                updated_at =
                  NOW()

              RETURNING

                id_remote,
                id_local,
                employee_code,

                first_name,
                last_name_paternal,
                last_name_maternal,
                full_name,

                rfc,
                curp,
                nss,

                phone,
                department,

                is_active,

                company_id,
                site_id,

                category_id,
                hire_date,
                start_time,
                end_time
              `,
              [
                // $1
                id_local.trim(),

                // $2
                employeeCode,

                // $3
                firstName,

                // $4
                lastNamePaternal || null,

                // $5
                lastNameMaternal || null,

                // $6
                normalizedFullName,

                // $7
                normalizedRfc,

                // $8
                normalizedCurp,

                // $9
                normalizedNss,

                // $10
                normalizedDepartment,

                // $11
                is_active,

                // $12
                device.company_id,

                /*
                 * $13
                 *
                 * Centro general/de compatibilidad.
                 * Para trabajadores nuevos inicia NULL.
                 * Después se configura desde administración.
                 */
                null,
              ]
            );

          const savedEmployee =
            result.rows[0];

          /* ===============================================
             ASOCIAR EMPLEADO AL DISPOSITIVO
          =============================================== */

          await client.query(
            `
            UPDATE devices
            SET employee_id = $1
            WHERE id_remote = $2
            `,
            [
              savedEmployee.id_remote,
              req.deviceId,
            ]
          );

          await client.query(
            'COMMIT'
          );

          /* ===============================================
             AGREGAR A SINCRONIZADOS
          =============================================== */

          synced.push({

            id_local:
              savedEmployee.id_local,

            id_remote:
              savedEmployee.id_remote,

            employee_code:
              savedEmployee.employee_code,

            full_name:
              savedEmployee.full_name,

            category_id:
              savedEmployee.category_id ?? null,

            hire_date:
              savedEmployee.hire_date ?? null,
          });

          /* ===============================================
             LOG
          =============================================== */

          console.log(
            `Empleado sincronizado: ` +
            `${savedEmployee.full_name} ` +
            `[${savedEmployee.employee_code}] ` +

            `nombre=${savedEmployee.first_name ?? '-'} ` +

            `apellidoPaterno=` +
            `${savedEmployee.last_name_paternal ?? '-'} ` +

            `apellidoMaterno=` +
            `${savedEmployee.last_name_maternal ?? '-'} ` +

            `RFC=${savedEmployee.rfc ?? '-'} ` +

            `CURP=${savedEmployee.curp ?? '-'} ` +

            `NSS=${savedEmployee.nss ?? '-'} ` +

            `cargo=${savedEmployee.department ?? '-'} ` +

            `categoria=${savedEmployee.category_id ?? 'SIN ASIGNAR'} ` +

            `ingreso=${savedEmployee.hire_date ?? 'SIN FECHA'} ` +

            `centroGeneral=${savedEmployee.site_id ?? 'SIN ASIGNAR'} ` +

            `(${savedEmployee.id_local})`
          );

        } catch (error) {

          await client.query(
            'ROLLBACK'
          );

          console.error(
            `Error sincronizando empleado ${id_local}:`,
            error
          );

          errors.push({

            id_local,

            error:
              'Error interno del servidor',
          });

        } finally {

          client.release();
        }
      }

      /* ===================================================
         RESPUESTA
      =================================================== */

      res.status(200).json({
        synced,
        errors,
      });

    } catch (error) {

      console.error(
        'Error verificando dispositivo para sincronizar empleados:',
        error
      );

      res.status(500).json({
        error:
          'Error interno del servidor',
      });
    }
  }
);


/* =========================================================
   GET /api/employees/work-assignments

   Descarga a Android la configuración laboral por día de los
   empleados pertenecientes a la empresa del dispositivo.

   SITE:
   - tiene un centro de trabajo fijo para ese día.

   FOREIGN:
   - no tiene centro fijo.
   - site_id se devuelve NULL.
   - Android puede activar el seguimiento de ubicación foránea.

   Esta ruta NO modifica empleados ni configuraciones.
========================================================= */
router.get(
  '/work-assignments',
  authMiddleware,
  async (
    req: AuthRequest,
    res: Response
  ): Promise<void> => {

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
            company_id
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

      const result = await pool.query(
        `
          SELECT
            e.id_remote AS employee_id_remote,
            e.id_local AS employee_id_local,
            e.employee_code,
            ews.weekday,
            ews.work_mode,
            CASE
              WHEN ews.work_mode = 'FOREIGN' THEN NULL
              ELSE ews.site_id
            END AS site_id,
            CASE
              WHEN ews.work_mode = 'FOREIGN' THEN NULL
              ELSE s.name
            END AS site_name
          FROM employees e
          JOIN employee_work_sites ews
            ON ews.employee_id = e.id_remote
          LEFT JOIN sites s
            ON s.id = ews.site_id
           AND s.company_id = e.company_id
          WHERE e.company_id = $1
            AND e.is_active = TRUE
          ORDER BY
            e.employee_code,
            ews.weekday
        `,
        [device.company_id],
      );

      res.status(200).json({
        assignments: result.rows,
      });

    } catch (error) {
      console.error(
        'Error descargando configuración laboral para Android:',
        error
      );

      res.status(500).json({
        error: 'Error interno del servidor',
      });
    }
  }
);

export default router;