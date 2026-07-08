import 'dotenv/config';
import express from 'express';
import { initDb } from './db/migrate';
import devicesRouter from './routes/devices';
import attendanceRouter from './routes/attendance';

const app = express();
const PORT = process.env.PORT ?? 3000;

app.use(express.json());

// Health check
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// Rutas
app.use('/api/devices', devicesRouter);
app.use('/api/attendance', attendanceRouter);

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
