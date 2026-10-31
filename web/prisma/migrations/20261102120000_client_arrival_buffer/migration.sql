-- Each client's late-arrival buffer (spec 1.9.0): the end of every appointment window that daily planning avoids, so a
-- long repair, a break or a fuel stop does not make the technician late. A window's buffer is arrivalBufferPercent of it
-- in whole minutes, held between the two minute bounds and never more than half the window. Every client starts at the
-- owner's default of 25 percent, at least 15 and at most 60 minutes; a maximum of 0 turns the buffer off.
ALTER TABLE client_solver_settings
  ADD COLUMN "arrivalBufferPercent" INTEGER NOT NULL DEFAULT 25 CHECK ("arrivalBufferPercent" BETWEEN 0 AND 50),
  ADD COLUMN "arrivalBufferMinMinutes" INTEGER NOT NULL DEFAULT 15 CHECK ("arrivalBufferMinMinutes" BETWEEN 0 AND 240),
  ADD COLUMN "arrivalBufferMaxMinutes" INTEGER NOT NULL DEFAULT 60 CHECK ("arrivalBufferMaxMinutes" BETWEEN 0 AND 240),
  ADD CONSTRAINT client_solver_settings_arrival_buffer_bounds
    CHECK ("arrivalBufferMinMinutes" <= "arrivalBufferMaxMinutes");
