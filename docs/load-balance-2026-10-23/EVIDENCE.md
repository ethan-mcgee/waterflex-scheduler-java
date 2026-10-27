# Load balance head to head 2026-10-23: evidence and methods

## Source and scope

The one immutable input is `docs/evidence/solver-load-balance-2026-10-23.json`. `infra/load_balance_study.py summarize` wrote it at the end of the run `infra/run-load-balance-study.ps1` made on 2026-10-23, from 05:36 to 08:59 UTC, on revision `15da8a0`. That revision is main `52339c8` plus the Mixed Shift fixture, a test-only change, built once into a detached worktree under `C:\Projects\waterflex-worktrees`. The raw rows, logs and 96 flight recordings stay in `experiments/runs/load-balance-h2h-20261023T053623/` of the main checkout, which is gitignored.

| Block                                                                              | What it holds                                                                                                                                                                                                   |
| ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `manifest`                                                                         | Revision, JVM flags, fleets, workloads, fixture seeds, solver seed, budgets (15 s and 30 s), repetitions (1), arm order                                                                                         |
| `budgets.<ms>.runs`                                                                | All 240 solves per budget (2 arms, 120 cases). Each one records its reference and final cost, ceiling, acceptance reason, both phases' moves per second, and all five measures of its reference and final plans |
| `budgets.<ms>.measures`                                                            | Per measure: median per-case ratio of load balance to b, its 95 percent bootstrap interval, the cases lower and higher, and the cases higher by more than 5 percent                                             |
| `budgets.<ms>.cost`, `speed`, `convergence`, `byFleet`, `byWorkload`, `acceptance` | Cost differences, paired speed ratios by phase and fleet, time to best over the phase budget, the same readings by group, and acceptance counts                                                                 |
| `budgets.<ms>.rule`, `passes`                                                      | Each part of the rule written before the run, and whether all hold                                                                                                                                              |
| `decision`                                                                         | `switch`: false                                                                                                                                                                                                 |
| `profiles`                                                                         | One flight-recording profile per arm and phase at 15 s on fixture seed 7001, 24 recordings each, overall and by fleet                                                                                           |

## Validation

`report_data.py` checks the following before anything reads the file:

- **The run:** it is the full study on the pinned revision, with the planned matrix (fleets 5, 10, 20 and 50; six workloads; fixture seeds 7001 to 7005; solver seed 17; budgets 15 s and 30 s; one repetition).
- **Completeness:** every arm and case is present exactly once at each budget.
- **The results:** zero violations, and no accepted result over its ceiling.
- **The readings, recomputed from the individual runs:** every median ratio, every bootstrap interval (same seed and resample count), the counts lower, higher and beyond the tolerance, the cost medians, the speed medians and the acceptance counts. Each must equal the recorded value.
- **The rule and the decision:** each part of the rule is recomputed, and the decision must follow from the budgets.
- **The profiles:** every group is a known one, shares add to 100, and the fleets' recordings add up.

`verify_report.py` repeats the work without the builder's or loader's helpers, finding each table by its header row. It recomputes from the raw JSON:

- both appendices, every case
- the workload table of section 2
- the speed table of section 6
- the rule table of section 3, with each part's verdict and the decision

It also checks the chart data against the evidence, the template parts byte for byte, figure descriptions and captions, unsplittable rows, and the absence of em and en dashes.

## Derived measures

- **Ratio to b on a measure:** load balance's final value divided by b's in the same case. Medians are taken over cases where b's value is above zero. Cases where b reads zero are counted separately.
- **Bootstrap interval:** the 2.5th and 97.5th percentiles of 10,000 medians of the case ratios resampled with replacement, with `random.Random(0)`.
- **Less fair by more than 5 percent:** load balance's value above b's times 1.05. A case where b reads zero and load balance does not counts here.
- **Same plan:** both arms ended a stage on the same cost and the same five measures. This is derived in the report, not recorded by the harness.
- **Reference cost gap:** load balance's reference cost minus b's, per case. It is derived from the recorded reference costs.
- **Speedup:** load balance's moves per second in a phase divided by b's in the same case.
- **Allowance spent:** (final cost minus reference cost) divided by (ceiling minus reference cost), per run.
- **The road matrix study's ratio:** recomputed in `report_data.earlier_ratio` from `docs/evidence/solver-matrix-2026-10-20.json` the same way, per case, over its cases with a nonzero variance.

Nothing missing is replaced with a value. A ratio whose denominator is zero is left out of a median and shown as n/a, or as "even" in the heatmap.

## Figures

Nine charts come from `charts.py` (matplotlib, Calibri), and `build_report.py` regenerates them with `chart_data.json`. b is teal and load balance rose, as in the fairness measures report, and each workload keeps one color.

Two diagrams are self-contained HTML files in `diagrams/`, made with the diagram-design skill's default skin:

- `study-design.html`: how one case runs
- `weighting.html`: how each measure counts a technician

They pass the skill's `self_check.py`. Render them to `generated/` before building:

```powershell
python <diagram-design skill>/scripts/export_png.py diagrams/study-design.html generated/study-design.png 2.5
python <diagram-design skill>/scripts/export_png.py diagrams/weighting.html generated/weighting.png 2.5
```

## Interpretation limits

- **Synthetic days:** deterministic fixtures of 5 to 50 technicians, five drawn days of each shape. Mixed Shift is one way to mix capacities, not a model of any client's roster.
- **One repetition:** a case's own run-to-run noise is not measured; the rule's fixed 5 percent tolerance stands in for it.
- **Harness, not service:** each solve ran in its own JVM limited to two processors on a 24-processor workstation. Absolute moves per second are the harness's; the paired ratios are what transfers.
- **Leaf frames only:** the profiles keep leaf frames, not stacks. Reading the extra collections time as the collector's map, and the decimal score work as the unfairness conversion, are interpretations and are labeled so.
