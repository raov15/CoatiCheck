import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';

const JWT_SECRET = process.env.JWT_SECRET;

export interface UserAuthRequest extends Request {
  user?: { id: string; companyId: string | null; role: string; mustChangePassword: boolean };
}

interface UserTokenPayload {
  id: string;
  companyId: string | null;
  role: string;
  mustChangePassword: boolean;
}

export function userAuthMiddleware(req: UserAuthRequest, res: Response, next: NextFunction): void {
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
    const payload = jwt.verify(header.slice(7), JWT_SECRET) as UserTokenPayload;
    req.user = payload;
    next();
  } catch {
    res.status(401).json({ error: 'Token invalido o expirado' });
  }
}

export function requirePasswordChangeComplete(
  req: UserAuthRequest,
  res: Response,
  next: NextFunction,
): void {
  if (req.user?.mustChangePassword) {
    res.status(403).json({ error: 'Debe cambiar su contraseña antes de continuar', code: 'PASSWORD_CHANGE_REQUIRED' });
    return;
  }
  next();
}

export function requireRole(...roles: string[]) {
  return (req: UserAuthRequest, res: Response, next: NextFunction): void => {
    if (!req.user || !roles.includes(req.user.role)) {
      res.status(403).json({ error: 'Permisos insuficientes' });
      return;
    }
    next();
  };
}