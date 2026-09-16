-- Configure each metro with its own customer service radius.
ALTER TABLE "metro"
  ADD COLUMN "serviceRadiusMi" DOUBLE PRECISION NOT NULL DEFAULT 65;

-- Convert the original local seed records to the Omaha operating area.
-- Foreign keys use ON UPDATE CASCADE, so existing related dev data follows.
UPDATE "metro"
SET "id" = 'metro-omaha',
    "name" = 'Omaha Metro Area',
    "timezone" = 'America/Chicago',
    "serviceRadiusMi" = 65
WHERE "id" = 'metro-dfw';

UPDATE "depot"
SET "id" = 'depot-omaha-main',
    "name" = 'Omaha Main Depot',
    "lat" = 41.2565,
    "lng" = -95.9345
WHERE "id" = 'depot-dfw-main';

UPDATE "technician"
SET "homeLat" = CASE "id"
      WHEN 'tech-1' THEN 41.1544
      WHEN 'tech-2' THEN 41.2619
      WHEN 'tech-3' THEN 41.2864
      ELSE "homeLat"
    END,
    "homeLng" = CASE "id"
      WHEN 'tech-1' THEN -96.0422
      WHEN 'tech-2' THEN -95.8608
      WHEN 'tech-3' THEN -96.2345
      ELSE "homeLng"
    END
WHERE "id" IN ('tech-1', 'tech-2', 'tech-3');
