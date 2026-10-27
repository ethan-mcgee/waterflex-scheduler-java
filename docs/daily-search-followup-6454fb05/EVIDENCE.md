# Daily search follow-up 6454fb05 evidence and methods

## Source and scope

The immutable inputs are three archives under `experiments/runs/`, all measured at revision `c21b54b36f9c0e73ef2b17584b531a58ad74781d` (main, containing PR #134):

- Main arm `20261013T052707Z-daily-search-followup-6454fb05`, analysis `20261013T103623Z-5d47be01`. Its matrix is 5 configurations (TABU control, TABU_KOPT, TABU_KOPT_SIZE_3, TABU_SIZE_3, KOPT) x fleets 5, 10, 20, 30 and 50 x four workloads x fixture seeds 1 and 2 x search seeds 211, 223 and 227 x 60 and 120 seconds: 1,200 cases, six at once.
- Long budget arm `20261013T103709Z-daily-search-long-budget-a317757e`, analysis `20261013T143907Z-ca1aefff`. TABU_KOPT (control), TABU, KOPT and LATE_ACCEPTANCE x fleets 30 and 50 x four workloads x fixture seed 3 x seeds 211 and 223 x 240, 480 and 600 seconds: 192 cases, six at once.
- Sequential confirmation `20261013T143914Z-daily-search-confirm-tabu-kopt-size-3-877fd9f7`, analysis `20261013T154455Z-ee4e5912`. TABU and the driver's chosen candidate TABU_KOPT_SIZE_3 x fleets 20 and 50 x four workloads x fixture seed 1 x seeds 211 and 223 x 120 seconds: 32 cases, one at a time.

Every case is `COST_ONLY`: the cost search receives the whole budget and no fairness phase runs. The driver status (`experiments/runs/bench-2026-10-12/status.json`, local only) records the stage order, `DONE` with no problems, and the confirmation ranking.

The first Tabu variant study (`docs/daily-tabu-variants-5d15129b`, run 5d15129b) is loaded through its own validated loader for comparison. Its values are never pooled with this study's.

## Validation

`report_data.py` checks, for each archive:

- **Provenance:** revision, canonical configuration and case hashes against the manifest, the complete cross product including fixture seeds, unique case ids, every raw SHA-256 listed in the analysis provenance and matching its completion receipt, and one result per raw file.
- **Measurement:** `COST_ONLY` with no `fairnessPhase`, zero violations, zero overtime at every stage, TIME_LIMIT termination, and a cost search budget equal to the whole case budget.
- **Policy:** an accepted case keeps the cost search result; a rejected case keeps the starting schedule, and a `FAIRNESS_REGRESSION_WITHIN_ALLOWANCE` rejection has a start within the 2% ceiling and a less even cost search result (SchedulingPolicy.compare).
- **Matched pairs:** fixture fingerprint and starting schedule identity between every configuration and its control, one fingerprint per fleet, workload and fixture seed, no fixture shared between the main and long arms, and the confirmation's fixtures drawn from the main arm.
- **Fairness:** an independent recomputation of fairness variance from paid minutes and capacity.

36 of the 1,200 main cases kept their starting schedule under the policy rule above, all on 5 and 10 technician days. 1,197 of 1,200 main cases ran with every slot busy for at least 95% of their wall time.

`verify_report.py` recomputes 20 numerical tables and all eleven chart datasets from raw results without using the builder's derived fields, including the cross-study tables and the time curve from the first study's raw cases. It checks that the template's styles, numbering, theme, fonts and footer are byte-identical to the reference, and that no em or en dash appears in the document XML.
`build_report.py` asserts every qualitative claim in the prose (signs, orderings, "every" and "never" statements) before writing it.

## Derived measures

- **Saving:** control accepted cost minus the configuration's accepted cost, in cents, for the same fleet, workload, fixture seed, search seed and budget. The control is TABU in the main arm and confirmation and TABU_KOPT in the long arm. Long arm savings against TABU are read from raw costs of the same pair.
- **Cost search time across studies:** this study's budget, or two thirds of the first study's budget. The replication figure compares the first study's 90 second budget with this study's 60 second cases on reference (cost search) savings.
- **Steps per second, moves per step, thousand moves per second and best at:** from the cost search's `steps`, `moveEvaluations`, `solveMs` and `timeToBestMs`. "Late" means best at above 0.9.
- **Own budget gain:** the same configuration, fixture and seed at a shorter budget minus a longer one, in exact integer cents rounded half up.

Means weight cases equally. Wins, ties and losses compare exact cents. Nothing missing is replaced with zero, and the archives have no missing values.

## Interpretation limits

- **Fixtures:** synthetic fixtures with a synthetic travel matrix, not customer territories or measured roads. The long arm has one fixture per fleet and workload and 8 pairs per point.
- **No fairness phase:** variance here is the cost search's own. The first study's archive shows that a fairness search removes most of it; the variance gaps reported are an upper bound for clients with fairness search time above zero.
- **Contention:** six at once, as in the first study. The confirmation found parallel move rates at a median 0.85 (TABU) and 0.82 (TABU_KOPT_SIZE_3) of sequential, and the same sign in 16 of 16 pairs. Those pairs reuse main arm fixtures and seeds, so they rule out a contention artifact but are not an independent sample.
- **Hindsight figures:** the best per-fleet rule is selected on the same data and is optimistic. The first study's rule, scored here on new fixtures, is out of sample.
- **Interpretation versus measurement:** statements about why a configuration behaved as it did beyond recorded steps, candidates per step and time to best (for example a short tabu list revisiting arrangements on 5 technician days) are labeled as interpretation in the report.

## Rebuild

```text
python -m pip install -r docs/daily-search-followup-6454fb05/requirements.txt
python docs/daily-search-followup-6454fb05/report_data.py
python docs/daily-search-followup-6454fb05/build_report.py
python docs/daily-search-followup-6454fb05/verify_report.py
```

The archives under `experiments/runs` (both studies) and the local driver status are gitignored and must be present locally. Calibri on Windows or Carlito on Linux is required for the figures. Render every page after changes; LibreOffice and PyMuPDF were used for review.
