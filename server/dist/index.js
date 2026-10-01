"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
require("dotenv/config");
const express_1 = __importDefault(require("express"));
const path_1 = __importDefault(require("path"));
const migrate_1 = require("./db/migrate");
const devices_1 = __importDefault(require("./routes/devices"));
const employees_1 = __importDefault(require("./routes/employees"));
const attendance_1 = __importDefault(require("./routes/attendance"));
const admin_1 = __importDefault(require("./routes/admin"));
const app = (0, express_1.default)();
const PORT = Number(process.env.PORT ?? 3000);
/* =========================
   SEGURIDAD
========================= */
if (process.env.NODE_ENV === 'production' &&
    !process.env.JWT_SECRET) {
    throw new Error('JWT_SECRET es obligatorio en producción');
}
/* =========================
   MIDDLEWARE
========================= */
app.disable('x-powered-by');
app.use(express_1.default.json({
    limit: '10mb',
}));
/* =========================
   ARCHIVOS PÚBLICOS
========================= */
const publicDir = path_1.default.resolve(process.env.PUBLIC_DIR ?? path_1.default.join(process.cwd(), 'public'));
console.log('Sirviendo archivos públicos desde:', publicDir);
app.use(express_1.default.static(publicDir));
app.use('/img', express_1.default.static(path_1.default.join(publicDir, 'img')));
/* =========================
   HEALTH CHECK
========================= */
app.get('/api/health', (_req, res) => {
    res.status(200).json({
        status: 'ok',
        timestamp: new Date().toISOString(),
    });
});
/* =========================
   RUTAS API
========================= */
app.use('/api/devices', devices_1.default);
app.use('/api/employees', employees_1.default);
app.use('/api/attendance', attendance_1.default);
app.use('/api/admin', admin_1.default);
/* =========================
   MANEJO DE RUTAS API 404
========================= */
app.use('/api', (_req, res) => {
    res.status(404).json({
        error: 'Ruta API no encontrada',
    });
});
/* =========================
   ARRANQUE
========================= */
async function start() {
    try {
        await (0, migrate_1.initDb)();
        /*
         * No se especifica host de forma manual.
         * Esto mantiene compatibilidad con el healthcheck actual:
         * http://localhost:3000/api/health
         */
        app.listen(PORT, () => {
            console.log(`CoatiCheck API corriendo en puerto ${PORT}`);
            console.log(`Public dir: ${publicDir}`);
        });
    }
    catch (err) {
        console.error('Error al inicializar la base de datos:', err);
        process.exit(1);
    }
}
void start();
