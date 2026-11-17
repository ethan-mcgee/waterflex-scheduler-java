-- A dispatcher's move that changed the day or arrival window a customer was promised. The dealership's policy keeps
-- those promises, so the schedule page saves such a move only after the dispatcher acknowledges the violation, and
-- records it here in the same transaction as the move. A row goes with its appointment when fake data is purged.
CREATE TABLE appointment_window_change (
  "id" TEXT NOT NULL PRIMARY KEY,
  "appointmentId" TEXT NOT NULL REFERENCES appointment(id) ON DELETE CASCADE ON UPDATE CASCADE,
  "fromServiceDate" TIMESTAMP(3) NOT NULL,
  "fromWindowStart" TIMESTAMP(3) NOT NULL,
  "fromWindowEnd" TIMESTAMP(3) NOT NULL,
  "toServiceDate" TIMESTAMP(3) NOT NULL,
  "toWindowStart" TIMESTAMP(3) NOT NULL,
  "toWindowEnd" TIMESTAMP(3) NOT NULL,
  "acknowledgedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT appointment_window_change_windows CHECK ("fromWindowStart" < "fromWindowEnd" AND "toWindowStart" < "toWindowEnd"),
  CONSTRAINT appointment_window_change_changed CHECK (("fromServiceDate", "fromWindowStart", "fromWindowEnd")
    IS DISTINCT FROM ("toServiceDate", "toWindowStart", "toWindowEnd"))
);
CREATE INDEX appointment_window_change_appointment ON appointment_window_change ("appointmentId");
