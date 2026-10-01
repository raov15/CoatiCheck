"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.userAuthMiddleware = userAuthMiddleware;
exports.requirePasswordChangeComplete = requirePasswordChangeComplete;
exports.requireRole = requireRole;
const jsonwebtoken_1 = __importDefault(require("jsonwebtoken"));
const JWT_SECRET = process.env.JWT_SECRET;
function userAuthMiddleware(req, res, next) {
    if (!JWT_SECRET) {
        res.status(500).json({ error: 'JWT_SECRET no está configurado' });
        return;
    }
    const header = req.headers.authorization;
    if (!header?.startsWith('Bearer ')) {
        res.status(401).json({ error: 'Token de usuario requerido' });
        return;
    }
    try {
        const payload = jsonwebtoken_1.default.verify(header.slice(7), JWT_SECRET);
        req.user = payload;
        next();
    }
    catch {
        res.status(401).json({ error: 'Token invalido o expirado' });
    }
}
function requirePasswordChangeComplete(req, res, next) {
    if (req.user?.mustChangePassword) {
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
