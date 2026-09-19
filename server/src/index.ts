import 'dotenv/config';
import express from 'express';
import path from 'path';

import { initDb } from './db/migrate';
import devicesRouter from './routes/devices';
import employeesRouter from './routes/employees';
import attendanceRouter from './routes/attendance';
import adminRouter from './routes/admin';

const app = express();
const PORT = process.env.PORT ?? 3000;

app.use(express.json());

/* =========================
   ARCHIVOS PÚBLICOS
========================= */

const publicDir = path.resolve(
  process.env.PUBLIC_DIR ?? path.join(process.cwd(), 'public')
);

console.log('Sirviendo archivos públicos desde:', publicDir);

app.use(express.static(publicDir));

/* Ruta explícita para imágenes */
app.use(
  '/img',
  express.static(path.join(publicDir, 'img'))
);

/* =========================
   SEGURIDAD
========================= */

if (
  process.env.NODE_ENV === 'production' &&
  !process.env.JWT_SECRET
) {
  throw new Error(
    'JWT_SECRET es obligatorio en producción'
  );
}

/* =========================
   HEALTH CHECK
========================= */

app.get('/api/health', (_req, res) => {
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString()
  });
});

/* =========================
   RUTAS API
========================= */

app.use('/api/devices', devicesRouter);

app.use(
  '/api/employees',
  employeesRouter
);

app.use(
  '/api/attendance',
  attendanceRouter
);

app.use(
  '/api/admin',
  adminRouter
);

/* =========================
   ARRANQUE
========================= */

initDb()
  .then(() => {

    app.listen(PORT, () => {

      console.log(
        `CoatiCheck API corriendo en puerto ${PORT}`
      );

      console.log(
        `Public dir: ${publicDir}`
      );

    });

  })
  .catch((err) => {

    console.error(
      'Error al inicializar la base de datos:',
      err
    );

    process.exit(1);

  });