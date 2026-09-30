import type { Prisma, PrismaClient } from "@prisma/client";

/** Delete only a successful fixture, atomically. Dependent changes and search requests cascade. */
export async function removeSuccessfulCase(prisma: PrismaClient, caseId: string) {
  const jobs: Prisma.JobWhereInput = { customerId: `${caseId}-customer` };
  const technicians: Prisma.TechnicianWhereInput = { depotAssignments: { some: { depot: { metroId: caseId } } } };
  await prisma.$transaction(async tx => {
    await tx.optimizationRun.deleteMany({ where: { metroId: caseId } });
    await tx.reservationArrangement.deleteMany({ where: { metroId: caseId } });
    await tx.appointment.deleteMany({ where: { job: jobs } });
    await tx.slotHold.deleteMany({ where: { job: jobs } });
    await tx.bookingOptimization.deleteMany({ where: { job: jobs } });
    await tx.bookingOffer.deleteMany({ where: { job: jobs } });
    await tx.bookingOfferSet.deleteMany({ where: { job: jobs } });
    await tx.job.deleteMany({ where: jobs });
    await tx.address.deleteMany({ where: { customerId: `${caseId}-customer` } });
    await tx.customer.deleteMany({ where: { id: `${caseId}-customer` } });
    await tx.timeOffRequest.deleteMany({ where: { technician: technicians } });
    await tx.scheduleDay.deleteMany({ where: { technician: technicians } });
    await tx.technicianQualification.deleteMany({ where: { technician: technicians } });
    await tx.technicianShiftOverride.deleteMany({ where: { technician: technicians } });
    await tx.technician.deleteMany({ where: technicians });
    await tx.serviceCatalog.deleteMany({ where: { id: { in: [`${caseId}-common`, `${caseId}-scarce`] } } });
    await tx.depot.deleteMany({ where: { id: `${caseId}-depot` } });
    await tx.dealership.deleteMany({ where: { id: `${caseId}-dealer` } });
    await tx.metro.deleteMany({ where: { id: caseId } });
  }, { timeout: 60000 });
}
