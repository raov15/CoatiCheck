"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.initDb = initDb;
const client_1 = __importDefault(require("./client"));
const bcryptjs_1 = __importDefault(require("bcryptjs"));
async function initDb() {
    await client_1.default.query(`
    CREATE EXTENSION IF NOT EXISTS pgcrypto;

    /* =========================================================
       EMPRESAS
    ========================================================= */

    CREATE TABLE IF NOT EXISTS companies (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      name TEXT NOT NULL,
      slug TEXT NOT NULL UNIQUE,
      logo_path TEXT,
      timezone TEXT NOT NULL DEFAULT 'America/Mexico_City',
      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    /* =========================================================
       CENTROS DE TRABAJO
    ========================================================= */

    CREATE TABLE IF NOT EXISTS sites (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL
        REFERENCES companies(id)
        ON DELETE CASCADE,
      name TEXT NOT NULL,
      latitude DOUBLE PRECISION,
      longitude DOUBLE PRECISION,
      radius_m INTEGER NOT NULL DEFAULT 300,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(company_id, name)
    );


    ALTER TABLE sites ADD COLUMN IF NOT EXISTS site_key TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS postal_code TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS road_type TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS street TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS street_number TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS interior_number TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS neighborhood TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS locality TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS municipality TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS state TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS between_streets TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS reference_street TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS observations TEXT;
    ALTER TABLE sites ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

    /* =========================================================
       USUARIOS ADMINISTRATIVOS
    ========================================================= */

    CREATE TABLE IF NOT EXISTS users (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID
        REFERENCES companies(id)
        ON DELETE CASCADE,
      site_id UUID
        REFERENCES sites(id)
        ON DELETE SET NULL,
      username TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      full_name TEXT NOT NULL,
      role TEXT NOT NULL
        CHECK (
          role IN (
            'admin',
            'administrator',
            'operator'
          )
        ),
      employee_code TEXT,
      must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    /* =========================================================
       POLÍTICAS / REGLAMENTO POR EMPRESA

       Se guarda como un texto completo para que el panel muestre
       el reglamento de asistencia y ausentismo en un área amplia.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS company_policies (
      company_id UUID PRIMARY KEY
        REFERENCES companies(id)
        ON DELETE CASCADE,
      policy_text TEXT NOT NULL DEFAULT '',
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_by UUID
        REFERENCES users(id)
        ON DELETE SET NULL
    );

    /* =========================================================
       CATEGORÍAS

       La configuración laboral se guarda por categoría.
       Cada empresa puede tener tantas categorías como necesite.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS categories (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL
        REFERENCES companies(id)
        ON DELETE CASCADE,
      category_number INTEGER NOT NULL
        CHECK (category_number >= 1),
      name TEXT NOT NULL,
      start_time TEXT,
      end_time TEXT,
      tolerance_minutes INTEGER NOT NULL DEFAULT 10
        CHECK (tolerance_minutes >= 0),

      /*
       * Tiempo extraordinario:
       * TRUE  = Sí permitido. No requiere valor adicional.
       * FALSE = No permitido. La interfaz podrá pedir un valor.
       */
      overtime_allowed BOOLEAN NOT NULL DEFAULT TRUE,
      overtime_value NUMERIC(10,2),

      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      configured_at TIMESTAMPTZ,

      UNIQUE(company_id, category_number),
      UNIQUE(company_id, name),

      CHECK (
        overtime_allowed = TRUE
        OR overtime_value IS NOT NULL
      )
    );

    ALTER TABLE categories
      ADD COLUMN IF NOT EXISTS configured_at TIMESTAMPTZ;

    ALTER TABLE categories
      ADD COLUMN IF NOT EXISTS meal_start_time TEXT;

    ALTER TABLE categories
      ADD COLUMN IF NOT EXISTS meal_end_time TEXT;

    /* =========================================================
       DÍAS LABORALES POR CATEGORÍA

       weekday usa ISO:
       1=Lunes, 2=Martes, 3=Miércoles, 4=Jueves,
       5=Viernes, 6=Sábado, 7=Domingo.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS category_workdays (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      category_id UUID NOT NULL
        REFERENCES categories(id)
        ON DELETE CASCADE,
      weekday SMALLINT NOT NULL
        CHECK (weekday BETWEEN 1 AND 7),
      is_workday BOOLEAN NOT NULL DEFAULT TRUE,
      start_time TEXT,
      end_time TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(category_id, weekday)
    );

    /* =========================================================
       DÍAS DE DESCANSO POR CATEGORÍA

       Permite cero, uno o varios descansos semanales.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS category_rest_days (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      category_id UUID NOT NULL
        REFERENCES categories(id)
        ON DELETE CASCADE,
      weekday SMALLINT NOT NULL
        CHECK (weekday BETWEEN 1 AND 7),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(category_id, weekday)
    );

    /* =========================================================
       VALORES DE INCIDENCIAS POR CATEGORÍA

       Aquí solo existe CONCEPTO + VALOR.
       No existe "clasificación".
       Tiempo extraordinario se configura en categories para
       que no aparezca dos veces.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS category_incidence_values (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      category_id UUID NOT NULL
        REFERENCES categories(id)
        ON DELETE CASCADE,
      concept TEXT NOT NULL,
      value NUMERIC(10,2) NOT NULL DEFAULT 0,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(category_id, concept),
      CHECK (
        concept IN (
          'Ausentismo',
          'Retardo',
          'Permiso médico justificado',
          'Permiso médico no justificado',
          'Permiso de salida',
          'Falta justificada'
        )
      )
    );

    /* =========================================================
       DISPOSITIVOS
    ========================================================= */

    CREATE TABLE IF NOT EXISTS devices (
      id_remote UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local TEXT NOT NULL UNIQUE,
      device_name TEXT NOT NULL,
      fingerprint TEXT NOT NULL,
      company_id UUID
        REFERENCES companies(id),
      site_id UUID
        REFERENCES sites(id),
      employee_id UUID,
      auth_token TEXT,
      registered_at BIGINT NOT NULL
        DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    /* =========================================================
       CÓDIGOS DE VINCULACIÓN
    ========================================================= */

    CREATE TABLE IF NOT EXISTS device_enrollment_codes (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL
        REFERENCES companies(id)
        ON DELETE CASCADE,
      site_id UUID
        REFERENCES sites(id)
        ON DELETE SET NULL,
      code_hash TEXT NOT NULL UNIQUE,
      expires_at TIMESTAMPTZ NOT NULL,
      used_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    /* =========================================================
       EMPLEADOS
    ========================================================= */

    CREATE TABLE IF NOT EXISTS employees (
      id_remote UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local TEXT NOT NULL UNIQUE,
      employee_code TEXT NOT NULL UNIQUE,
      first_name TEXT,
      last_name_paternal TEXT,
      last_name_maternal TEXT,
      full_name TEXT NOT NULL,
      rfc TEXT,
      curp TEXT,
      nss TEXT,
      phone TEXT,
      department TEXT,

      /*
       * Se conservan por compatibilidad con instalaciones
       * existentes. La configuración real de horario se hará
       * por categoría.
       */
      start_time TEXT,
      end_time TEXT,

      face_image TEXT,
      hire_date DATE,
      category_id UUID
        REFERENCES categories(id)
        ON DELETE SET NULL,
      company_id UUID
        REFERENCES companies(id),

      /*
       * Se conserva como centro general/de compatibilidad.
       * Para la validación diaria se usará employee_work_sites.
       */
      site_id UUID
        REFERENCES sites(id),

      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE employees ADD COLUMN IF NOT EXISTS workdays_customized BOOLEAN NOT NULL DEFAULT FALSE;
    ALTER TABLE employees ADD COLUMN IF NOT EXISTS workdays_customized_at TIMESTAMPTZ;

    /* =========================================================
       CENTRO DE TRABAJO DEL EMPLEADO POR DÍA

       Permite, por ejemplo:
       Lunes      -> Oficina A
       Miércoles  -> Oficina B
       Resto      -> Oficina A
    ========================================================= */

    CREATE TABLE IF NOT EXISTS employee_work_sites (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      employee_id UUID NOT NULL
        REFERENCES employees(id_remote)
        ON DELETE CASCADE,
      weekday SMALLINT NOT NULL
        CHECK (weekday BETWEEN 1 AND 7),
      site_id UUID
        REFERENCES sites(id)
        ON DELETE CASCADE,
      work_mode TEXT NOT NULL DEFAULT 'SITE'
        CHECK (work_mode IN ('SITE', 'FOREIGN')),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(employee_id, weekday)
    );

    /* =========================================================
       ASISTENCIAS
    ========================================================= */

    CREATE TABLE IF NOT EXISTS attendance_records (
      id_remote UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local TEXT NOT NULL UNIQUE,
      employee_id TEXT NOT NULL,
      event_type TEXT NOT NULL,
      occurred_at BIGINT NOT NULL,
      latitude DOUBLE PRECISION,
      longitude DOUBLE PRECISION,
      accuracy_m REAL,
      altitude_m DOUBLE PRECISION,
      face_confidence REAL,
      device_id TEXT,
      company_id UUID
        REFERENCES companies(id),
      site_id UUID
        REFERENCES sites(id),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    /* =========================================================
       RECORRIDO GPS DE TRABAJADORES FORÁNEOS

       Cada punto se guarda automáticamente desde la app móvil
       durante una jornada foránea. Los puntos se conservan por
       trabajador, fecha y hora para reconstruir el recorrido.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS employee_location_points (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL
        REFERENCES companies(id)
        ON DELETE CASCADE,
      employee_id UUID NOT NULL
        REFERENCES employees(id_remote)
        ON DELETE CASCADE,
      device_id UUID
        REFERENCES devices(id_remote)
        ON DELETE SET NULL,
      work_date DATE NOT NULL,
      recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      latitude DOUBLE PRECISION NOT NULL
        CHECK (latitude BETWEEN -90 AND 90),
      longitude DOUBLE PRECISION NOT NULL
        CHECK (longitude BETWEEN -180 AND 180),
      accuracy_m REAL,
      altitude_m DOUBLE PRECISION,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS idx_employee_location_points_company_date
      ON employee_location_points(company_id, work_date DESC);

    CREATE INDEX IF NOT EXISTS idx_employee_location_points_employee_date
      ON employee_location_points(employee_id, work_date DESC, recorded_at ASC);

    CREATE INDEX IF NOT EXISTS idx_employee_location_points_recorded_at
      ON employee_location_points(recorded_at DESC);

    /* Evita guardar exactamente el mismo punto dos veces para el mismo trabajador. */
    CREATE UNIQUE INDEX IF NOT EXISTS uq_employee_location_points_employee_recorded_at
      ON employee_location_points(employee_id, recorded_at);

    /* =========================================================
       INCIDENCIAS CALIFICADAS

       Cuando el administrador elige, por ejemplo, "Retardo",
       applied_value se toma automáticamente del valor configurado
       para la categoría del trabajador.
    ========================================================= */

    ALTER TABLE employee_work_sites
      ALTER COLUMN site_id DROP NOT NULL;

    ALTER TABLE employee_work_sites
      ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

    ALTER TABLE employee_work_sites
      ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

    CREATE TABLE IF NOT EXISTS qualified_incidents (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL
        REFERENCES companies(id)
        ON DELETE CASCADE,
      attendance_record_id UUID
        REFERENCES attendance_records(id_remote)
        ON DELETE CASCADE,
      employee_id UUID NOT NULL
        REFERENCES employees(id_remote)
        ON DELETE CASCADE,
      category_id UUID
        REFERENCES categories(id)
        ON DELETE SET NULL,
      concept TEXT NOT NULL,
      incident_date DATE NOT NULL DEFAULT CURRENT_DATE,
      applied_value NUMERIC(10,2) NOT NULL DEFAULT 0,
      notes TEXT,
      qualified_by UUID
        REFERENCES users(id)
        ON DELETE SET NULL,
      qualified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

      CHECK (
        concept IN (
          'Ausentismo',
          'Retardo',
          'Permiso médico justificado',
          'Permiso médico no justificado',
          'Permiso de salida',
          'Falta justificada',
          'Tiempo extraordinario'
        )
      )
    );

    /* =========================================================
       MIGRACIONES PARA BASES YA EXISTENTES
    ========================================================= */

    ALTER TABLE companies
      ADD COLUMN IF NOT EXISTS timezone TEXT DEFAULT 'America/Mexico_City';

    UPDATE companies
    SET timezone = 'America/Mexico_City'
    WHERE timezone IS NULL OR BTRIM(timezone) = '';

    ALTER TABLE companies
      ALTER COLUMN timezone SET DEFAULT 'America/Mexico_City';

    ALTER TABLE companies
      ALTER COLUMN timezone SET NOT NULL;

    ALTER TABLE sites
      ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;

    ALTER TABLE sites
      ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;

    ALTER TABLE sites
      ADD COLUMN IF NOT EXISTS radius_m INTEGER DEFAULT 300;

    UPDATE sites
    SET radius_m = 300
    WHERE radius_m IS DISTINCT FROM 300;

    ALTER TABLE sites
      ALTER COLUMN radius_m SET DEFAULT 300;

    ALTER TABLE sites
      ALTER COLUMN radius_m SET NOT NULL;

    ALTER TABLE devices
      ADD COLUMN IF NOT EXISTS company_id UUID
      REFERENCES companies(id);

    ALTER TABLE devices
      ADD COLUMN IF NOT EXISTS site_id UUID
      REFERENCES sites(id);

    ALTER TABLE devices
      ADD COLUMN IF NOT EXISTS employee_id UUID;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS first_name TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS last_name_paternal TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS last_name_maternal TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS rfc TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS curp TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS nss TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS phone TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS department TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS start_time TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS end_time TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS face_image TEXT;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS hire_date DATE;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS category_id UUID
      REFERENCES categories(id)
      ON DELETE SET NULL;

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS company_id UUID
      REFERENCES companies(id);

    ALTER TABLE employees
      ADD COLUMN IF NOT EXISTS site_id UUID
      REFERENCES sites(id);

    ALTER TABLE attendance_records
      ADD COLUMN IF NOT EXISTS company_id UUID
      REFERENCES companies(id);

    ALTER TABLE attendance_records
      ADD COLUMN IF NOT EXISTS site_id UUID
      REFERENCES sites(id);

    /* =========================================================
       RANGO FIJO DE 300 METROS
    ========================================================= */

    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'sites_radius_300_check'
      ) THEN
        ALTER TABLE sites
          ADD CONSTRAINT sites_radius_300_check
          CHECK (radius_m = 300);
      END IF;
    END $$;

    /* =========================================================
       RELACIÓN DISPOSITIVO -> EMPLEADO
    ========================================================= */

    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'devices_employee_id_fkey'
      ) THEN
        ALTER TABLE devices
          ADD CONSTRAINT devices_employee_id_fkey
          FOREIGN KEY (employee_id)
          REFERENCES employees(id_remote)
          ON DELETE SET NULL;
      END IF;
    END $$;

    /* =========================================================
       CATEGORÍAS DINÁMICAS + MIGRACIÓN SEGURA

       - Ya NO se crean Categoría 1, 2 y 3 automáticamente.
       - category_number deja de estar limitado a 1..3.
       - Las categorías existentes se conservan.
       - Los valores faltantes de incidencias nacen en 0.
       - La corrección de los antiguos valores predeterminados 1 -> 0
         se ejecuta una sola vez para no pisar cambios posteriores
         hechos por el administrador.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS app_schema_migrations (
      migration_key TEXT PRIMARY KEY,
      applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE categories
      DROP CONSTRAINT IF EXISTS categories_category_number_check;

    DO $$
    DECLARE
      constraint_name TEXT;
    BEGIN
      SELECT con.conname
      INTO constraint_name
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
      JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
      WHERE nsp.nspname = 'public'
        AND rel.relname = 'categories'
        AND con.contype = 'c'
        AND pg_get_constraintdef(con.oid) ILIKE '%category_number%between%1%3%'
      LIMIT 1;

      IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE categories DROP CONSTRAINT %I', constraint_name);
      END IF;
    END $$;

    ALTER TABLE categories
      ALTER COLUMN category_number TYPE INTEGER;

    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'categories_category_number_positive_check'
      ) THEN
        ALTER TABLE categories
          ADD CONSTRAINT categories_category_number_positive_check
          CHECK (category_number >= 1);
      END IF;
    END $$;

    /* Eliminar la automatización antigua de Categoría 1, 2 y 3. */
    DROP TRIGGER IF EXISTS trg_create_default_company_categories ON companies;
    DROP FUNCTION IF EXISTS create_default_company_categories();

    /*
     * Corrección UNA SOLA VEZ de los cuatro conceptos que antes
     * se sembraban en 1. Después de esta marca, migrate.ts nunca
     * volverá a modificar un valor que el usuario cambie manualmente.
     */
    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1
        FROM app_schema_migrations
        WHERE migration_key = 'incidence_permission_defaults_to_zero_v1'
      ) THEN
        UPDATE category_incidence_values
        SET value = 0, updated_at = NOW()
        WHERE concept IN (
          'Permiso médico justificado',
          'Permiso médico no justificado',
          'Permiso de salida',
          'Falta justificada'
        )
        AND value = 1;

        INSERT INTO app_schema_migrations (migration_key)
        VALUES ('incidence_permission_defaults_to_zero_v1');
      END IF;
    END $$;

    /*
     * Si una categoría existente no tiene alguno de estos conceptos,
     * se crea en 0. ON CONFLICT DO NOTHING conserva cualquier valor
     * que ya haya sido configurado por el usuario.
     */
    INSERT INTO category_incidence_values (category_id, concept, value)
    SELECT cat.id, v.concept, 0
    FROM categories cat
    CROSS JOIN (
      VALUES
        ('Ausentismo'),
        ('Retardo'),
        ('Permiso médico justificado'),
        ('Permiso médico no justificado'),
        ('Permiso de salida'),
        ('Falta justificada')
    ) AS v(concept)
    ON CONFLICT (category_id, concept) DO NOTHING;

    /*
     * Mantener los siete días disponibles para cada categoría existente.
     * No sobrescribe horarios ya configurados.
     */
    INSERT INTO category_workdays (
      category_id, weekday, is_workday, start_time, end_time
    )
    SELECT
      cat.id,
      d.weekday,
      CASE WHEN d.weekday BETWEEN 1 AND 5 THEN TRUE ELSE FALSE END,
      cat.start_time,
      cat.end_time
    FROM categories cat
    CROSS JOIN (VALUES (1), (2), (3), (4), (5), (6), (7)) AS d(weekday)
    ON CONFLICT (category_id, weekday) DO NOTHING;

    /* =========================================================
       ÍNDICES ÚTILES
    ========================================================= */

    CREATE INDEX IF NOT EXISTS idx_employees_company_id
      ON employees(company_id);

    CREATE INDEX IF NOT EXISTS idx_employees_site_id
      ON employees(site_id);

    CREATE INDEX IF NOT EXISTS idx_employees_category_id
      ON employees(category_id);

    CREATE INDEX IF NOT EXISTS idx_employees_hire_date
      ON employees(hire_date);

    CREATE INDEX IF NOT EXISTS idx_employees_rfc
      ON employees(rfc);

    CREATE INDEX IF NOT EXISTS idx_employees_curp
      ON employees(curp);

    CREATE INDEX IF NOT EXISTS idx_employees_nss
      ON employees(nss);

    CREATE INDEX IF NOT EXISTS idx_devices_company_id
      ON devices(company_id);

    CREATE INDEX IF NOT EXISTS idx_devices_site_id
      ON devices(site_id);

    CREATE INDEX IF NOT EXISTS idx_attendance_company_id
      ON attendance_records(company_id);

    CREATE INDEX IF NOT EXISTS idx_attendance_site_id
      ON attendance_records(site_id);

    CREATE INDEX IF NOT EXISTS idx_attendance_employee_id
      ON attendance_records(employee_id);

    ALTER TABLE qualified_incidents
      ADD COLUMN IF NOT EXISTS incident_date DATE;

    UPDATE qualified_incidents
    SET incident_date = (qualified_at AT TIME ZONE 'America/Mexico_City')::date
    WHERE incident_date IS NULL;

    ALTER TABLE qualified_incidents
      ALTER COLUMN incident_date SET DEFAULT CURRENT_DATE;

    ALTER TABLE qualified_incidents
      ALTER COLUMN incident_date SET NOT NULL;

    CREATE INDEX IF NOT EXISTS idx_company_policies_updated_at
      ON company_policies(updated_at DESC);

    CREATE INDEX IF NOT EXISTS idx_qualified_incidents_incident_date
      ON qualified_incidents(company_id, incident_date DESC);

    CREATE INDEX IF NOT EXISTS idx_categories_company_id
      ON categories(company_id);

    CREATE INDEX IF NOT EXISTS idx_category_workdays_category_id
      ON category_workdays(category_id);

    CREATE INDEX IF NOT EXISTS idx_category_rest_days_category_id
      ON category_rest_days(category_id);

    CREATE INDEX IF NOT EXISTS idx_category_incidence_values_category_id
      ON category_incidence_values(category_id);

    /* =========================================================
       MODO DE TRABAJO POR DÍA

       SITE    = trabaja en un centro asignado
       FOREIGN = trabajador foráneo, sin centro/geocerca fija

       No se deduce FOREIGN a partir de site_id NULL.
       Las asignaciones existentes permanecen como SITE.
    ========================================================= */

    ALTER TABLE employee_work_sites
      ADD COLUMN IF NOT EXISTS work_mode TEXT NOT NULL DEFAULT 'SITE';

    UPDATE employee_work_sites
    SET work_mode = 'SITE'
    WHERE work_mode IS NULL
       OR work_mode NOT IN ('SITE', 'FOREIGN');

    ALTER TABLE employee_work_sites
      ALTER COLUMN work_mode SET DEFAULT 'SITE';

    ALTER TABLE employee_work_sites
      ALTER COLUMN work_mode SET NOT NULL;

    ALTER TABLE employee_work_sites
      DROP CONSTRAINT IF EXISTS employee_work_sites_work_mode_check;

    ALTER TABLE employee_work_sites
      ADD CONSTRAINT employee_work_sites_work_mode_check
      CHECK (work_mode IN ('SITE', 'FOREIGN'));

    CREATE INDEX IF NOT EXISTS idx_employee_work_sites_work_mode
      ON employee_work_sites(work_mode);

    CREATE INDEX IF NOT EXISTS idx_employee_work_sites_employee_id
      ON employee_work_sites(employee_id);

    CREATE INDEX IF NOT EXISTS idx_employee_work_sites_site_id
      ON employee_work_sites(site_id);

    CREATE INDEX IF NOT EXISTS idx_qualified_incidents_company_id
      ON qualified_incidents(company_id);

    CREATE INDEX IF NOT EXISTS idx_qualified_incidents_employee_id
      ON qualified_incidents(employee_id);

    CREATE INDEX IF NOT EXISTS idx_qualified_incidents_category_id
      ON qualified_incidents(category_id);

    CREATE INDEX IF NOT EXISTS idx_qualified_incidents_concept
      ON qualified_incidents(concept);

    CREATE INDEX IF NOT EXISTS idx_qualified_incidents_qualified_at
      ON qualified_incidents(qualified_at DESC);

    CREATE UNIQUE INDEX IF NOT EXISTS uq_qualified_incident_attendance
      ON qualified_incidents(attendance_record_id)
      WHERE attendance_record_id IS NOT NULL;
  `);
    /* =========================================================
       USUARIO ADMINISTRADOR INICIAL
    ========================================================= */
    const bootstrapPassword = process.env.ADMIN_BOOTSTRAP_PASSWORD;
    if (bootstrapPassword) {
        const passwordHash = await bcryptjs_1.default.hash(bootstrapPassword, 12);
        await client_1.default.query(`
        INSERT INTO users (
          username,
          password_hash,
          full_name,
          role,
          must_change_password
        )
        VALUES (
          $1,
          $2,
          $3,
          'admin',
          TRUE
        )
        ON CONFLICT (username)
        DO NOTHING
      `, [
            process.env.ADMIN_BOOTSTRAP_USERNAME ?? 'admin',
            passwordHash,
            'Administrador inicial',
        ]);
    }
}
