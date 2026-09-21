ALTER TABLE "metro" ADD COLUMN "stateCode" TEXT;
UPDATE "metro" SET "stateCode" = 'NE' WHERE id = 'metro-omaha';
