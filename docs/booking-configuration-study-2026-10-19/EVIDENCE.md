# Booking configuration study 2026-10-17 to 2026-10-19: evidence and methods

## Source and scope

The immutable inputs are the two study files under `docs/evidence/`, each written by `infra/run-booking-configuration-study.ps1`:

| File | Runner | Cells | What it holds |
| --- | --- | ---: | --- |
| `api-load-2026-10-19-booking-configuration.json.gz` | ef0089d | 1184 | The first study: quality (40), one-desk load (840), capacity (224) and the 500 and 250 ms reserve arms (80) |
| `api-load-2026-10-19-booking-configuration-full.json.gz` | 530d356 | 35 | The full-schedule rerun after the horizon and reserve fixes: quality (10) and the 800 ms reserve arm (25) |

Each file records the stack, the booking pool shape, the protocol, the hypotheses written before the first run, the client token ids (revoked after the rerun), the shapes configured and verified, the runs with defects (none) and the runs that never got an answer (20 in the first study, all uncapped searches at the 500 and 250 ms reserves; none in the rerun).

Every run used the host conformance kit (`infra/host-conformance`: `quality.ts` for quality, `load.ts` for load, capacity and the reserve arm's load) against the `deploy/vm` rehearsal stack on one AMD Ryzen 9 7900X (12 cores, 24 hardware threads). Docker `cpus` count hardware threads, as a VM vCPU does.

## Validation

`report_data.py` checks every kit report before any figure is read from it:

- **Shape:** the machine has 24 processors, and each part holds the number of cells the protocol promised (quality 40, load 840, capacity 224, reserve 80; rerun quality 10, reserve 25).
- **Contract:** no contract breach and no host anomaly recorded; `runsWithDefects` is empty in both files.
- **Consistency:** each operation's statuses add up to its calls; p50 ≤ p95 ≤ p99 ≤ max; searches equal the booking offers calls; each quality case has one entry per variant.

`build_report.py` asserts the two claims the narrative states without a table: no 2:3000 search at one desk on a full schedule reported `searchComplete` false, and every uncapped search from 10 technicians up did.

`verify_report.py` recomputes the section 4 quality table, the section 8 one-desk table, both section 9 layout grids, the section 10 reserve table and seven chart datasets from the raw evidence with its own loops, without `report_data.py` or the summarizer's pooling, and checks the preserved template parts, the captions, the embedded images,
that every table row is kept together and that no em or en dash appears.

## Derived measures

- **Gap:** for each case answered by both a variant and the reference (variant `1000`), the variant's best offer's `addedCostCents` minus the reference's, priced by `routes/evaluate` on a copy of the day; the mean and median are over the cases priced on both sides.
- **Same offer set:** the variant's offers equal the reference's as a set of service date, start and end.
- **Search p50 (quality):** the median of the variant's answered searches' times at the caller.
- **One-desk rate:** searches answered over the summed duration of the repeats; p50, p95 and p99 are the mean of the repeats' own percentiles.
- **Server time:** the request log's booking offers 201 breakdown, each repeat's p50 or p95 weighted by its calls.
- **Capacity rate:** each client's searches over its own run time, summed over the clients; p50 and p95 are the clients' mean.
- **Shed:** refinements reported SHED over searches answered.
- **Noise floor:** the largest spread between thresholds 0, 2, 5 and 10 at one cap, which ran the identical search because no threshold up to 10 was ever reached.

Means and percentiles are the kit's own. Nothing missing is replaced with a value: a figure the evidence does not hold prints as n/a (the reserve runs whose searches were all refused have no commit time), and a chart value that is missing stops the build.

## Interpretation limits

- **Not customer traffic:** random points inside Omaha and synthetic schedules; cost is the scheduler's modeled fleet cost, not money billed.
- **Two files:** full-schedule quality comes only from the rerun; the first study's full-schedule quality searched 2 dates and is not used. Half-schedule quality is from 2-date searches and is shown as an indication only. Load, capacity and the first reserve arms come from the first study.
- **The reference is not an optimum:** the uncapped search is the best the 5 s deadline allows, and from 15 technicians it stops on the deadline itself.
- **Noise:** identical searches differ by up to $0.82 a booking over 40 cases, and the two insertion-only variants by up to $0.73; the cause is not established. Gaps under about $0.80 are read as ties.
- **Interpretation versus measurement:** the mechanisms (the shed check, the cap's clock and the neighborhood fetch inside it, the database admission guard) are read from the scheduler's code and labeled so; the share of a 250 ms cap the fetch takes is a difference of separate runs' medians, and the engine's progress inside a refinement is not logged.
- **Open results:** the 2 vCPU reversal (2x1 beating 1x2), the UNAVAILABLE refinements and deadline stops at 20 technicians at the 800 ms reserve, and the uncapped search's 3 s of routing at 20 and 30 technicians are measured but not explained.
- **Absolute numbers:** the rehearsal applies the VM's CPU caps on a workstation; the VM's clock speed will set its own times.

## Rebuild

```text
python -m pip install -r docs/booking-configuration-study-2026-10-19/requirements.txt
python docs/booking-configuration-study-2026-10-19/report_data.py
python docs/booking-configuration-study-2026-10-19/build_report.py
python docs/booking-configuration-study-2026-10-19/verify_report.py
```

Calibri on Windows or Carlito on Linux is required for the figures. Figure 1 (the search pipeline) and the deadline split it draws are read from the code (BookingOffers, SearchDeadline, application.properties), not measured, and its caption says so.
Every figure is authored as HTML with inline SVG (figures.py, in the report's skin) and rasterized by render.py, which needs Playwright with its Chromium build (`python -m playwright install chromium`). Render every page after changes.
