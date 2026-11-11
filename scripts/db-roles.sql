-- The episode runtime's database role (design §13, R11; plan D-lite). Run by the OWNER account after episode-schema.sql:
--   psql -v ON_ERROR_STOP=1 -v rt_password='…' -U incident_recovery -d incident_recovery -f scripts/db-roles.sql
-- The runtime appends and reads; it never changes or removes a row (the tables are append-only by use, §13). The
-- password comes from the psql variable rt_password, never from this file. Safe to run again.
-- The -v value shows in shell history and process lists, and ALTER ROLE … PASSWORD is logged when log_statement is
-- ddl or all: pass a throwaway value and then set the real one with psql's \password incident_recovery_rt, which avoids both.
-- The names are unqualified: run it with the search_path the runtime uses (the schema episode-schema.sql was applied in).
-- Keep the table list in step with episode-schema.sql and EpisodeStore's SCHEMA_OBJECTS (EpisodeDbRolesTest checks).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'incident_recovery_rt') THEN
        CREATE ROLE incident_recovery_rt LOGIN;
    END IF;
END
$$;
-- An existing role keeps whatever it was given before: take it back.
ALTER ROLE incident_recovery_rt LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- A runtime role that may create in the schema could create, and so own, the audit tables (EpisodeStore.ensureSchema
-- runs the DDL for any account with CREATE while a table is missing). Before Postgres 15, PUBLIC has CREATE on public.
DO $$
BEGIN
    IF has_schema_privilege('incident_recovery_rt', current_schema(), 'CREATE') THEN
        RAISE EXCEPTION 'incident_recovery_rt may CREATE in schema %: first REVOKE CREATE ON SCHEMA % FROM PUBLIC (and from incident_recovery_rt or any role it is in) - a runtime role that can create could own the audit tables',
            current_schema(), current_schema();
    END IF;
END
$$;
ALTER ROLE incident_recovery_rt PASSWORD :'rt_password';

REVOKE ALL ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log FROM incident_recovery_rt;
GRANT SELECT, INSERT ON episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log TO incident_recovery_rt;
REVOKE ALL ON SEQUENCE episode_notice_id_seq, episode_watch_log_id_seq FROM incident_recovery_rt;
GRANT USAGE, SELECT ON SEQUENCE episode_notice_id_seq, episode_watch_log_id_seq TO incident_recovery_rt;
