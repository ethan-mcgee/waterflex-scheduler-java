# Stack verification 2026-10-20: evidence and methods

## Source and scope

The one immutable input is `docs/evidence/api-load-2026-10-20-binary-variance.json`. `infra/run-stack-verification.ps1` wrote it from the run of 2026-10-20, 21:56 to 22:22 UTC, on the VM rehearsal stack (`deploy/vm` with `rehearsal.override.yml`, 127.0.0.1:18080). The runner was at revision `2138349`. Two images were built, each from its own commit:

- **before** `cdd19a6`: main's decimal variance objective, with the study receipt
- **after** `68828b5`: option b, score model `bendable-decimal-repair-v3`

The report also reads option b's harness ratios by fleet from `docs/evidence/solver-matrix-2026-10-20.json`, the road matrix numbering study.

| Block                | What it holds                                                                                                                                                                                                                                                                     |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `images`             | Each image's commit, tag and image ID                                                                                                                                                                                                                                             |
| `protocol`           | The run's own description of its two steps (see the correction below)                                                                                                                                                                                                             |
| `containersVerified` | Every replica the run read: its image ID, CPU and memory limits, and the study environment variables                                                                                                                                                                              |
| `runs`               | 20 kit reports: step 1 (18 runs: 3 fleets, 3 seeds, 2 images, 4 proposals each) and step 2 (2 runs of 12 queued proposals). Each report carries the calls, decisions, queue waits, routing, containers and the study receipts, every receipt with Timefold's speed for each phase |

## Corrections to the evidence

The evidence file is not edited. Three things in it read differently from what happened:

- **Roads were not cold at each run's start:** The protocol text says they were. The scheduler keeps fetched legs in Postgres (`road_route_cache`), and that table survived the switch of images. So roads were cold only for the first image of each pair:
  - in step 1 the before runs fetched every pair and the after runs fetched none;
  - in step 2 the after run fetched 6,400 pairs and the before run none.

  Moves per second and plans do not depend on this, because the search runs its fixed time after routing. Call maxima and the daily replica's mean CPU do, and the report says so.
- **Booking replica names are one-element lists:** In `containersVerified` each booking replica's `container` is a list holding one name, because the script nested an array. The loader accepts exactly one string in a list and treats it as that name. The script is fixed in the same commit as this report.
- **The policy is the kit's, not the client's:** Every run used `TABU` with `fairnessBudget` 0, so each cost ceiling equals its reference plan's cost. The settings block records this correctly; the report discloses it because the example client policy is `TABU_KOPT` with 0.02.

## Validation

`report_data.py` checks the following before anything reads the file:

- **The images:**
  - both are the pinned commits, with tags that name them;
  - every replica the run verified ran its image's ID with `TIMEFOLD_LOG_LEVEL=INFO`;
  - every daily replica had `SCHEDULER_STUDY_RECEIPTS=true` and the daily pool's shape (2 CPUs, 2 GiB).
- **The runs:**
  - every run of both steps is present exactly once;
  - each has the expected planners, mode, proposals and settings, and the kit's policy;
  - there are no contract breaches or host anomalies;
  - every call answered 201, and every decision was `IMPROVED`;
  - the number of queue waits matches the mode.
- **The receipts:**
  - every receipt was paired with exactly one speed per phase, and none is unmeasured;
  - there is one receipt per proposal, 96 in all;
  - each names `TABU`, seed 17, 3 visits per technician and both phases;
  - each has a searched and accepted fairness phase, a ceiling equal to the reference cost, a proposal inside it, and no overtime.
- **The kit's summary, recomputed from the receipts:**
  - each phase's median speed and solve time;
  - the reference and proposed cost medians;
  - the variance medians.

  Each must equal what the kit recorded.

`report_data.harness()` checks that the harness file is the numbering study (revision `ed97d8b`, 15 s, 72 pairs, fleets 10, 30 and 50) before reading b's fairness-phase medians by fleet.

`verify_report.py` repeats the work without the builder's or loader's helpers. It recomputes these tables from the raw JSON:

- the summary table
- the phase share table
- the per-run speed table
- the per-pair plan table
- the service table
- the queue rows
- both appendices of proposals and runs

It also checks:

- the chart data against the evidence
- that the reference template's styles, numbering, theme, fonts and footer are byte-identical
- that every figure has a description and a black italic caption
- that no table row may split
- that no em or en dash appears in the document XML

## Derived measures

- **p50:** the kit's nearest-rank median, `sorted[ceil(n/2) - 1]`. With four values it is the second smallest. The report uses it wherever it reads a single run, so its numbers match the kit's.
- **Ratio:** after over before on one phase, the after run's p50 moves per second divided by the before run's on the same fleet and seed. A fleet's figure is the ordinary median of its three seeds' ratios.
- **Share:** a proposal's fairness-phase moves per second divided by its own reference phase's. The report gives the ordinary median over the proposals.
- **Fairer:** a proposal whose DECIMAL128 variance is below its own reference plan's, compared exactly.
- **Cost change:** a proposal's cost minus its own reference plan's, in cents.

Nothing missing is replaced with a value. The loader refuses evidence with an unpaired or unmeasured receipt rather than leave one out.

## Figures

Seven charts come from `charts.py` (matplotlib, Calibri). `build_report.py` regenerates them with `chart_data.json`. Each image keeps one color across every chart:

- before: grey
- after: teal
- the harness: amber

Two diagrams are self-contained HTML files in `diagrams/`, made with the diagram-design skill's default skin:

- `stack-path.html`: the path one proposal takes
- `receipt-pairing.html`: how the kit pairs a receipt with its speeds

They pass the skill's `self_check.py` and `verify-geometry.py`. Render them to `generated/` before building, with Playwright's Chromium:

```powershell
python <diagram-design skill>/scripts/export_png.py diagrams/stack-path.html generated/stack-path.png 2.5
python <diagram-design skill>/scripts/export_png.py diagrams/receipt-pairing.html generated/receipt-pairing.png 2.5
```

## Interpretation limits

- **Synthetic fleets:**
  - 10, 20 and 50 technicians;
  - 3 visits each;
  - points seeds 1 to 4 in the Omaha metro area.

  The gain grows with the fleet, so larger metros should expect more, but that is an extrapolation.
- **The workstation, not the VM:** the stack ran with the VM's compute caps on the workstation that also ran the load generator. Absolute rates are this stack's. The paired ratios are what transfers.
- **One run per step 2 image:** the queued ratio is a single pair, consistent with step 1 at the same fleet but without seeds of its own.
- **Harness comparison:** the harness ran different fleets (10, 30 and 50 technicians, four workloads) and `TABU_KOPT`. The report compares only the ratios. Why the stack's are a little lower is not established by either file.
