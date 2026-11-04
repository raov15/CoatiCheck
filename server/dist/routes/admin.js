"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const bcryptjs_1 = __importDefault(require("bcryptjs"));
const jsonwebtoken_1 = __importDefault(require("jsonwebtoken"));
const multer_1 = __importDefault(require("multer"));
const path_1 = __importDefault(require("path"));
const fs_1 = __importDefault(require("fs"));
const crypto_1 = __importDefault(require("crypto"));
const client_1 = __importDefault(require("../db/client"));
const user_auth_1 = require("../middleware/user-auth");
const router = (0, express_1.Router)();
function parseCompanyProfile(value) {
    if (value == null || value === '')
        return {};
    let parsed = value;
    if (typeof value === 'string') {
        try {
            parsed = JSON.parse(value);
        }
        catch {
            throw new Error('Perfil de empresa inválido');
        }
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
        throw new Error('Perfil de empresa inválido');
    const allowed = ['person_type', 'rfc', 'name', 'curp', 'nss', 'business_name', 'trade_name', 'employer_registration', 'postal_code', 'street', 'number', 'latitude', 'longitude', 'radius_m'];
    return Object.fromEntries(allowed.filter(k => Object.prototype.hasOwnProperty.call(parsed, k)).map(k => [k, parsed[k]]));
}
const JWT_SECRET = process.env.JWT_SECRET;
const uploadDir = path_1.default.resolve(process.env.UPLOAD_DIR ?? 'uploads/logos');
fs_1.default.mkdirSync(uploadDir, { recursive: true });
const upload = (0, multer_1.default)({
    dest: uploadDir,
    limits: { fileSize: 2 * 1024 * 1024 },
    fileFilter: (_req, file, cb) => cb(null, ['image/png', 'image/jpeg'].includes(file.mimetype)),
});
router.post('/login', async (req, res) => {
    if (!JWT_SECRET) {
        res.status(500).json({ error: 'JWT_SECRET no está configurado' });
        return;
    }
    const { username, password } = req.body ?? {};
    if (typeof username !== 'string' || typeof password !== 'string') {
        res.status(400).json({ error: 'Usuario y contraseña requeridos' });
        return;
    }
    const result = await client_1.default.query('SELECT id, company_id, role, must_change_password, password_hash FROM users WHERE username = $1 AND is_active = TRUE', [username]);
    const user = result.rows[0];
    if (!user ||
        !(await bcryptjs_1.default.compare(password ?? '', user.password_hash))) {
        res.status(401).json({ error: 'Usuario o contraseña incorrectos' });
        return;
    }
    const token = jsonwebtoken_1.default.sign({
        id: user.id,
        companyId: user.company_id,
        role: user.role,
        mustChangePassword: user.must_change_password,
    }, JWT_SECRET, { expiresIn: '8h' });
    res.json({
        token,
        must_change_password: user.must_change_password,
        role: user.role,
        user: { id: user.id, role: user.role },
    });
});
router.post('/change-password', user_auth_1.userAuthMiddleware, async (req, res) => {
    const { current_password, password } = req.body ?? {};
    if (typeof password !== 'string' || password.length < 8) {
        res.status(400).json({
            error: 'La contraseña debe tener al menos 8 caracteres',
        });
        return;
    }
    const current = await client_1.default.query('SELECT password_hash FROM users WHERE id = $1 AND is_active = TRUE', [req.user.id]);
    if (!current.rows[0] ||
        !(await bcryptjs_1.default.compare(current_password ?? '', current.rows[0].password_hash))) {
        res.status(401).json({
            error: 'La contraseña actual es incorrecta',
        });
        return;
    }
    await client_1.default.query('UPDATE users SET password_hash = $1, must_change_password = FALSE WHERE id = $2', [await bcryptjs_1.default.hash(password, 12), req.user.id]);
    res.json({ changed: true });
});
// Protección global de las rutas de empresa, incluso si una ruta olvida validar permisos.
router.use('/companies/:companyId', async (req, res, next) => {
    // El logo/branding puede consultarse sin sesión, como en el sistema original.
    if (req.method === 'GET' && req.path === '/branding')
        return next();
    (0, user_auth_1.userAuthMiddleware)(req, res, () => {
        if (!req.user || req.user.mustChangePassword || !['admin', 'superadmin'].includes(req.user.role)) {
            res.status(403).json({ error: 'Debe iniciar sesión y completar el cambio de contraseña' });
            return;
        }
        if (!canAccessCompany(req, req.params.companyId)) {
            res.status(403).json({ error: 'No tiene acceso a esta empresa' });
            return;
        }
        next();
    });
});
router.post('/companies', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), upload.single('logo'), async (req, res) => {
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
        const client = await client_1.default.connect();
        let result;
        try {
            await client.query('BEGIN');
            result = await client.query('INSERT INTO companies (name, slug, logo_path, profile) VALUES ($1, $2, $3, $4::jsonb) RETURNING id, name, slug, logo_path, profile', [name, slug, logoPath, JSON.stringify(parseCompanyProfile(req.body?.profile))]);
            if (req.user.role === 'admin') {
                await client.query('INSERT INTO user_companies (user_id, company_id) VALUES ($1, $2)', [req.user.id, result.rows[0].id]);
            }
            await client.query('COMMIT');
        }
        catch (error) {
            await client.query('ROLLBACK');
            throw error;
        }
        finally {
            client.release();
        }
        res.status(201).json(result.rows[0]);
    }
    catch {
        if (req.file) {
            fs_1.default.rmSync(req.file.path, { force: true });
        }
        res.status(409).json({
            error: 'No fue posible crear la empresa; el slug puede estar duplicado',
        });
    }
});
/* Editar datos básicos y logotipo de una empresa sin afectar sus trabajadores. */
router.patch('/companies/:companyId', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), upload.single('logo'), async (req, res) => {
    const { companyId } = req.params;
    const name = typeof req.body?.name === 'string' ? req.body.name.trim() : '';
    const slug = typeof req.body?.slug === 'string' ? req.body.slug.trim() : '';
    if (!name || !slug) {
        if (req.file)
            fs_1.default.rmSync(req.file.path, { force: true });
        res.status(400).json({ error: 'El nombre y el identificador son obligatorios' });
        return;
    }
    try {
        const logoPath = req.file ? `/api/admin/logos/${req.file.filename}` : null;
        const profile = req.body?.profile == null ? null : JSON.stringify(parseCompanyProfile(req.body.profile));
        const result = await client_1.default.query(`UPDATE companies
         SET name = $1, slug = $2, logo_path = COALESCE($3, logo_path),
             profile = CASE WHEN $5::jsonb IS NULL THEN profile ELSE COALESCE(profile, '{}'::jsonb) || $5::jsonb END
         WHERE id = $4 AND is_active = TRUE
         RETURNING id, name, slug, logo_path, profile`, [name, slug, logoPath, companyId, profile]);
        if (!result.rows[0]) {
            if (req.file)
                fs_1.default.rmSync(req.file.path, { force: true });
            res.status(404).json({ error: 'Empresa no encontrada' });
            return;
        }
        res.json(result.rows[0]);
    }
    catch (error) {
        if (req.file)
            fs_1.default.rmSync(req.file.path, { force: true });
        res.status(error?.code === '23505' ? 409 : 500).json({
            error: error?.code === '23505' ? 'El identificador ya está en uso' : 'No se pudo actualizar la empresa',
        });
    }
});
/* Desactivar empresa sin borrar su historial ni sus trabajadores. */
router.delete('/companies/:companyId', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    try {
        const result = await client_1.default.query(`UPDATE companies SET is_active = FALSE
         WHERE id = $1 AND is_active = TRUE
         RETURNING id, name`, [req.params.companyId]);
        if (!result.rows[0]) {
            res.status(404).json({ error: 'Empresa no encontrada o ya desactivada' });
            return;
        }
        res.json({ deleted: true, company: result.rows[0] });
    }
    catch (error) {
        console.error('Error desactivando empresa:', error);
        res.status(500).json({ error: 'No fue posible desactivar la empresa' });
    }
});
router.post('/companies/:companyId/enrollment-codes', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { expires_in_minutes = 30 } = req.body ?? {};
    const plainCode = crypto_1.default.randomBytes(12).toString('hex');
    const codeHash = crypto_1.default
        .createHash('sha256')
        .update(plainCode)
        .digest('hex');
    const minutes = Number(expires_in_minutes);
    const expiresInMinutes = Number.isInteger(minutes)
        ? Math.min(Math.max(minutes, 5), 1440)
        : 30;
    // Buscar automáticamente el sitio de esta empresa
    const siteResult = await client_1.default.query(`
        SELECT s.id
        FROM sites s
        WHERE s.company_id = $1
        ORDER BY s.created_at ASC
        LIMIT 1
      `, [req.params.companyId]);
    const siteId = siteResult.rows[0]?.id ?? null;
    if (!siteId) {
        res.status(400).json({
            error: 'La empresa no tiene una ubicación configurada',
        });
        return;
    }
    const result = await client_1.default.query(`
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
      `, [
        req.params.companyId,
        siteId,
        codeHash,
        expiresInMinutes,
    ]);
    res.status(201).json({
        ...result.rows[0],
        enrollment_code: plainCode,
    });
});
router.post('/companies/:companyId/sites', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede crear centros para otra empresa',
        });
        return;
    }
    const { name, site_key = null, postal_code = null, road_type = null, street = null, street_number = null, interior_number = null, neighborhood = null, locality = null, municipality = null, state = null, between_streets = null, reference_street = null, observations = null, latitude = null, longitude = null, } = req.body ?? {};
    if (typeof name !== 'string' || !name.trim()) {
        res.status(400).json({
            error: 'El nombre del sitio es requerido',
        });
        return;
    }
    const company = await client_1.default.query('SELECT id FROM companies WHERE id = $1 AND is_active = TRUE', [companyId]);
    if (!company.rows[0]) {
        res.status(404).json({
            error: 'Empresa no encontrada',
        });
        return;
    }
    const lat = latitude === '' || latitude == null
        ? null
        : Number(latitude);
    const lng = longitude === '' || longitude == null
        ? null
        : Number(longitude);
    if (lat !== null &&
        (!Number.isFinite(lat) || lat < -90 || lat > 90)) {
        res.status(400).json({
            error: 'Latitud inválida',
        });
        return;
    }
    if (lng !== null &&
        (!Number.isFinite(lng) || lng < -180 || lng > 180)) {
        res.status(400).json({
            error: 'Longitud inválida',
        });
        return;
    }
    try {
        const result = await client_1.default.query(`INSERT INTO sites (
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
           $14, $15, $16, $17, 300, NOW()
         )
         RETURNING *`, [
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
        ]);
        res.status(201).json(result.rows[0]);
    }
    catch (error) {
        console.error('Error creando centro de trabajo:', error);
        res.status(500).json({
            error: 'No fue posible crear el centro de trabajo',
            detail: error instanceof Error ? error.message : String(error),
        });
    }
});
router.get('/companies/:companyId/sites', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    if (!canAccessCompany(req, req.params.companyId)) {
        res.status(403).json({
            error: 'No puede consultar sitios de otra empresa',
        });
        return;
    }
    const result = await client_1.default.query(`SELECT id, company_id, name, site_key, postal_code, road_type, street, street_number,
              interior_number, neighborhood, locality, municipality, state, between_streets,
              reference_street, observations, latitude, longitude, radius_m, created_at, updated_at
       FROM sites
       WHERE company_id = $1
       ORDER BY name`, [req.params.companyId]);
    res.json(result.rows);
});
router.get('/logos/:filename', (req, res) => {
    const filename = path_1.default.basename(req.params.filename);
    res.sendFile(path_1.default.join(uploadDir, filename), (error) => {
        if (error && !res.headersSent) {
            const statusCode = 'statusCode' in error &&
                typeof error.statusCode === 'number'
                ? error.statusCode
                : 500;
            res
                .status(statusCode === 404 ? 404 : 500)
                .json({
                error: 'Logo no encontrado',
            });
        }
    });
});
// Solo el superadministrador crea cuentas de clientes, sin empresa inicial.
router.post('/users', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (req, res) => {
    const { username, password, full_name } = req.body ?? {};
    if (typeof username !== 'string' || !/^[a-zA-Z0-9_.-]{3,80}$/.test(username) ||
        typeof password !== 'string' || password.length < 12 ||
        typeof full_name !== 'string' || !full_name.trim()) {
        res.status(400).json({ error: 'Usuario (3-80 caracteres), nombre y contraseña temporal de mínimo 12 caracteres requeridos' });
        return;
    }
    try {
        const result = await client_1.default.query(`INSERT INTO users (username, password_hash, full_name, role, company_id, site_id, must_change_password, is_active)
         VALUES ($1,$2,$3,'admin',NULL,NULL,TRUE,TRUE)
         RETURNING id, username, full_name, role, must_change_password, is_active`, [username.trim(), await bcryptjs_1.default.hash(password, 12), full_name.trim()]);
        res.status(201).json(result.rows[0]);
    }
    catch (error) {
        res.status(error?.code === '23505' ? 409 : 500).json({ error: 'No se pudo crear la cuenta' });
    }
});
router.get('/superadmin/users', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (_req, res) => {
    try {
        const result = await client_1.default.query(`
        SELECT id, username, full_name AS "fullName", is_active AS "isActive"
        FROM users WHERE role = 'admin' ORDER BY created_at DESC
      `);
        res.json({ users: result.rows });
    }
    catch (error) {
        console.error('Error consultando administradores:', error);
        res.status(500).json({ error: 'No fue posible consultar los administradores' });
    }
});
router.post('/superadmin/users', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (req, res) => {
    const username = req.body?.username;
    const fullName = req.body?.fullName;
    if (typeof username !== 'string' || !/^[a-zA-Z0-9_.-]{3,80}$/.test(username) ||
        typeof fullName !== 'string' || !fullName.trim()) {
        res.status(400).json({ error: 'Usuario y nombre válidos requeridos' });
        return;
    }
    const temporaryPassword = crypto_1.default.randomBytes(18).toString('base64url');
    try {
        const result = await client_1.default.query(`
        INSERT INTO users (username, password_hash, full_name, role, company_id, site_id, must_change_password, is_active)
        VALUES ($1,$2,$3,'admin',NULL,NULL,TRUE,TRUE)
        RETURNING username
      `, [username.trim(), await bcryptjs_1.default.hash(temporaryPassword, 12), fullName.trim()]);
        res.status(201).json({ username: result.rows[0].username, temporaryPassword });
    }
    catch (error) {
        res.status(error?.code === '23505' ? 409 : 500).json({
            error: error?.code === '23505' ? 'Ese nombre de usuario ya existe' : 'No fue posible crear la cuenta'
        });
    }
});
router.patch('/superadmin/users/:userId', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (req, res) => {
    if (typeof req.body?.isActive !== 'boolean') {
        res.status(400).json({ error: 'isActive debe ser booleano' });
        return;
    }
    const result = await client_1.default.query(`UPDATE users SET is_active=$1
      WHERE id=$2 AND role='admin' RETURNING id, username, is_active AS "isActive"`, [req.body.isActive, req.params.userId]);
    if (!result.rowCount) {
        res.status(404).json({ error: 'Administrador no encontrado' });
        return;
    }
    res.json(result.rows[0]);
});
router.post('/superadmin/users/:userId/reset-password', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (req, res) => {
    const temporaryPassword = crypto_1.default.randomBytes(18).toString('base64url');
    const result = await client_1.default.query(`UPDATE users SET password_hash=$1, must_change_password=TRUE
      WHERE id=$2 AND role='admin' RETURNING id, username`, [await bcryptjs_1.default.hash(temporaryPassword, 12), req.params.userId]);
    if (!result.rowCount) {
        res.status(404).json({ error: 'Administrador no encontrado' });
        return;
    }
    res.json({ username: result.rows[0].username, temporaryPassword });
});
router.get('/users', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (_req, res) => {
    const result = await client_1.default.query(`SELECT id, username, full_name, role, is_active, must_change_password, created_at
       FROM users ORDER BY created_at DESC`);
    res.json(result.rows);
});
router.patch('/users/:userId/status', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (req, res) => {
    if (req.user.id === req.params.userId) {
        res.status(400).json({ error: 'No puede desactivar su propia cuenta' });
        return;
    }
    if (typeof req.body?.is_active !== 'boolean') {
        res.status(400).json({ error: 'is_active debe ser booleano' });
        return;
    }
    const result = await client_1.default.query(`UPDATE users SET is_active=$1 WHERE id=$2 AND role='admin'
       RETURNING id, username, is_active`, [req.body.is_active, req.params.userId]);
    if (!result.rowCount) {
        res.status(404).json({ error: 'Administrador no encontrado' });
        return;
    }
    res.json(result.rows[0]);
});
router.post('/users/:userId/reset-password', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (req, res) => {
    if (req.user.id === req.params.userId) {
        res.status(400).json({ error: 'Use cambiar contraseña para su cuenta' });
        return;
    }
    const temporaryPassword = crypto_1.default.randomBytes(18).toString('base64url');
    const result = await client_1.default.query(`UPDATE users SET password_hash=$1, must_change_password=TRUE
       WHERE id=$2 AND role='admin'
       RETURNING id, username, must_change_password`, [await bcryptjs_1.default.hash(temporaryPassword, 12), req.params.userId]);
    if (!result.rowCount) {
        res.status(404).json({ error: 'Administrador no encontrado' });
        return;
    }
    res.json({ ...result.rows[0], temporary_password: temporaryPassword });
});
// Listado global exclusivo para el panel de superadministración.
router.get('/superadmin/companies', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('superadmin'), async (_req, res) => {
    try {
        const result = await client_1.default.query(`
        SELECT c.id, c.name, c.is_active AS "isActive",
               COALESCE(string_agg(DISTINCT u.username, ', '), '') AS "ownerUsername"
        FROM companies c
        LEFT JOIN user_companies uc ON uc.company_id = c.id
        LEFT JOIN users u ON u.id = uc.user_id AND u.role = 'admin'
        GROUP BY c.id, c.name, c.is_active
        ORDER BY c.name, c.id
      `);
        res.json({ companies: result.rows });
    }
    catch (error) {
        console.error('Error consultando empresas de superadmin:', error);
        res.status(500).json({ error: 'No fue posible consultar las empresas' });
    }
});
router.get('/companies/:companyId/branding', async (req, res) => {
    const result = await client_1.default.query('SELECT id, name, slug, logo_path FROM companies WHERE id = $1 AND is_active = TRUE', [req.params.companyId]);
    if (!result.rows[0]) {
        res.status(404).json({
            error: 'Empresa no encontrada',
        });
        return;
    }
    res.json(result.rows[0]);
});
router.get('/companies', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const result = await client_1.default.query(`SELECT c.id, c.name, c.slug, c.logo_path, c.is_active, c.profile
       FROM companies c
       WHERE c.is_active = TRUE
         AND ($1::text = 'superadmin' OR EXISTS (
           SELECT 1 FROM user_companies uc
           WHERE uc.company_id = c.id AND uc.user_id = $2
         ))
       ORDER BY c.name`, [req.user.role, req.user.id]);
    res.json(result.rows);
});
router.get('/companies/:companyId/devices', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    if (!canAccessCompany(req, req.params.companyId)) {
        res.status(403).json({
            error: 'No puede consultar dispositivos de otra empresa',
        });
        return;
    }
    const result = await client_1.default.query(`
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
      `, [req.params.companyId]);
    res.json(result.rows);
});
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
router.delete('/companies/:companyId/employees/:employeeCode', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId, employeeCode } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede eliminar trabajadores de otra empresa',
        });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const employeeResult = await client.query(`SELECT
           id_remote,
           id_local,
           employee_code,
           full_name
         FROM employees
         WHERE company_id = $1
           AND employee_code = $2
         LIMIT 1
         FOR UPDATE`, [companyId, employeeCode]);
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
        await client.query(`UPDATE devices
         SET employee_id = NULL,
             updated_at = NOW()
         WHERE company_id = $1
           AND employee_id = $2`, [companyId, employee.id_remote]);
        /*
         * Eliminar configuración por día del trabajador.
         */
        await client.query('DELETE FROM employee_day_categories WHERE employee_id = $1', [employee.id_remote]);
        await client.query(`DELETE FROM employee_work_sites
         WHERE employee_id = $1`, [employee.id_remote]);
        /*
         * Eliminar incidencias calificadas asociadas a sus
         * registros de asistencia.
         *
         * Normalmente attendance_records.employee_id guarda id_local.
         * Incluimos también id_remote por compatibilidad con
         * registros antiguos.
         */
        await client.query(`DELETE FROM qualified_incidents
         WHERE company_id = $1
           AND employee_id = $2`, [companyId, employee.id_remote]);
        /*
         * Eliminar asistencias.
         */
        await client.query(`DELETE FROM attendance_records
         WHERE company_id = $1
           AND (
             employee_id = $2
             OR employee_id = $3
             OR employee_id = $4
           )`, [
            companyId,
            employee.id_local,
            employee.id_remote,
            employee.employee_code,
        ]);
        /*
         * Finalmente eliminar el empleado.
         */
        await client.query(`DELETE FROM employees
         WHERE company_id = $1
           AND id_remote = $2`, [companyId, employee.id_remote]);
        await client.query('COMMIT');
        res.json({
            deleted: true,
            employee_code: employee.employee_code,
            employee_name: employee.full_name,
        });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error eliminando trabajador:', error);
        const detail = error instanceof Error
            ? error.message
            : String(error);
        res.status(500).json({
            error: 'No fue posible eliminar el trabajador',
            detail,
        });
    }
    finally {
        client.release();
    }
});
/* Ausencias automáticas: 10:00 para turnos hasta las 10; otros turnos +2 h.
   Ejecutar cada minuto y al arrancar. El índice único evita duplicados. */
async function processAutomaticAbsences() {
    await client_1.default.query(`
    WITH day_context AS (
      SELECT (NOW() AT TIME ZONE 'America/Mexico_City')::date AS today,
             (NOW() AT TIME ZONE 'America/Mexico_City')::time AS local_time
    ), candidates AS (
      SELECT e.company_id,e.id_remote AS employee_id,
             COALESCE(edc.category_id,e.category_id) AS category_id,
             dc.today AS incident_date,
             COALESCE(cw.start_time,c.start_time) AS start_time
      FROM employees e CROSS JOIN day_context dc
      LEFT JOIN employee_day_categories edc ON edc.employee_id=e.id_remote
        AND edc.weekday IN (EXTRACT(ISODOW FROM dc.today)::int,
          CASE WHEN EXTRACT(ISODOW FROM dc.today)::int=7 THEN 0 ELSE -1 END)
      JOIN categories c ON c.id=COALESCE(edc.category_id,e.category_id)
      JOIN category_workdays cw ON cw.category_id=c.id
        AND cw.weekday IN (EXTRACT(ISODOW FROM dc.today)::int,
          CASE WHEN EXTRACT(ISODOW FROM dc.today)::int=7 THEN 0 ELSE -1 END)
      WHERE e.is_active=TRUE AND cw.is_workday=TRUE
        AND (e.hire_date IS NULL OR e.hire_date::date<=dc.today)
        AND NOT EXISTS (SELECT 1 FROM category_rest_days rd WHERE rd.category_id=c.id
          AND rd.weekday IN (EXTRACT(ISODOW FROM dc.today)::int,
            CASE WHEN EXTRACT(ISODOW FROM dc.today)::int=7 THEN 0 ELSE -1 END))
        AND NOT EXISTS (SELECT 1 FROM employee_vacations v
          WHERE v.employee_id=e.id_remote AND v.company_id=e.company_id
            AND dc.today BETWEEN v.start_date AND v.end_date)
        AND NOT EXISTS (SELECT 1 FROM qualified_incidents q
          WHERE q.employee_id=e.id_remote AND q.company_id=e.company_id
            AND q.incident_date=dc.today AND q.concept IN
              ('Permiso médico justificado','Falta justificada','Permiso de salida'))
    )
    INSERT INTO attendance_auto_incidents
      (company_id,employee_id,category_id,incident_date,concept,origin,notes)
    SELECT c.company_id,c.employee_id,c.category_id,c.incident_date,
      'Ausentismo','ABSENCE','Sin registro de entrada al cumplirse el plazo'
    FROM candidates c CROSS JOIN day_context dc
    WHERE c.start_time ~ '^([01]?[0-9]|2[0-3]):[0-5][0-9]'
      AND dc.local_time >= CASE
        WHEN c.start_time::time <= TIME '10:00' THEN TIME '10:00'
        ELSE (c.start_time::time + INTERVAL '2 hours')::time END
      AND NOT EXISTS (SELECT 1 FROM attendance_records a
        JOIN employees e2 ON e2.id_remote=c.employee_id
        WHERE a.company_id=c.company_id AND a.event_type='CLOCK_IN'
          AND a.employee_id IN (e2.id_local,e2.id_remote::text,e2.employee_code)
          AND (to_timestamp(a.occurred_at/1000.0) AT TIME ZONE 'America/Mexico_City')::date=c.incident_date)
    ON CONFLICT DO NOTHING
  `);
}
let absenceJobRunning = false;
async function runAbsenceJob() {
    if (absenceJobRunning)
        return;
    absenceJobRunning = true;
    try {
        await processAutomaticAbsences();
    }
    catch (error) {
        console.error('Error generando ausentismo automático:', error);
    }
    finally {
        absenceJobRunning = false;
    }
}
const absenceInterval = setInterval(() => { void runAbsenceJob(); }, 60000);
absenceInterval.unref();
void runAbsenceJob();
router.get('/companies/:companyId/automatic-incidents', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    if (!canAccessCompany(req, req.params.companyId)) {
        res.status(403).json({ error: 'Acceso denegado' });
        return;
    }
    const status = String(req.query.status ?? 'pending');
    if (!['pending', 'reviewed', 'all'].includes(status)) {
        res.status(400).json({ error: 'Estado inválido' });
        return;
    }
    try {
        const data = await client_1.default.query(`SELECT ai.*,e.employee_code,e.full_name AS employee_name,
        cat.name AS category_name
        FROM attendance_auto_incidents ai
        JOIN employees e ON e.id_remote=ai.employee_id
        LEFT JOIN categories cat ON cat.id=ai.category_id
        WHERE ai.company_id=$1 AND ($2='all' OR ai.status=$2)
        ORDER BY ai.incident_date DESC,ai.created_at DESC LIMIT 500`, [req.params.companyId, status]);
        res.json({ records: data.rows });
    }
    catch (error) {
        console.error(error);
        res.status(500).json({ error: 'No se pudieron consultar incidencias automáticas' });
    }
});
router.post('/companies/:companyId/automatic-incidents/:incidentId/review', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    if (!canAccessCompany(req, req.params.companyId)) {
        res.status(403).json({ error: 'Acceso denegado' });
        return;
    }
    const notes = String(req.body?.notes ?? '').trim();
    if (!notes || notes.length > 4000) {
        res.status(400).json({ error: 'Escribe observaciones (máximo 4000 caracteres)' });
        return;
    }
    try {
        const result = await client_1.default.query(`UPDATE attendance_auto_incidents
        SET notes=$3,status='reviewed',reviewed_at=NOW(),reviewed_by=$4
        WHERE company_id=$1 AND id=$2 AND status='pending' RETURNING *`, [req.params.companyId, req.params.incidentId, notes, req.user.id]);
        if (!result.rowCount) {
            res.status(404).json({ error: 'Incidencia no encontrada o ya revisada' });
            return;
        }
        res.json({ saved: true, record: result.rows[0] });
    }
    catch (error) {
        console.error(error);
        res.status(500).json({ error: 'No se pudo guardar la revisión' });
    }
});
function canAccessCompany(req, companyId) {
    if (!req.user)
        return false;
    if (req.user.role === 'superadmin')
        return true;
    return req.user.role === 'admin' &&
        Array.isArray(req.user.companyIds) && req.user.companyIds.includes(companyId);
}
function normalizeWeekday(value) {
    const weekday = Number(value);
    if (!Number.isInteger(weekday) ||
        weekday < 0 ||
        weekday > 7) {
        return null;
    }
    return weekday;
}
const VALID_CONCEPTS = [
    'Ausentismo',
    'Retardo',
    'Permiso médico justificado',
    'Permiso médico no justificado',
    'Falta justificada',
    'Salida no autorizada',
    'Salida autorizada',
    'Tiempo extraordinario',
    'Historial',
];
router.get('/companies/:companyId/employees', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar trabajadores de otra empresa',
        });
        return;
    }
    const result = await client_1.default.query(`SELECT
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
         e.employee_code`, [companyId]);
    res.json(result.rows);
});
router.get('/companies/:companyId/categories/:categoryNumber/config', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, categoryNumber } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar categorías de otra empresa',
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
    const categoryResult = await client_1.default.query(`SELECT
         id,
         company_id,
         category_number,
         name,
         start_time,
         end_time,
         tolerance_minutes,
         meal_start_time,
         meal_end_time,
         rest_start_time,
         rest_duration_minutes,
         meal_duration_minutes,
         overtime_allowed,
         overtime_value
       FROM categories
       WHERE company_id = $1
         AND category_number = $2
       LIMIT 1`, [companyId, number]);
    const category = categoryResult.rows[0];
    if (!category) {
        res.status(404).json({
            error: 'Categoría no encontrada',
        });
        return;
    }
    const [workdaysResult, restDaysResult, incidenceValuesResult,] = await Promise.all([
        client_1.default.query(`SELECT
           weekday,
           is_workday,
           start_time,
           end_time
         FROM category_workdays
         WHERE category_id = $1
         ORDER BY weekday`, [category.id]),
        client_1.default.query(`SELECT weekday
         FROM category_rest_days
         WHERE category_id = $1
         ORDER BY weekday`, [category.id]),
        client_1.default.query(`SELECT
           concept,
           value
         FROM category_incidence_values
         WHERE category_id = $1
         ORDER BY concept`, [category.id]),
    ]);
    res.json({
        ...category,
        workdays: workdaysResult.rows,
        rest_days: restDaysResult.rows.map((row) => row.weekday),
        incidence_values: incidenceValuesResult.rows,
    });
});
router.put('/companies/:companyId/categories/:categoryNumber/config', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId, categoryNumber } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede modificar categorías de otra empresa',
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
    const { name = `Categoría ${number}`, start_time = null, end_time = null, tolerance_minutes = 0, overtime_allowed = false, overtime_value = null, workdays = [], rest_days = [], incidence_values = [], } = req.body ?? {};
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
            error: 'incidence_values debe ser un arreglo',
        });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const categoryResult = await client.query(`INSERT INTO categories (
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
         RETURNING *`, [
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
        ]);
        const category = categoryResult.rows[0];
        await client.query(`DELETE FROM category_rest_days
         WHERE category_id = $1`, [category.id]);
        for (const rawWeekday of rest_days) {
            const weekday = normalizeWeekday(rawWeekday);
            if (weekday === null)
                continue;
            await client.query(`INSERT INTO category_rest_days (
             category_id,
             weekday
           )
           VALUES ($1, $2)
           ON CONFLICT (
             category_id,
             weekday
           )
           DO NOTHING`, [category.id, weekday]);
        }
        await client.query(`DELETE FROM category_workdays
         WHERE category_id = $1`, [category.id]);
        for (const item of workdays) {
            const weekday = normalizeWeekday(item?.weekday);
            if (weekday === null)
                continue;
            await client.query(`INSERT INTO category_workdays (
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
             updated_at = NOW()`, [
                category.id,
                weekday,
                Boolean(item?.is_workday),
                item?.start_time ?? null,
                item?.end_time ?? null,
            ]);
        }
        await client.query(`DELETE FROM category_incidence_values
         WHERE category_id = $1`, [category.id]);
        for (const item of incidence_values) {
            const concept = String(item?.concept ?? '').trim();
            const value = Number(item?.value);
            if (!VALID_CONCEPTS.includes(concept) ||
                !Number.isFinite(value)) {
                continue;
            }
            await client.query(`INSERT INTO category_incidence_values (
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
             updated_at = NOW()`, [
                category.id,
                concept,
                value,
            ]);
        }
        await client.query('COMMIT');
        res.json({
            saved: true,
            category_id: category.id,
            category_number: number,
        });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error guardando categoría:', error);
        const detail = error instanceof Error
            ? error.message
            : String(error);
        res.status(500).json({
            error: 'No fue posible guardar la categoría',
            detail,
        });
    }
    finally {
        client.release();
    }
});
router.get('/companies/:companyId/foreign-locations', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar recorridos de otra empresa',
        });
        return;
    }
    const employeeCode = typeof req.query.employeeCode === 'string'
        ? req.query.employeeCode.trim()
        : '';
    const date = typeof req.query.date === 'string'
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
        const values = [companyId];
        const conditions = [
            'lp.company_id = $1',
        ];
        if (employeeCode) {
            values.push(employeeCode);
            conditions.push(`e.employee_code = $${values.length}`);
        }
        if (date) {
            values.push(date);
            conditions.push(`lp.work_date = $${values.length}::date`);
        }
        values.push(limit);
        const limitParam = `$${values.length}`;
        const result = await client_1.default.query(`
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
  `, values);
        res.json({
            records: result.rows,
            count: result.rows.length,
        });
    }
    catch (error) {
        console.error('Error consultando recorrido foráneo:', error);
        res.status(500).json({
            error: 'No se pudieron consultar los puntos GPS foráneos',
        });
    }
});
router.patch('/companies/:companyId/sites/:siteId', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, siteId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede modificar centros de otra empresa',
        });
        return;
    }
    const { name, site_key = null, postal_code = null, road_type = null, street = null, street_number = null, interior_number = null, neighborhood = null, locality = null, municipality = null, state = null, between_streets = null, reference_street = null, observations = null, latitude = null, longitude = null, } = req.body ?? {};
    const lat = latitude === '' || latitude == null
        ? null
        : Number(latitude);
    const lng = longitude === '' || longitude == null
        ? null
        : Number(longitude);
    if (lat !== null &&
        (!Number.isFinite(lat) || lat < -90 || lat > 90)) {
        res.status(400).json({
            error: 'Latitud inválida',
        });
        return;
    }
    if (lng !== null &&
        (!Number.isFinite(lng) || lng < -180 || lng > 180)) {
        res.status(400).json({
            error: 'Longitud inválida',
        });
        return;
    }
    const result = await client_1.default.query(`UPDATE sites
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
       RETURNING *`, [
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
    ]);
    if (!result.rows[0]) {
        res.status(404).json({
            error: 'Centro de trabajo no encontrado',
        });
        return;
    }
    res.json(result.rows[0]);
});
router.get('/companies/:companyId/categories', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar categorías de otra empresa',
        });
        return;
    }
    const categories = await client_1.default.query(`SELECT
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
       ORDER BY category_number`, [companyId]);
    const categoryIds = categories.rows.map((row) => row.id);
    if (categoryIds.length === 0) {
        res.json([]);
        return;
    }
    const [workdays, restDays, values] = await Promise.all([
        client_1.default.query(`SELECT
             category_id,
             weekday,
             is_workday,
             start_time,
             end_time
           FROM category_workdays
           WHERE category_id = ANY($1::uuid[])
           ORDER BY weekday`, [categoryIds]),
        client_1.default.query(`SELECT
             category_id,
             weekday
           FROM category_rest_days
           WHERE category_id = ANY($1::uuid[])
           ORDER BY weekday`, [categoryIds]),
        client_1.default.query(`SELECT
             category_id,
             concept,
             value
           FROM category_incidence_values
           WHERE category_id = ANY($1::uuid[])
           ORDER BY concept`, [categoryIds]),
    ]);
    res.json(categories.rows.map((category) => ({
        ...category,
        workdays: workdays.rows.filter((row) => row.category_id === category.id),
        rest_days: restDays.rows
            .filter((row) => row.category_id === category.id)
            .map((row) => row.weekday),
        incidence_values: values.rows.filter((row) => row.category_id === category.id),
    })));
});
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
router.post('/companies/:companyId/categories', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede crear categorías para otra empresa',
        });
        return;
    }
    const requestedName = typeof req.body?.name === 'string'
        ? req.body.name.trim()
        : '';
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        // Bloquea por empresa para evitar que dos altas
        // reciban el mismo número.
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`categories:${companyId}`]);
        const next = await client.query(`SELECT
           COALESCE(MAX(category_number), 0) + 1
             AS next_number
         FROM categories
         WHERE company_id = $1`, [companyId]);
        const categoryNumber = Number(next.rows[0].next_number);
        const name = requestedName ||
            `Categoría ${categoryNumber}`;
        const category = await client.query(`INSERT INTO categories (
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
         RETURNING *`, [
            companyId,
            categoryNumber,
            name,
        ]);
        const concepts = [
            'Ausentismo',
            'Retardo',
            'Permiso médico justificado',
            'Permiso médico no justificado',
            'Falta justificada',
            'Salida no autorizada',
            'Salida autorizada',
        ];
        for (const concept of concepts) {
            await client.query(`INSERT INTO category_incidence_values (
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
           DO NOTHING`, [
                category.rows[0].id,
                concept,
            ]);
        }
        await client.query('COMMIT');
        res.status(201).json(category.rows[0]);
    }
    catch (error) {
        await client.query('ROLLBACK');
        const detail = error instanceof Error
            ? error.message
            : String(error);
        res
            .status(error?.code === '23505'
            ? 409
            : 500)
            .json({
            error: 'No fue posible crear la categoría',
            detail,
        });
    }
    finally {
        client.release();
    }
});
router.delete('/companies/:companyId/categories/:categoryNumber', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'No puede eliminar categorías de otra empresa' });
        return;
    }
    const number = Number(req.params.categoryNumber);
    if (!Number.isInteger(number) || number < 1) {
        res.status(400).json({ error: 'Número de categoría inválido' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const found = await client.query(`SELECT id, name FROM categories WHERE company_id=$1 AND category_number=$2 FOR UPDATE`, [companyId, number]);
        const category = found.rows[0];
        if (!category) {
            await client.query('ROLLBACK');
            res.status(404).json({ error: 'Categoría no encontrada' });
            return;
        }
        // No permitir borrar categorías usadas como principal o en cualquier día.
        const assigned = await client.query(`SELECT
           (SELECT COUNT(*)::int FROM employees
            WHERE company_id=$1 AND category_id=$2) AS principal,
           (SELECT COUNT(*)::int FROM employee_day_categories edc
            JOIN employees e ON e.id_remote=edc.employee_id
            WHERE e.company_id=$1 AND edc.category_id=$2) AS semanal`, [companyId, category.id]);
        const principal = Number(assigned.rows[0]?.principal || 0);
        const semanal = Number(assigned.rows[0]?.semanal || 0);
        if (principal > 0 || semanal > 0) {
            await client.query('ROLLBACK');
            res.status(409).json({
                error: 'No puedes eliminar esta categoría porque tiene trabajadores asignados, ya sea como categoría principal o en algún día de la semana.',
                assigned_principal: principal,
                assigned_weekly: semanal,
            });
            return;
        }
        await client.query('DELETE FROM category_workdays WHERE category_id=$1', [category.id]);
        await client.query('DELETE FROM category_rest_days WHERE category_id=$1', [category.id]);
        await client.query('DELETE FROM category_incidence_values WHERE category_id=$1', [category.id]);
        await client.query('DELETE FROM categories WHERE id=$1', [category.id]);
        // Renumerar sin cambiar los UUID usados por trabajadores y configuraciones.
        // Dos fases para no chocar con UNIQUE(company_id, category_number).
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`categories:${companyId}`]);
        const remaining = await client.query('SELECT id, category_number FROM categories WHERE company_id=$1 ORDER BY category_number, id FOR UPDATE', [companyId]);
        const maxNumber = Math.max(number, ...remaining.rows.map((r) => Number(r.category_number)));
        const offset = maxNumber + remaining.rows.length + 100;
        for (let i = 0; i < remaining.rows.length; i++) {
            await client.query('UPDATE categories SET category_number=$1 WHERE id=$2', [offset + i, remaining.rows[i].id]);
        }
        for (let i = 0; i < remaining.rows.length; i++) {
            await client.query('UPDATE categories SET category_number=$1 WHERE id=$2', [i + 1, remaining.rows[i].id]);
        }
        await client.query('COMMIT');
        res.json({ deleted: true, category_number: number, name: category.name,
            employees_without_category: 0 });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error eliminando categoría:', error);
        res.status(500).json({ error: 'No fue posible eliminar la categoría',
            detail: error instanceof Error ? error.message : String(error) });
    }
    finally {
        client.release();
    }
});
router.put('/companies/:companyId/categories/:categoryNumber', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede modificar categorías de otra empresa',
        });
        return;
    }
    const categoryNumber = Number(req.params.categoryNumber);
    if (!Number.isInteger(categoryNumber) ||
        categoryNumber < 1) {
        res.status(400).json({
            error: 'Número de categoría inválido',
        });
        return;
    }
    const { name, start_time = null, end_time = null, meal_start_time = null, meal_end_time = null, rest_start_time = null, rest_duration_minutes = 30, meal_duration_minutes = 60, tolerance_minutes = 10, overtime_allowed = true, overtime_value = null, rest_days = [], workdays = [], incidence_values = [], } = req.body ?? {};
    const tolerance = Number(tolerance_minutes);
    if (!Number.isInteger(tolerance) ||
        tolerance < 0) {
        res.status(400).json({
            error: 'Tolerancia inválida',
        });
        return;
    }
    const overtimeAllowed = Boolean(overtime_allowed);
    const overtimeValue = overtimeAllowed
        ? null
        : Number(overtime_value);
    if (!overtimeAllowed &&
        !Number.isFinite(overtimeValue)) {
        res.status(400).json({
            error: 'Si Tiempo extraordinario = No, debe indicar un valor',
        });
        return;
    }
    if (!Array.isArray(rest_days) ||
        !Array.isArray(workdays) ||
        !Array.isArray(incidence_values)) {
        res.status(400).json({
            error: 'Configuración de categoría inválida',
        });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const categoryResult = await client.query(`UPDATE categories
           SET name =
                 COALESCE(
                   NULLIF(TRIM($1), ''),
                   name
                 ),
               start_time = $2,
               end_time = $3,
               meal_start_time = $4,
               meal_end_time = $5,
               rest_start_time = $11,
               rest_duration_minutes = $12,
               meal_duration_minutes = $13,
               tolerance_minutes = $6,
               overtime_allowed = $7,
               overtime_value = $8,
               updated_at = NOW(),
               configured_at = NOW()
           WHERE company_id = $9
             AND category_number = $10
           RETURNING *`, [
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
            rest_start_time || null,
            Math.max(0, Number(rest_duration_minutes) || 0),
            Math.max(0, Number(meal_duration_minutes) || 0),
        ]);
        const category = categoryResult.rows[0];
        if (!category) {
            await client.query('ROLLBACK');
            res.status(404).json({
                error: 'Categoría no encontrada',
            });
            return;
        }
        await client.query(`DELETE FROM category_rest_days
         WHERE category_id = $1`, [category.id]);
        for (const rawDay of rest_days) {
            const weekday = normalizeWeekday(rawDay);
            if (weekday === null)
                continue;
            await client.query(`INSERT INTO category_rest_days (
             category_id,
             weekday
           )
           VALUES ($1, $2)
           ON CONFLICT (
             category_id,
             weekday
           )
           DO NOTHING`, [
                category.id,
                weekday,
            ]);
        }
        /*
         * Reemplaza completamente los días laborales
         * de la categoría.
         *
         * Así, si antes era L-V y ahora solo L-M,
         * Mi-X-J-V dejan de quedar activos.
         */
        await client.query(`DELETE FROM category_workdays
         WHERE category_id = $1`, [category.id]);
        for (const item of workdays) {
            const weekday = normalizeWeekday(item?.weekday);
            if (weekday === null)
                continue;
            await client.query(`INSERT INTO category_workdays (
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
             updated_at = NOW()`, [
                category.id,
                weekday,
                Boolean(item?.is_workday),
                item?.start_time ?? null,
                item?.end_time ?? null,
            ]);
        }
        for (const item of incidence_values) {
            const concept = String(item?.concept ?? '').trim();
            const value = Number(item?.value);
            if (!VALID_CONCEPTS.includes(concept) ||
                !Number.isFinite(value)) {
                continue;
            }
            await client.query(`INSERT INTO category_incidence_values (
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
             updated_at = NOW()`, [
                category.id,
                concept,
                value,
            ]);
        }
        await client.query('COMMIT');
        res.json({
            saved: true,
            category_id: category.id,
            category_number: categoryNumber,
        });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error guardando categoría:', error);
        const detail = error instanceof Error
            ? error.message
            : String(error);
        res.status(500).json({
            error: 'No fue posible guardar la categoría',
            detail,
        });
    }
    finally {
        client.release();
    }
});
/* ---------------------------------------------------------
   GET configuración completa de un trabajador.
--------------------------------------------------------- */
router.get('/companies/:companyId/employees/:employeeCode/config', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, employeeCode, } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar trabajadores de otra empresa',
        });
        return;
    }
    const employeeResult = await client_1.default.query(`SELECT
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
           e.daily_salary,
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
         LIMIT 1`, [
        companyId,
        employeeCode,
    ]);
    const employee = employeeResult.rows[0];
    if (!employee) {
        res.status(404).json({
            error: 'Trabajador no encontrado',
        });
        return;
    }
    const [sitesByDay, workdays, restDays, values, counts,] = await Promise.all([
        client_1.default.query(`SELECT
           ews.weekday,
           ews.work_mode,
           ews.site_id,
           s.name AS site_name
         FROM employee_work_sites ews
         LEFT JOIN sites s
           ON s.id = ews.site_id
         WHERE ews.employee_id = $1
         ORDER BY ews.weekday`, [employee.id_remote]),
        employee.category_id
            ? client_1.default.query(`SELECT
               weekday,
               is_workday,
               start_time,
               end_time
             FROM category_workdays
             WHERE category_id = $1
             ORDER BY weekday`, [employee.category_id])
            : Promise.resolve({
                rows: [],
            }),
        employee.category_id
            ? client_1.default.query(`SELECT weekday
             FROM category_rest_days
             WHERE category_id = $1
             ORDER BY weekday`, [employee.category_id])
            : Promise.resolve({
                rows: [],
            }),
        employee.category_id
            ? client_1.default.query(`SELECT
               concept,
               value
             FROM category_incidence_values
             WHERE category_id = $1
             ORDER BY concept`, [employee.category_id])
            : Promise.resolve({
                rows: [],
            }),
        client_1.default.query(`SELECT
           concept,
           COUNT(*)::int AS count
         FROM qualified_incidents
         WHERE company_id = $1
           AND employee_id = $2
         GROUP BY concept
         ORDER BY concept`, [
            companyId,
            employee.id_remote,
        ]),
    ]);
    res.json({
        ...employee,
        work_sites_by_day: sitesByDay.rows,
        categories_by_day: (await client_1.default.query(`SELECT edc.weekday, c.category_number, c.name AS category_name
         FROM employee_day_categories edc
         JOIN categories c ON c.id = edc.category_id
         WHERE edc.employee_id = $1 ORDER BY edc.weekday`, [employee.id_remote])).rows,
        category_configuration: employee.category_id
            ? {
                start_time: employee.category_start_time,
                end_time: employee.category_end_time,
                tolerance_minutes: employee.tolerance_minutes,
                overtime_allowed: employee.overtime_allowed,
                overtime_value: employee.overtime_value,
                workdays: workdays.rows,
                rest_days: restDays.rows.map((row) => row.weekday),
                incidence_values: values.rows,
            }
            : null,
        incidence_counts: counts.rows,
    });
});
/* ---------------------------------------------------------
   PUT configuración propia del trabajador.

   NO duplica horarios/valores: esos se heredan de la categoría.
   Aquí solo asignamos categoría, fecha de ingreso y centros por día.
--------------------------------------------------------- */
router.put('/companies/:companyId/employees/:employeeCode/config', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, employeeCode, } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede modificar trabajadores de otra empresa',
        });
        return;
    }
    const { category_number, hire_date = null, phone = null, first_name, last_name_paternal, last_name_maternal, full_name, department, rfc, curp, nss, daily_salary, categories_by_day, work_sites_by_day = [], } = req.body ?? {};
    const salaryProvided = Object.prototype.hasOwnProperty.call(req.body ?? {}, 'daily_salary');
    const salary = daily_salary === null || daily_salary === ''
        ? null
        : Number(daily_salary);
    if (salaryProvided && (salary !== null &&
        (!Number.isFinite(salary) || salary < 0 || salary > 9999999999.99))) {
        res.status(400).json({ error: 'Sueldo diario inválido' });
        return;
    }
    const categoryNumber = Number(category_number);
    if (!Number.isInteger(categoryNumber) ||
        categoryNumber < 1) {
        res.status(400).json({
            error: 'Debe asignar una categoría válida',
        });
        return;
    }
    if (!Array.isArray(work_sites_by_day)) {
        res.status(400).json({
            error: 'work_sites_by_day debe ser un arreglo',
        });
        return;
    }
    if (categories_by_day !== undefined && !Array.isArray(categories_by_day)) {
        res.status(400).json({ error: 'categories_by_day debe ser un arreglo' });
        return;
    }
    const dayCategoryNumbers = new Map();
    if (Array.isArray(categories_by_day)) {
        for (const item of categories_by_day) {
            const weekday = Number(item?.weekday);
            const number = Number(item?.category_number);
            if (!Number.isInteger(weekday) || weekday < 1 || weekday > 7 ||
                !Number.isInteger(number) || number < 1 || dayCategoryNumbers.has(weekday)) {
                res.status(400).json({ error: 'Categorías por día inválidas o duplicadas' });
                return;
            }
            dayCategoryNumbers.set(weekday, number);
        }
        const siteDays = new Set(work_sites_by_day.map((x) => Number(x?.weekday)));
        if (siteDays.size !== dayCategoryNumbers.size ||
            [...siteDays].some(day => !dayCategoryNumbers.has(day))) {
            res.status(400).json({ error: 'Cada día con centro debe tener una categoría' });
            return;
        }
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const categoryResult = await client.query(`SELECT id
           FROM categories
           WHERE company_id = $1
             AND category_number = $2
           LIMIT 1`, [
            companyId,
            categoryNumber,
        ]);
        if (!categoryResult.rows[0]) {
            await client.query('ROLLBACK');
            res.status(404).json({
                error: 'Categoría no encontrada',
            });
            return;
        }
        const employeeResult = await client.query(`UPDATE employees
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
               daily_salary = CASE WHEN $14::boolean THEN $15::numeric ELSE daily_salary END,
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
             hire_date,
             daily_salary`, [
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
            salaryProvided,
            salary,
        ]);
        const employee = employeeResult.rows[0];
        if (!employee) {
            await client.query('ROLLBACK');
            res.status(404).json({
                error: 'Trabajador no encontrado',
            });
            return;
        }
        if (Array.isArray(categories_by_day)) {
            const validCategories = await client.query(`SELECT id, category_number FROM categories WHERE company_id = $1
           AND category_number = ANY($2::int[])`, [companyId, [...dayCategoryNumbers.values()]]);
            const ids = new Map(validCategories.rows.map((row) => [Number(row.category_number), row.id]));
            if ([...dayCategoryNumbers.values()].some(n => !ids.has(n))) {
                await client.query('ROLLBACK');
                res.status(400).json({ error: 'Una categoría diaria no existe en esta empresa' });
                return;
            }
            await client.query('DELETE FROM employee_day_categories WHERE employee_id = $1', [employee.id_remote]);
            for (const [weekday, number] of dayCategoryNumbers) {
                await client.query(`INSERT INTO employee_day_categories (employee_id, weekday, category_id)
             VALUES ($1,$2,$3)`, [employee.id_remote, weekday, ids.get(number)]);
            }
        }
        await client.query(`DELETE FROM employee_work_sites
         WHERE employee_id = $1`, [employee.id_remote]);
        for (const item of work_sites_by_day) {
            const weekday = normalizeWeekday(item?.weekday);
            if (weekday === null) {
                continue;
            }
            const rawWorkMode = String(item?.work_mode ?? 'SITE')
                .trim()
                .toUpperCase();
            if (rawWorkMode !== 'SITE' &&
                rawWorkMode !== 'FOREIGN') {
                await client.query('ROLLBACK');
                res.status(400).json({
                    error: `Modo de trabajo inválido para el día ${weekday}. ` +
                        'Debe ser SITE o FOREIGN',
                });
                return;
            }
            const workMode = rawWorkMode;
            const requestedSiteId = typeof item?.site_id ===
                'string' &&
                item.site_id.trim()
                ? item.site_id.trim()
                : null;
            const siteId = workMode === 'FOREIGN'
                ? null
                : requestedSiteId;
            if (workMode === 'SITE' &&
                !siteId) {
                await client.query('ROLLBACK');
                res.status(400).json({
                    error: `Debe seleccionar un centro de trabajo para el día ${weekday}`,
                });
                return;
            }
            if (siteId) {
                const site = await client.query(`SELECT id
               FROM sites
               WHERE id = $1
                 AND company_id = $2
               LIMIT 1`, [
                    siteId,
                    companyId,
                ]);
                if (!site.rows[0]) {
                    await client.query('ROLLBACK');
                    res.status(400).json({
                        error: `El centro configurado para el día ${weekday} ` +
                            'no pertenece a la empresa',
                    });
                    return;
                }
            }
            await client.query(`INSERT INTO employee_work_sites (
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
             updated_at = NOW()`, [
                employee.id_remote,
                weekday,
                siteId,
                workMode,
            ]);
        }
        /*
         * Compatibilidad:
         * el centro general usa solamente un día SITE.
         */
        const firstSite = await client.query(`SELECT site_id
           FROM employee_work_sites
           WHERE employee_id = $1
             AND work_mode = 'SITE'
             AND site_id IS NOT NULL
           ORDER BY weekday
           LIMIT 1`, [employee.id_remote]);
        await client.query(`UPDATE employees
         SET site_id = $1,
             updated_at = NOW()
         WHERE id_remote = $2`, [
            firstSite.rows[0]
                ?.site_id ?? null,
            employee.id_remote,
        ]);
        const savedEmployeeResult = await client.query(`SELECT
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
             e.daily_salary,
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
           LIMIT 1`, [
            companyId,
            employeeCode,
        ]);
        const savedSitesResult = await client.query(`SELECT
             ews.weekday,
             ews.work_mode,
             ews.site_id,
             s.name AS site_name
           FROM employee_work_sites ews
           LEFT JOIN sites s
             ON s.id = ews.site_id
           WHERE ews.employee_id = $1
           ORDER BY ews.weekday`, [employee.id_remote]);
        await client.query('COMMIT');
        res.json({
            saved: true,
            ...savedEmployeeResult.rows[0],
            work_sites_by_day: savedSitesResult.rows,
            categories_by_day: (await client_1.default.query(`SELECT edc.weekday, c.category_number, c.name AS category_name
           FROM employee_day_categories edc
           JOIN categories c ON c.id = edc.category_id
           WHERE edc.employee_id = $1 ORDER BY edc.weekday`, [employee.id_remote])).rows,
        });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error guardando configuración del trabajador:', error);
        const message = error instanceof Error
            ? error.message
            : 'Error desconocido';
        res.status(500).json({
            error: `No fue posible guardar la configuración del trabajador: ${message}`,
        });
    }
    finally {
        client.release();
    }
});
/* ---------------------------------------------------------
   GET incidencias / registros para revisión.

   No muestra valuación editable. Si ya fue calificada devuelve
   el valor aplicado automáticamente.
--------------------------------------------------------- */
router.get('/companies/:companyId/incidents', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar incidencias de otra empresa',
        });
        return;
    }
    const status = String(req.query.status ?? 'pending').toLowerCase();
    const requestedLimit = Number(req.query.limit ?? 200);
    const limit = Number.isInteger(requestedLimit)
        ? Math.min(Math.max(requestedLimit, 1), 500)
        : 200;
    const result = await client_1.default.query(`SELECT

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

       LIMIT $3`, [
        companyId,
        status,
        limit,
    ]);
    res.json({
        records: result.rows,
        status,
        limit,
    });
});
/* ---------------------------------------------------------
   POST calificar incidencia.

   Body: { concept, notes? }

   NO acepta un valor manual.

   El valor sale de category_incidence_values según la categoría
   que tenga el trabajador.
--------------------------------------------------------- */
router.post('/companies/:companyId/incidents/:attendanceRecordId/qualify', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId, attendanceRecordId, } = req.params;
    const concept = String(req.body?.concept ?? '').trim();
    const notes = typeof req.body?.notes === 'string'
        ? req.body.notes.trim() || null
        : null;
    if (!VALID_CONCEPTS.includes(concept)) {
        res.status(400).json({
            error: 'Concepto de incidencia inválido',
        });
        return;
    }
    if (!notes) {
        res.status(400).json({ error: 'Las observaciones son obligatorias' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const recordResult = await client.query(`SELECT
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

           FOR UPDATE OF a`, [
            attendanceRecordId,
            companyId,
        ]);
        const record = recordResult.rows[0];
        if (!record) {
            await client.query('ROLLBACK');
            res.status(404).json({
                error: 'Registro de asistencia no encontrado',
            });
            return;
        }
        if (!record.category_id) {
            await client.query('ROLLBACK');
            res.status(409).json({
                error: 'El trabajador todavía no tiene categoría asignada',
            });
            return;
        }
        let appliedValue = 0;
        if (concept === 'Historial' || concept === 'Salida autorizada' ||
            concept === 'Permiso médico justificado' || concept === 'Falta justificada') {
            // Registro administrativo sin sanción.
            appliedValue = 0;
        }
        else if (concept === 'Tiempo extraordinario') {
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
            const ausentismoResult = await client.query(`SELECT value
     FROM category_incidence_values
     WHERE category_id = $1
       AND concept = 'Ausentismo'
     LIMIT 1`, [record.category_id]);
            if (!ausentismoResult.rows[0]) {
                await client.query('ROLLBACK');
                res.status(409).json({
                    error: 'No existe valuación configurada para Ausentismo',
                });
                return;
            }
            const ausentismoValue = Number(ausentismoResult.rows[0].value ?? 0);
            /*
             * Contar retardos calificados del trabajador
             * durante el mismo mes.
             *
             * Excluimos el registro actual por si se está
             * editando una incidencia ya calificada.
             */
            const retardoCountResult = await client.query(`SELECT COUNT(*)::int AS total
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
       )`, [
                companyId,
                record.employee_id,
                attendanceRecordId,
                record.incident_date,
            ]);
            const previousRetardos = Number(retardoCountResult.rows[0]?.total ?? 0);
            const currentRetardoNumber = previousRetardos + 1;
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
        }
        else {
            /*
             * Para Ausentismo y las demás incidencias
             * se usa directamente el valor configurado.
             */
            const valueResult = await client.query(`SELECT value
     FROM category_incidence_values
     WHERE category_id = $1
       AND concept = $2
     LIMIT 1`, [
                record.category_id,
                concept,
            ]);
            if (!valueResult.rows[0]) {
                await client.query('ROLLBACK');
                res.status(409).json({
                    error: `No existe valuación configurada para ${concept}`,
                });
                return;
            }
            appliedValue = Number(valueResult.rows[0].value);
        }
        const result = await client.query(`INSERT INTO qualified_incidents (
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
       qualified_at`, [
            companyId,
            attendanceRecordId,
            record.employee_id,
            record.category_id,
            concept,
            record.incident_date,
            appliedValue,
            notes,
            req.user?.id ?? null,
        ]);
        await client.query('COMMIT');
        const verification = await client_1.default.query(`SELECT
     id,
     attendance_record_id,
     employee_id,
     concept,
     incident_date,
     applied_value,
     qualified_at
   FROM qualified_incidents
   WHERE attendance_record_id = $1
   LIMIT 1`, [attendanceRecordId]);
        console.log('VERIFICACION INCIDENCIA DESPUES DE COMMIT:', verification.rows);
        if (!verification.rows[0]) {
            res.status(500).json({
                error: 'La incidencia no quedó guardada en PostgreSQL',
            });
            return;
        }
        res.json(verification.rows[0]);
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error calificando incidencia:', error);
        res.status(500).json({
            error: 'No fue posible calificar la incidencia',
        });
    }
    finally {
        client.release();
    }
});
/* ---------------------------------------------------------
   GET conteo de incidencias por trabajador.

   Ejemplo:
   Ausentismo 0,
   Retardo 2,
   etc.
--------------------------------------------------------- */
router.get('/companies/:companyId/employees/:employeeCode/incidence-counts', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, employeeCode, } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar trabajadores de otra empresa',
        });
        return;
    }
    const employee = await client_1.default.query(`SELECT
       e.id_remote,
       e.category_id,
       COALESCE(civ.value, 0) AS ausentismo_value

     FROM employees e

     LEFT JOIN category_incidence_values civ
       ON civ.category_id = e.category_id
      AND civ.concept = 'Ausentismo'

     WHERE e.company_id = $1
       AND e.employee_code = $2

     LIMIT 1`, [
        companyId,
        employeeCode,
    ]);
    if (!employee.rows[0]) {
        res.status(404).json({
            error: 'Trabajador no encontrado',
        });
        return;
    }
    const counts = await client_1.default.query(`WITH concepts(concept) AS (

       VALUES
         ('Ausentismo'),
         ('Retardo'),
         ('Permiso médico justificado'),
         ('Permiso médico no justificado'),
         ('Falta justificada'),
         ('Salida no autorizada'),
         ('Salida autorizada'),
         ('Tiempo extraordinario'),
         ('Historial')

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

     ORDER BY c.concept`, [
        companyId,
        employee.rows[0].id_remote,
    ]);
    res.json({
        employee_code: employeeCode,
        ausentismo_value: Number(employee.rows[0].ausentismo_value || 0),
        incidences: counts.rows,
    });
});
/* Ausentismo real: días laborales concluidos sin ninguna entrada registrada.
   No crea incidencias automáticamente durante una consulta. */
router.get('/companies/:companyId/absences', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const companyId = req.params.companyId;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Acceso denegado' });
        return;
    }
    const read = (key) => typeof req.query[key] === 'string' ? String(req.query[key]).trim() : '';
    const from = read('from') || new Date(Date.now() - 30 * 86400000).toISOString().slice(0, 10);
    const to = read('to') || new Date().toISOString().slice(0, 10);
    const employee = read('employee');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to) || from > to ||
        (Date.parse(to) - Date.parse(from)) / 86400000 > 366) {
        res.status(400).json({ error: 'Rango inválido (máximo 366 días)' });
        return;
    }
    try {
        const result = await client_1.default.query(`
      WITH days AS (
        SELECT generate_series($2::date, LEAST($3::date, (NOW() AT TIME ZONE 'America/Mexico_City')::date - 1), interval '1 day')::date AS work_date
      ), scheduled AS (
        SELECT e.id_remote AS employee_id, e.employee_code, e.full_name AS employee_name,
               e.company_id, e.site_id, d.work_date, c.id AS category_id,
               cw.end_time,
               COALESCE(cw.is_workday, FALSE) AS is_workday
        FROM employees e
        CROSS JOIN days d
        JOIN categories c ON c.id = COALESCE(
          (SELECT edc.category_id FROM employee_day_categories edc
           WHERE edc.employee_id = e.id_remote
             AND edc.weekday IN (EXTRACT(ISODOW FROM d.work_date)::int,
               CASE WHEN EXTRACT(ISODOW FROM d.work_date)::int = 7 THEN 0 ELSE -1 END)
           ORDER BY CASE WHEN edc.weekday = EXTRACT(ISODOW FROM d.work_date)::int THEN 0 ELSE 1 END
           LIMIT 1), e.category_id)
        JOIN category_workdays cw ON cw.category_id = c.id
          AND cw.weekday IN (EXTRACT(ISODOW FROM d.work_date)::int,
            CASE WHEN EXTRACT(ISODOW FROM d.work_date)::int = 7 THEN 0 ELSE -1 END)
        WHERE e.company_id = $1 AND e.is_active = TRUE
          AND (e.hire_date IS NULL OR e.hire_date::date <= d.work_date)
          AND ($4::text = '' OR e.employee_code = $4 OR e.full_name ILIKE '%' || $4 || '%')
          AND NOT EXISTS (SELECT 1 FROM category_rest_days rd WHERE rd.category_id = c.id
            AND rd.weekday IN (EXTRACT(ISODOW FROM d.work_date)::int,
              CASE WHEN EXTRACT(ISODOW FROM d.work_date)::int = 7 THEN 0 ELSE -1 END))
      )
      SELECT s.employee_id, s.employee_code, s.employee_name, s.work_date,
             s.category_id, s.site_id, 'Ausentismo'::text AS concept,
             qi.id AS incident_id, qi.notes, qi.applied_value, qi.qualified_at,
             CASE WHEN qi.id IS NULL THEN 'pending' ELSE 'qualified' END AS status
      FROM scheduled s
      LEFT JOIN LATERAL (
        SELECT q.id, q.notes, q.applied_value, q.qualified_at
        FROM qualified_incidents q
        WHERE q.company_id = s.company_id AND q.employee_id = s.employee_id
          AND q.incident_date = s.work_date AND q.concept IN ('Ausentismo', 'Falta justificada')
          AND q.attendance_record_id IS NULL
        ORDER BY q.qualified_at DESC LIMIT 1
      ) qi ON TRUE
      WHERE s.is_workday
        AND NOT EXISTS (
          SELECT 1 FROM attendance_records a
          WHERE a.company_id = s.company_id
            AND a.employee_id IN (s.employee_id::text,
              (SELECT e2.id_local FROM employees e2 WHERE e2.id_remote = s.employee_id), s.employee_code)
            AND a.event_type = 'CLOCK_IN'
            AND (to_timestamp(a.occurred_at / 1000.0) AT TIME ZONE 'America/Mexico_City')::date = s.work_date
        )
      ORDER BY s.work_date DESC, s.employee_name LIMIT 20000`, [companyId, from, to, employee]);
        res.json({ records: result.rows, absences: result.rows, count: result.rows.length });
    }
    catch (error) {
        console.error('Error consultando ausentismo:', error);
        res.status(500).json({ error: 'No se pudo consultar el ausentismo' });
    }
});
/* Registrar observaciones de una ausencia sin generar duplicados. */
router.post('/companies/:companyId/absences/notes', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Acceso denegado' });
        return;
    }
    const employeeId = String(req.body?.employee_id ?? req.body?.employeeId ?? '');
    const workDate = String(req.body?.work_date ?? req.body?.workDate ?? '');
    const notes = String(req.body?.notes ?? '').trim();
    if (!/^[0-9a-f-]{36}$/i.test(employeeId) || !/^\d{4}-\d{2}-\d{2}$/.test(workDate) || !notes || notes.length > 4000) {
        res.status(400).json({ error: 'Trabajador, fecha y observaciones válidos son obligatorios' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`absence:${companyId}:${employeeId}:${workDate}`]);
        const check = await client.query(`SELECT e.id_remote, COALESCE(edc.category_id, e.category_id) AS category_id
      FROM employees e
      LEFT JOIN employee_day_categories edc ON edc.employee_id = e.id_remote
        AND edc.weekday IN (EXTRACT(ISODOW FROM $3::date)::int,
          CASE WHEN EXTRACT(ISODOW FROM $3::date)::int = 7 THEN 0 ELSE -1 END)
      WHERE e.company_id = $1 AND e.id_remote = $2 AND e.is_active = TRUE
        AND $3::date < (NOW() AT TIME ZONE 'America/Mexico_City')::date
        AND EXISTS (SELECT 1 FROM category_workdays cw
          WHERE cw.category_id = COALESCE(edc.category_id, e.category_id) AND cw.is_workday
            AND cw.weekday IN (EXTRACT(ISODOW FROM $3::date)::int,
              CASE WHEN EXTRACT(ISODOW FROM $3::date)::int = 7 THEN 0 ELSE -1 END))
        AND NOT EXISTS (SELECT 1 FROM category_rest_days rd
          WHERE rd.category_id = COALESCE(edc.category_id, e.category_id)
            AND rd.weekday IN (EXTRACT(ISODOW FROM $3::date)::int,
              CASE WHEN EXTRACT(ISODOW FROM $3::date)::int = 7 THEN 0 ELSE -1 END))
        AND NOT EXISTS (SELECT 1 FROM attendance_records a
          WHERE a.company_id = $1 AND a.event_type = 'CLOCK_IN'
            AND a.employee_id IN (e.id_remote::text, e.id_local, e.employee_code)
            AND (to_timestamp(a.occurred_at / 1000.0) AT TIME ZONE 'America/Mexico_City')::date = $3::date)
      LIMIT 1`, [companyId, employeeId, workDate]);
        if (!check.rows.length) {
            await client.query('ROLLBACK');
            res.status(409).json({ error: 'No corresponde a una ausencia laboral concluida sin entrada' });
            return;
        }
        const previous = await client.query(`SELECT id FROM qualified_incidents
      WHERE company_id = $1 AND employee_id = $2 AND incident_date = $3::date
        AND attendance_record_id IS NULL AND concept IN ('Ausentismo', 'Falta justificada')
      ORDER BY qualified_at DESC LIMIT 1 FOR UPDATE`, [companyId, employeeId, workDate]);
        const result = previous.rows.length
            ? await client.query(`UPDATE qualified_incidents SET notes = $2, qualified_at = NOW(), qualified_by = $3
          WHERE id = $1 RETURNING *`, [previous.rows[0].id, notes, req.user?.id ?? null])
            : await client.query(`INSERT INTO qualified_incidents
          (company_id, attendance_record_id, employee_id, category_id, concept, incident_date, applied_value, notes, qualified_by)
          VALUES ($1, NULL, $2, $3, 'Ausentismo', $4::date, 0, $5, $6) RETURNING *`, [companyId, employeeId, check.rows[0].category_id, workDate, notes, req.user?.id ?? null]);
        await client.query('COMMIT');
        res.json({ saved: true, record: result.rows[0] });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error guardando observaciones de ausentismo:', error);
        res.status(500).json({ error: 'No se pudieron guardar las observaciones' });
    }
    finally {
        client.release();
    }
});
/* Exportación del historial para el Excel generado en administración. */
router.get('/companies/:companyId/history-export', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'No tiene acceso a esta empresa' });
        return;
    }
    const param = (name) => typeof req.query[name] === 'string' ? String(req.query[name]).trim() : '';
    const from = param('from');
    const to = param('to');
    const employee = param('employee');
    const role = param('role');
    const worksite = param('worksite');
    const concept = param('concept');
    const onlyIncidents = param('only_incidents') === 'true';
    const datePattern = /^\d{4}-\d{2}-\d{2}$/;
    if ((from && !datePattern.test(from)) || (to && !datePattern.test(to)) || (from && to && from > to)) {
        res.status(400).json({ error: 'El rango de fechas no es válido' });
        return;
    }
    try {
        const result = await client_1.default.query(`WITH movements AS (
          SELECT a.*, e.id_remote AS worker_id, e.employee_code, e.full_name,
                 e.category_id, e.site_id AS employee_site_id,
                 cat.name AS category_name,
                 (a.event_type = 'CLOCK_IN' AND
                   (to_timestamp(a.occurred_at / 1000.0) AT TIME ZONE 'America/Mexico_City')::time
                   > (CASE WHEN cat.start_time ~ '^([01]?[0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$' THEN cat.start_time::time END) + make_interval(mins => COALESCE(cat.tolerance_minutes, 0))) AS is_late
          FROM attendance_records a
          JOIN employees e ON e.company_id = a.company_id
            AND a.employee_id IN (e.id_local, e.id_remote::text, e.employee_code)
          LEFT JOIN categories cat ON cat.id = e.category_id AND cat.company_id = a.company_id
          WHERE a.company_id = $1
        ), history AS (
          SELECT a.employee_code, a.full_name, a.event_type, a.occurred_at,
                 COALESCE(qi.concept, ai.concept) AS concept,
                 qi.applied_value, COALESCE(qi.notes, ai.notes) AS notes,
                 COALESCE(qi.qualified_at, ai.reviewed_at) AS qualified_at,
                 a.category_name, a.category_id, COALESCE(a.site_id, a.employee_site_id) AS site_id,
                 COALESCE(a.is_late, FALSE) AS is_late,
                 qi.id IS NOT NULL OR ai.id IS NOT NULL AS is_incident
          FROM movements a
          LEFT JOIN qualified_incidents qi ON qi.attendance_record_id = a.id_remote AND qi.company_id = $1
          LEFT JOIN LATERAL (
            SELECT i.* FROM attendance_auto_incidents i
            WHERE i.company_id = $1 AND i.attendance_record_id = a.id_remote AND i.status = 'reviewed'
            ORDER BY i.reviewed_at DESC LIMIT 1
          ) ai ON TRUE
          WHERE qi.id IS NOT NULL OR ai.id IS NOT NULL OR (
            NOT COALESCE(a.is_late, FALSE) AND COALESCE(a.late_return_minutes, 0) = 0
            AND NOT EXISTS (SELECT 1 FROM attendance_auto_incidents pending
              WHERE pending.company_id = $1 AND pending.attendance_record_id = a.id_remote)
          )
          UNION ALL
          SELECT e.employee_code, e.full_name, 'INCIDENT',
                 EXTRACT(EPOCH FROM (qi.incident_date::timestamp AT TIME ZONE 'America/Mexico_City')) * 1000,
                 qi.concept, qi.applied_value, qi.notes, qi.qualified_at,
                 cat.name, e.category_id, e.site_id, FALSE, TRUE
          FROM qualified_incidents qi
          JOIN employees e ON e.id_remote = qi.employee_id AND e.company_id = qi.company_id
          LEFT JOIN categories cat ON cat.id = qi.category_id
          WHERE qi.company_id = $1 AND qi.attendance_record_id IS NULL
          UNION ALL
          SELECT e.employee_code, e.full_name, 'INCIDENT',
                 EXTRACT(EPOCH FROM (ai.incident_date::timestamp AT TIME ZONE 'America/Mexico_City')) * 1000,
                 ai.concept, NULL, ai.notes, ai.reviewed_at,
                 cat.name, e.category_id, e.site_id, FALSE, TRUE
          FROM attendance_auto_incidents ai
          JOIN employees e ON e.id_remote = ai.employee_id AND e.company_id = ai.company_id
          LEFT JOIN categories cat ON cat.id = ai.category_id
          WHERE ai.company_id = $1 AND ai.status = 'reviewed' AND ai.attendance_record_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM qualified_incidents qi
              WHERE qi.company_id = ai.company_id AND qi.employee_id = ai.employee_id
                AND qi.incident_date = ai.incident_date AND qi.attendance_record_id IS NULL
                AND qi.concept = ai.concept)
        )
        SELECT * FROM history h
        WHERE ($2::text = '' OR (to_timestamp(h.occurred_at / 1000.0) AT TIME ZONE 'America/Mexico_City')::date >= NULLIF($2, '')::date)
          AND ($3::text = '' OR (to_timestamp(h.occurred_at / 1000.0) AT TIME ZONE 'America/Mexico_City')::date <= NULLIF($3, '')::date)
          AND ($4::text = '' OR h.employee_code = $4 OR h.full_name ILIKE '%' || $4 || '%')
          AND ($5::text = '' OR h.category_name = $5 OR h.category_id::text = $5)
          AND ($6::text = '' OR h.site_id::text = $6 OR EXISTS (SELECT 1 FROM sites st WHERE st.id = h.site_id AND st.name = $6))
          AND ($7::text = '' OR h.concept = $7)
          AND (NOT $8::boolean OR h.is_incident)
        ORDER BY h.occurred_at DESC LIMIT 20000`, [companyId, from, to, employee, role, worksite, concept, onlyIncidents]);
        res.json({ records: result.rows, count: result.rows.length });
    }
    catch (error) {
        console.error('Error exportando historial:', error);
        res.status(500).json({ error: 'No se pudo generar el historial de asistencias' });
    }
});
/* =========================================================
   GET /api/admin/companies/:companyId/attendance

   Devuelve registros de asistencia para reportes.
   Permite filtrar por rango de fechas.

   Las horas extra NO se calculan aquí:
   esta ruta devuelve también la configuración de categoría
   para que el reporte pueda calcular únicamente cuando
   overtime_allowed = TRUE.
   ========================================================= */
router.get('/companies/:companyId/attendance', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar asistencias de otra empresa',
        });
        return;
    }
    const from = typeof req.query.from === 'string'
        ? req.query.from.trim()
        : '';
    const to = typeof req.query.to === 'string'
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
        const values = [companyId];
        const conditions = [
            'a.company_id = $1',
        ];
        if (from) {
            values.push(from);
            conditions.push(`TO_TIMESTAMP(a.occurred_at / 1000.0)::date >= $${values.length}::date`);
        }
        if (to) {
            values.push(to);
            conditions.push(`TO_TIMESTAMP(a.occurred_at / 1000.0)::date <= $${values.length}::date`);
        }
        const result = await client_1.default.query(`
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
        `, values);
        res.json({
            records: result.rows,
            count: result.rows.length,
        });
    }
    catch (error) {
        console.error('Error consultando asistencias:', error);
        const detail = error instanceof Error
            ? error.message
            : String(error);
        res.status(500).json({
            error: 'No se pudieron consultar las asistencias',
            detail,
        });
    }
});
/* =========================================================
   VACACIONES DE TRABAJADORES
========================================================= */
/*
 * Obtener resumen e historial de vacaciones.
 */
router.get('/companies/:companyId/employees/:employeeCode/vacations', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, employeeCode } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede consultar trabajadores de otra empresa',
        });
        return;
    }
    try {
        const employeeResult = await client_1.default.query(`SELECT
           id_remote,
           employee_code,
           full_name,
           hire_date
         FROM employees
         WHERE company_id = $1
           AND employee_code = $2
           AND is_active = TRUE
         LIMIT 1`, [companyId, employeeCode]);
        const employee = employeeResult.rows[0];
        if (!employee) {
            res.status(404).json({
                error: 'Trabajador no encontrado',
            });
            return;
        }
        const vacationsResult = await client_1.default.query(`SELECT
           id,
           start_date,
           end_date,
           days_taken,
           notes,
           created_at
         FROM employee_vacations
         WHERE company_id = $1
           AND employee_id = $2
         ORDER BY start_date DESC, created_at DESC`, [companyId, employee.id_remote]);
        const totalTaken = vacationsResult.rows.reduce((total, row) => total + Number(row.days_taken ?? 0), 0);
        res.json({
            employee_code: employee.employee_code,
            employee_name: employee.full_name,
            hire_date: employee.hire_date,
            total_taken: totalTaken,
            vacations: vacationsResult.rows,
        });
    }
    catch (error) {
        console.error('Error consultando vacaciones:', error);
        res.status(500).json({
            error: 'No fue posible consultar las vacaciones',
        });
    }
});
/*
 * Registrar vacaciones tomadas.
 */
router.post('/companies/:companyId/employees/:employeeCode/vacations', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, employeeCode } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({
            error: 'No puede modificar trabajadores de otra empresa',
        });
        return;
    }
    const { start_date, end_date, days_taken, notes = null, } = req.body ?? {};
    const daysTaken = Number(days_taken);
    if (!start_date ||
        !end_date ||
        !Number.isFinite(daysTaken) ||
        daysTaken <= 0) {
        res.status(400).json({
            error: 'Fecha inicial, fecha final y días tomados son requeridos',
        });
        return;
    }
    try {
        const employeeResult = await client_1.default.query(`SELECT id_remote
         FROM employees
         WHERE company_id = $1
           AND employee_code = $2
           AND is_active = TRUE
         LIMIT 1`, [companyId, employeeCode]);
        const employee = employeeResult.rows[0];
        if (!employee) {
            res.status(404).json({
                error: 'Trabajador no encontrado',
            });
            return;
        }
        const result = await client_1.default.query(`INSERT INTO employee_vacations (
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
           created_at`, [
            companyId,
            employee.id_remote,
            start_date,
            end_date,
            daysTaken,
            typeof notes === 'string'
                ? notes.trim() || null
                : null,
        ]);
        res.status(201).json({
            saved: true,
            vacation: result.rows[0],
        });
    }
    catch (error) {
        console.error('Error registrando vacaciones:', error);
        res.status(500).json({
            error: 'No fue posible registrar las vacaciones',
        });
    }
});
/* Políticas sincronizadas por empresa en PostgreSQL. */
router.get('/companies/:companyId/policies', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Sin acceso a la empresa' });
        return;
    }
    try {
        const result = await client_1.default.query('SELECT id, title, text, created_at, updated_at FROM company_policies WHERE company_id=$1 ORDER BY created_at,id', [companyId]);
        res.json(result.rows);
    }
    catch (error) {
        console.error('Consultando políticas:', error);
        res.status(500).json({ error: 'No se pudieron consultar las políticas. ¿Ejecutaste la migración SQL?' });
    }
});
router.put('/companies/:companyId/policies', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Sin acceso a la empresa' });
        return;
    }
    const rows = req.body?.policies;
    if (!Array.isArray(rows) || rows.length > 100 || rows.some((p) => typeof p?.title !== 'string' || typeof p?.text !== 'string' || p.title.length > 500 || p.text.length > 100000)) {
        res.status(400).json({ error: 'Lista de políticas inválida' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        await client.query('SELECT id FROM companies WHERE id=$1 FOR UPDATE', [companyId]);
        await client.query('DELETE FROM company_policies WHERE company_id=$1', [companyId]);
        for (const p of rows) {
            await client.query('INSERT INTO company_policies(company_id,title,text) VALUES ($1,$2,$3)', [companyId, p.title, p.text]);
        }
        await client.query('COMMIT');
        res.json({ saved: true, count: rows.length });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Guardando políticas:', error);
        res.status(500).json({ error: 'No se pudieron guardar las políticas' });
    }
    finally {
        client.release();
    }
});
/* ==============================================================
   Datos complementarios del portal (sin sustituir tablas oficiales).
   Todos los accesos se restringen a la empresa autorizada.
   Se usan versiones para evitar sobrescribir datos de otra PC.
============================================================== */
router.get('/companies/:companyId/portal-data/:storageKey', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, storageKey } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Acceso denegado' });
        return;
    }
    if (!/^[a-zA-Z0-9_-]{1,150}$/.test(storageKey)) {
        res.status(400).json({ error: 'Clave inválida' });
        return;
    }
    try {
        const result = await client_1.default.query('SELECT data, revision, updated_at FROM company_portal_data WHERE company_id=$1 AND storage_key=$2', [companyId, storageKey]);
        if (!result.rowCount) {
            res.json({ exists: false, data: null, updated_at: null, revision: null });
            return;
        }
        res.json({ exists: true, ...result.rows[0] });
    }
    catch (e) {
        console.error('portal-data GET', e);
        res.status(500).json({ error: 'Error consultando datos del portal' });
    }
});
router.put('/companies/:companyId/portal-data/:storageKey', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, async (req, res) => {
    const { companyId, storageKey } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Acceso denegado' });
        return;
    }
    if (!/^[a-zA-Z0-9_-]{1,150}$/.test(storageKey)) {
        res.status(400).json({ error: 'Clave inválida' });
        return;
    }
    const { data, expected_revision } = req.body || {};
    if (data === undefined || JSON.stringify(data).length > 1500000) {
        res.status(400).json({ error: 'Datos ausentes o demasiado grandes' });
        return;
    }
    if (expected_revision !== null && expected_revision !== undefined && (!Number.isSafeInteger(expected_revision) || expected_revision < 1)) {
        res.status(400).json({ error: 'Revisión inválida' });
        return;
    }
    try {
        // Si expected_revision es null, solo crea la primera versión.
        // Si se proporciona una fecha, actualiza solo si coincide.
        const result = await client_1.default.query(`INSERT INTO company_portal_data(company_id,storage_key,data)
    VALUES ($1,$2,$3::jsonb)
    ON CONFLICT(company_id,storage_key) DO UPDATE
    SET data=EXCLUDED.data,revision=company_portal_data.revision+1,updated_at=NOW()
    WHERE ($4::bigint IS NOT NULL AND company_portal_data.revision=$4::bigint)
    RETURNING revision,updated_at`, [companyId, storageKey, JSON.stringify(data), expected_revision ?? null]);
        if (!result.rowCount) {
            res.status(409).json({ error: 'Hay una versión más reciente en PostgreSQL. Recarga antes de guardar.' });
            return;
        }
        res.json({ saved: true, revision: result.rows[0].revision, updated_at: result.rows[0].updated_at });
    }
    catch (e) {
        console.error('portal-data PUT', e);
        res.status(500).json({ error: 'Error guardando datos del portal' });
    }
});
/* ============================================================
   CHECADORES COMPARTIDOS - independientes de devices y de
   device_enrollment_codes (app Android).
   Requiere shared_checkers y shared_checker_enrollment_codes.
============================================================ */
router.get('/companies/:companyId/shared-checkers', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    if (!canAccessCompany(req, req.params.companyId)) {
        res.status(403).json({ error: 'No tiene acceso a esta empresa' });
        return;
    }
    try {
        const result = await client_1.default.query(`
        SELECT sc.id, sc.company_id, sc.site_id, sc.device_name,
               sc.is_active, sc.created_at, sc.linked_at, s.name AS site_name
        FROM shared_checkers sc
        JOIN sites s ON s.id = sc.site_id AND s.company_id = sc.company_id
        WHERE sc.company_id = $1 ORDER BY sc.created_at DESC`, [req.params.companyId]);
        res.json(result.rows);
    }
    catch (error) {
        console.error('Error listando checadores compartidos:', error);
        res.status(500).json({ error: 'No se pudieron consultar los checadores' });
    }
});
router.post('/companies/:companyId/shared-checkers', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const companyId = req.params.companyId;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'No tiene acceso a esta empresa' });
        return;
    }
    const deviceName = typeof req.body?.device_name === 'string' ? req.body.device_name.trim() : '';
    const siteId = typeof req.body?.site_id === 'string' ? req.body.site_id : '';
    if (!deviceName || deviceName.length > 80 || !/^[0-9a-f-]{36}$/i.test(siteId)) {
        res.status(400).json({ error: 'Nombre o centro de trabajo inválido' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const site = await client.query(`SELECT s.id FROM sites s
        JOIN companies c ON c.id=s.company_id AND c.is_active=TRUE
        WHERE s.id=$1 AND s.company_id=$2`, [siteId, companyId]);
        if (!site.rowCount) {
            await client.query('ROLLBACK');
            res.status(400).json({ error: 'Centro de trabajo no válido para esta empresa' });
            return;
        }
        const created = await client.query(`INSERT INTO shared_checkers
        (company_id, site_id, device_name) VALUES ($1,$2,$3) RETURNING id`, [companyId, siteId, deviceName]);
        // Código de 8 caracteres: 40 bits de entropía; un solo uso, 10 minutos.
        const plainCode = crypto_1.default.randomBytes(5).toString('hex').toUpperCase();
        const codeHash = crypto_1.default.createHash('sha256').update(plainCode).digest('hex');
        const enrollment = await client.query(`INSERT INTO shared_checker_enrollment_codes
        (checker_id, code_hash, expires_at)
        VALUES ($1,$2,NOW()+INTERVAL '10 minutes') RETURNING expires_at`, [created.rows[0].id, codeHash]);
        await client.query('COMMIT');
        res.status(201).json({ checker_id: created.rows[0].id,
            enrollment_code: plainCode, expires_at: enrollment.rows[0].expires_at });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error creando checador compartido:', error);
        res.status(500).json({ error: 'No se pudo generar el código' });
    }
    finally {
        client.release();
    }
});
router.delete('/companies/:companyId/shared-checkers/:checkerId', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    if (!canAccessCompany(req, req.params.companyId)) {
        res.status(403).json({ error: 'No tiene acceso a esta empresa' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const result = await client.query('SELECT id FROM shared_checkers WHERE id=$1 AND company_id=$2 FOR UPDATE', [req.params.checkerId, req.params.companyId]);
        if (!result.rowCount) {
            await client.query('ROLLBACK');
            res.status(404).json({ error: 'Checador no encontrado' });
            return;
        }
        await client.query('DELETE FROM shared_checker_enrollment_codes WHERE checker_id=$1', [req.params.checkerId]);
        await client.query('DELETE FROM shared_checkers WHERE id=$1 AND company_id=$2', [req.params.checkerId, req.params.companyId]);
        await client.query('COMMIT');
        res.json({ deleted: true });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error eliminando checador:', error);
        res.status(500).json({ error: 'No se pudo eliminar el checador' });
    }
    finally {
        client.release();
    }
});
// Pública únicamente para canjear un código. NO permite registrar asistencias.
router.post('/shared-checkers/redeem', async (req, res) => {
    const code = typeof req.body?.code === 'string' ? req.body.code.trim().toUpperCase() : '';
    if (!/^[A-F0-9]{10}$/.test(code)) {
        res.status(400).json({ error: 'Código inválido' });
        return;
    }
    const hash = crypto_1.default.createHash('sha256').update(code).digest('hex');
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const found = await client.query(`SELECT ec.id, sc.id AS checker_id, sc.company_id,
       sc.site_id, sc.device_name
       FROM shared_checker_enrollment_codes ec
       JOIN shared_checkers sc ON sc.id=ec.checker_id
       JOIN companies c ON c.id=sc.company_id AND c.is_active=TRUE
       WHERE ec.code_hash=$1 AND ec.used_at IS NULL AND ec.expires_at>NOW()
         AND sc.is_active=FALSE AND sc.device_token_hash IS NULL
       FOR UPDATE OF ec, sc`, [hash]);
        if (!found.rowCount) {
            await client.query('ROLLBACK');
            res.status(400).json({ error: 'Código inválido, vencido o utilizado' });
            return;
        }
        const row = found.rows[0];
        const token = crypto_1.default.randomBytes(32).toString('hex');
        const tokenHash = crypto_1.default.createHash('sha256').update(token).digest('hex');
        await client.query('UPDATE shared_checker_enrollment_codes SET used_at=NOW() WHERE id=$1', [row.id]);
        await client.query(`UPDATE shared_checkers SET device_token_hash=$1,
      is_active=TRUE, linked_at=NOW() WHERE id=$2`, [tokenHash, row.checker_id]);
        await client.query('COMMIT');
        res.json({ linked: true, device_token: token, checker_id: row.checker_id,
            company_id: row.company_id, site_id: row.site_id, device_name: row.device_name });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error canjeando código compartido:', error);
        res.status(500).json({ error: 'No se pudo vincular el dispositivo' });
    }
    finally {
        client.release();
    }
});
/* ============================================================
   ASIGNACIÓN DE TRABAJADORES A TABLET COMPARTIDA
   No almacena rostros ni acepta marcajes sin prueba biométrica.
============================================================ */
function validUuid(value) {
    return typeof value === 'string' &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}
router.get('/companies/:companyId/shared-checkers/:checkerId/employees', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId, checkerId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Sin acceso a esta empresa' });
        return;
    }
    if (!validUuid(checkerId)) {
        res.status(400).json({ error: 'Checador inválido' });
        return;
    }
    try {
        const found = await client_1.default.query(`SELECT e.id_remote AS employee_id, e.employee_code, e.full_name,
                sce.enabled, sce.enrolled_at
         FROM shared_checker_employees sce
         JOIN employees e ON e.id_remote = sce.employee_id AND e.company_id = sce.company_id
         JOIN shared_checkers sc ON sc.id = sce.checker_id AND sc.company_id = sce.company_id
         WHERE sce.company_id = $1 AND sce.checker_id = $2
         ORDER BY e.full_name`, [companyId, checkerId]);
        res.json(found.rows);
    }
    catch (error) {
        console.error('Error consultando trabajadores del checador:', error);
        res.status(500).json({ error: 'No se pudo consultar la asignación' });
    }
});
router.post('/companies/:companyId/shared-checkers/:checkerId/employees', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId, checkerId } = req.params;
    const employeeCode = typeof req.body?.employee_code === 'string'
        ? req.body.employee_code.trim() : '';
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Sin acceso a esta empresa' });
        return;
    }
    if (!validUuid(checkerId) || !employeeCode || employeeCode.length > 100) {
        res.status(400).json({ error: 'Checador o código de empleado inválido' });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        const checker = await client.query(`SELECT id, site_id FROM shared_checkers
         WHERE id=$1 AND company_id=$2 AND is_active=TRUE FOR UPDATE`, [checkerId, companyId]);
        if (!checker.rowCount) {
            await client.query('ROLLBACK');
            res.status(404).json({ error: 'Tablet no vinculada o inexistente' });
            return;
        }
        const employees = await client.query(`SELECT id_remote, employee_code, full_name FROM employees
         WHERE company_id=$1 AND employee_code=$2`, [companyId, employeeCode]);
        if (employees.rowCount !== 1) {
            await client.query('ROLLBACK');
            res.status(404).json({ error: 'Código de empleado no encontrado o ambiguo' });
            return;
        }
        const employee = employees.rows[0];
        const existing = await client.query(`SELECT checker_id FROM shared_checker_employees
         WHERE employee_id=$1 FOR UPDATE`, [employee.id_remote]);
        if (existing.rowCount && existing.rows[0].checker_id !== checkerId) {
            await client.query('ROLLBACK');
            res.status(409).json({
                error: 'Este trabajador ya está asignado a otra tablet. Primero libera su asignación.'
            });
            return;
        }
        await client.query(`INSERT INTO shared_checker_employees (employee_id, checker_id, company_id, enabled)
         VALUES ($1,$2,$3,TRUE)
         ON CONFLICT (employee_id) DO UPDATE SET enabled=TRUE`, [employee.id_remote, checkerId, companyId]);
        await client.query('COMMIT');
        res.status(201).json({
            assigned: true, employee_id: employee.id_remote,
            employee_code: employee.employee_code, full_name: employee.full_name,
            checker_id: checkerId
        });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error asignando trabajador al checador:', error);
        res.status(500).json({ error: 'No se pudo asignar el trabajador' });
    }
    finally {
        client.release();
    }
});
router.delete('/companies/:companyId/shared-checkers/:checkerId/employees/:employeeId', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId, checkerId, employeeId } = req.params;
    if (!canAccessCompany(req, companyId)) {
        res.status(403).json({ error: 'Sin acceso a esta empresa' });
        return;
    }
    if (!validUuid(checkerId) || !validUuid(employeeId)) {
        res.status(400).json({ error: 'Identificador inválido' });
        return;
    }
    try {
        const removed = await client_1.default.query(`DELETE FROM shared_checker_employees
         WHERE company_id=$1 AND checker_id=$2 AND employee_id=$3
         RETURNING employee_id`, [companyId, checkerId, employeeId]);
        if (!removed.rowCount) {
            res.status(404).json({ error: 'Asignación no encontrada' });
            return;
        }
        res.json({
            removed: true,
            warning: 'La tablet debe eliminar también la plantilla facial local del trabajador.'
        });
    }
    catch (error) {
        console.error('Error quitando asignación:', error);
        res.status(500).json({ error: 'No se pudo quitar la asignación' });
    }
});
// La tablet solo puede consultar trabajadores asignados a ella.
// Este endpoint NO certifica identidad facial ni autoriza registrar asistencia.
router.post('/shared-checkers/employee-lookup', async (req, res) => {
    const token = typeof req.body?.device_token === 'string' ? req.body.device_token : '';
    const code = typeof req.body?.employee_code === 'string'
        ? req.body.employee_code.trim() : '';
    if (!/^[a-f0-9]{64}$/i.test(token) || !code || code.length > 100) {
        res.status(400).json({ error: 'Datos de consulta inválidos' });
        return;
    }
    try {
        const tokenHash = crypto_1.default.createHash('sha256').update(token).digest('hex');
        const result = await client_1.default.query(`SELECT e.id_remote AS employee_id, e.full_name, e.employee_code,
                sc.id AS checker_id, sc.site_id
         FROM shared_checkers sc
         JOIN shared_checker_employees sce
           ON sce.checker_id=sc.id AND sce.company_id=sc.company_id AND sce.enabled=TRUE
         JOIN employees e
           ON e.id_remote=sce.employee_id AND e.company_id=sc.company_id
         JOIN companies c ON c.id=sc.company_id AND c.is_active=TRUE
         WHERE sc.device_token_hash=$1 AND sc.is_active=TRUE
           AND e.employee_code=$2 LIMIT 1`, [tokenHash, code]);
        if (!result.rowCount) {
            res.status(404).json({ error: 'Empleado no habilitado para esta tablet' });
            return;
        }
        res.json(result.rows[0]);
    }
    catch (error) {
        console.error('Error buscando empleado del checador:', error);
        res.status(500).json({ error: 'No se pudo consultar al trabajador' });
    }
});
exports.default = router;
