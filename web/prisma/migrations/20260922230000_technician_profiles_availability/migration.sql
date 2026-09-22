ALTER TABLE technician ADD COLUMN email TEXT, ADD COLUMN phone TEXT, ADD COLUMN bio TEXT,
  ADD COLUMN color TEXT, ADD COLUMN "homeAddressLine1" TEXT,
  ADD COLUMN "homeAddressCity" TEXT, ADD COLUMN "homeAddressState" TEXT,
  ADD COLUMN "homeAddressPostalCode" TEXT;

-- Match the previously displayed JavaScript ID hash palette exactly for existing rows.
DO $$
DECLARE r RECORD; h BIGINT; i INTEGER; palette TEXT[] := ARRAY['#2563eb','#dc2626','#059669','#d97706','#7c3aed','#0891b2','#db2777','#65a30d'];
BEGIN
  FOR r IN SELECT id FROM technician LOOP
    h := 0;
    FOR i IN 1..length(r.id) LOOP
      h := mod(mod(h * 31 + ascii(substr(r.id, i, 1)) + 2147483648, 4294967296) + 4294967296, 4294967296) - 2147483648;
    END LOOP;
    UPDATE technician SET color = palette[(abs(h) % 8)::integer + 1] WHERE id = r.id;
  END LOOP;
END $$;
ALTER TABLE technician ALTER COLUMN color SET NOT NULL;

CREATE TABLE technician_availability_version (
  id TEXT PRIMARY KEY, "technicianId" TEXT NOT NULL REFERENCES technician(id) ON DELETE CASCADE,
  "effectiveDate" TIMESTAMP(3) NOT NULL,
  CONSTRAINT technician_availability_version_technician_date_key UNIQUE ("technicianId", "effectiveDate")
);
CREATE TABLE technician_availability_day (
  "versionId" TEXT NOT NULL REFERENCES technician_availability_version(id) ON DELETE CASCADE,
  "dayOfWeek" INTEGER NOT NULL CHECK ("dayOfWeek" BETWEEN 0 AND 6),
  available BOOLEAN NOT NULL, "shiftStartMin" INTEGER, "shiftEndMin" INTEGER,
  CONSTRAINT technician_availability_day_pkey PRIMARY KEY ("versionId", "dayOfWeek"),
  CONSTRAINT technician_availability_day_hours CHECK (
    (available AND "shiftStartMin" IS NOT NULL AND "shiftEndMin" IS NOT NULL AND
      "shiftStartMin" >= 0 AND "shiftEndMin" <= 1440 AND "shiftStartMin" < "shiftEndMin") OR
    (NOT available AND "shiftStartMin" IS NULL AND "shiftEndMin" IS NULL)
  )
);

INSERT INTO technician_availability_version (id, "technicianId", "effectiveDate")
SELECT 'initial-' || id, id, TIMESTAMP '1900-01-01' FROM technician;
INSERT INTO technician_availability_day ("versionId", "dayOfWeek", available, "shiftStartMin", "shiftEndMin")
SELECT 'initial-' || t.id, dow, dow BETWEEN 1 AND 5,
  CASE WHEN dow BETWEEN 1 AND 5 THEN t."shiftStartMin" ELSE NULL END,
  CASE WHEN dow BETWEEN 1 AND 5 THEN t."shiftEndMin" ELSE NULL END
FROM technician t CROSS JOIN generate_series(0, 6) AS dow;
