import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';

const JWT_SECRET = process.env.JWT_SECRET;

export interface AuthRequest extends Request {
  deviceId?: string;
}

export function authMiddleware(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): void {
  if (!JWT_SECRET) {
    res.status(500).json({ error: 'JWT_SECRET no está configurado' });
    return;
  }
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) {
    res.status(401).json({ error: 'Token requerido' });
    return;
  }

  const token = header.slice(7);
  try {
    const payload = jwt.verify(token, JWT_SECRET) as { deviceId: string };
    req.deviceId = payload.deviceId;
    next();
  } catch {
    res.status(401).json({ error: 'Token invalido o expirado' });
  }
}
