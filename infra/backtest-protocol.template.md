# Backtest protocol: <client>, <date>

This protocol is written before any long run and confirmed by the owner. The summary (`infra/backtest_summary.py`) reads the bars below from each rule set's `study-config.json` and reports each one as met or missed; nothing here is tuned after the run.

## Data

- **Source:** the client's work-order history, read in place and never copied; derived files hold pseudonymous ids and coordinates only.
- **Splits:** calibration = <calibration years>; test = <test years>. The importer rules and the duration repairs are frozen before the run and never refit on the test split.
- **Exclusions:** rows with a bad year, no technician, no complaint, no booked time, a booking before 07:00 or at or after the latest shift end, a bad duration, a pin outside the area, or a pin no road reaches (`UNROUTABLE_LOCATION`) leave every plan and are counted in the ledger.
- **Plans per day:** as planned (the dispatcher's order by booked time, booked durations), as executed (the logged order, booked durations), the delivered plan, and each played forward with the logged durations. The delivered plan is the production calculation's proposal from the as-planned baseline when it was accepted, and the as-planned plan when it was not, so a rejected candidate never counts.

## Rule sets

- **Primary:** <primary rule set>.
- **Sensitivities, reported, not gated:** <other rule sets>.
- **strict-17:** every technician is back at the depot by <end>.
- **evening-sales:** <end> for everyone, except that a technician the dispatcher gave a sales call that cannot finish by <end> works to <sales end>, and that call stays with them.
- **sales-day:** every technician works to <sales end> on any day with a sales call.
- **Held out:** a job no single technician can serve at its booked time inside the rule set's shift (arrive in the window after the drive out, do the work, back at the depot by the end) leaves every plan of that rule set, the dispatcher's included, and is counted by rule set, sales calls separately.

## Bars

The bars are read on the test split of the primary rule set, split by whether the dispatcher's booked plan holds under the rule set.

- **Efficiency:** on the days the dispatcher's booked plan holds, paired by day, the delivered minus as-planned median drive minutes has a 95 percent bootstrap interval entirely below zero, and paid minutes are not worse (the interval's upper bound at or below zero).
- **Repair:** on the days the dispatcher's booked plan does not hold, the delivered plan is valid on at least <repair share> of them (`bars.repairShareMin`); the Wilson 95 percent interval, the drive and paid cost of the repair and the violations removed are reported beside it.
- **Validity:** every accepted plan carries zero lateness, past-shift minutes, overtime and unassigned jobs; played forward, the delivered plan's late visits per day are not worse than the executed plan's under the same model; the public API agrees with the harness decision on at least 90 percent of the days whose baseline holds; the noise standard deviation across repetitions is below one third of the efficiency median.
- **Reported, not gated:** delivered against as executed on every day, cost cents and meters, the fairness variance, the share of days the shipped API refuses (an as-planned baseline that does not hold), sales-call days separately, the exclusion and hold-out ledgers, the travel gap analysis by distance band and year, unreachable legs per year.

## Settings

- **Shift:** <start> to <end> local, no overtime; the rule sets above say who may work to <sales end>.
- **Windows:** <n> hours from the booked time, capped at the technician's end; the booked duration.
- **Roster:** every weekday from a technician's first to last appearance, minus the days the company logged no work; weekends roster only who worked.
- **Depot:** one start and end for everyone at <address>.
- **Solver:** <variant>, <cost> s cost + <fairness> s fairness, fairness budget <budget>, arrival buffer <percent>/<min>/<max>, travel buffer <pct> + <minutes> min.
- **Rates:** <regular>/h regular, <overtime>/h overtime, <mileage>/mi.
- **Map:** region <region>, version <version>, routing identity <identity>.
