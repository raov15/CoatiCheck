import { Request, Response, NextFunction } from 'express';
import jwt, { JwtPayload } from 'jsonwebtoken';

const JWT_SECRET = process.env.JWT_SECRET;

export interface AuthRequest extends Request {
  deviceId?: string;
}

interface DeviceTokenPayload extends JwtPayload {
  deviceId: string;
}

export function authMiddleware(
  req: AuthRequest,
  res: Response,
  next: NextFunction,
): void {
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
    const payload = jwt.verify(token, JWT_SECRET);

    if (
      typeof payload === 'string' ||
      !payload ||
      typeof (payload as DeviceTokenPayload).deviceId !== 'string' ||
      !(payload as DeviceTokenPayload).deviceId.trim()
    ) {
      res.status(401).json({ error: 'Token inválido' });
      return;
    }

    req.deviceId = (payload as DeviceTokenPayload).deviceId;
    next();
  } catch {
    res.status(401).json({ error: 'Token inválido o expirado' });
  }
}