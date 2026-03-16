-- PostgreSQL schema for Akka Persistence JDBC
-- Database: akka_flight_ops

-- Create the database (run this as postgres superuser)
-- CREATE DATABASE akka_flight_ops;
-- \c akka_flight_ops;

-- Drop tables if they exist (for clean reinstall)
DROP TABLE IF EXISTS public.snapshot CASCADE;
DROP TABLE IF EXISTS public.journal CASCADE;

-- Journal table for event storage
CREATE TABLE IF NOT EXISTS public.journal(
  ordering BIGSERIAL,
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,
  tags VARCHAR(255) DEFAULT NULL,
  message BYTEA NOT NULL,
  PRIMARY KEY(persistence_id, sequence_number)
);

CREATE UNIQUE INDEX journal_ordering_idx ON public.journal(ordering);

-- Snapshot table for state snapshots
CREATE TABLE IF NOT EXISTS public.snapshot(
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  created BIGINT NOT NULL,
  snapshot BYTEA NOT NULL,
  PRIMARY KEY(persistence_id, sequence_number)
);

-- Optional: Create indexes for better query performance
CREATE INDEX journal_persistence_id_idx ON public.journal(persistence_id);
CREATE INDEX snapshot_persistence_id_idx ON public.snapshot(persistence_id);

-- Grant permissions (adjust username as needed)
-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
-- GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO postgres;

-- Verify tables created
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public' 
ORDER BY table_name;
