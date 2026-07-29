import pool from '../db/client';
import bcrypt from 'bcryptjs';

export async function initDb(): Promise<void> {
  await pool.query(`
    CREATE EXTENSION IF NOT EXISTS pgcrypto;

    CREATE TABLE IF NOT EXISTS companies (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      name TEXT NOT NULL,
      slug TEXT NOT NULL UNIQUE,
      logo_path TEXT,
      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS sites (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
      name TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(company_id, name)
    );

    CREATE TABLE IF NOT EXISTS users (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID REFERENCES companies(id) ON DELETE CASCADE,
      site_id UUID REFERENCES sites(id) ON DELETE SET NULL,
      username TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      full_name TEXT NOT NULL,
      role TEXT NOT NULL CHECK (role IN ('admin', 'administrator', 'operator')),
      employee_code TEXT,
      must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS devices (
      id_remote UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local TEXT NOT NULL UNIQUE,
      device_name TEXT NOT NULL,
      fingerprint TEXT NOT NULL,
      site_id UUID,
      auth_token TEXT,
      registered_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS device_enrollment_codes (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
      site_id UUID REFERENCES sites(id) ON DELETE SET NULL,
      code_hash TEXT NOT NULL UNIQUE,
      expires_at TIMESTAMPTZ NOT NULL,
      used_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS employees (
      id_remote UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local TEXT NOT NULL UNIQUE,
      employee_code TEXT NOT NULL UNIQUE,
      full_name TEXT NOT NULL,
      is_active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

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
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE devices ADD COLUMN IF NOT EXISTS company_id UUID REFERENCES companies(id);
    ALTER TABLE devices ADD COLUMN IF NOT EXISTS site_id UUID REFERENCES sites(id);
    ALTER TABLE employees ADD COLUMN IF NOT EXISTS company_id UUID REFERENCES companies(id);
    ALTER TABLE employees ADD COLUMN IF NOT EXISTS site_id UUID REFERENCES sites(id);
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS company_id UUID REFERENCES companies(id);
    ALTER TABLE attendance_records ADD COLUMN IF NOT EXISTS site_id UUID REFERENCES sites(id);
  `);

  const bootstrapPassword = process.env.ADMIN_BOOTSTRAP_PASSWORD;
  if (bootstrapPassword) {
    const passwordHash = await bcrypt.hash(bootstrapPassword, 12);
    await pool.query(
      `INSERT INTO users (username, password_hash, full_name, role, must_change_password)
       VALUES ($1, $2, $3, 'admin', TRUE)
       ON CONFLICT (username) DO NOTHING`,
      [process.env.ADMIN_BOOTSTRAP_USERNAME ?? 'admin', passwordHash, 'Administrador inicial'],
    );
  }
}