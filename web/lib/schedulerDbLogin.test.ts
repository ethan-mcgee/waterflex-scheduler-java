import assert from "node:assert/strict";
import { test } from "node:test";
import { schedulerLogin, schedulerLoginStatements } from "./schedulerDbLogin";

const password = "local-compose-scheduler-db-password";

test("a valid login and password build the create-or-update statements and the role grant", () => {
  const statements = schedulerLoginStatements(schedulerLogin({ SCHEDULER_DB_LOGIN: "scheduler", SCHEDULER_DB_PASSWORD: password }));
  assert.equal(statements.length, 2);
  assert.match(statements[0] ?? "", /CREATE ROLE "scheduler" LOGIN INHERIT NOSUPERUSER NOBYPASSRLS/);
  assert.match(statements[0] ?? "", /ALTER ROLE "scheduler" LOGIN INHERIT PASSWORD/);
  assert.equal(statements[1], 'GRANT scheduler_app TO "scheduler"');
});

test("a missing login or password is refused, never defaulted", () => {
  assert.throws(() => schedulerLogin({ SCHEDULER_DB_PASSWORD: password }), /SCHEDULER_DB_LOGIN/);
  assert.throws(() => schedulerLogin({ SCHEDULER_DB_LOGIN: "scheduler" }), /SCHEDULER_DB_PASSWORD/);
});

test("values that could break out of the SQL are refused", () => {
  assert.throws(() => schedulerLogin({ SCHEDULER_DB_LOGIN: "scheduler\"; DROP", SCHEDULER_DB_PASSWORD: password }), /SCHEDULER_DB_LOGIN/);
  assert.throws(() => schedulerLogin({ SCHEDULER_DB_LOGIN: "Scheduler", SCHEDULER_DB_PASSWORD: password }), /SCHEDULER_DB_LOGIN/);
  assert.throws(() => schedulerLogin({ SCHEDULER_DB_LOGIN: "scheduler", SCHEDULER_DB_PASSWORD: "it's-a-long-password" }), /SCHEDULER_DB_PASSWORD/);
  assert.throws(() => schedulerLogin({ SCHEDULER_DB_LOGIN: "scheduler", SCHEDULER_DB_PASSWORD: "short" }), /SCHEDULER_DB_PASSWORD/);
});
