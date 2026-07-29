import 'dotenv/config';
import express from 'express';
import { initDb } from './db/migrate';
import devicesRouter from './routes/devices';
import attendanceRouter from './routes/attendance';
import adminRouter from './routes/admin';
import path from 'path';

const app = express();
const PORT = process.env.PORT ?? 3000;

app.use(express.json());
app.use(express.static(path.resolve(process.env.PUBLIC_DIR ?? 'public')));

if (process.env.NODE_ENV === 'production' && !process.env.JWT_SECRET) {
  throw new Error('JWT_SECRET es obligatorio en producción');
}

// Health check
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// Rutas
app.use('/api/devices', devicesRouter);
app.use('/api/attendance', attendanceRouter);
app.use('/api/admin', adminRouter);

// Arranque
initDb()
  .then(() => {
    app.listen(PORT, () => {
      console.log(`CoatiCheck API corriendo en puerto ${PORT}`);
    });
  })
  .catch((err) => {
    console.error('Error al inicializar la base de datos:', err);
    process.exit(1);
  });
