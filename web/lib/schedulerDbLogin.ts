/**
 * The scheduler's database login: a member of scheduler_app, the least-privilege runtime role, so the running
 * scheduler can read tenants and token digests, keep the route cache, act as scheduler_tenant and run the retention
 * purge, and nothing else. The migration step creates it after the migrations and sets its password on every run, so
 * rotating SCHEDULER_DB_PASSWORD is a restart. Both values are checked before any SQL is built: a role name and a
 * password cannot be bound as statement parameters.
 */
export type SchedulerLogin = { login: string; password: string };

const LOGIN = /^[a-z_][a-z0-9_]{0,62}$/;
const PASSWORD = /^[A-Za-z0-9._~-]{16,128}$/;

export function schedulerLogin(env: Record<string, string | undefined>): SchedulerLogin {
  const login = env.SCHEDULER_DB_LOGIN;
  const password = env.SCHEDULER_DB_PASSWORD;
  if (login === undefined || !LOGIN.test(login))
    throw new Error("SCHEDULER_DB_LOGIN must be a lowercase Postgres role name (letters, digits, underscore)");
  if (password === undefined || !PASSWORD.test(password))
    throw new Error("SCHEDULER_DB_PASSWORD must be 16 to 128 characters of letters, digits, '.', '_', '~' or '-'");
  return { login, password };
}

/**
 * Statements that create or update the login. They refuse a role that is the migrating user or holds any power beyond
 * a login (superuser, bypass RLS, create roles or databases), since granting scheduler_app to such a role would not
 * confine it.
 */
export function schedulerLoginStatements({ login, password }: SchedulerLogin): string[] {
  return [
    `DO $$
BEGIN
  IF '${login}' = current_user THEN
    RAISE EXCEPTION 'The scheduler login must not be the migrating user %', current_user;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${login}' AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)) THEN
    RAISE EXCEPTION 'Role ${login} has privileges beyond a login; the scheduler needs a plain login';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${login}') THEN
    ALTER ROLE "${login}" LOGIN INHERIT PASSWORD '${password}';
  ELSE
    CREATE ROLE "${login}" LOGIN INHERIT NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '${password}';
  END IF;
END $$`,
    `GRANT scheduler_app TO "${login}"`,
  ];
}
