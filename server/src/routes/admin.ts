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
  fileFilter: (_req, file, cb) => cb(null, ['image/png', 'image/jpeg'].includes(file.mimetype)),
});

router.post('/login', async (req: Request, res: Response): Promise<void> => {
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
  if (!user || !(await bcrypt.compare(password ?? '', user.password_hash))) {
    res.status(401).json({ error: 'Usuario o contraseña incorrectos' });
    return;
  }
  const token = jwt.sign(
    { id: user.id, companyId: user.company_id, role: user.role, mustChangePassword: user.must_change_password },
    JWT_SECRET,
    { expiresIn: '8h' },
  );
  res.json({ token, must_change_password: user.must_change_password });
});

router.post('/change-password', userAuthMiddleware, async (req: UserAuthRequest, res: Response): Promise<void> => {
  const { current_password, password } = req.body ?? {};
  if (typeof password !== 'string' || password.length < 8) {
    res.status(400).json({ error: 'La contraseña debe tener al menos 8 caracteres' });
    return;
  }
  const current = await pool.query('SELECT password_hash FROM users WHERE id = $1 AND is_active = TRUE', [req.user!.id]);
  if (!current.rows[0] || !(await bcrypt.compare(current_password ?? '', current.rows[0].password_hash))) {
    res.status(401).json({ error: 'La contraseña actual es incorrecta' });
    return;
  }
  await pool.query('UPDATE users SET password_hash = $1, must_change_password = FALSE WHERE id = $2', [
    await bcrypt.hash(password, 12),
    req.user!.id,
  ]);
  res.json({ changed: true });
});

router.post('/companies', userAuthMiddleware, requirePasswordChangeComplete, requireRole('admin'), upload.single('logo'), async (req: UserAuthRequest, res: Response): Promise<void> => {
  const { name, slug } = req.body ?? {};
  if (!name || !slug) {
    res.status(400).json({ error: 'name y slug son requeridos' });
    return;
  }
  try {
    const logoPath = req.file ? `/api/admin/logos/${req.file.filename}` : null;
    const result = await pool.query(
      'INSERT INTO companies (name, slug, logo_path) VALUES ($1, $2, $3) RETURNING id, name, slug, logo_path',
      [name, slug, logoPath],
    );
    res.status(201).json(result.rows[0]);
  } catch {
    if (req.file) fs.rmSync(req.file.path, { force: true });
    res.status(409).json({ error: 'No fue posible crear la empresa; el slug puede estar duplicado' });
  }
});

router.post('/companies/:companyId/enrollment-codes', userAuthMiddleware, requirePasswordChangeComplete, requireRole('admin'), async (req: UserAuthRequest, res: Response): Promise<void> => {
  const { site_id, expires_in_minutes = 30 } = req.body ?? {};
  const plainCode = crypto.randomBytes(12).toString('hex');
  const codeHash = crypto.createHash('sha256').update(plainCode).digest('hex');
  const minutes = Number(expires_in_minutes);
  const expiresInMinutes = Number.isInteger(minutes) ? Math.min(Math.max(minutes, 5), 1440) : 30;
  const result = await pool.query(
    `INSERT INTO device_enrollment_codes (company_id, site_id, code_hash, expires_at)
     VALUES ($1, $2, $3, NOW() + ($4 * INTERVAL '1 minute'))
     RETURNING id, company_id, site_id, expires_at`,
    [req.params.companyId, site_id ?? null, codeHash, expiresInMinutes],
  );
  res.status(201).json({ ...result.rows[0], enrollment_code: plainCode });
});

router.get('/logos/:filename', (req: Request, res: Response): void => {
  const filename = path.basename(req.params.filename);
  res.sendFile(path.join(uploadDir, filename), (error) => {
    if (error && !res.headersSent) {
      res.status(error.statusCode === 404 ? 404 : 500).json({ error: 'Logo no encontrado' });
    }
  });
});

router.post('/users', userAuthMiddleware, requirePasswordChangeComplete, requireRole('admin'), async (req: UserAuthRequest, res: Response): Promise<void> => {
  const { username, password, full_name, role = 'operator', site_id, employee_code, company_id } = req.body ?? {};
  if (!username || !password || !full_name || !company_id) {
    res.status(400).json({ error: 'username, password, full_name y company_id son requeridos' });
    return;
  }
  if (!['administrator', 'operator'].includes(role)) {
    res.status(400).json({ error: 'Rol inválido' });
    return;
  }
  const company = await pool.query('SELECT id FROM companies WHERE id = $1 AND is_active = TRUE', [company_id]);
  if (!company.rows[0]) {
    res.status(404).json({ error: 'Empresa no encontrada' });
    return;
  }
  if (site_id) {
    const site = await pool.query(
      'SELECT id FROM sites WHERE id = $1 AND company_id = $2',
      [site_id, company_id],
    );
    if (!site.rows[0]) {
      res.status(400).json({ error: 'El sitio no pertenece a la empresa' });
      return;
    }
  }
  try {
    const result = await pool.query(
      `INSERT INTO users (company_id, site_id, username, password_hash, full_name, role, employee_code)
       VALUES ($1, $2, $3, $4, $5, $6, $7)
       RETURNING id, company_id, username, full_name, role, employee_code, must_change_password`,
      [company_id, site_id ?? null, username, await bcrypt.hash(password, 12), full_name, role, employee_code ?? null],
    );
    res.status(201).json(result.rows[0]);
  } catch {
    res.status(409).json({ error: 'No fue posible crear el usuario; el nombre puede estar duplicado' });
  }
});

router.get('/companies/:companyId/branding', async (req: Request, res: Response): Promise<void> => {
  const result = await pool.query(
    'SELECT id, name, slug, logo_path FROM companies WHERE id = $1 AND is_active = TRUE',
    [req.params.companyId],
  );
  if (!result.rows[0]) {
    res.status(404).json({ error: 'Empresa no encontrada' });
    return;
  }
  res.json(result.rows[0]);
});

router.get('/companies', userAuthMiddleware, requirePasswordChangeComplete, requireRole('admin'), async (_req, res) => {
  const result = await pool.query('SELECT id, name, slug, logo_path, is_active FROM companies ORDER BY name');
  res.json(result.rows);
});

router.get('/companies/:companyId/devices', userAuthMiddleware, requirePasswordChangeComplete, async (req: UserAuthRequest, res: Response): Promise<void> => {
  if (req.user?.role !== 'admin' && req.user?.companyId !== req.params.companyId) {
    res.status(403).json({ error: 'No puede consultar dispositivos de otra empresa' });
    return;
  }
  const result = await pool.query(
    `SELECT d.id_remote, d.id_local, d.device_name, d.fingerprint, d.site_id,
            d.created_at, c.name AS company_name
     FROM devices d
     JOIN companies c ON c.id = d.company_id
     WHERE d.company_id = $1
     ORDER BY d.created_at DESC`,
    [req.params.companyId],
  );
  res.json(result.rows);
});

router.get('/companies/:companyId/attendance', userAuthMiddleware, requirePasswordChangeComplete, async (req: UserAuthRequest, res: Response): Promise<void> => {
  if (req.user?.role !== 'admin' && req.user?.companyId !== req.params.companyId) {
    res.status(403).json({ error: 'No puede consultar registros de otra empresa' });
    return;
  }
  const requestedLimit = Number(req.query.limit ?? 100);
  const requestedOffset = Number(req.query.offset ?? 0);
  const limit = Number.isInteger(requestedLimit) ? Math.min(Math.max(requestedLimit, 1), 500) : 100;
  const offset = Number.isInteger(requestedOffset) ? Math.max(requestedOffset, 0) : 0;
  const result = await pool.query(
    `SELECT a.id_remote, a.id_local, a.employee_id, a.event_type, a.occurred_at,
            a.latitude, a.longitude, a.accuracy_m, a.face_confidence,
            a.device_id, a.site_id, c.name AS company_name, c.logo_path
     FROM attendance_records a
     JOIN companies c ON c.id = a.company_id
     WHERE a.company_id = $1
     ORDER BY a.occurred_at DESC
     LIMIT $2 OFFSET $3`,
    [req.params.companyId, limit, offset],
  );
  res.json({ records: result.rows, limit, offset });
});

export default router;