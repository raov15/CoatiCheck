      import { Router, Request, Response } from 'express';

import bcrypt from 'bcryptjs';

import jwt from 'jsonwebtoken';

import multer from 'multer';

import path from 'path';

import fs from 'fs';

import crypto from 'crypto';

import pool from '../db/client';

import {
  requirePasswordChangeComplete,
  requireRole,
  UserAuthRequest,
  userAuthMiddleware,
} from '../middleware/user-auth';

const router = Router();

const JWT_SECRET = process.env.JWT_SECRET;

const uploadDir = path.resolve(process.env.UPLOAD_DIR ?? 'uploads/logos');

fs.mkdirSync(uploadDir, { recursive: true });

const upload = multer({
  dest: uploadDir,
  limits: { fileSize: 2 * 1024 * 1024 },
  fileFilter: (_req, file, cb) =>
    cb(null, ['image/png', 'image/jpeg'].includes(file.mimetype)),
});

router.post(
  '/login',
  async (req: Request, res: Response): Promise<void> => {
    if (!JWT_SECRET) {
      res.status(500).json({ error: 'JWT_SECRET no está configurado' });
      return;
    }

    const { username, password } = req.body ?? {};

    const result = await pool.query(
      'SELECT id, company_id, role, must_change_password, password_hash FROM users WHERE username = $1 AND is_active = TRUE',
      [username],
    );

    const user = result.rows[0];

    if (
      !user ||
      !(await bcrypt.compare(password ?? '', user.password_hash))
    ) {
      res.status(401).json({ error: 'Usuario o contraseña incorrectos' });
      return;
    }

    const token = jwt.sign(
      {
        id: user.id,
        companyId: user.company_id,
        role: user.role,
        mustChangePassword: user.must_change_password,
      },
      JWT_SECRET,
      { expiresIn: '8h' },
    );

    res.json({
      token,
      must_change_password: user.must_change_password,
    });
  },
);

router.post(
  '/change-password',
  userAuthMiddleware,
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { current_password, password } = req.body ?? {};

    if (typeof password !== 'string' || password.length < 8) {
      res.status(400).json({
        error: 'La contraseña debe tener al menos 8 caracteres',
      });
      return;
    }

    const current = await pool.query(
      'SELECT password_hash FROM users WHERE id = $1 AND is_active = TRUE',
      [req.user!.id],
    );

    if (
      !current.rows[0] ||
      !(await bcrypt.compare(
        current_password ?? '',
        current.rows[0].password_hash,
      ))
    ) {
      res.status(401).json({
        error: 'La contraseña actual es incorrecta',
      });
      return;
    }

    await pool.query(
      'UPDATE users SET password_hash = $1, must_change_password = FALSE WHERE id = $2',
      [await bcrypt.hash(password, 12), req.user!.id],
    );

    res.json({ changed: true });
  },
);

router.post(
  '/companies',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  requireRole('admin'),
  upload.single('logo'),
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { name, slug } = req.body ?? {};

    if (!name || !slug) {
      res.status(400).json({
        error: 'name y slug son requeridos',
      });
      return;
    }

    try {
      const logoPath = req.file
        ? `/api/admin/logos/${req.file.filename}`
        : null;

      const result = await pool.query(
        'INSERT INTO companies (name, slug, logo_path) VALUES ($1, $2, $3) RETURNING id, name, slug, logo_path',
        [name, slug, logoPath],
      );

      res.status(201).json(result.rows[0]);
    } catch {
      if (req.file) {
        fs.rmSync(req.file.path, { force: true });
      }

      res.status(409).json({
        error:
          'No fue posible crear la empresa; el slug puede estar duplicado',
      });
    }
  },
);

router.post(
  '/companies/:companyId/enrollment-codes',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  requireRole('admin'),
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { expires_in_minutes = 30 } = req.body ?? {};

    const plainCode = crypto.randomBytes(12).toString('hex');

    const codeHash = crypto
      .createHash('sha256')
      .update(plainCode)
      .digest('hex');

    const minutes = Number(expires_in_minutes);

    const expiresInMinutes = Number.isInteger(minutes)
      ? Math.min(Math.max(minutes, 5), 1440)
      : 30;

    // Buscar automáticamente el sitio de esta empresa
    const siteResult = await pool.query(
      `
        SELECT s.id
        FROM sites s
        WHERE s.company_id = $1
        ORDER BY s.created_at ASC
        LIMIT 1
      `,
      [req.params.companyId],
    );

    const siteId = siteResult.rows[0]?.id ?? null;

    if (!siteId) {
      res.status(400).json({
        error: 'La empresa no tiene una ubicación configurada',
      });
      return;
    }

    const result = await pool.query(
      `
        INSERT INTO device_enrollment_codes (
          company_id,
          site_id,
          code_hash,
          expires_at
        )
        VALUES (
          $1,
          $2,
          $3,
          NOW() + ($4 * INTERVAL '1 minute')
        )
        RETURNING
          id,
          company_id,
          site_id,
          expires_at
      `,
      [
        req.params.companyId,
        siteId,
        codeHash,
        expiresInMinutes,
      ],
    );

    res.status(201).json({
      ...result.rows[0],
      enrollment_code: plainCode,
    });
  },
);

router.post(
  '/companies/:companyId/sites',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede crear centros para otra empresa',
      });
      return;
    }

    const {
      name,
      site_key = null,
      postal_code = null,
      road_type = null,
      street = null,
      street_number = null,
      interior_number = null,
      neighborhood = null,
      locality = null,
      municipality = null,
      state = null,
      between_streets = null,
      reference_street = null,
      observations = null,
      latitude = null,
      longitude = null,
    } = req.body ?? {};

    if (typeof name !== 'string' || !name.trim()) {
      res.status(400).json({
        error: 'El nombre del sitio es requerido',
      });
      return;
    }

    const company = await pool.query(
      'SELECT id FROM companies WHERE id = $1 AND is_active = TRUE',
      [companyId],
    );

    if (!company.rows[0]) {
      res.status(404).json({
        error: 'Empresa no encontrada',
      });
      return;
    }

    const lat =
      latitude === '' || latitude == null
        ? null
        : Number(latitude);

    const lng =
      longitude === '' || longitude == null
        ? null
        : Number(longitude);

    if (
      lat !== null &&
      (!Number.isFinite(lat) || lat < -90 || lat > 90)
    ) {
      res.status(400).json({
        error: 'Latitud inválida',
      });
      return;
    }

    if (
      lng !== null &&
      (!Number.isFinite(lng) || lng < -180 || lng > 180)
    ) {
      res.status(400).json({
        error: 'Longitud inválida',
      });
      return;
    }

    try {
      const result = await pool.query(
        `INSERT INTO sites (
           company_id,
           name,
           site_key,
           postal_code,
           road_type,
           street,
           street_number,
           interior_number,
           neighborhood,
           locality,
           municipality,
           state,
           between_streets,
           reference_street,
           observations,
           latitude,
           longitude,
           radius_m,
           updated_at
         )
         VALUES (
           $1, $2, $3, $4, $5, $6, $7,
           $8, $9, $10, $11, $12, $13,
           $14, $15, $16, $17, 100, NOW()
         )
         RETURNING *`,
        [
          companyId,
          name.trim(),
          site_key,
          postal_code,
          road_type,
          street,
          street_number,
          interior_number,
          neighborhood,
          locality,
          municipality,
          state,
          between_streets,
          reference_street,
          observations,
          lat,
          lng,
        ],
      );

      res.status(201).json(result.rows[0]);
    } catch (error) {
      console.error('Error creando centro de trabajo:', error);

      res.status(500).json({
        error: 'No fue posible crear el centro de trabajo',
      });
    }
  },
);
    

  

router.get(
  '/companies/:companyId/sites',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    if (
      req.user?.role !== 'admin' &&
      req.user?.companyId !== req.params.companyId
    ) {
      res.status(403).json({
        error: 'No puede consultar sitios de otra empresa',
      });
      return;
    }

    const result = await pool.query(
      `SELECT id, company_id, name, site_key, postal_code, road_type, street, street_number,
              interior_number, neighborhood, locality, municipality, state, between_streets,
              reference_street, observations, latitude, longitude, radius_m, created_at, updated_at
       FROM sites
       WHERE company_id = $1
       ORDER BY name`,
      [req.params.companyId],
    );

    res.json(result.rows);
  },
);

router.get(
  '/logos/:filename',
  (req: Request, res: Response): void => {
    const filename = path.basename(req.params.filename);

    res.sendFile(
      path.join(uploadDir, filename),
      (error) => {
        if (error && !res.headersSent) {
          const statusCode =
            'statusCode' in error &&
            typeof error.statusCode === 'number'
              ? error.statusCode
              : 500;

          res
            .status(statusCode === 404 ? 404 : 500)
            .json({
              error: 'Logo no encontrado',
            });
        }
      },
    );
  },
);

router.post(
  '/users',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  requireRole('admin'),
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const {
      username,
      password,
      full_name,
      role = 'operator',
      site_id,
      employee_code,
      company_id,
    } = req.body ?? {};

    if (
      !username ||
      !password ||
      !full_name ||
      !company_id
    ) {
      res.status(400).json({
        error:
          'username, password, full_name y company_id son requeridos',
      });
      return;
    }

    if (!['administrator', 'operator'].includes(role)) {
      res.status(400).json({
        error: 'Rol inválido',
      });
      return;
    }

    const company = await pool.query(
      'SELECT id FROM companies WHERE id = $1 AND is_active = TRUE',
      [company_id],
    );

    if (!company.rows[0]) {
      res.status(404).json({
        error: 'Empresa no encontrada',
      });
      return;
    }

    if (site_id) {
      const site = await pool.query(
        'SELECT id FROM sites WHERE id = $1 AND company_id = $2',
        [site_id, company_id],
      );

      if (!site.rows[0]) {
        res.status(400).json({
          error: 'El sitio no pertenece a la empresa',
        });
        return;
      }
    }

    try {
      const result = await pool.query(
        `INSERT INTO users (
           company_id,
           site_id,
           username,
           password_hash,
           full_name,
           role,
           employee_code
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7)
         RETURNING
           id,
           company_id,
           username,
           full_name,
           role,
           employee_code,
           must_change_password`,
        [
          company_id,
          site_id ?? null,
          username,
          await bcrypt.hash(password, 12),
          full_name,
          role,
          employee_code ?? null,
        ],
      );

      res.status(201).json(result.rows[0]);
    } catch {
      res.status(409).json({
        error:
          'No fue posible crear el usuario; el nombre puede estar duplicado',
      });
    }
  },
);

router.get(
  '/companies/:companyId/branding',
  async (
    req: Request,
    res: Response,
  ): Promise<void> => {
    const result = await pool.query(
      'SELECT id, name, slug, logo_path FROM companies WHERE id = $1 AND is_active = TRUE',
      [req.params.companyId],
    );

    if (!result.rows[0]) {
      res.status(404).json({
        error: 'Empresa no encontrada',
      });
      return;
    }

    res.json(result.rows[0]);
  },
);

router.get(
  '/companies',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  requireRole('admin'),
  async (_req, res) => {
    const result = await pool.query(
      'SELECT id, name, slug, logo_path, is_active FROM companies ORDER BY name',
    );

    res.json(result.rows);
  },
);

router.get(
  '/companies/:companyId/devices',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    if (
      req.user?.role !== 'admin' &&
      req.user?.companyId !== req.params.companyId
    ) {
      res.status(403).json({
        error:
          'No puede consultar dispositivos de otra empresa',
      });
      return;
    }

    const result = await pool.query(
      `
      SELECT
        d.id_remote,
        d.id_local,
        d.device_name,
        d.fingerprint,
        d.company_id,
        d.site_id AS device_site_id,
        d.created_at,

        c.name AS company_name,

        /*
         * Centro del dispositivo.
         * Se conserva solo para diagnóstico.
         */
        ds.name AS device_site_name,

        /*
         * Datos reales del trabajador.
         */
        e.id_remote AS employee_id_remote,
        e.id_local AS employee_id_local,
        e.employee_code,

        e.first_name,
        e.last_name_paternal,
        e.last_name_maternal,
        e.full_name AS employee_name,

        e.rfc,
        e.curp,
        e.nss,
        e.phone,
        e.department,
        e.hire_date,
        e.category_id,
        cat.category_number,
        cat.name AS category_name,

        /*
         * Centro asignado específicamente al EMPLEADO.
         */
        e.site_id AS employee_site_id,
        es.name AS employee_site_name

      FROM devices d

      JOIN companies c
        ON c.id = d.company_id

      LEFT JOIN sites ds
        ON ds.id = d.site_id

      LEFT JOIN employees e
        ON e.id_remote = d.employee_id
       AND e.is_active = TRUE

      LEFT JOIN sites es
        ON es.id = e.site_id

      LEFT JOIN categories cat
        ON cat.id = e.category_id

      WHERE d.company_id = $1

        /*
         * La sección Trabajadores no debe mostrar dispositivos vacíos.
         */
        AND e.id_remote IS NOT NULL

      ORDER BY d.created_at DESC
      `,
      [req.params.companyId],
    );

    res.json(result.rows);
  },
);

/* =========================================================
   DELETE /api/admin/companies/:companyId/employees/:employeeCode

   Elimina realmente al trabajador de PostgreSQL.

   También:
   - elimina sus registros de asistencia;
   - desvincula el dispositivo del trabajador;
   - elimina el empleado.

   El dispositivo NO se elimina: queda disponible para volver

      a asignarse posteriormente.
   ========================================================= */

router.delete(
  '/companies/:companyId/employees/:employeeCode',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  requireRole('admin'),
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId, employeeCode } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede eliminar trabajadores de otra empresa',
      });
      return;
    }

    const client = await pool.connect();

    try {
      await client.query('BEGIN');

      const employeeResult = await client.query(
        `SELECT
           id_remote,
           id_local,
           employee_code,
           full_name
         FROM employees
         WHERE company_id = $1
           AND employee_code = $2
         LIMIT 1
         FOR UPDATE`,
        [companyId, employeeCode],
      );

      const employee = employeeResult.rows[0];

      if (!employee) {
        await client.query('ROLLBACK');

        res.status(404).json({
          error: 'Trabajador no encontrado',
        });
        return;
      }

      /*
       * Desvincular los dispositivos.
       * El dispositivo se conserva.
       */
      await client.query(
        `UPDATE devices
         SET employee_id = NULL,
             updated_at = NOW()
         WHERE company_id = $1
           AND employee_id = $2`,
        [companyId, employee.id_remote],
      );

      /*
       * Eliminar configuración por día del trabajador.
       */
      await client.query(
        `DELETE FROM employee_work_sites
         WHERE employee_id = $1`,
        [employee.id_remote],
      );

      /*
       * Eliminar incidencias calificadas asociadas a sus
       * registros de asistencia.
       *
       * Normalmente attendance_records.employee_id guarda id_local.
       * Incluimos también id_remote por compatibilidad con
       * registros antiguos.
       */
      await client.query(
        `DELETE FROM qualified_incidents
         WHERE company_id = $1
           AND employee_id = $2`,
        [companyId, employee.id_remote],
      );

      /*
       * Eliminar asistencias.
       */
      await client.query(
        `DELETE FROM attendance_records
         WHERE company_id = $1
           AND (
             employee_id = $2
             OR employee_id = $3
             OR employee_id = $4
           )`,
        [
          companyId,
          employee.id_local,
          employee.id_remote,
          employee.employee_code,
        ],
      );

      /*
       * Finalmente eliminar el empleado.
       */
      await client.query(
        `DELETE FROM employees
         WHERE company_id = $1
           AND id_remote = $2`,
        [companyId, employee.id_remote],
      );

      await client.query('COMMIT');

      res.json({
        deleted: true,
        employee_code: employee.employee_code,
        employee_name: employee.full_name,
      });
    } catch (error) {
      await client.query('ROLLBACK');

      console.error(
        'Error eliminando trabajador:',
        error,
      );

      const detail =
        error instanceof Error
          ? error.message
          : String(error);

      res.status(500).json({
        error: 'No fue posible eliminar el trabajador',
        detail,
      });
    } finally {
      client.release();
    }
  },
);

function canAccessCompany(
  req: UserAuthRequest,
  companyId: string,
): boolean {
  return (
    req.user?.role === 'admin' ||
    req.user?.companyId === companyId
  );
}

function normalizeWeekday(
  value: unknown,
): number | null {
  const weekday = Number(value);

  if (
    !Number.isInteger(weekday) ||
    weekday < 0 ||
    weekday > 7
  ) {
    return null;
  }

  return weekday;
}

const VALID_CONCEPTS = [
  'Ausentismo',
  'Retardo',
  'Permiso médico justificado',
  'Permiso médico no justificado',
  'Permiso de salida',
  'Falta justificada',
] as const;

router.get(
  '/companies/:companyId/employees',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error:
          'No puede consultar trabajadores de otra empresa',
      });
      return;
    }

    const result = await pool.query(
      `SELECT
         e.id_remote,
         e.id_local,
         e.employee_code,
         e.first_name,
         e.last_name_paternal,
         e.last_name_maternal,
         e.full_name,
         e.rfc,
         e.curp,
         e.nss,
         e.phone,
         e.department,
         e.hire_date,
         e.category_id,
         e.site_id,
         e.is_active,
         e.workdays_customized,
         e.workdays_customized_at,

         c.category_number,
         c.name AS category_name,

         s.name AS site_name

       FROM employees e

       LEFT JOIN categories c
         ON c.id = e.category_id

       LEFT JOIN sites s
         ON s.id = e.site_id

       WHERE e.company_id = $1
         AND e.is_active = TRUE

       ORDER BY
         e.full_name,
         e.employee_code`,
      [companyId],
    );

    res.json(result.rows);
  },
);



router.get(
  '/companies/:companyId/categories/:categoryNumber/config',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId, categoryNumber } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error:
          'No puede consultar categorías de otra empresa',
      });
      return;
    }

    const number = Number(categoryNumber);

    if (!Number.isInteger(number) || number < 1) {
      res.status(400).json({
        error: 'Número de categoría inválido',
      });
      return;
    }

    const categoryResult = await pool.query(
      `SELECT
         id,
         company_id,
         category_number,
         name,
         start_time,
         end_time,
         tolerance_minutes,
         overtime_allowed,
         overtime_value
       FROM categories
       WHERE company_id = $1
         AND category_number = $2
       LIMIT 1`,
      [companyId, number],
    );

    const category = categoryResult.rows[0];

    if (!category) {
      res.status(404).json({
        error: 'Categoría no encontrada',
      });
      return;
    }

    const [
      workdaysResult,
      restDaysResult,
      incidenceValuesResult,
    ] = await Promise.all([
      pool.query(
        `SELECT
           weekday,
           is_workday,
           start_time,
           end_time
         FROM category_workdays
         WHERE category_id = $1
         ORDER BY weekday`,
        [category.id],
      ),

      pool.query(
        `SELECT weekday
         FROM category_rest_days
         WHERE category_id = $1
         ORDER BY weekday`,
        [category.id],
      ),

      pool.query(
        `SELECT
           concept,
           value
         FROM category_incidence_values
         WHERE category_id = $1
         ORDER BY concept`,
        [category.id],
      ),
    ]);

    res.json({
      ...category,

      workdays: workdaysResult.rows,

      rest_days: restDaysResult.rows.map(
        (row: any) => row.weekday,
      ),

      incidence_values:
        incidenceValuesResult.rows,
    });
  },
);

router.put(
  '/companies/:companyId/categories/:categoryNumber/config',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  requireRole('admin'),
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId, categoryNumber } =
      req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error:
          'No puede modificar categorías de otra empresa',
      });
      return;
    }

    const number = Number(categoryNumber);

    if (!Number.isInteger(number) || number < 1) {
      res.status(400).json({
        error: 'Número de categoría inválido',
      });
      return;
    }

    const {
      name = `Categoría ${number}`,
      start_time = null,
      end_time = null,
      tolerance_minutes = 0,
      overtime_allowed = false,
      overtime_value = null,
      workdays = [],
      rest_days = [],
      incidence_values = [],
    } = req.body ?? {};

    if (!Array.isArray(workdays)) {
      res.status(400).json({
        error: 'workdays debe ser un arreglo',
      });
      return;
    }

    if (!Array.isArray(rest_days)) {
      res.status(400).json({
        error: 'rest_days debe ser un arreglo',
      });
      return;
    }

    if (!Array.isArray(incidence_values)) {
      res.status(400).json({
        error:
          'incidence_values debe ser un arreglo',
      });
      return;
    }

    const client = await pool.connect();

    try {
      await client.query('BEGIN');

      const categoryResult = await client.query(
        `INSERT INTO categories (
           company_id,
           category_number,
           name,
           start_time,
           end_time,
           tolerance_minutes,
           overtime_allowed,
           overtime_value,
           updated_at
         )
         VALUES (
           $1,$2,$3,$4,$5,$6,$7,$8,NOW()
         )
         ON CONFLICT (
           company_id,
           category_number
         )
         DO UPDATE SET
           name = EXCLUDED.name,
           start_time = EXCLUDED.start_time,
           end_time = EXCLUDED.end_time,
           tolerance_minutes =
             EXCLUDED.tolerance_minutes,
           overtime_allowed =
             EXCLUDED.overtime_allowed,
           overtime_value =
             EXCLUDED.overtime_value,
           updated_at = NOW()
         RETURNING *`,
        [
          companyId,
          number,
          String(name).trim() ||
            `Categoría ${number}`,
          start_time || null,
          end_time || null,
          Number(tolerance_minutes) || 0,
          Boolean(overtime_allowed),
          overtime_value === '' ||
          overtime_value == null
            ? null
            : Number(overtime_value),
        ],
      );

      const category = categoryResult.rows[0];

      await client.query(
        `DELETE FROM category_rest_days
         WHERE category_id = $1`,
        [category.id],
      );

      for (const rawWeekday of rest_days) {
        const weekday =
          normalizeWeekday(rawWeekday);

        if (weekday === null) continue;

        await client.query(
          `INSERT INTO category_rest_days (
             category_id,
             weekday
           )
           VALUES ($1, $2)
           ON CONFLICT (
             category_id,
             weekday
           )
           DO NOTHING`,
          [category.id, weekday],
        );
      }

      await client.query(
        `DELETE FROM category_workdays
         WHERE category_id = $1`,
        [category.id],
      );

      for (const item of workdays) {
        const weekday =
          normalizeWeekday(item?.weekday);

        if (weekday === null) continue;

        await client.query(
          `INSERT INTO category_workdays (
             category_id,
             weekday,
             is_workday,
             start_time,
             end_time,
             updated_at
           )
           VALUES (
             $1,$2,$3,$4,$5,NOW()
           )
           ON CONFLICT (
             category_id,
             weekday
           )
           DO UPDATE SET
             is_workday =
               EXCLUDED.is_workday,
             start_time =
               EXCLUDED.start_time,
             end_time =
               EXCLUDED.end_time,
             updated_at = NOW()`,
          [
            category.id,
            weekday,
            Boolean(item?.is_workday),
            item?.start_time ?? null,
            item?.end_time ?? null,
          ],
        );
      }

      await client.query(
        `DELETE FROM category_incidence_values
         WHERE category_id = $1`,
        [category.id],
      );

      for (const item of incidence_values) {
        const concept = String(
          item?.concept ?? '',
        ).trim();

        const value = Number(item?.value);

        if (
          !VALID_CONCEPTS.includes(
            concept as any,
          ) ||
          !Number.isFinite(value)
        ) {
          continue;
        }

        await client.query(
          `INSERT INTO category_incidence_values (
             category_id,
             concept,
             value,
             updated_at
           )
           VALUES ($1,$2,$3,NOW())
           ON CONFLICT (
             category_id,
             concept
           )
           DO UPDATE SET
             value = EXCLUDED.value,
             updated_at = NOW()`,
          [
            category.id,
            concept,
            value,
          ],
        );
      }

      await client.query('COMMIT');

      res.json({
        saved: true,
        category_id: category.id,
        category_number: number,
      });
    } catch (error) {
      await client.query('ROLLBACK');

      console.error(
        'Error guardando categoría:',
        error,
      );

      const detail =
        error instanceof Error
          ? error.message
          : String(error);

      res.status(500).json({
        error:
          'No fue posible guardar la categoría',
        detail,
      });
    } finally {
      client.release();
    }
  },
);
router.get(
  '/companies/:companyId/foreign-locations',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede consultar recorridos de otra empresa',
      });
      return;
    }

    const employeeCode =
      typeof req.query.employeeCode === 'string'
        ? req.query.employeeCode.trim()
        : '';

    const date =
      typeof req.query.date === 'string'
        ? req.query.date.trim()
        : '';

    const requestedLimit = Number(req.query.limit ?? 1000);

    const limit = Number.isInteger(requestedLimit)
      ? Math.min(Math.max(requestedLimit, 1), 5000)
      : 1000;

    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      res.status(400).json({
        error: 'La fecha debe tener formato YYYY-MM-DD',
      });
      return;
    }

    try {
      const values: Array<string | number> = [companyId];

      const conditions: string[] = [
        'lp.company_id = $1',
      ];

      if (employeeCode) {
        values.push(employeeCode);

        conditions.push(
          `e.employee_code = $${values.length}`,
        );
      }

      if (date) {
        values.push(date);

        conditions.push(
          `lp.work_date = $${values.length}::date`,
        );
      }

      values.push(limit);

      const limitParam = `$${values.length}`;

      const result = await pool.query(
  `
  SELECT
    lp.id,
    lp.company_id,
    lp.employee_id,
    e.employee_code,
    e.full_name AS employee_name,
    lp.device_id,
    lp.work_date,
    (EXTRACT(EPOCH FROM lp.recorded_at) * 1000)::bigint AS occurred_at,
    lp.recorded_at,
    lp.latitude,
    lp.longitude,
    lp.accuracy_m,
    lp.altitude_m,
    lp.created_at,

    (
      SELECT ar.event_type
      FROM attendance_records ar
      WHERE ar.company_id = lp.company_id
        AND (
          ar.employee_id = e.id_local
          OR ar.employee_id = e.id_remote::text
          OR ar.employee_id = e.employee_code
        )
        AND ar.event_type IN ('CLOCK_IN', 'CLOCK_OUT')
        AND ABS(
          ar.occurred_at -
          (EXTRACT(EPOCH FROM lp.recorded_at) * 1000)
        ) <= 120000
      ORDER BY ABS(
        ar.occurred_at -
        (EXTRACT(EPOCH FROM lp.recorded_at) * 1000)
      )
      LIMIT 1
    ) AS attendance_event_type

  FROM employee_location_points lp

  LEFT JOIN employees e
    ON e.company_id = lp.company_id
   AND e.id_remote = lp.employee_id

  WHERE ${conditions.join('\n          AND ')}

  ORDER BY lp.recorded_at ASC

  LIMIT ${limitParam}
  `,
  values,
);
      

      res.json({
        records: result.rows,
        count: result.rows.length,
      });
    } catch (error) {
      console.error(
        'Error consultando recorrido foráneo:',
        error,
      );

      res.status(500).json({
        error:
          'No se pudieron consultar los puntos GPS foráneos',
      });
    }
  },
);

router.patch(
  '/companies/:companyId/sites/:siteId',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { companyId, siteId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede modificar centros de otra empresa',
      });
      return;
    }

    const {
      name,
      site_key = null,
      postal_code = null,
      road_type = null,
      street = null,
      street_number = null,
      interior_number = null,
      neighborhood = null,
      locality = null,
      municipality = null,
      state = null,
      between_streets = null,
      reference_street = null,
      observations = null,
      latitude = null,
      longitude = null,
    } = req.body ?? {};

    const lat =
      latitude === '' || latitude == null
        ? null
        : Number(latitude);

    const lng =
      longitude === '' || longitude == null
        ? null
        : Number(longitude);

    if (
      lat !== null &&
      (!Number.isFinite(lat) || lat < -90 || lat > 90)
    ) {
      res.status(400).json({
        error: 'Latitud inválida',
      });
      return;
    }

    if (
      lng !== null &&
      (!Number.isFinite(lng) || lng < -180 || lng > 180)
    ) {
      res.status(400).json({
        error: 'Longitud inválida',
      });
      return;
    }

    const result = await pool.query(
      `UPDATE sites
       SET name = COALESCE(NULLIF(TRIM($1), ''), name),
           site_key=$2,
           postal_code=$3,
           road_type=$4,
           street=$5,
           street_number=$6,
           interior_number=$7,
           neighborhood=$8,
           locality=$9,
           municipality=$10,
           state=$11,
           between_streets=$12,
           reference_street=$13,
           observations=$14,
           latitude=$15,
           longitude=$16,
           radius_m=300,
           updated_at=NOW()
       WHERE id=$17
         AND company_id=$18
       RETURNING *`,
      [
        typeof name === 'string' ? name : '',
        site_key,
        postal_code,
        road_type,
        street,
        street_number,
        interior_number,
        neighborhood,
        locality,
        municipality,
        state,
        between_streets,
        reference_street,
        observations,
        lat,
        lng,
        siteId,
        companyId,
      ],
    );

    if (!result.rows[0]) {
      res.status(404).json({
        error: 'Centro de trabajo no encontrado',
      });
      return;
    }

    res.json(result.rows[0]);
  },
);
router.get(
  '/companies/:companyId/categories',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede consultar categorías de otra empresa',
      });
      return;
    }

    const categories = await pool.query(
      `SELECT
         id,
         company_id,
         category_number,
         name,
         start_time,
         end_time,
         meal_start_time,
         meal_end_time,
         tolerance_minutes,
         overtime_allowed,
         overtime_value,
         configured_at,
         created_at,
         updated_at
       FROM categories
       WHERE company_id = $1
       ORDER BY category_number`,
      [companyId],
    );

    const categoryIds = categories.rows.map(
      (row) => row.id,
    );

    if (categoryIds.length === 0) {
      res.json([]);
      return;
    }

    const [workdays, restDays, values] =
      await Promise.all([
        pool.query(
          `SELECT
             category_id,
             weekday,
             is_workday,
             start_time,
             end_time
           FROM category_workdays
           WHERE category_id = ANY($1::uuid[])
           ORDER BY weekday`,
          [categoryIds],
        ),

        pool.query(
          `SELECT
             category_id,
             weekday
           FROM category_rest_days
           WHERE category_id = ANY($1::uuid[])
           ORDER BY weekday`,
          [categoryIds],
        ),

        pool.query(
          `SELECT
             category_id,
             concept,
             value
           FROM category_incidence_values
           WHERE category_id = ANY($1::uuid[])
           ORDER BY concept`,
          [categoryIds],
        ),
      ]);

    res.json(
      categories.rows.map((category) => ({
        ...category,

        workdays: workdays.rows.filter(
          (row) =>
            row.category_id === category.id,
        ),

        rest_days: restDays.rows
          .filter(
            (row) =>
              row.category_id === category.id,
          )
          .map((row) => row.weekday),

        incidence_values: values.rows.filter(
          (row) =>
            row.category_id === category.id,
        ),
      })),
    );
  },
);

/* ---------------------------------------------------------
   CREAR / EDITAR / ELIMINAR categorías dinámicas.

   Body esperado:
   {
     name?,
     start_time?,
     end_time?,
     tolerance_minutes?,
     overtime_allowed: true|false,
     overtime_value: null|number,
     rest_days: [6,7],
     workdays: [
       {
         weekday:1,
         is_workday:true,
         start_time:'09:00',
         end_time:'17:00'
       }
     ],
     incidence_values: [
       {concept:'Retardo',value:2},
       ...
     ]
   }
--------------------------------------------------------- */

router.post(
  '/companies/:companyId/categories',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error:
          'No puede crear categorías para otra empresa',
      });
      return;
    }

    const requestedName =
      typeof req.body?.name === 'string'
        ? req.body.name.trim()
        : '';

    const client = await pool.connect();

    try {
      await client.query('BEGIN');

      // Bloquea por empresa para evitar que dos altas
      // reciban el mismo número.
      await client.query(
        'SELECT pg_advisory_xact_lock(hashtext($1))',
        [`categories:${companyId}`],
      );

      const next = await client.query(
        `SELECT
           COALESCE(MAX(category_number), 0) + 1
             AS next_number
         FROM categories
         WHERE company_id = $1`,
        [companyId],
      );

      const categoryNumber = Number(
        next.rows[0].next_number,
      );

      const name =
        requestedName ||
        `Categoría ${categoryNumber}`;

      const category = await client.query(
        `INSERT INTO categories (
           company_id,
           category_number,
           name,
           tolerance_minutes,
           overtime_allowed,
           overtime_value,
           updated_at
         )
         VALUES (
           $1,$2,$3,10,TRUE,NULL,NOW()
         )
         RETURNING *`,
        [
          companyId,
          categoryNumber,
          name,
        ],
      );

      const concepts = [
        'Ausentismo',
        'Retardo',
        'Permiso médico justificado',
        'Permiso médico no justificado',
        'Permiso de salida',
        'Falta justificada',
      ];

      for (const concept of concepts) {
        await client.query(
          `INSERT INTO category_incidence_values (
             category_id,
             concept,
             value,
             updated_at
           )
           VALUES ($1,$2,0,NOW())
           ON CONFLICT (
             category_id,
             concept
           )
           DO NOTHING`,
          [
            category.rows[0].id,
            concept,
          ],
        );
      }

      await client.query('COMMIT');

      res.status(201).json(
        category.rows[0],
      );
    } catch (error: any) {
      await client.query('ROLLBACK');

      const detail =
        error instanceof Error
          ? error.message
          : String(error);

      res
        .status(
          error?.code === '23505'
            ? 409
            : 500,
        )
        .json({
          error:
            'No fue posible crear la categoría',
          detail,
        });
    } finally {
      client.release();
    }
  },
);

router.delete(
  '/companies/:companyId/categories/:categoryNumber',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error:
          'No puede eliminar categorías de otra empresa',
      });
      return;
    }

    const categoryNumber = Number(
      req.params.categoryNumber,
    );

    if (
      !Number.isInteger(categoryNumber) ||
      categoryNumber < 1
    ) {
      res.status(400).json({
        error: 'Número de categoría inválido',
      });
      return;
    }

    const category = await pool.query(
      `SELECT
         id,
         name
       FROM categories
       WHERE company_id = $1
         AND category_number = $2
       LIMIT 1`,
      [
        companyId,
        categoryNumber,
      ],
    );

    if (!category.rows[0]) {
      res.status(404).json({
        error: 'Categoría no encontrada',
      });
      return;
    }

    const used = await pool.query(
      `SELECT
         COUNT(*)::int AS total
       FROM employees
       WHERE company_id = $1
         AND category_id = $2
         AND is_active = TRUE`,
      [
        companyId,
        category.rows[0].id,
      ],
    );

    if (Number(used.rows[0].total) > 0) {
      res.status(409).json({
        error:
          'No puede eliminar la categoría porque tiene trabajadores asignados',
      });
      return;
    }

    await pool.query(
      `DELETE FROM categories
       WHERE company_id = $1
         AND category_number = $2`,
      [
        companyId,
        categoryNumber,
      ],
    );

    res.json({
      deleted: true,
      category_number: categoryNumber,
      name: category.rows[0].name,
    });
  },
);

router.put(
  '/companies/:companyId/categories/:categoryNumber',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (req: UserAuthRequest, res: Response): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error:
          'No puede modificar categorías de otra empresa',
      });
      return;
    }

    const categoryNumber = Number(
      req.params.categoryNumber,
    );

    if (
      !Number.isInteger(categoryNumber) ||
      categoryNumber < 1
    ) {
      res.status(400).json({
        error: 'Número de categoría inválido',
      });
      return;
    }

    const {
      name,
      start_time = null,
      end_time = null,
      meal_start_time = null,
      meal_end_time = null,
      tolerance_minutes = 10,
      overtime_allowed = true,
      overtime_value = null,
      rest_days = [],
      workdays = [],
      incidence_values = [],
    } = req.body ?? {};

    const tolerance = Number(
      tolerance_minutes,
    );

    if (
      !Number.isInteger(tolerance) ||
      tolerance < 0
    ) {
      res.status(400).json({
        error: 'Tolerancia inválida',
      });
      return;
    }

    const overtimeAllowed =
      Boolean(overtime_allowed);
          const overtimeValue =
      overtimeAllowed
        ? null
        : Number(overtime_value);

    if (
      !overtimeAllowed &&
      !Number.isFinite(overtimeValue)
    ) {
      res.status(400).json({
        error:
          'Si Tiempo extraordinario = No, debe indicar un valor',
      });
      return;
    }

    if (
      !Array.isArray(rest_days) ||
      !Array.isArray(workdays) ||
      !Array.isArray(incidence_values)
    ) {
      res.status(400).json({
        error:
          'Configuración de categoría inválida',
      });
      return;
    }

    const client = await pool.connect();

    try {
      await client.query('BEGIN');

      const categoryResult =
        await client.query(
          `UPDATE categories
           SET name =
                 COALESCE(
                   NULLIF(TRIM($1), ''),
                   name
                 ),
               start_time = $2,
               end_time = $3,
               meal_start_time = $4,
               meal_end_time = $5,
               tolerance_minutes = $6,
               overtime_allowed = $7,
               overtime_value = $8,
               updated_at = NOW(),
               configured_at = NOW()
           WHERE company_id = $9
             AND category_number = $10
           RETURNING *`,
          [
            typeof name === 'string'
              ? name
              : '',
            start_time,
            end_time,
            meal_start_time,
            meal_end_time,
            tolerance,
            overtimeAllowed,
            overtimeValue,
            companyId,
            categoryNumber,
          ],
        );

      const category =
        categoryResult.rows[0];

      if (!category) {
        await client.query('ROLLBACK');

        res.status(404).json({
          error: 'Categoría no encontrada',
        });
        return;
      }

      await client.query(
        `DELETE FROM category_rest_days
         WHERE category_id = $1`,
        [category.id],
      );

      for (const rawDay of rest_days) {
        const weekday =
          normalizeWeekday(rawDay);

        if (weekday === null) continue;

        await client.query(
          `INSERT INTO category_rest_days (
             category_id,
             weekday
           )
           VALUES ($1, $2)
           ON CONFLICT (
             category_id,
             weekday
           )
           DO NOTHING`,
          [
            category.id,
            weekday,
          ],
        );
      }

      /*
       * Reemplaza completamente los días laborales
       * de la categoría.
       *
       * Así, si antes era L-V y ahora solo L-M,
       * Mi-X-J-V dejan de quedar activos.
       */
      await client.query(
        `DELETE FROM category_workdays
         WHERE category_id = $1`,
        [category.id],
      );

      for (const item of workdays) {
        const weekday =
          normalizeWeekday(
            item?.weekday,
          );

        if (weekday === null) continue;

        await client.query(
          `INSERT INTO category_workdays (
             category_id,
             weekday,
             is_workday,
             start_time,
             end_time,
             updated_at
           )
           VALUES (
             $1,$2,$3,$4,$5,NOW()
           )
           ON CONFLICT (
             category_id,
             weekday
           )
           DO UPDATE SET
             is_workday =
               EXCLUDED.is_workday,
             start_time =
               EXCLUDED.start_time,
             end_time =
               EXCLUDED.end_time,
             updated_at = NOW()`,
          [
            category.id,
            weekday,
            Boolean(
              item?.is_workday,
            ),
            item?.start_time ?? null,
            item?.end_time ?? null,
          ],
        );
      }

      for (
        const item of incidence_values
      ) {
        const concept = String(
          item?.concept ?? '',
        ).trim();

        const value = Number(
          item?.value,
        );

        if (
          !VALID_CONCEPTS.includes(
            concept as any,
          ) ||
          !Number.isFinite(value)
        ) {
          continue;
        }

        await client.query(
          `INSERT INTO category_incidence_values (
             category_id,
             concept,
             value,
             updated_at
           )
           VALUES (
             $1,$2,$3,NOW()
           )
           ON CONFLICT (
             category_id,
             concept
           )
           DO UPDATE SET
             value = EXCLUDED.value,
             updated_at = NOW()`,
          [
            category.id,
            concept,
            value,
          ],
        );
      }

      await client.query('COMMIT');

      res.json({
        saved: true,
        category_id: category.id,
        category_number:
          categoryNumber,
      });
    } catch (error) {
      await client.query('ROLLBACK');

      console.error(
        'Error guardando categoría:',
        error,
      );

      const detail =
        error instanceof Error
          ? error.message
          : String(error);

      res.status(500).json({
        error:
          'No fue posible guardar la categoría',
        detail,
      });
    } finally {
      client.release();
    }
  },
);

/* ---------------------------------------------------------
   GET configuración completa de un trabajador.
--------------------------------------------------------- */

router.get(
  '/companies/:companyId/employees/:employeeCode/config',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const {
      companyId,
      employeeCode,
    } = req.params;

    if (
      !canAccessCompany(
        req,
        companyId,
      )
    ) {
      res.status(403).json({
        error:
          'No puede consultar trabajadores de otra empresa',
      });
      return;
    }

    const employeeResult =
      await pool.query(
        `SELECT
           e.id_remote,
           e.id_local,
           e.employee_code,
           e.first_name,
           e.last_name_paternal,
           e.last_name_maternal,
           e.full_name,
           e.rfc,
           e.curp,
           e.nss,
           e.phone,
           e.department,
           e.hire_date,
           e.category_id,
           e.site_id,
           e.is_active,
           e.workdays_customized,
           e.workdays_customized_at,
           c.category_number,
           c.name AS category_name,
           c.start_time
             AS category_start_time,
           c.end_time
             AS category_end_time,
           c.tolerance_minutes,
           c.overtime_allowed,
           c.overtime_value
         FROM employees e
         LEFT JOIN categories c
           ON c.id = e.category_id
         WHERE e.company_id = $1
           AND e.employee_code = $2
         LIMIT 1`,
        [
          companyId,
          employeeCode,
        ],
      );

    const employee =
      employeeResult.rows[0];

    if (!employee) {
      res.status(404).json({
        error:
          'Trabajador no encontrado',
      });
      return;
    }

    const [
      sitesByDay,
      workdays,
      restDays,
      values,
      counts,
    ] = await Promise.all([
      pool.query(
        `SELECT
           ews.weekday,
           ews.work_mode,
           ews.site_id,
           s.name AS site_name
         FROM employee_work_sites ews
         LEFT JOIN sites s
           ON s.id = ews.site_id
         WHERE ews.employee_id = $1
         ORDER BY ews.weekday`,
        [employee.id_remote],
      ),

      employee.category_id
        ? pool.query(
            `SELECT
               weekday,
               is_workday,
               start_time,
               end_time
             FROM category_workdays
             WHERE category_id = $1
             ORDER BY weekday`,
            [employee.category_id],
          )
        : Promise.resolve({
            rows: [],
          } as any),

      employee.category_id
        ? pool.query(
            `SELECT weekday
             FROM category_rest_days
             WHERE category_id = $1
             ORDER BY weekday`,
            [employee.category_id],
          )
        : Promise.resolve({
            rows: [],
          } as any),

      employee.category_id
        ? pool.query(
            `SELECT
               concept,
               value
             FROM category_incidence_values
             WHERE category_id = $1
             ORDER BY concept`,
            [employee.category_id],
          )
        : Promise.resolve({
            rows: [],
          } as any),

      pool.query(
        `SELECT
           concept,
           COUNT(*)::int AS count
         FROM qualified_incidents
         WHERE company_id = $1
           AND employee_id = $2
         GROUP BY concept
         ORDER BY concept`,
        [
          companyId,
          employee.id_remote,
        ],
      ),
    ]);

    res.json({
      ...employee,

      work_sites_by_day:
        sitesByDay.rows,

      category_configuration:
        employee.category_id
          ? {
              start_time:
                employee.category_start_time,

              end_time:
                employee.category_end_time,

              tolerance_minutes:
                employee.tolerance_minutes,

              overtime_allowed:
                employee.overtime_allowed,

              overtime_value:
                employee.overtime_value,

              workdays:
                workdays.rows,

              rest_days:
                restDays.rows.map(
                  (row: any) =>
                    row.weekday,
                ),

              incidence_values:
                values.rows,
            }
          : null,

      incidence_counts:
        counts.rows,
    });
  },
);
/* ---------------------------------------------------------
   PUT configuración propia del trabajador.

   NO duplica horarios/valores: esos se heredan de la categoría.
   Aquí solo asignamos categoría, fecha de ingreso y centros por día.
--------------------------------------------------------- */

router.put(
  '/companies/:companyId/employees/:employeeCode/config',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const {
      companyId,
      employeeCode,
    } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error:
          'No puede modificar trabajadores de otra empresa',
      });
      return;
    }

    const {
      category_number,
      hire_date = null,
      phone = null,
      first_name,
      last_name_paternal,
      last_name_maternal,
      full_name,
      department,
      rfc,
      curp,
      nss,
      work_sites_by_day = [],
    } = req.body ?? {};

    const categoryNumber =
      Number(category_number);

    if (
      !Number.isInteger(categoryNumber) ||
      categoryNumber < 1
    ) {
      res.status(400).json({
        error:
          'Debe asignar una categoría válida',
      });
      return;
    }

    if (!Array.isArray(work_sites_by_day)) {
      res.status(400).json({
        error:
          'work_sites_by_day debe ser un arreglo',
      });
      return;
    }

    const client = await pool.connect();

    try {
      await client.query('BEGIN');

      const categoryResult =
        await client.query(
          `SELECT id
           FROM categories
           WHERE company_id = $1
             AND category_number = $2
           LIMIT 1`,
          [
            companyId,
            categoryNumber,
          ],
        );

      if (!categoryResult.rows[0]) {
        await client.query('ROLLBACK');

        res.status(404).json({
          error:
            'Categoría no encontrada',
        });
        return;
      }

      const employeeResult =
        await client.query(
          `UPDATE employees
           SET category_id = $1,
               hire_date = $2,
               phone = $3,
               first_name =
                 COALESCE($4, first_name),
               last_name_paternal =
                 COALESCE(
                   $5,
                   last_name_paternal
                 ),
               last_name_maternal =
                 COALESCE(
                   $6,
                   last_name_maternal
                 ),
               full_name =
                 COALESCE($7, full_name),
               department =
                 COALESCE($8, department),
               rfc =
                 COALESCE($9, rfc),
               curp =
                 COALESCE($10, curp),
               nss =
                 COALESCE($11, nss),
               workdays_customized = TRUE,
               workdays_customized_at = NOW(),
               updated_at = NOW()
           WHERE company_id = $12
             AND employee_code = $13
           RETURNING
             id_remote,
             employee_code,
             first_name,
             last_name_paternal,
             last_name_maternal,
             full_name,
             category_id,
             hire_date`,
          [
            categoryResult.rows[0].id,
            hire_date || null,
            phone || null,
            first_name ?? null,
            last_name_paternal ?? null,
            last_name_maternal ?? null,
            full_name ?? null,
            department ?? null,
            rfc ?? null,
            curp ?? null,
            nss ?? null,
            companyId,
            employeeCode,
          ],
        );

      const employee =
        employeeResult.rows[0];

      if (!employee) {
        await client.query('ROLLBACK');

        res.status(404).json({
          error:
            'Trabajador no encontrado',
        });
        return;
      }

      await client.query(
        `DELETE FROM employee_work_sites
         WHERE employee_id = $1`,
        [employee.id_remote],
      );

      for (
        const item of work_sites_by_day
      ) {
        const weekday =
          normalizeWeekday(
            item?.weekday,
          );

        if (weekday === null) {
          continue;
        }

        const rawWorkMode = String(
          item?.work_mode ?? 'SITE',
        )
          .trim()
          .toUpperCase();

        if (
          rawWorkMode !== 'SITE' &&
          rawWorkMode !== 'FOREIGN'
        ) {
          await client.query('ROLLBACK');

          res.status(400).json({
            error:
              `Modo de trabajo inválido para el día ${weekday}. ` +
              'Debe ser SITE o FOREIGN',
          });
          return;
        }

        const workMode:
          | 'SITE'
          | 'FOREIGN' =
          rawWorkMode;

        const requestedSiteId =
          typeof item?.site_id ===
            'string' &&
          item.site_id.trim()
            ? item.site_id.trim()
            : null;

        const siteId =
          workMode === 'FOREIGN'
            ? null
            : requestedSiteId;

        if (
          workMode === 'SITE' &&
          !siteId
        ) {
          await client.query('ROLLBACK');

          res.status(400).json({
            error:
              `Debe seleccionar un centro de trabajo para el día ${weekday}`,
          });
          return;
        }

        if (siteId) {
          const site =
            await client.query(
              `SELECT id
               FROM sites
               WHERE id = $1
                 AND company_id = $2
               LIMIT 1`,
              [
                siteId,
                companyId,
              ],
            );

          if (!site.rows[0]) {
            await client.query(
              'ROLLBACK',
            );

            res.status(400).json({
              error:
                `El centro configurado para el día ${weekday} ` +
                'no pertenece a la empresa',
            });
            return;
          }
        }

        await client.query(
          `INSERT INTO employee_work_sites (
             employee_id,
             weekday,
             site_id,
             work_mode,
             updated_at
           )
           VALUES (
             $1,$2,$3,$4,NOW()
           )
           ON CONFLICT (
             employee_id,
             weekday
           )
           DO UPDATE SET
             site_id =
               EXCLUDED.site_id,
             work_mode =
               EXCLUDED.work_mode,
             updated_at = NOW()`,
          [
            employee.id_remote,
            weekday,
            siteId,
            workMode,
          ],
        );
      }

      /*
       * Compatibilidad:
       * el centro general usa solamente un día SITE.
       */
      const firstSite =
        await client.query(
          `SELECT site_id
           FROM employee_work_sites
           WHERE employee_id = $1
             AND work_mode = 'SITE'
             AND site_id IS NOT NULL
           ORDER BY weekday
           LIMIT 1`,
          [employee.id_remote],
        );

      await client.query(
        `UPDATE employees
         SET site_id = $1,
             updated_at = NOW()
         WHERE id_remote = $2`,
        [
          firstSite.rows[0]
            ?.site_id ?? null,
          employee.id_remote,
        ],
      );

      const savedEmployeeResult =
        await client.query(
          `SELECT
             e.id_remote,
             e.id_local,
             e.employee_code,
             e.first_name,
             e.last_name_paternal,
             e.last_name_maternal,
             e.full_name,
             e.rfc,
             e.curp,
             e.nss,
             e.phone,
             e.department,
             e.hire_date,
             e.category_id,
             e.site_id,
             e.is_active,
             e.workdays_customized,
             e.workdays_customized_at,
             c.category_number,
             c.name AS category_name
           FROM employees e
           LEFT JOIN categories c
             ON c.id = e.category_id
           WHERE e.company_id = $1
             AND e.employee_code = $2
           LIMIT 1`,
          [
            companyId,
            employeeCode,
          ],
        );

      const savedSitesResult =
        await client.query(
          `SELECT
             ews.weekday,
             ews.work_mode,
             ews.site_id,
             s.name AS site_name
           FROM employee_work_sites ews
           LEFT JOIN sites s
             ON s.id = ews.site_id
           WHERE ews.employee_id = $1
           ORDER BY ews.weekday`,
          [employee.id_remote],
        );

      await client.query('COMMIT');

      res.json({
        saved: true,
        ...savedEmployeeResult.rows[0],
        work_sites_by_day:
          savedSitesResult.rows,
                });

    } catch (error) {

      await client.query('ROLLBACK');

      console.error(
        'Error guardando configuración del trabajador:',
        error,
      );

      const message =
        error instanceof Error
          ? error.message
          : 'Error desconocido';

      res.status(500).json({
        error:
          `No fue posible guardar la configuración del trabajador: ${message}`,
      });

    } finally {

      client.release();

    }

  },

);


/* ---------------------------------------------------------
   GET incidencias / registros para revisión.

   No muestra valuación editable. Si ya fue calificada devuelve
   el valor aplicado automáticamente.
--------------------------------------------------------- */

router.get(

  '/companies/:companyId/incidents',

  userAuthMiddleware,

  requirePasswordChangeComplete,

  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {

    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {

      res.status(403).json({
        error:
          'No puede consultar incidencias de otra empresa',
      });

      return;
    }

    const status = String(
      req.query.status ?? 'pending',
    ).toLowerCase();

    const requestedLimit = Number(
      req.query.limit ?? 200,
    );

    const limit =
      Number.isInteger(requestedLimit)
        ? Math.min(
            Math.max(requestedLimit, 1),
            500,
          )
        : 200;

    const result = await pool.query(

      `SELECT

         a.id_remote AS attendance_record_id,

         a.id_local AS attendance_id_local,

         a.event_type,

         a.occurred_at,

         a.latitude,

         a.longitude,

         a.site_id,

         s.name AS site_name,

         e.id_remote AS employee_id,

         e.employee_code,

         e.full_name AS employee_name,

         e.department,

         e.category_id,

         cat.category_number,

         cat.name AS category_name,

         qi.id AS qualified_incident_id,

         qi.concept,

         qi.applied_value,

         qi.notes,

         qi.qualified_at

       FROM attendance_records a

       JOIN employees e

         ON e.company_id = a.company_id

        AND (
          e.id_local = a.employee_id
          OR e.id_remote::text = a.employee_id
        )

       LEFT JOIN sites s
         ON s.id = a.site_id

       LEFT JOIN categories cat
         ON cat.id = e.category_id

       LEFT JOIN qualified_incidents qi
         ON qi.attendance_record_id = a.id_remote

      WHERE a.company_id = $1

  /*
   * Las salidas del trabajo NO son incidencias.
   *
   * Si Tiempo extraordinario = Sí:
   * el CLOCK_OUT se conserva para calcular el tiempo extra,
   * pero no aparece en Incidencias.
   *
   * Si Tiempo extraordinario = No:
   * se conserva como salida normal y tampoco aparece
   * en Incidencias.
   */
  AND UPPER(COALESCE(a.event_type, '')) <> 'CLOCK_OUT'

  AND (
    ($2 = 'pending' AND qi.id IS NULL)

    OR
    ($2 = 'qualified' AND qi.id IS NOT NULL)

    OR
    ($2 = 'all')
  )
       ORDER BY a.occurred_at DESC

       LIMIT $3`,

      [
        companyId,
        status,
        limit,
      ],

    );

    res.json({
      records: result.rows,
      status,
      limit,
    });

  },

);


/* ---------------------------------------------------------
   POST calificar incidencia.

   Body: { concept, notes? }

   NO acepta un valor manual.

   El valor sale de category_incidence_values según la categoría
   que tenga el trabajador.
--------------------------------------------------------- */

router.post(

  '/companies/:companyId/incidents/:attendanceRecordId/qualify',

  userAuthMiddleware,

  requirePasswordChangeComplete,

  requireRole('admin'),

  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {

    const {
      companyId,
      attendanceRecordId,
    } = req.params;

    const concept = String(
      req.body?.concept ?? '',
    ).trim();

    const notes =
      typeof req.body?.notes === 'string'
        ? req.body.notes.trim() || null
        : null;

    if (
      !VALID_CONCEPTS.includes(
        concept as any,
      ) &&
      concept !== 'Tiempo extraordinario'
    ) {

      res.status(400).json({
        error:
          'Concepto de incidencia inválido',
      });

      return;
    }

    const client = await pool.connect();

    try {

      await client.query('BEGIN');

      const recordResult =
        await client.query(

          `SELECT
             a.id_remote,

             to_timestamp(a.occurred_at / 1000.0)::date AS incident_date,

             e.id_remote AS employee_id,

             e.category_id,

             cat.overtime_allowed,

             cat.overtime_value

           FROM attendance_records a

           JOIN employees e

             ON e.company_id = a.company_id

            AND (
              e.id_local = a.employee_id
              OR e.id_remote::text = a.employee_id
            )

           LEFT JOIN categories cat
             ON cat.id = e.category_id

           WHERE a.id_remote = $1
             AND a.company_id = $2

           LIMIT 1

           FOR UPDATE OF a`,

          [
            attendanceRecordId,
            companyId,
          ],

        );

      const record =
        recordResult.rows[0];

      if (!record) {

        await client.query('ROLLBACK');

        res.status(404).json({
          error:
            'Registro de asistencia no encontrado',
        });

        return;
      }

      if (!record.category_id) {

        await client.query('ROLLBACK');

        res.status(409).json({
          error:
            'El trabajador todavía no tiene categoría asignada',
        });

        return;
      }
    let appliedValue = 0;
if (
  concept === 'Tiempo extraordinario'
) {

  /*
   * Sí permitido = 0.
   * No permitido = valor configurado.
   */
  appliedValue =
    record.overtime_allowed
      ? 0
      : Number(record.overtime_value ?? 0);

} 

 else if (concept === 'Retardo') {

  /*
   * REGLA DE RETARDOS:
   *
   * 1 retardo calificado = 1 punto.
   * 3 puntos = 1 ausentismo.
   * Después de 3, el conteo de puntos vuelve a empezar.
   *
   * El valor configurado de "Retardo" NO se utiliza
   * como cantidad de retardos.
   *
   * El valor configurado de "Ausentismo" es el
   * porcentaje que corresponde a un ausentismo.
   */

  const ausentismoResult = await client.query(
    `SELECT value
     FROM category_incidence_values
     WHERE category_id = $1
       AND concept = 'Ausentismo'
     LIMIT 1`,
    [record.category_id],
  );

  if (!ausentismoResult.rows[0]) {
    await client.query('ROLLBACK');

    res.status(409).json({
      error:
        'No existe valuación configurada para Ausentismo',
    });

    return;
  }

  const ausentismoValue = Number(
    ausentismoResult.rows[0].value ?? 0,
  );

  /*
   * Contar retardos calificados del trabajador
   * durante el mismo mes.
   *
   * Excluimos el registro actual por si se está
   * editando una incidencia ya calificada.
   */
  const retardoCountResult = await client.query(
    `SELECT COUNT(*)::int AS total
     FROM qualified_incidents
     WHERE company_id = $1
       AND employee_id = $2
       AND concept = 'Retardo'
       AND incident_date >=
           date_trunc('month', $4::date)::date
       AND incident_date <
           (
             date_trunc('month', $4::date)
             + INTERVAL '1 month'
           )::date
       AND (
         attendance_record_id IS NULL
         OR attendance_record_id <> $3
       )`,
    [
      companyId,
      record.employee_id,
      attendanceRecordId,
      record.incident_date,
    ],
  );

  const previousRetardos = Number(
    retardoCountResult.rows[0]?.total ?? 0,
  );

  const currentRetardoNumber =
    previousRetardos + 1;

  /*
   * Cada tercer retardo genera el porcentaje
   * correspondiente a un Ausentismo.
   *
   * Retardo 1 -> 0
   * Retardo 2 -> 0
   * Retardo 3 -> 1.40
   * Retardo 4 -> 0
   * Retardo 5 -> 0
   * Retardo 6 -> 1.40
   */
  appliedValue =
    currentRetardoNumber % 3 === 0
      ? ausentismoValue
      : 0;


} else {

  /*
   * Para Ausentismo y las demás incidencias
   * se usa directamente el valor configurado.
   */
  const valueResult = await client.query(
    `SELECT value
     FROM category_incidence_values
     WHERE category_id = $1
       AND concept = $2
     LIMIT 1`,
    [
      record.category_id,
      concept,
    ],
  );

  if (!valueResult.rows[0]) {
    await client.query('ROLLBACK');

    res.status(409).json({
      error:
        `No existe valuación configurada para ${concept}`,
    });

    return;
  }

  appliedValue = Number(
    valueResult.rows[0].value,
  );
}
const result =
  await client.query(

    `INSERT INTO qualified_incidents (
       company_id,
       attendance_record_id,
       employee_id,
       category_id,
       concept,
       incident_date,
       applied_value,
       notes,
       qualified_by
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
       $9
     )
     ON CONFLICT (attendance_record_id)
     WHERE attendance_record_id IS NOT NULL

     DO UPDATE SET
       category_id =
         EXCLUDED.category_id,

       concept =
         EXCLUDED.concept,

       incident_date =
         EXCLUDED.incident_date,

       applied_value =
         EXCLUDED.applied_value,

       notes =
         EXCLUDED.notes,

       qualified_by =
         EXCLUDED.qualified_by,

       qualified_at = NOW()

     RETURNING
       id,
       attendance_record_id,
       employee_id,
       category_id,
       concept,
       incident_date,
       applied_value,
       notes,
       qualified_at`,

    [
      companyId,
      attendanceRecordId,
      record.employee_id,
      record.category_id,
      concept,
      record.incident_date,
      appliedValue,
      notes,
      req.user?.id ?? null,
    ],

    );

    await client.query('COMMIT');

const verification = await pool.query(
  `SELECT
     id,
     attendance_record_id,
     employee_id,
     concept,
     incident_date,
     applied_value,
     qualified_at
   FROM qualified_incidents
   WHERE attendance_record_id = $1
   LIMIT 1`,
  [attendanceRecordId],
);

console.log(
  'VERIFICACION INCIDENCIA DESPUES DE COMMIT:',
  verification.rows,
);

if (!verification.rows[0]) {
  res.status(500).json({
    error:
      'La incidencia no quedó guardada en PostgreSQL',
  });
  return;
}

res.json(verification.rows[0]);

    } catch (error) {

      await client.query('ROLLBACK');

      console.error(
        'Error calificando incidencia:',
        error,
      );

      res.status(500).json({
        error:
          'No fue posible calificar la incidencia',
      });

    } finally {

      client.release();

    }

  },

);


/* ---------------------------------------------------------
   GET conteo de incidencias por trabajador.

   Ejemplo:
   Ausentismo 0,
   Retardo 2,
   etc.
--------------------------------------------------------- */

router.get(

  '/companies/:companyId/employees/:employeeCode/incidence-counts',

  userAuthMiddleware,

  requirePasswordChangeComplete,

  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {

    const {
      companyId,
      employeeCode,
    } = req.params;

    if (
      !canAccessCompany(
        req,
        companyId,
      )
    ) {

      res.status(403).json({
        error:
          'No puede consultar trabajadores de otra empresa',
      });

      return;
    }

    const employee =
  await pool.query(

    `SELECT
       e.id_remote,
       e.category_id,
       COALESCE(civ.value, 0) AS ausentismo_value

     FROM employees e

     LEFT JOIN category_incidence_values civ
       ON civ.category_id = e.category_id
      AND civ.concept = 'Ausentismo'

     WHERE e.company_id = $1
       AND e.employee_code = $2

     LIMIT 1`,

    [
      companyId,
      employeeCode,
    ],

  );

    if (!employee.rows[0]) {

      res.status(404).json({
        error:
          'Trabajador no encontrado',
      });

      return;
    }

    

    const counts =
  await pool.query(

    `WITH concepts(concept) AS (

       VALUES
         ('Ausentismo'),
         ('Retardo'),
         ('Permiso médico justificado'),
         ('Permiso médico no justificado'),
         ('Permiso de salida'),
         ('Falta justificada'),
         ('Tiempo extraordinario')

     ),

     monthly_counts AS (

       SELECT
         concept,
         COUNT(*)::int AS total

       FROM qualified_incidents

       WHERE company_id = $1
         AND employee_id = $2

         AND incident_date >=
             date_trunc('month', CURRENT_DATE)::date

         AND incident_date <
             (
               date_trunc('month', CURRENT_DATE)
               + INTERVAL '1 month'
             )::date

       GROUP BY concept

     ),

     retardo_data AS (

       SELECT
         COALESCE(
           (
             SELECT total
             FROM monthly_counts
             WHERE concept = 'Retardo'
           ),
           0
         )::int AS total_retardos

     )

     SELECT
       c.concept,

       CASE

         /* Cada 3 retardos se convierten en 1 ausentismo */
         WHEN c.concept = 'Retardo' THEN
           (
             SELECT total_retardos % 3
             FROM retardo_data
           )

         WHEN c.concept = 'Ausentismo' THEN
           COALESCE(
             (
               SELECT total
               FROM monthly_counts
               WHERE concept = 'Ausentismo'
             ),
             0
           )
           +
           (
             SELECT FLOOR(total_retardos / 3.0)::int
             FROM retardo_data
           )

         ELSE
           COALESCE(
             (
               SELECT total
               FROM monthly_counts
               WHERE concept = c.concept
             ),
             0
           )

       END::int AS count

     FROM concepts c

     ORDER BY c.concept`,

    [
      companyId,
      employee.rows[0].id_remote,
    ],

  );

    res.json({

  employee_code:
    employeeCode,

  ausentismo_value:
    Number(employee.rows[0].ausentismo_value || 0),

  incidences:
    counts.rows,

});

  },

);

/* =========================================================
   GET /api/admin/companies/:companyId/attendance

   Devuelve registros de asistencia para reportes.
   Permite filtrar por rango de fechas.

   Las horas extra NO se calculan aquí:
   esta ruta devuelve también la configuración de categoría
   para que el reporte pueda calcular únicamente cuando
   overtime_allowed = TRUE.
   ========================================================= */

router.get(
  '/companies/:companyId/attendance',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede consultar asistencias de otra empresa',
      });
      return;
    }

    const from =
      typeof req.query.from === 'string'
        ? req.query.from.trim()
        : '';

    const to =
      typeof req.query.to === 'string'
        ? req.query.to.trim()
        : '';

    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      res.status(400).json({
        error: 'La fecha inicial debe tener formato YYYY-MM-DD',
      });
      return;
    }

    if (to && !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      res.status(400).json({
        error: 'La fecha final debe tener formato YYYY-MM-DD',
      });
      return;
    }

    try {
      const values: Array<string> = [companyId];

      const conditions: string[] = [
        'a.company_id = $1',
      ];

      if (from) {
        values.push(from);

        conditions.push(
          `TO_TIMESTAMP(a.occurred_at / 1000.0)::date >= $${values.length}::date`,
        );
      }

      if (to) {
        values.push(to);

        conditions.push(
          `TO_TIMESTAMP(a.occurred_at / 1000.0)::date <= $${values.length}::date`,
        );
      }

      const result = await pool.query(
        `
        SELECT
          a.id_remote,
          a.id_local,
          a.employee_id,
          a.event_type,
          a.occurred_at,
          a.latitude,
          a.longitude,
          a.accuracy_m,
          a.altitude_m,
          a.device_id,
          a.company_id,
          a.site_id,
          a.created_at,

          e.id_remote AS employee_id_remote,
          e.id_local AS employee_id_local,
          e.employee_code,
          e.full_name AS employee_name,
          e.department,
          e.category_id,

          c.category_number,
          c.name AS category_name,
          c.start_time,
          c.end_time,
          c.overtime_allowed,
          c.overtime_value

        FROM attendance_records a

        JOIN employees e
          ON e.company_id = a.company_id
         AND (
              e.id_local = a.employee_id
              OR e.id_remote::text = a.employee_id
              OR e.employee_code = a.employee_id
         )

        LEFT JOIN categories c
          ON c.id = e.category_id
         AND c.company_id = a.company_id

        WHERE ${conditions.join('\n          AND ')}

        ORDER BY
          a.occurred_at ASC
        `,
        values,
      );

      res.json({
        records: result.rows,
        count: result.rows.length,
      });
    } catch (error) {
      console.error(
        'Error consultando asistencias:',
        error,
      );

      const detail =
        error instanceof Error
          ? error.message
          : String(error);

      res.status(500).json({
        error: 'No se pudieron consultar las asistencias',
        detail,
      });
    }
  },
);

/* =========================================================
   VACACIONES DE TRABAJADORES
========================================================= */

/*
 * Obtener resumen e historial de vacaciones.
 */
router.get(
  '/companies/:companyId/employees/:employeeCode/vacations',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId, employeeCode } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede consultar trabajadores de otra empresa',
      });
      return;
    }

    try {
      const employeeResult = await pool.query(
        `SELECT
           id_remote,
           employee_code,
           full_name,
           hire_date
         FROM employees
         WHERE company_id = $1
           AND employee_code = $2
           AND is_active = TRUE
         LIMIT 1`,
        [companyId, employeeCode],
      );

      const employee = employeeResult.rows[0];

      if (!employee) {
        res.status(404).json({
          error: 'Trabajador no encontrado',
        });
        return;
      }

      const vacationsResult = await pool.query(
        `SELECT
           id,
           start_date,
           end_date,
           days_taken,
           notes,
           created_at
         FROM employee_vacations
         WHERE company_id = $1
           AND employee_id = $2
         ORDER BY start_date DESC, created_at DESC`,
        [companyId, employee.id_remote],
      );

      const totalTaken = vacationsResult.rows.reduce(
        (total: number, row: any) =>
          total + Number(row.days_taken ?? 0),
        0,
      );

      res.json({
        employee_code: employee.employee_code,
        employee_name: employee.full_name,
        hire_date: employee.hire_date,
        total_taken: totalTaken,
        vacations: vacationsResult.rows,
      });
    } catch (error) {
      console.error(
        'Error consultando vacaciones:',
        error,
      );

      res.status(500).json({
        error: 'No fue posible consultar las vacaciones',
      });
    }
  },
);

/*
 * Registrar vacaciones tomadas.
 */
router.post(
  '/companies/:companyId/employees/:employeeCode/vacations',
  userAuthMiddleware,
  requirePasswordChangeComplete,
  async (
    req: UserAuthRequest,
    res: Response,
  ): Promise<void> => {
    const { companyId, employeeCode } = req.params;

    if (!canAccessCompany(req, companyId)) {
      res.status(403).json({
        error: 'No puede modificar trabajadores de otra empresa',
      });
      return;
    }

    const {
      start_date,
      end_date,
      days_taken,
      notes = null,
    } = req.body ?? {};

    const daysTaken = Number(days_taken);

    if (
      !start_date ||
      !end_date ||
      !Number.isFinite(daysTaken) ||
      daysTaken <= 0
    ) {
      res.status(400).json({
        error:
          'Fecha inicial, fecha final y días tomados son requeridos',
      });
      return;
    }

    try {
      const employeeResult = await pool.query(
        `SELECT id_remote
         FROM employees
         WHERE company_id = $1
           AND employee_code = $2
           AND is_active = TRUE
         LIMIT 1`,
        [companyId, employeeCode],
      );

      const employee = employeeResult.rows[0];

      if (!employee) {
        res.status(404).json({
          error: 'Trabajador no encontrado',
        });
        return;
      }

      const result = await pool.query(
        `INSERT INTO employee_vacations (
           company_id,
           employee_id,
           start_date,
           end_date,
           days_taken,
           notes
         )
         VALUES ($1,$2,$3,$4,$5,$6)
         RETURNING
           id,
           start_date,
           end_date,
           days_taken,
           notes,
           created_at`,
        [
          companyId,
          employee.id_remote,
          start_date,
          end_date,
          daysTaken,
          typeof notes === 'string'
            ? notes.trim() || null
            : null,
        ],
      );

      res.status(201).json({
        saved: true,
        vacation: result.rows[0],
      });
    } catch (error) {
      console.error(
        'Error registrando vacaciones:',
        error,
      );

      res.status(500).json({
        error: 'No fue posible registrar las vacaciones',
      });
    }
  },
);
export default router;


