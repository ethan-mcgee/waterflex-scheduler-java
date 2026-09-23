# Dealership route endpoints

Each technician belongs to one dealership. A dealership belongs to one metro and has one depot with a confirmed location pin. Its departure and return controls independently select `HOME` or `DEPOT`, so all four combinations are available. All technicians at the dealership use the same policy. The existing technician home address and qualifications remain part of setup.

## Setup and onboarding checklist

1. Open **Dealerships** and choose the metro.
2. Add a depot using its street address, then confirm one of the verified pins.
3. Create a dealership, assign its depot, and select departure and return endpoints.
4. Open **Technicians** and add each technician to the dealership with a verified home address, weekly availability, and qualifications.

This is the start of an onboarding checklist. There is no guided onboarding flow yet. The depot pin is required before a depot endpoint can be selected. A technician's depot is inherited from the dealership. Existing technicians are backfilled by their prior depot, with home departure and home return retained.

## Scheduling behavior

The departure and return legs are separate directed road trips. Every working segment divided by approved time off uses the dealership's selected departure and return endpoints. The route's paid time, travel, mileage, daily limit, overtime limit, booking feasibility, optimization score, and dispatch road geometry include those legs. Travel from home to a depot before a depot departure is outside the modeled route.

Policy edits take effect on the current service day only before 6 a.m. America/Chicago. After that cutoff, edits start on the next service day. Older service days continue to use the policy that applied to them. The policy history table records the effective date. A policy edit locks the dealership, affected technicians, and booked schedule days, checks active holds, and recomputes arrival and end times in existing sequence. It preserves customer windows and rejects the whole transaction if any affected route is infeasible. Booked day versions are incremented only after a successful edit. A configuration fingerprint includes the selected policy and depot pin so a previous optimization preview cannot be applied after a relevant edit.

New optimization previews save endpoint coordinates. Their before and proposed road maps use those saved coordinates even after later edits. Previews saved before endpoint snapshots existed report historical geometry as unavailable.

## Verification

Run `npm run test:dealership:integration` against an isolated migrated and seeded database with `SCHEDULER_TEST_URL` pointing to a scheduler instance using that database. The scenario checks all four endpoint combinations, directed geometry, customer promises, active holds, infeasible edits, the 6 a.m. cutoff, and changed preview fingerprints. Java route tests cover split working segments and directed leg costs.
