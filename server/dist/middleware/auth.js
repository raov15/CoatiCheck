"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.authMiddleware = authMiddleware;
const jsonwebtoken_1 = __importDefault(require("jsonwebtoken"));
const JWT_SECRET = process.env.JWT_SECRET;
function authMiddleware(req, res, next) {
    if (!JWT_SECRET) {
        res.status(500).json({ error: 'JWT_SECRET no está configurado' });
        return;
    }
    const header = req.headers.authorization;
    if (!header) {
        res.status(401).json({ error: 'Token requerido' });
        return;
    }
    const [scheme, token] = header.trim().split(/\s+/);
    if (scheme?.toLowerCase() !== 'bearer' || !token) {
        res.status(401).json({ error: 'Formato de token inválido' });
        return;
    }
    try {
        const payload = jsonwebtoken_1.default.verify(token, JWT_SECRET);
        if (typeof payload === 'string' ||
            !payload ||
            typeof payload.deviceId !== 'string' ||
            !payload.deviceId.trim()) {
            res.status(401).json({ error: 'Token inválido' });
            return;
        }
        req.deviceId = payload.deviceId;
        next();
    }
    catch {
        res.status(401).json({ error: 'Token inválido o expirado' });
    }
}
