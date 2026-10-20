# Road matrix numbering 2026-10-20: evidence and methods

## Source and scope

The one immutable input is `docs/evidence/solver-matrix-2026-10-20.json`. `infra/matrix_study.py` wrote it from the run `infra/run-matrix-study.ps1` made on 2026-10-20, from 15:55 UTC, in `.scratch/matrix-study-20261020T155528`. Two trees were built once each from their commits:
- tree o: `d571d83`, the code before the fix (the tree the fairness measure study ran);
- tree n: `ed97d8b`, the fix.

| Block | What it holds |
| --- | --- |
| `manifest` | Both revisions, JVM flags, fleets (10, 30, 50), workloads, seeds, the 15 s budget, repetitions (2), tree and arm order rotation |
| `paired.runs` | All 432 solves (6 arms, 36 cases, 2 repetitions). Each records its tree, reference and final cost, ceiling, acceptance reason, both phases' moves per second, and all five measures of its reference and final plans |
| `paired.fix` | The fix's paired speedups for production (baseline over old) and b (b over old-b), overall and by fleet, and the median cost difference |
| `paired.pairs` | For b and c, each case's variances and costs on both sides, the fixed production's spread between repetitions, and the mean differences |
| `paired.summary` | b and c against the four bars, with the fairness-phase speedup by fleet |
| `paired.comparison` | Every arm on every measure against the fixed production (median ratio, cases lower and higher), cost difference, fairness-phase speed, and acceptance reasons |
| `paired.decision` | The fix promoted, b promoted, c not, load balance compared without bars |
| `profiles` | One flight-recording profile per arm and phase on seed 17, twelve recordings each (one per fleet and workload): samples by kind of work, allocation, and the top leaf frames |

`stacks.json` is the second input. `stacks.py` wrote it once from the run's raw recordings, which stay outside git in the run folder. It holds, for the four arm and phase pairs in section 2 of the report, the share of samples at each named call site and the SHA-256 of every recording read. Rerun it with:

```
python stacks.py --run <repository>/.scratch/matrix-study-20261020T155528 --jfr "C:/Program Files/Java/jdk-25/bin/jfr.exe"
```

The fairness measure study's evidence, `docs/evidence/solver-variance-2026-10-20.json`, is read only for this morning's figures quoted for comparison.

## Validation

`report_data.py` checks the following before anything reads the file:
- **The run:**
  - it is the full study, not the smoke run, on the two pinned revisions, at 15 s with two repetitions;
  - the fleets are 10, 30 and 50 technicians, 36 cases in all;
  - every arm, case and repetition is present exactly once.
- **The results:**
  - zero violations;
  - nothing outside a ceiling;
  - no reference plan over its own ceiling;
  - every run carries all five measures for its reference and final plans.
- **The decisions, recomputed from the individual runs:**
  - the fix's median speedups in both phases, overall and by fleet, and its median cost difference;
  - the fix decision (not slower in either phase);
  - the median fairness-phase speedups and cost differences of b and c;
  - the count of cases less fair beyond production's spread;
  - every comparison ratio, rounded as recorded;
  - every acceptance count.

  Each must equal the recorded summary, and each decision must follow from its bars.
- **The profiles:**
  - every group is a known one;
  - shares add to 100;
  - each has twelve recordings;
  - every arm and phase is present.

`verify_report.py` repeats the work without the builder's or loader's helpers, with exact decimal arithmetic (80 digits, so two equal 34-digit variances cannot round apart when averaged). It recomputes these tables from the raw JSON:
- the fix's speedups by fleet;
- what the fix's extra moves bought;
- the bars table, including each pass and fail;
- the comparison table and load balance against b by fleet;
- the call-site table (from `stacks.json`) and the profile table;
- appendices A, B and C.

It also checks:
- the chart data against the evidence;
- that the reference template's styles, numbering, theme, fonts and footer are byte-identical;
- that every figure has a description and a black italic caption;
- that no table row may split;
- that no em or en dash appears in the document XML.

## Derived measures

- **Speedup:** an arm's moves per second in a phase divided by its comparison's in the same case and repetition. The fix compares each variant on tree n with the same variant on tree o; the options compare with tree n's production. The report gives the median over the 72 pairs, never the ratio of medians.
- **Ratio to production on a measure:** each case's mean over two repetitions, divided by the fixed production's mean in the same case. The median is taken over the cases where production's value is above zero.
- **Cost difference:** each case's mean final cost minus its comparison's, in cents. The median is taken over the 36 cases. Medians of two-repetition means can fall on a quarter cent and are shown to it.
- **Spread between repetitions:** the comparison arm's larger value in a case minus its smaller. This is the noise floor for "beyond noise" counts and for the fairness bar.
- **Call-site share:** the share of a phase's execution samples whose leaf frame and nearest scheduler frame match a named site (`stacks.py`, `site`).
- **Thousandths:** variances are shown multiplied by 1000.

Nothing missing is replaced with a value. A ratio whose denominator is zero is left out of a median.

## Figures

Twelve charts come from `charts.py` (matplotlib, Calibri). `build_report.py` regenerates them with `chart_data.json`. Each arm keeps one color across every chart:
- old light grey;
- old-b pale teal;
- baseline (the fixed production) dark grey;
- b teal;
- c light blue;
- load-balance rose.

Two diagrams are self-contained HTML files in `diagrams/`, made with the diagram-design skill's default skin:
- `numbering-arms.html`: the two trees, the six arms and what each is compared with;
- `numbering-lookup.html`: how a leg finds its road matrix cell, before and after the fix.

They pass the skill's `self_check.py`. Render them to `generated/` before building, with Playwright's Chromium:

```
python <diagram-design skill>/scripts/export_png.py diagrams/numbering-arms.html generated/numbering-arms.png 2.5
python <diagram-design skill>/scripts/export_png.py diagrams/numbering-lookup.html generated/numbering-lookup.png 2.5
```

## Interpretation limits

- **Synthetic fleets:** deterministic fixtures of 10, 30 and 50 technicians on fixture seed 7001, four workloads, three seeds.
- **Harness, not service:** each solve ran in its own JVM, limited to two processors on a 24-processor workstation. Absolute moves per second are the harness's. The paired ratios are what transfers.
- **Why the gain shrinks with fleet size:** the report reads this from the profile (the removed work is per leg; the fleet-wide part of a move grows with the fleet). It was not measured separately.
- **Load balance against b:** this study and the fairness measure study share only the 50-technician fleet, where they agree. The lead on 10 and 30 technicians is one study's result and the report asks for a head-to-head test before any policy change.
- **The fairness bar against a zero spread:** where production reached the same plan in both repetitions, any loss fails the bar. This is how the owner set it before the fairness study, and the report does not reargue it.
