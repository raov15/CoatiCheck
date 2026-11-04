"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.userAuthMiddleware = userAuthMiddleware;
exports.requirePasswordChangeComplete = requirePasswordChangeComplete;
exports.requireRole = requireRole;
const jsonwebtoken_1 = __importDefault(require("jsonwebtoken"));
const client_1 = __importDefault(require("../db/client"));
async function userAuthMiddleware(req, res, next) {
    const secret = process.env.JWT_SECRET;
    if (!secret) {
        res.status(500).json({ error: 'JWT_SECRET no está configurado' });
        return;
    }
    const match = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization ?? '');
    if (!match) {
        res.status(401).json({ error: 'Token de usuario requerido' });
        return;
    }
    try {
        const payload = jsonwebtoken_1.default.verify(match[1], secret);
        if (typeof payload === 'string' || typeof payload.id !== 'string') {
            res.status(401).json({ error: 'Token inválido' });
            return;
        }
        const result = await client_1.default.query(`SELECT id, company_id, role, must_change_password
       FROM users WHERE id=$1 AND is_active=TRUE`, [payload.id]);
        const user = result.rows[0];
        if (!user || !['superadmin', 'admin', 'worker'].includes(user.role)) {
            res.status(401).json({ error: 'Cuenta inexistente, inactiva o sin permisos' });
            return;
        }
        const companyResult = user.role === 'admin'
            ? await client_1.default.query(`SELECT uc.company_id FROM user_companies uc
           JOIN companies c ON c.id=uc.company_id AND c.is_active=TRUE
           WHERE uc.user_id=$1`, [user.id])
            : { rows: [] };
        req.user = {
            id: user.id,
            companyId: user.company_id,
            companyIds: companyResult.rows.map((r) => r.company_id),
            role: user.role,
            mustChangePassword: user.must_change_password,
        };
        next();
    }
    catch (error) {
        if (error instanceof jsonwebtoken_1.default.JsonWebTokenError || error instanceof jsonwebtoken_1.default.TokenExpiredError) {
            res.status(401).json({ error: 'Token inválido o expirado' });
            return;
        }
        next(error);
    }
}
function requirePasswordChangeComplete(req, res, next) {
    if (!req.user) {
        res.status(401).json({ error: 'Sesión requerida' });
        return;
    }
    if (req.user.mustChangePassword) {
        res.status(403).json({ error: 'Debe cambiar su contraseña antes de continuar', code: 'PASSWORD_CHANGE_REQUIRED' });
        return;
    }
    next();
}
function requireRole(...roles) {
    return (req, res, next) => {
        if (!req.user || !roles.includes(req.user.role)) {
            res.status(403).json({ error: 'Permisos insuficientes' });
            return;
        }
        next();
    };
}
