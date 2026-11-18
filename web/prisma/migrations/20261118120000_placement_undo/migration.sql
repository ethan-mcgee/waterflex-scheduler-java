-- What a save on the schedule page replaced, so the dispatcher can undo it: the earlier planned times of every visit
-- on the technician-days the save wrote, the earlier promise when it changed, and those days' lastModified right
-- after the save. An undo restores them only while the days still carry those stamps, and is used once.
CREATE TABLE placement_undo (
  "id" TEXT NOT NULL PRIMARY KEY,
  "clientId" TEXT NOT NULL,
  "createdAt" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "usedAt" TIMESTAMPTZ(6),
  "payload" JSONB NOT NULL
);
CREATE INDEX placement_undo_client ON placement_undo ("clientId", "createdAt");
