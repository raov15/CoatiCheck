import { Router, Request, Response } from 'express';
import { v4 as uuidv4 } from 'uuid';
import jwt from 'jsonwebtoken';
import pool from '../db/client';
import { authMiddleware, AuthRequest } from '../middleware/auth';

const router = Router();
const JWT_SECRET = process.env.JWT_SECRET ?? 'change_this_secret';
const TOKEN_EXPIRY = '365d';

// POST /api/devices/register
router.post('/register', async (req: Request, res: Response): Promise<void> => {
  const { device_name, device_fingerprint, local_id } = req.body;

  if (!device_name || !device_fingerprint || !local_id) {
    res.status(400).json({ error: 'device_name, device_fingerprint y local_id son requeridos' });
    return;
  }

  try {
    // Upsert: si el dispositivo ya existe (mismo local_id) renueva el token
    const existing = await pool.query(
      'SELECT id_remote FROM devices WHERE id_local = $1',
      [local_id]
    );

    let deviceId: string;

    if (existing.rows.length > 0) {
      deviceId = existing.rows[0].id_remote;
    } else {
      deviceId = uuidv4();
      await pool.query(
        `INSERT INTO devices (id_remote, id_local, device_name, fingerprint)
         VALUES ($1, $2, $3, $4)`,
        [deviceId, local_id, device_name, device_fingerprint]
      );
    }

    const authToken = jwt.sign({ deviceId }, JWT_SECRET, { expiresIn: TOKEN_EXPIRY });

    await pool.query(
      'UPDATE devices SET auth_token = $1 WHERE id_remote = $2',
      [authToken, deviceId]
    );

    res.status(200).json({
      device_id: deviceId,
      auth_token: authToken,
      site_id: null,
    });
  } catch (err) {
    console.error('Error en /devices/register:', err);
    res.status(500).json({ error: 'Error interno del servidor' });
  }
});

// GET /api/devices/verify
router.get('/verify', authMiddleware, (req: AuthRequest, res: Response): void => {
  res.status(200).json({
    valid: true,
    device_id: req.deviceId,
  });
});

// POST /api/devices/refresh-token
router.post('/refresh-token', authMiddleware, async (req: AuthRequest, res: Response): Promise<void> => {
  try {
    const newToken = jwt.sign({ deviceId: req.deviceId }, JWT_SECRET, { expiresIn: TOKEN_EXPIRY });

    await pool.query(
      'UPDATE devices SET auth_token = $1 WHERE id_remote = $2',
      [newToken, req.deviceId]
    );

    res.status(200).json({ auth_token: newToken });
  } catch (err) {
    console.error('Error en /devices/refresh-token:', err);
    res.status(500).json({ error: 'Error interno del servidor' });
  }
});

export default router;
