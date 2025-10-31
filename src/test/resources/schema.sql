-- Test schema initialization
DO $$ BEGIN
    CREATE TYPE command_status AS ENUM ('PENDING','RUNNING','SUCCEEDED','FAILED','TIMED_OUT');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

CREATE TABLE IF NOT EXISTS command (
  id uuid primary key,
  name text not null,
  business_key text not null,
  payload jsonb not null,
  idempotency_key text not null,
  status command_status not null,
  requested_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  retries int not null default 0,
  processing_lease_until timestamptz,
  last_error text,
  reply jsonb not null default '{}'::jsonb,
  unique (name, business_key),
  unique (idempotency_key)
);

CREATE TABLE IF NOT EXISTS inbox (
  message_id text not null,
  handler text not null,
  processed_at timestamptz not null default now(),
  primary key (message_id, handler)
);

CREATE TABLE IF NOT EXISTS outbox (
  id uuid primary key,
  category text not null,
  topic text not null,
  key text not null,
  type text not null,
  payload jsonb not null,
  headers jsonb not null default '{}'::jsonb,
  status text not null default 'NEW',
  attempts int not null default 0,
  next_at timestamptz,
  claimed_by text,
  created_at timestamptz not null default now(),
  published_at timestamptz,
  last_error text
);

CREATE INDEX IF NOT EXISTS outbox_dispatch_idx ON outbox (status, coalesce(next_at, 'epoch'::timestamptz), created_at);

CREATE TABLE IF NOT EXISTS command_dlq (
  id uuid primary key default gen_random_uuid(),
  command_id uuid not null,
  command_name text not null,
  business_key text not null,
  payload jsonb not null,
  failed_status text not null,
  error_class text not null,
  error_message text,
  attempts int not null default 0,
  parked_by text not null,
  parked_at timestamptz not null default now()
);
