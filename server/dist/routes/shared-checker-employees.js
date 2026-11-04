"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = require("express");
const crypto_1 = __importDefault(require("crypto"));
const client_1 = __importDefault(require("../db/client"));
const user_auth_1 = require("../middleware/user-auth");
const router = (0, express_1.Router)();
const uuid = (v) => typeof v === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const field = (v, limit = 120) => typeof v === 'string' ? v.trim().slice(0, limit) : '';
router.post('/companies/:companyId/shared-checkers/:checkerId/new-employee', user_auth_1.userAuthMiddleware, user_auth_1.requirePasswordChangeComplete, (0, user_auth_1.requireRole)('admin', 'superadmin'), async (req, res) => {
    const { companyId, checkerId } = req.params;
    if (!uuid(companyId) || !uuid(checkerId)) {
        res.status(400).json({
            error: 'Identificador inválido'
        });
        return;
    }
    const user = req.user;
    if (!user ||
        (user.role !== 'superadmin' &&
            !(user.role === 'admin' &&
                user.companyIds?.includes(companyId)))) {
        res.status(403).json({
            error: 'Sin acceso a esta empresa'
        });
        return;
    }
    let code = '';
    const first = field(req.body?.first_name);
    const paternal = field(req.body?.last_name_paternal);
    const maternal = field(req.body?.last_name_maternal);
    const rfc = field(req.body?.rfc, 13).toUpperCase();
    const curp = field(req.body?.curp, 18).toUpperCase();
    const nss = field(req.body?.nss, 11);
    const department = field(req.body?.department);
    const phone = field(req.body?.phone, 30);
    const hireDate = field(req.body?.hire_date, 10);
    // Validaciones
    if (!first || !paternal) {
        res.status(400).json({
            error: 'Nombre y apellido paterno son obligatorios'
        });
        return;
    }
    if (rfc && !/^[A-ZÑ&0-9]{12,13}$/.test(rfc)) {
        res.status(400).json({
            error: 'RFC inválido'
        });
        return;
    }
    if (curp && !/^[A-Z0-9]{18}$/.test(curp)) {
        res.status(400).json({
            error: 'CURP inválida'
        });
        return;
    }
    if (nss && !/^\d{11}$/.test(nss)) {
        res.status(400).json({
            error: 'NSS debe tener 11 dígitos'
        });
        return;
    }
    if (hireDate &&
        (!/^\d{4}-\d{2}-\d{2}$/.test(hireDate) ||
            Number.isNaN(Date.parse(hireDate)))) {
        res.status(400).json({
            error: 'Fecha de ingreso inválida'
        });
        return;
    }
    const client = await client_1.default.connect();
    try {
        await client.query('BEGIN');
        // Verificar que la tablet pertenezca a la empresa.
        const checker = await client.query(`SELECT id, site_id
         FROM shared_checkers
         WHERE id = $1
           AND company_id = $2
           AND is_active = TRUE
         FOR UPDATE`, [checkerId, companyId]);
        if (!checker.rowCount) {
            await client.query('ROLLBACK');
            res.status(404).json({
                error: 'Primero vincula la tablet'
            });
            return;
        }
        const siteId = checker.rows[0].site_id;
        // Bloqueo por empresa.
        // Utiliza el mismo bloqueo que employees.ts
        // para evitar códigos duplicados cuando se
        // registran trabajadores simultáneamente.
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [String(companyId)]);
        // Obtener el slug de la empresa.
        const companyResult = await client.query(`SELECT slug
         FROM companies
         WHERE id = $1
         LIMIT 1`, [companyId]);
        if (!companyResult.rows.length) {
            throw new Error('Empresa no encontrada para generar código');
        }
        const prefix = String(companyResult.rows[0].slug || '').trim().toUpperCase();
        if (!prefix) {
            throw new Error('La empresa no tiene un identificador válido');
        }
        // Obtener el siguiente número disponible
        // únicamente para esta empresa.
        //
        // Ejemplos:
        // COA261102AAA-0004
        // ICA0903308R8-0003
        // GUHSO211215T-0001
        const codeResult = await client.query(`SELECT COALESCE(
          MAX(
            CASE
              WHEN split_part(employee_code, '-', 1) = $2
               AND split_part(employee_code, '-', 2) ~ '^[0-9]+$'
              THEN split_part(employee_code, '-', 2)::integer
              ELSE 0
            END
          ),
          0
        ) + 1 AS next_number
        FROM employees
        WHERE company_id = $1`, [companyId, prefix]);
        const nextNumber = Number(codeResult.rows[0].next_number);
        code = `${prefix}-${String(nextNumber).padStart(4, '0')}`;
        // Generar identificadores internos únicos.
        const remoteId = crypto_1.default.randomUUID();
        const localId = crypto_1.default.randomUUID();
        const fullName = [
            first,
            paternal,
            maternal
        ].filter(Boolean).join(' ');
        // Registrar trabajador en la empresa.
        await client.query(`INSERT INTO employees (
          id_remote,
          id_local,
          company_id,
          site_id,
          employee_code,
          first_name,
          last_name_paternal,
          last_name_maternal,
          full_name,
          rfc,
          curp,
          nss,
          phone,
          department,
          hire_date,
          is_active
        )
        VALUES (
          $1, $2, $3, $4,
          $5, $6, $7, $8,
          $9, $10, $11, $12,
          $13, $14, $15::date, TRUE
        )`, [
            remoteId,
            localId,
            companyId,
            siteId,
            code,
            first,
            paternal,
            maternal,
            fullName,
            rfc || null,
            curp || null,
            nss || null,
            phone || null,
            department || null,
            hireDate || null
        ]);
        // Asignar el trabajador a la tablet.
        await client.query(`INSERT INTO shared_checker_employees (
          employee_id,
          checker_id,
          company_id,
          enabled
        )
        VALUES ($1, $2, $3, TRUE)`, [
            remoteId,
            checkerId,
            companyId
        ]);
        // Confirmar ambas operaciones.
        await client.query('COMMIT');
        res.status(201).json({
            created: true,
            assigned: true,
            employee_id: remoteId,
            employee_code: code,
            full_name: fullName
        });
    }
    catch (error) {
        await client.query('ROLLBACK');
        console.error('Error creando trabajador para tablet:', error);
        res.status(500).json({
            error: 'No se pudo registrar el trabajador. Revisa el registro del servidor.'
        });
    }
    finally {
        client.release();
    }
});
exports.default = router;
