import pool from './client';
import bcrypt from 'bcryptjs';

export async function initDb(): Promise<void> {
  await pool.query(`
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
       CATEGORÍAS 1 / 2 / 3

       La configuración laboral se guarda por categoría.
       Cada empresa tiene exactamente las categorías 1, 2 y 3.
    ========================================================= */

    CREATE TABLE IF NOT EXISTS categories (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL
        REFERENCES companies(id)
        ON DELETE CASCADE,
      category_number SMALLINT NOT NULL
        CHECK (category_number BETWEEN 1 AND 3),
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
       CREAR AUTOMÁTICAMENTE CATEGORÍAS 1, 2 Y 3
       PARA EMPRESAS EXISTENTES
    ========================================================= */

    INSERT INTO categories (
      company_id,
      category_number,
      name,
      overtime_allowed,
      overtime_value
    )
    SELECT
      c.id,
      n.category_number,
      'Categoría ' || n.category_number,
      TRUE,
      NULL
    FROM companies c
    CROSS JOIN (
      VALUES (1), (2), (3)
    ) AS n(category_number)
    ON CONFLICT (company_id, category_number)
    DO NOTHING;

    /* =========================================================
       VALORES PREDETERMINADOS DE INCIDENCIAS
       PARA TODAS LAS CATEGORÍAS
    ========================================================= */

    INSERT INTO category_incidence_values (
      category_id,
      concept,
      value
    )
    SELECT
      cat.id,
      v.concept,
      v.value
    FROM categories cat
    CROSS JOIN (
      VALUES
        ('Ausentismo', 1::NUMERIC),
        ('Retardo', 1::NUMERIC),
        ('Permiso médico justificado', 1::NUMERIC),
        ('Permiso médico no justificado', 1::NUMERIC),
        ('Permiso de salida', 1::NUMERIC),
        ('Falta justificada', 1::NUMERIC)
    ) AS v(concept, value)
    ON CONFLICT (category_id, concept)
    DO NOTHING;

    /* =========================================================
       HORARIO BASE LUNES A VIERNES PARA CATEGORÍAS

       Solo se crea si todavía no existe configuración.
       Después el administrador podrá cambiarlo.
    ========================================================= */

    INSERT INTO category_workdays (
      category_id,
      weekday,
      is_workday,
      start_time,
      end_time
    )
    SELECT
      cat.id,
      d.weekday,
      CASE WHEN d.weekday BETWEEN 1 AND 5 THEN TRUE ELSE FALSE END,
      cat.start_time,
      cat.end_time
    FROM categories cat
    CROSS JOIN (
      VALUES (1), (2), (3), (4), (5), (6), (7)
    ) AS d(weekday)
    ON CONFLICT (category_id, weekday)
    DO NOTHING;

    /* =========================================================
       TRIGGER: CATEGORÍAS AUTOMÁTICAS PARA EMPRESAS NUEVAS
    ========================================================= */

    CREATE OR REPLACE FUNCTION create_default_company_categories()
    RETURNS TRIGGER
    LANGUAGE plpgsql
    AS $$
    DECLARE
      category_row RECORD;
    BEGIN
      INSERT INTO categories (
        company_id,
        category_number,
        name,
        overtime_allowed,
        overtime_value
      )
      VALUES
        (NEW.id, 1, 'Categoría 1', TRUE, NULL),
        (NEW.id, 2, 'Categoría 2', TRUE, NULL),
        (NEW.id, 3, 'Categoría 3', TRUE, NULL)
      ON CONFLICT (company_id, category_number)
      DO NOTHING;

      FOR category_row IN
        SELECT id
        FROM categories
        WHERE company_id = NEW.id
      LOOP
        INSERT INTO category_incidence_values (
          category_id,
          concept,
          value
        )
        VALUES
          (category_row.id, 'Ausentismo', 1),
          (category_row.id, 'Retardo', 1),
          (category_row.id, 'Permiso médico justificado', 1),
          (category_row.id, 'Permiso médico no justificado', 1),
          (category_row.id, 'Permiso de salida', 1),
          (category_row.id, 'Falta justificada', 1)
        ON CONFLICT (category_id, concept)
        DO NOTHING;

        INSERT INTO category_workdays (
          category_id,
          weekday,
          is_workday,
          start_time,
          end_time
        )
        VALUES
          (category_row.id, 1, TRUE, NULL, NULL),
          (category_row.id, 2, TRUE, NULL, NULL),
          (category_row.id, 3, TRUE, NULL, NULL),
          (category_row.id, 4, TRUE, NULL, NULL),
          (category_row.id, 5, TRUE, NULL, NULL),
          (category_row.id, 6, FALSE, NULL, NULL),
          (category_row.id, 7, FALSE, NULL, NULL)
        ON CONFLICT (category_id, weekday)
        DO NOTHING;
      END LOOP;

      RETURN NEW;
    END;
    $$;

    DROP TRIGGER IF EXISTS trg_create_default_company_categories
      ON companies;

    CREATE TRIGGER trg_create_default_company_categories
    AFTER INSERT ON companies
    FOR EACH ROW
    EXECUTE FUNCTION create_default_company_categories();

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

  const bootstrapPassword =
    process.env.ADMIN_BOOTSTRAP_PASSWORD;

  if (bootstrapPassword) {
    const passwordHash =
      await bcrypt.hash(
        bootstrapPassword,
        12
      );

    await pool.query(
      `
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
      `,
      [
        process.env.ADMIN_BOOTSTRAP_USERNAME ?? 'admin',
        passwordHash,
        'Administrador inicial',
      ],
    );
  }
}
