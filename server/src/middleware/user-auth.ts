import { Request, Response, NextFunction } from 'express';
import jwt, { JwtPayload } from 'jsonwebtoken';
import pool from '../db/client';

export interface UserAuthRequest extends Request {
  user?: {
    id: string;
    companyId: string | null;
    companyIds: string[];
    role: string;
    mustChangePassword: boolean;
  };
}

interface UserTokenPayload extends JwtPayload { id: string; }

export async function userAuthMiddleware(
  req: UserAuthRequest, res: Response, next: NextFunction,
): Promise<void> {
  const secret = process.env.JWT_SECRET;
  if (!secret) { res.status(500).json({ error: 'JWT_SECRET no está configurado' }); return; }
  const match = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization ?? '');
  if (!match) { res.status(401).json({ error: 'Token de usuario requerido' }); return; }
  try {
    const payload = jwt.verify(match[1], secret);
    if (typeof payload === 'string' || typeof (payload as UserTokenPayload).id !== 'string') {
      res.status(401).json({ error: 'Token inválido' }); return;
    }
    const result = await pool.query(
      `SELECT id, company_id, role, must_change_password
       FROM users WHERE id=$1 AND is_active=TRUE`,
      [(payload as UserTokenPayload).id],
    );
    const user = result.rows[0];
    if (!user || !['superadmin', 'admin', 'worker'].includes(user.role)) {
      res.status(401).json({ error: 'Cuenta inexistente, inactiva o sin permisos' }); return;
    }
    const companyResult = user.role === 'admin'
      ? await pool.query(
          `SELECT uc.company_id FROM user_companies uc
           JOIN companies c ON c.id=uc.company_id AND c.is_active=TRUE
           WHERE uc.user_id=$1`, [user.id],
        )
      : { rows: [] };
    req.user = {
      id: user.id,
      companyId: user.company_id,
      companyIds: companyResult.rows.map((r: { company_id: string }) => r.company_id),
      role: user.role,
      mustChangePassword: user.must_change_password,
    };
    next();
  } catch (error) {
    if (error instanceof jwt.JsonWebTokenError || error instanceof jwt.TokenExpiredError) {
      res.status(401).json({ error: 'Token inválido o expirado' }); return;
    }
    next(error);
  }
}

export function requirePasswordChangeComplete(
  req: UserAuthRequest, res: Response, next: NextFunction,
): void {
  if (!req.user) { res.status(401).json({ error: 'Sesión requerida' }); return; }
  if (req.user.mustChangePassword) {
    res.status(403).json({ error: 'Debe cambiar su contraseña antes de continuar', code: 'PASSWORD_CHANGE_REQUIRED' });
    return;
  }
  next();
}

export function requireRole(...roles: string[]) {
  return (req: UserAuthRequest, res: Response, next: NextFunction): void => {
    if (!req.user || !roles.includes(req.user.role)) {
      res.status(403).json({ error: 'Permisos insuficientes' }); return;
    }
    next();
  };
}
