# Fairness measures 2026-10-20: evidence and methods

## Source and scope

The one immutable input is `docs/evidence/solver-variance-2026-10-20.json`. `infra/variance_study.py summarize` wrote it from the run `infra/run-variance-study.ps1` made on 2026-10-20, 04:53 to 12:24 UTC, on revision `773f9f6`. That revision was built once into a frozen tree.

| Block | What it holds |
| --- | --- |
| `manifest` | Revision, JVM flags, fleets, workloads, seeds, budgets (15 s and 60 s), repetitions (2), variant order rotation |
| `budgets.<ms>.runs` | All 336 solves per budget (7 arms, 24 cases, 2 repetitions). Each one records its reference and final cost, ceiling, acceptance reason, both phases' moves per second, and all five measures of its reference and final plans |
| `budgets.<ms>.pairs` | For b and c, each case's variances and costs on both sides, production's spread between repetitions, and the mean differences |
| `budgets.<ms>.summary` | b and c against the four bars |
| `budgets.<ms>.comparison` | Every arm on every measure (median, median ratio to production, cases lower and higher), cost difference, fairness-phase speed, and acceptance reasons |
| `decision` | b promoted, c not, policies compared without bars |
| `profiles` | One flight-recording profile per arm and phase at 15 s on seed 17, with eight recordings each: samples by kind of work, allocation, and the top leaf frames |

The reference and final cost and the reference plan's measures were added to `runs` after the run, by re-running `summarize` on the same rows (commit after `0154ac6`). Every other block of the regenerated file is identical to the run's own output, which was checked field by field before it was replaced.

## Validation

`report_data.py` checks the following before anything reads the file:
- **The run:**
  - it is the full study, not the smoke run, on the pinned revision, with both budgets and two repetitions;
  - every arm, case and repetition is present exactly once;
  - there are 24 cases per budget.
- **The results:**
  - zero violations;
  - nothing outside a ceiling;
  - no reference plan over its own ceiling;
  - every run carries all five measures for its reference and final plans.
- **The decision, recomputed from the individual runs:**
  - the median fairness-phase speedups of b and c;
  - their median cost differences;
  - the count of cases less fair beyond production's spread;
  - every comparison ratio, rounded as recorded;
  - every acceptance count.

  Each must equal the recorded summary. The two promotion decisions must follow from the per-budget passes.
- **The profiles:**
  - every group is a known one;
  - shares add to 100;
  - each has eight recordings.

`verify_report.py` repeats the work without the builder's or loader's helpers. It recomputes these tables from the raw JSON:
- the bars table, including each pass and fail;
- both comparison tables;
- the allowance table;
- both pair appendices;
- both policy appendices;
- the profile table.

It also checks:
- the chart data against the evidence;
- that the reference template's styles, numbering, theme, fonts and footer are byte-identical;
- that every figure has a description and a black italic caption;
- that no table row may split;
- that no em or en dash appears in the document XML.

## Derived measures

- **Speedup:** an arm's moves per second in a phase divided by production's in the same case and repetition. The report gives the median over the 48 pairs of a budget, never the ratio of medians.
- **Ratio to production on a measure:** each case's mean over two repetitions, divided by production's mean in the same case. The median is taken over the cases where production's value is above zero.
- **Cost difference:** each case's mean final cost minus production's, in cents. The median is taken over the 24 cases.
- **Spread between repetitions:** production's larger final variance in a case minus its smaller. This is the fairness bar's noise floor for that case.
- **Allowance spent:** (final cost minus reference cost) divided by (ceiling minus reference cost), per run. It can be negative when the fairness phase ended cheaper than its reference.
- **Own measure, final over reference:** a run's final value on the measure its arm searched on, divided by its reference plan's value.
- **Thousandths:** variances are shown multiplied by 1000.

Nothing missing is replaced with a value. A ratio whose denominator is zero is left out of a median, or shown as n/a.

## Figures

Eleven charts come from `charts.py` (matplotlib, Calibri). `build_report.py` regenerates them with `chart_data.json`. Each arm keeps one color across every chart:
- baseline grey;
- b teal;
- c light blue;
- min-max amber;
- spread gold;
- abs-dev purple;
- load-balance rose.

Two diagrams are self-contained HTML files in `diagrams/`, made with the diagram-design skill's default skin:
- `fairness-computation.html`: what each arm does after a move;
- `fairness-acceptance.html`: the acceptance rule.

They pass the skill's `self_check.py`. Render them to `generated/` before building, with Playwright's Chromium:

```
python <diagram-design skill>/scripts/export_png.py diagrams/fairness-computation.html generated/fairness-computation.png 2.5
python <diagram-design skill>/scripts/export_png.py diagrams/fairness-acceptance.html generated/fairness-acceptance.png 2.5
```

## Interpretation limits

- **Synthetic fleets:**
  - deterministic fixtures of 20 and 50 technicians;
  - fixture seed 7001;
  - four workloads.

  The gain grows with fleet size, so larger metros should expect more, but that is an extrapolation.
- **Harness, not service:** each solve ran in its own JVM, limited to two processors on a 24-processor workstation. Absolute moves per second are the harness's. The paired ratios are what transfers.
- **Leaf frames only:** the profiles keep the top leaf frames, not stacks. Identifying MapN.probe as RoadMatrix's identity-to-number lookup rests on the CR07 write-up in `docs/solver-benchmarks.md`.
- **The fairness bar against a zero spread:** where production reached the same plan in both repetitions, any loss fails the bar. This is how the owner set it before the run, and the report does not reargue it.
