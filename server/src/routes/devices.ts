import { Router, Request, Response } from 'express';
import { v4 as uuidv4 } from 'uuid';
import jwt from 'jsonwebtoken';
import crypto from 'crypto';
import pool from '../db/client';
import { authMiddleware, AuthRequest } from '../middleware/auth';

const router = Router();
const JWT_SECRET = process.env.JWT_SECRET;
const TOKEN_EXPIRY = '365d';

// POST /api/devices/register
router.post('/register', async (req: Request, res: Response): Promise<void> => {
  const { device_name, device_fingerprint, local_id } = req.body ?? {};

  if (!device_name || !device_fingerprint || !local_id) {
    res.status(400).json({ error: 'device_name, device_fingerprint y local_id son requeridos' });
    return;
  }
  if (!JWT_SECRET) {
    res.status(500).json({ error: 'JWT_SECRET no está configurado' });
    return;
  }

  try {
    const existing = await pool.query(
      `SELECT id_remote, company_id, site_id
       FROM devices
       WHERE id_local = $1
       LIMIT 1`,
      [local_id],
    );

    let deviceId: string;

    if (existing.rows[0]) {
      deviceId = existing.rows[0].id_remote;

      await pool.query(
        `UPDATE devices
         SET device_name = $1,
             fingerprint = $2
         WHERE id_remote = $3`,
        [device_name, device_fingerprint, deviceId],
      );
    } else {
      deviceId = uuidv4();

      await pool.query(
        `INSERT INTO devices (id_remote, id_local, device_name, fingerprint)
         VALUES ($1, $2, $3, $4)`,
        [deviceId, local_id, device_name, device_fingerprint],
      );
    }

    const authToken = jwt.sign({ deviceId }, JWT_SECRET, {
      expiresIn: TOKEN_EXPIRY,
    });

    await pool.query(
      `UPDATE devices
       SET auth_token = $1
       WHERE id_remote = $2`,
      [authToken, deviceId],
    );

    const deviceState = await pool.query(
      `SELECT d.company_id, d.site_id,
              c.id, c.name, c.slug, c.logo_path
       FROM devices d
       LEFT JOIN companies c
         ON c.id = d.company_id
        AND c.is_active = TRUE
       WHERE d.id_remote = $1
       LIMIT 1`,
      [deviceId],
    );

    const row = deviceState.rows[0];

    res.status(200).json({
      device_id: deviceId,
      auth_token: authToken,
      site_id: row?.site_id ?? null,
      company_id: row?.company_id ?? null,
      branding: row?.id
        ? {
            id: row.id,
            name: row.name,
            slug: row.slug,
            logo_path: row.logo_path,
          }
        : null,
    });
  } catch (err) {
    console.error('Error en /devices/register:', err);
    res.status(500).json({ error: 'Error interno del servidor' });
  }
});

// GET /api/devices/verify
router.get('/verify', authMiddleware, async (req: AuthRequest, res: Response): Promise<void> => {
  try {
    const result = await pool.query(
      `SELECT id_remote, company_id, site_id
       FROM devices
       WHERE id_remote = $1
       LIMIT 1`,
      [req.deviceId],
    );

    if (!result.rows[0]) {
      res.status(404).json({ error: 'Dispositivo no encontrado' });
      return;
    }

    res.status(200).json({
      valid: true,
      device_id: result.rows[0].id_remote,
      company_id: result.rows[0].company_id ?? null,
      site_id: result.rows[0].site_id ?? null,
    });
  } catch (err) {
    console.error('Error en /devices/verify:', err);
    res.status(500).json({ error: 'Error interno del servidor' });
  }
});

// POST /api/devices/refresh-token
router.post('/refresh-token', authMiddleware, async (req: AuthRequest, res: Response): Promise<void> => {
  if (!JWT_SECRET) {
    res.status(500).json({ error: 'JWT_SECRET no está configurado' });
    return;
  }

  try {
    const newToken = jwt.sign(
      { deviceId: req.deviceId },
      JWT_SECRET,
      { expiresIn: TOKEN_EXPIRY },
    );

    await pool.query(
      `UPDATE devices
       SET auth_token = $1
       WHERE id_remote = $2`,
      [newToken, req.deviceId],
    );

    res.status(200).json({ auth_token: newToken });
  } catch (err) {
    console.error('Error en /devices/refresh-token:', err);
    res.status(500).json({ error: 'Error interno del servidor' });
  }
});

// GET /api/devices/branding
router.get('/branding', authMiddleware, async (req: AuthRequest, res: Response): Promise<void> => {
  try {
    const result = await pool.query(
      `SELECT c.id, c.name, c.slug, c.logo_path
       FROM devices d
       JOIN companies c
         ON c.id = d.company_id
        AND c.is_active = TRUE
       WHERE d.id_remote = $1
       LIMIT 1`,
      [req.deviceId],
    );

    if (!result.rows[0]) {
      res.status(404).json({ error: 'El dispositivo no está asociado a una empresa activa' });
      return;
    }

    res.json(result.rows[0]);
  } catch (err) {
    console.error('Error en /devices/branding:', err);
    res.status(500).json({ error: 'Error interno del servidor' });
  }
});

// POST /api/devices/enroll
router.post('/enroll', async (req: Request, res: Response): Promise<void> => {
  const {
    device_name,
    device_fingerprint,
    local_id,
    enrollment_code,
  } = req.body ?? {};

  if (!device_name || !device_fingerprint || !local_id || !enrollment_code) {
    res.status(400).json({
      error: 'device_name, device_fingerprint, local_id y enrollment_code son requeridos',
    });
    return;
  }

  if (!JWT_SECRET) {
    res.status(500).json({ error: 'JWT_SECRET no está configurado' });
    return;
  }

  const codeHash = crypto
    .createHash('sha256')
    .update(String(enrollment_code))
    .digest('hex');

  const client = await pool.connect();

  try {
    await client.query('BEGIN');

    const enrollment = await client.query(
      `SELECT ec.id, ec.company_id, ec.site_id
       FROM device_enrollment_codes ec
       JOIN companies c
         ON c.id = ec.company_id
        AND c.is_active = TRUE
       LEFT JOIN sites s
         ON s.id = ec.site_id
        AND s.company_id = ec.company_id
       WHERE ec.code_hash = $1
         AND ec.used_at IS NULL
         AND ec.expires_at > NOW()
         AND (ec.site_id IS NULL OR s.id IS NOT NULL)
       FOR UPDATE OF ec`,
      [codeHash],
    );

    if (!enrollment.rows[0]) {
      await client.query('ROLLBACK');
      res.status(401).json({ error: 'Código de enrolamiento inválido o expirado' });
      return;
    }

    const enrollmentRow = enrollment.rows[0];

    const existing = await client.query(
      `SELECT id_remote, company_id
       FROM devices
       WHERE id_local = $1
       FOR UPDATE`,
      [local_id],
    );

    if (
      existing.rows[0]?.company_id &&
      String(existing.rows[0].company_id) !== String(enrollmentRow.company_id)
    ) {
      await client.query('ROLLBACK');
      res.status(409).json({ error: 'El dispositivo ya pertenece a otra empresa' });
      return;
    }

    const deviceId = existing.rows[0]?.id_remote ?? uuidv4();

    if (existing.rows[0]) {
      await client.query(
        `UPDATE devices
         SET device_name = $1,
             fingerprint = $2,
             company_id = $3,
             site_id = $4
         WHERE id_remote = $5`,
        [
          device_name,
          device_fingerprint,
          enrollmentRow.company_id,
          enrollmentRow.site_id,
          deviceId,
        ],
      );
    } else {
      await client.query(
        `INSERT INTO devices (
           id_remote,
           id_local,
           device_name,
           fingerprint,
           company_id,
           site_id
         )
         VALUES ($1, $2, $3, $4, $5, $6)`,
        [
          deviceId,
          local_id,
          device_name,
          device_fingerprint,
          enrollmentRow.company_id,
          enrollmentRow.site_id,
        ],
      );
    }

    const authToken = jwt.sign(
      { deviceId },
      JWT_SECRET,
      { expiresIn: TOKEN_EXPIRY },
    );

    await client.query(
      `UPDATE devices
       SET auth_token = $1
       WHERE id_remote = $2`,
      [authToken, deviceId],
    );

    await client.query(
      `UPDATE device_enrollment_codes
       SET used_at = NOW()
       WHERE id = $1`,
      [enrollmentRow.id],
    );

    await client.query('COMMIT');

    const branding = await pool.query(
      `SELECT c.id, c.name, c.slug, c.logo_path
       FROM devices d
       JOIN companies c
         ON c.id = d.company_id
        AND c.is_active = TRUE
       WHERE d.id_remote = $1
       LIMIT 1`,
      [deviceId],
    );

    res.status(200).json({
      device_id: deviceId,
      auth_token: authToken,
      site_id: enrollmentRow.site_id ?? null,
      company_id: enrollmentRow.company_id,
      branding: branding.rows[0] ?? null,
    });
  } catch (error) {
    try {
      await client.query('ROLLBACK');
    } catch {
      // La transacción pudo haberse cerrado antes.
    }

    console.error('Error en /devices/enroll:', error);
    res.status(500).json({ error: 'Error interno del servidor' });
  } finally {
    client.release();
  }
});

export default router;
