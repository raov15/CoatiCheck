import pool from '../db/client';

export async function initDb(): Promise<void> {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS devices (
      id_remote     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local      TEXT NOT NULL UNIQUE,
      device_name   TEXT NOT NULL,
      fingerprint   TEXT NOT NULL,
      site_id       UUID,
      auth_token    TEXT,
      registered_at BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
      created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS employees (
      id_remote     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local      TEXT NOT NULL UNIQUE,
      employee_code TEXT NOT NULL UNIQUE,
      full_name     TEXT NOT NULL,
      is_active     BOOLEAN NOT NULL DEFAULT TRUE,
      created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS attendance_records (
      id_remote       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      id_local        TEXT NOT NULL UNIQUE,
      employee_id     TEXT NOT NULL,
      event_type      TEXT NOT NULL,
      occurred_at     BIGINT NOT NULL,
      latitude        DOUBLE PRECISION,
      longitude       DOUBLE PRECISION,
      accuracy_m      REAL,
      altitude_m      DOUBLE PRECISION,
      face_confidence REAL,
      device_id       TEXT,
      created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `);
}
