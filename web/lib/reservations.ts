import { Prisma } from "@prisma/client";
import type { z } from "zod";
import { prisma } from "./prisma";
import type { reservationBookRequest, reservationRequest } from "./contracts";
import { addCalendarDays, mondayOfWeek, todayInTz } from "./date";
import { demandBranches, demandSeries, technicianDays } from "./recurringDemand";
import { demandWeeks, planReservations } from "./recurringDemandCore";
import { intakeJob } from "./apiJobIntake";

type Failure = { status: number; error: string };
const fail = (status: number, error: string): Failure => ({ status, error });
const utcDay = (value: string) => new Date(`${value}T00:00:00.000Z`);

/**
 * Reserves a time for each given series in one branch week, in the given technician-days' free time (see
 * planReservations). One reservation run per branch at a time: an advisory lock on the branch keeps two dispatchers
 * from handing out the same free minutes. Series that fit nowhere are returned unplaced.
 */
export async function reserveTimes(clientId: string, input: z.infer<typeof reservationRequest>) {
  if (mondayOfWeek(input.week) !== input.week) return fail(400, "The week must start on a Monday.");
  try {
    return await prisma.$transaction(async tx => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${`reservation:${input.branchId}`}))`;
      const branches = await demandBranches(tx, clientId);
      const branch = branches.find(item => item.id === input.branchId);
      if (branch === undefined) return fail(404, "Branch not found.");
      const today = todayInTz(branch.timezone);
      const weeks = demandWeeks(today);
      if (!weeks.includes(input.week)) return fail(409, "That week is no longer on the demand calendar. Reload the page.");

      const series = await demandSeries(tx, clientId, branches, weeks, today, input.seriesIds);
      if (series.length !== input.seriesIds.length) return fail(404, "A series was not found or is no longer active.");
      const customers = [];
      for (const item of series) {
        if (item.branchId !== branch.id || item.week !== input.week) return fail(409, `Account ${item.accountNumber} is not due in this branch week. Reload the page.`);
        if (item.reservation !== null) return fail(409, `Account ${item.accountNumber} already has a reserved time.`);
        if (item.durationMin === null) return fail(422, `Account ${item.accountNumber} has a reason code without usable minutes.`);
        customers.push({ seriesId: item.id, dueDate: item.dueDate, durationMin: item.durationMin });
      }

      const offered = await technicianDays(tx, clientId, [branch], input.week, addCalendarDays(input.week, 4));
      const days = [];
      for (const wanted of input.days) {
        const found = offered.find(item => item.technicianId === wanted.technicianId && item.date === wanted.date);
        if (found === undefined) return fail(409, "A chosen day is not a working day at this branch. Reload the page.");
        if (found.date < today) return fail(409, "A chosen day has already passed.");
        days.push(found);
      }
      const { planned, unplaced } = planReservations(customers, days);
      const reserved = [];
      for (const item of planned) {
        const row = await tx.reservation.create({ data: { seriesId: item.seriesId, dueDate: utcDay(item.dueDate), depotId: branch.id,
          technicianId: item.technicianId, serviceDate: utcDay(item.date), startMin: item.startMin, endMin: item.endMin } });
        reserved.push({ id: row.id, seriesId: item.seriesId, technicianId: item.technicianId, date: item.date, startMin: item.startMin, endMin: item.endMin });
      }
      return { reserved, unplaced };
    });
  } catch (error) {
    // The partial unique index: another run reserved one of these series first.
    if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === "P2002") return fail(409, "One of these customers was just reserved. Reload the page.");
    throw error;
  }
}

async function ownReservation(clientId: string, id: string) {
  return prisma.reservation.findFirst({ where: { id, series: { clientId } },
    include: { depot: { select: { metroId: true } }, series: { include: { account: true, service: true,
      reasonCodes: { include: { reasonCode: true }, orderBy: { position: "asc" } } } } } });
}

/** Gives a reserved time back. A booked one has a work order and is not released here. */
export async function releaseReservation(clientId: string, id: string): Promise<{ released: true } | Failure> {
  const reservation = await ownReservation(clientId, id);
  if (reservation === null) return fail(404, "Reservation not found.");
  if (reservation.status === "RELEASED") return { released: true };
  const { count } = await prisma.reservation.updateMany({ where: { id, status: "RESERVED" }, data: { status: "RELEASED" } });
  if (count === 0) return fail(409, "This reservation was booked. Cancel its work order instead.");
  return { released: true };
}

const placeHref = (metroId: string, date: string, jobId: string) =>
  `/work-orders/schedule?${new URLSearchParams({ metroId, date, placeJob: jobId }).toString()}`;

/**
 * Takes a reservation's work order in: the series' account, reason codes and type, with the contact the dispatcher
 * confirmed. The request ID is the reservation's, so a retry finds the same job. The job waits for the calendar; the
 * link places it on the reserved day, where the scheduler checks the time as for any other job.
 */
export async function bookReservation(clientId: string, id: string, contact: z.infer<typeof reservationBookRequest>) {
  const reservation = await ownReservation(clientId, id);
  if (reservation === null) return fail(404, "Reservation not found.");
  const date = reservation.serviceDate.toISOString().slice(0, 10);
  if (reservation.status === "RELEASED") return fail(409, "This reservation was released.");
  if (reservation.status === "BOOKED") {
    if (reservation.jobId === null) return fail(500, "A booked reservation has no work order.");
    return { jobId: reservation.jobId, placeHref: placeHref(reservation.depot.metroId, date, reservation.jobId) };
  }
  const { series } = reservation;
  const [extraFirst, extraSecond] = series.reasonCodes.map(item => item.reasonCode.code);
  const extraReasonCodes = [extraFirst, extraSecond].filter((code): code is string => code !== undefined);
  const intake = await intakeJob(clientId, { requestId: `recurring-reservation-${reservation.id}`, accountNumber: series.account.accountNumber, ...contact,
    serviceCode: series.service.code, ...(extraReasonCodes.length > 0 ? { extraReasonCodes } : {}),
    ...(series.workOrderType === null ? {} : { workOrderType: series.workOrderType }) });
  if ("refusal" in intake) return fail(intake.refusal.status, intake.refusal.body.error);
  const { count } = await prisma.reservation.updateMany({ where: { id, status: "RESERVED" }, data: { status: "BOOKED", jobId: intake.job.id } });
  if (count === 0) {
    const now = await prisma.reservation.findUnique({ where: { id } });
    if (now?.status !== "BOOKED" || now.jobId !== intake.job.id) return fail(409, "This reservation changed while it was being booked. Reload the page.");
  }
  return { jobId: intake.job.id, placeHref: placeHref(reservation.depot.metroId, date, intake.job.id) };
}
