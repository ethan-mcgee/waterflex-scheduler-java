/*
  Warnings:

  - Added the required column `insertPosition` to the `slot_hold` table without a default value. This is not possible if the table is not empty.
  - Added the required column `jobId` to the `slot_hold` table without a default value. This is not possible if the table is not empty.
  - Added the required column `windowEnd` to the `slot_hold` table without a default value. This is not possible if the table is not empty.
  - Added the required column `windowStart` to the `slot_hold` table without a default value. This is not possible if the table is not empty.

*/
-- AlterTable
ALTER TABLE "slot_hold" ADD COLUMN     "insertPosition" INTEGER NOT NULL,
ADD COLUMN     "jobId" TEXT NOT NULL,
ADD COLUMN     "windowEnd" TIMESTAMP(3) NOT NULL,
ADD COLUMN     "windowStart" TIMESTAMP(3) NOT NULL;

-- CreateIndex
CREATE INDEX "slot_hold_jobId_idx" ON "slot_hold"("jobId");

-- AddForeignKey
ALTER TABLE "slot_hold" ADD CONSTRAINT "slot_hold_jobId_fkey" FOREIGN KEY ("jobId") REFERENCES "job"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
