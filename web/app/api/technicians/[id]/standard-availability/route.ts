import { NextRequest, NextResponse } from "next/server";
import { Prisma } from "@prisma/client";
import { prisma } from "@/lib/prisma";
import { readBody, updateStandardWeekRequest } from "@/lib/contracts";
import { addCalendarDays, calendarDateInTz, todayInTz } from "@/lib/date";
import { nextTemplateEffectiveDate, resolveWeeklyDay, validateVersions } from "@/lib/technicianAvailability";

const CHICAGO = "America/Chicago";
const dayStamp = (date: string) => new Date(`${date}T00:00:00Z`);

function minuteInChicago(value: Date): number {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: CHICAGO, hourCycle: "h23", hour: "2-digit", minute: "2-digit" }).formatToParts(value);
  const hour = Number(parts.find(part => part.type === "hour")?.value);
  const minute = Number(parts.find(part => part.type === "minute")?.value);
  if (!Number.isInteger(hour) || !Number.isInteger(minute)) throw new Error("Invalid commitment time");
  return hour * 60 + minute;
}

function commitmentMinute(value: Date, serviceDate: string, endBoundary: boolean): number | null {
  const date = calendarDateInTz(value, CHICAGO);
  const minute = minuteInChicago(value);
  if (date === serviceDate) return minute;
  return endBoundary && date === addCalendarDays(serviceDate, 1) && minute === 0 ? 1440 : null;
}

export async function PUT(request: NextRequest, { params }: { params: { id: string } }) {
  const parsed = await readBody(request, updateStandardWeekRequest);
  if (!parsed.success) return NextResponse.json({ error: "Invalid seven-day availability" }, { status: 400 });
  const now = new Date();
  const effectiveDate = nextTemplateEffectiveDate(now);
  const today = todayInTz(CHICAGO, now);
  try {
    await prisma.$transaction(async tx => {
      const locked = await tx.$queryRaw<Array<{ id: string }>>(Prisma.sql`SELECT id FROM technician WHERE id=${params.id} FOR UPDATE`);
      if (locked.length !== 1) throw new Error("Technician not found");
      const versions = await tx.technicianAvailabilityVersion.findMany({ where: { technicianId: params.id },
        include: { days: true }, orderBy: { effectiveDate: "asc" } });
      const current = validateVersions(versions.map(version => ({ effectiveDate: version.effectiveDate, days: version.days })));
      const future = current.filter(version => version.effectiveDate.toISOString().slice(0, 10) <= today);
      if (future.length === 0) throw new Error("Technician weekly availability is missing for the current date");
      const proposed = validateVersions([...future, { effectiveDate: dayStamp(effectiveDate), days: parsed.data.days }]);
      const [appointments, holds, exceptions, dependencies] = await Promise.all([
        tx.appointment.findMany({ where: { technicianId: params.id, serviceDate: { gte: dayStamp(today) }, cancelledAt: null },
          select: { serviceDate: true, windowStart: true, windowEnd: true, plannedStart: true, plannedEnd: true } }),
        tx.slotHold.findMany({ where: { technicianId: params.id, serviceDate: { gte: dayStamp(today) }, releasedAt: null, expiresAt: { gt: new Date() } },
          select: { serviceDate: true, windowStart: true, windowEnd: true, plannedStart: true, plannedEnd: true } }),
        tx.technicianShiftOverride.findMany({ where: { technicianId: params.id, serviceDate: { gte: dayStamp(today) } },
          select: { serviceDate: true } }),
        tx.reservationDependency.findMany({ where: { technicianId: params.id, serviceDate: { gte: dayStamp(today) },
          hold: { releasedAt: null, expiresAt: { gt: now } } }, select: { serviceDate: true } }),
      ]);
      const excepted = new Set(exceptions.map(item => item.serviceDate.toISOString().slice(0, 10)));
      for (const dependency of dependencies) {
        const date = dependency.serviceDate.toISOString().slice(0, 10);
        if (!excepted.has(date) && JSON.stringify(resolveWeeklyDay(current, date)) !== JSON.stringify(resolveWeeklyDay(proposed, date)))
          throw new Error(`Existing appointment or hold depends on the reserved arrangement on ${date}`);
      }
      for (const item of [...appointments, ...holds]) {
        const date = item.serviceDate.toISOString().slice(0, 10);
        if (excepted.has(date)) continue;
        const day = resolveWeeklyDay(proposed, date);
        const shiftStart = day.shiftStartMin, shiftEnd = day.shiftEndMin;
        const starts = [item.windowStart, item.plannedStart].map(value => commitmentMinute(value, date, false));
        const ends = [item.windowEnd, item.plannedEnd].map(value => commitmentMinute(value, date, true));
        if (!day.available || shiftStart == null || shiftEnd == null ||
            starts.some(minute => minute == null || minute < shiftStart) ||
            ends.some(minute => minute == null || minute > shiftEnd))
          throw new Error(`Existing appointment or hold conflicts with availability on ${date}`);
      }
      await tx.technicianAvailabilityVersion.deleteMany({ where: { technicianId: params.id, effectiveDate: { gt: dayStamp(today) } } });
      await tx.technicianAvailabilityVersion.create({ data: { technicianId: params.id, effectiveDate: dayStamp(effectiveDate),
        days: { create: parsed.data.days } } });
    }, { isolationLevel: Prisma.TransactionIsolationLevel.Serializable });
  } catch (error) {
    if (error instanceof Error && error.message === "Technician not found") return NextResponse.json({ error: error.message }, { status: 404 });
    if (error instanceof Error && error.message.startsWith("Existing appointment or hold")) return NextResponse.json({ error: error.message }, { status: 409 });
    if (error instanceof Error && error.message.startsWith("Technician weekly availability")) return NextResponse.json({ error: error.message }, { status: 409 });
    throw error;
  }
  return NextResponse.json({ success: true, effectiveDate });
}
