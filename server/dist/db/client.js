"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const pg_1 = require("pg");
const connectionString = process.env.DATABASE_URL;
if (!connectionString) {
    throw new Error('DATABASE_URL no está configurado');
}
const pool = new pg_1.Pool({
    connectionString,
});
pool.on('error', (err) => {
    console.error('Error inesperado en PostgreSQL:', err);
});
exports.default = pool;
