# The WaterFlex scheduler: a briefing (2026-10-22): sources and methods

## Sources

The briefing is built from files in the repository, read through the loaders that already validate each study, so the same checks apply here.

| Source | Read through | What the briefing takes from it |
| --- | --- | --- |
| The September booking study, the field validation summary, the September routing evidence, the two daily search studies and the October load studies | `docs/scheduler-explained-2026-10-14/report_data.py` (imported; its document-table check is not called, because the living docs it parses have changed since) and, through it, the sibling loaders | Booking methods, daily-plan savings, wins and losses, the example day, savings by budget, load progression, holds, time split, routing |
| `docs/evidence/api-load-2026-10-16-dynamic-horizon.json` | this folder's loader | Searches with no offer and offers per search, high demand, 1 and 5 desks, three seeds summed |
| `docs/evidence/api-load-2026-10-19-booking-configuration*.json.gz` | `docs/booking-configuration-study-2026-10-19/report_data.py` (imported) | The full-schedule polish frontier (rerun) and the booking layouts (first study) |
| `docs/evidence/solver-representation-2026-10-20.json` | this folder's loader | Speed ratios of T and S |
| `docs/evidence/solver-variance-2026-10-20.json` | `docs/fairness-measures-2026-10-20/report_data.py` (imported) | Formula b and c summaries and decisions |
| `docs/evidence/solver-matrix-2026-10-20.json` | `docs/road-matrix-numbering-2026-10-20/report_data.py` (imported) | The numbering fix, formula b's 36 pairs and its speed by fleet |
| `docs/evidence/api-load-2026-10-20-binary-variance.json`, `api-load-2026-10-22-long-score-money-w-a.json` | `docs/stack-verification-2026-10-20/report_data.py` (imported, validates the first) and this folder's loader | Stack ratios by fleet for formula b and for the shipped engine changes |

The technical descriptions (the score's seven levels, the cost and fairness formulas, the acceptance rule, the booking search's bounds and deadline, admission, the daily methods' moves and acceptance settings, the engine changes) are taken from the code and the living docs: `docs/scheduler-policy.md` (Numeric contract; Search and reservation lifecycle;
Configuration), `calculation-engine/.../SolverEngine.java` (move weights, tabu size 7 or 3, late acceptance size 400, accepted-count 1,000 or 1), `SchedulingPolicy.fairness` (the capacity-weighted variance) and `docs/solver-benchmarks.md` (Road matrix numbering; Shipped representation). The CPU and memory limits table is read at build time from the defaults in `deploy/vm/docker-compose.yml`.

Quoted from `docs/solver-benchmarks.md`, not recomputed: the profile shares in Part V (the identity lookup at about a fifth of the cost phase before the numbering fix; after formula b, the fairness phase's split of 30, 28, 16, 16 and 6 percent; 57 percent of the fairness phase in the decimal variance).

Three statements are quoted, not recomputed, and the text says so: the study 3 ratios (long score and money 1.288x, W and A over it 1.051x, W alone 1.047x, W and A over main 1.342x, the route-walk cascade about 0.63x, 2,340 solves), quoted from `docs/solver-benchmarks.md` "Shipped representation", whose evidence is on the local branch `bench/study-3`;
and the October 1 budget study's 1,280 cases, whose run archive is not in the repository.

## Validation

`report_data.py` adds these checks to the imported loaders': the horizon file has 24 runs, no defect, no contract breach and no failed search, three runs per cell; every polish variant is priced on all 40 cases of its fleet; the representation study is the full run with no violation; formula b has 36 pairs and no zero production variance;
each stack file's two images are the pinned commits, with no contract breach or host anomaly in any run; the example day's accepted plan is cheaper than the day as sent and adds no overtime.

`build_report.py` asserts every claim the narrative states before writing it: Tabu + K opt positive at every fleet size in both studies; the late acceptance family negative at 50 technicians; bounded served more than insertion, which served more than shared; no hold lost after the fix; the example day's fairness search spent at most 2 percent and ended more even;
extra savings rising with budget at 50 technicians; formula b never less fair at either budget and c not promoted; best throughput rising from 2 to 3 to 4 vCPU and 2x1 beating 1x2; the shipped engine above 1.1x and formula b above 2x on the stack at every fleet;
2 s of polish closing less than 3 s, a quarter second closing nothing beyond noise and more of the gap closing after the first second than within it; size 3 ahead of Tabu + K opt on the new days; every 95 percent interval of Tabu + K opt's saving above zero in both studies and every sign test below 1 in 10,000 overall; pairing reducing the day-to-day spread at least tenfold;
the late acceptance family finding its best in the last tenth of its time at 50 technicians while Tabu settled before three quarters; formula c's 15 s median cost above the baseline and one day less fair beyond noise at 60 s; the tail table's last stage under a second at the 99th percentile.

`verify_report.py` repeats the work without the builder's or the loader's helpers: the horizon counts from the raw runs; both stack ratios by fleet from the raw phase summaries; formula b's 36 variance ratios and cost changes (exact decimals) and its median fairness-phase speedup from the raw runs; the polish frontier against the booking configuration study's published table in `docs/api-load.md`;
the daily heatmap, the headline saving and the illustrative year from every raw daily result file; each fleet's days, day-level wins and losses, exact sign test and mean saving, and that every interval contains its mean and stays above zero; the histogram counts; the convergence shares from each case's time to best over its cost-phase time; and that the VM limits overcommit 8 CPUs.
It checks that the template's styles, numbering, theme, fonts and footer are byte-identical, that each of the 32 figures is captioned once with both reading paragraphs, that no table row may split and that no em or en dash appears.

## Derived measures

- **Saving per day:** the control's accepted cost minus the method's, in dollars, for the matched case; means are over all cases of a cell.
- **Saving over the day as sent:** the case's starting cost minus its accepted cost, by fleet and budget, from the first daily study.
- **Extra saving over 60 s:** the saving over the day as sent at a budget, minus the same at 60 s, by fleet.
- **Headline booking capacity:** searches per minute at five desks on the rehearsal stack, the VM-shaped baseline against the shipped admission build (the second half of the load series only).
- **Holds lost:** holds unavailable at confirm over holds selected, at the first load test and after the fix on fresh seeds.
- **Polish frontier:** mean added cost per booking against the uncapped search on the same full-schedule cases, and the median search time at one desk.
- **Layout throughput:** each layout's searches per minute at 10 clients, the 2:250 variant, averaged over 5 and 10 technicians on half and full schedules.
- **Stack ratio:** after over before, each phase's kit p50 moves per second on the same fleet and seed, the median of the three seeds.
- **Paired statistics:** a day is a case key without its budget and method (fleet, workload, the fixture where the study has several, seed); the first study has 40 days per fleet run at four budgets, the follow-up 24 per fleet at two. The 95 percent interval of the mean saving is a percentile bootstrap of 10,000 draws of whole days (seed 20261022).
  The sign test is two-sided and exact, on each day's mean saving, ties dropped.
- **Pairing ratio:** the standard deviation of Tabu's accepted cost across a fleet's cases over the standard deviation of the paired savings.
- **Convergence:** each case's time to its best plan over the cost phase's solve time, averaged over the 160 cases of a method and fleet.
- **Latency profile:** each load stage's p50, p95, p99 and maximum for booking offers and confirms, as the load study recorded them.
- **Illustrative year:** Tabu + K opt's mean saving per day over Tabu at one fleet size times 200, 250 or 300 working days. An illustration, not a forecast.

Nothing missing is replaced with a value.

## Interpretation limits

- **Not customer traffic:** synthetic days with a synthetic travel matrix in the daily studies; random Omaha points in the booking and load runs.
- **Modeled cost:** paid minutes at hourly rates plus mileage, not payroll or revenue. The saving over the day as sent depends on how the synthetic day was built; the comparisons between methods do not.
- **Two halves of the load series:** the first four stages ran the scheduler alone on one machine, the last four on the rehearsal stack beside five planners. The staircase marks the boundary and the text never joins them.
- **Different studies, different controls:** savings are never pooled and speed ratios never multiplied.
- **Rehearsal, not production:** absolute rates are the workstation's; the ratios are what transfer. The latency at the WaterFlex desk across the network has not been measured.

## Rebuild

```text
python -m pip install -r docs/president-briefing-2026-10-22/requirements.txt
python -m playwright install chromium
python docs/president-briefing-2026-10-22/report_data.py
python docs/president-briefing-2026-10-22/build_report.py
python docs/president-briefing-2026-10-22/verify_report.py
```

`build_report.py` computes the chart data (`charts.py`, recorded in `generated/chart_data.json`), writes each figure as HTML to `pages/` and rasterizes it to `generated/` with the October 14 report's `figures/render.py`, then writes the Word document. `python charts.py --redraw [names]` redraws figures from the recorded data without reloading the studies.
The sibling loaders need the experiment archives under `experiments/runs` and `experiments/runs/bench-2026-10-12/status.json`, as the October 14 report does. Calibri on Windows or Carlito on Linux is required.
