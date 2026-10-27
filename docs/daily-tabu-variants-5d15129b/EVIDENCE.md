# TABU variant run 5d15129b evidence and methods

## Source and scope

The immutable inputs are three groups of archives under `experiments/runs/`, all measured at revision `99be00b031678a353ad445222b07ccb5f3f9ef92` (branch `bench/tabu-variants`, PR #130):

- Main study `20261010T040557Z-daily-tabu-variants-p6-5d15129b`, analysis `20261011T070344Z-a8941f51`. Its matrix is 7 configurations x fleets 5, 10, 20 and 50 x CLUSTERED, DISPERSED, MIXED_SKILL and TIGHT_WINDOW x ten seeds x budgets of 60, 90, 120 and 240 seconds: 4,480 cases, six at a time.
- Contention check: sequential `20261010T024747Z-daily-contention-1-772fc235`, compared with 4, 6 and 10 at once (`...contention-4-bdad2801`, `...contention-6-df602432`, `...contention-10-575575c0`). Each parallel archive holds its saved `contention/*.json` report.
- Sequential confirmation `20261011T070444Z-daily-tabu-confirm-tabu-kopt-3a77f220`, analysis `20261011T133321Z-7e74d4fc`. This is TABU and TABU_KOPT on seeds 17 and 23, every fleet and workload, at 120 and 240 seconds, one case at a time: 128 cases.

No parallel setting met the 0.95 contention bar. The owner chose six at once on 2026-10-10. The unattended driver recorded that decision (`experiments/runs/bench-2026-10-09/status.json`, local only).

## Validation

`report_data.py` checks, for the main and confirmation archives:

- **Provenance:** canonical configuration and case hashes against the manifest, the complete cross product, unique case ids, every raw SHA-256 listed in the analysis provenance, and one result per raw file.
- **Measurement:** zero violations, zero overtime, the 2% cost ceiling, TIME_LIMIT termination of both phases, and a two thirds reference phase budget.
- **Matched pairs:** fixture and starting-schedule identity between every configuration and its Tabu twin.
- **Fairness:** an independent recomputation of fairness variance from paid minutes and capacity.

It also loads the three contention reports and checks that they reference the same sequential run. 4,475 of the 4,480 main cases ran with every slot busy for at least 95% of their wall time; the remaining five are the run's tail.

`verify_report.py` recomputes 16 numerical tables and all ten chart datasets from raw results without using the builder's derived fields. It also checks that the template's styles, numbering, theme, fonts and footer are byte-identical to the reference. It confirms that no em or en dash appears in the document XML.

## Derived measures

- **Saving:** Tabu accepted cost minus the configuration's accepted cost, in cents, for the same fleet, workload, seed and budget.
- **Steps per second, moves per step and thousand moves per second:** taken from the reference phase's `steps`, `moveEvaluations` and `solveMs`.
- **Best at:** `timeToBestMs / solveMs` of the reference phase. "Late" means best at above 0.9.
- **Fairness spend:** accepted cost minus reference cost.

Means weight cases equally. Wins, ties and losses compare exact cents. Nothing missing is replaced with zero, and the archive has no missing values.

## Interpretation limits

- **Fixtures:** sixteen deterministic synthetic fixtures and ten seeds, not customer territories or measured roads.
- **Contention:** six cases at once reduced move rates by about 15% for every configuration. Comparisons between configurations are fair; absolute budgets correspond to roughly 0.84 of solo time. The sequential confirmation agrees in sign on 60 of 64 matched cases.
- **Interpretation versus measurement:** explanations of why a configuration behaved as it did rest on recorded step counts, candidates per step and time to best. Statements beyond those (for example cycling with tabu size 3 on five technicians) are labeled as interpretation in the report.
- **Hindsight figure:** the per-fleet "best in hindsight" figure is selected on the same data and is optimistic.

## Rebuild

```text
python -m pip install -r docs/daily-tabu-variants-5d15129b/requirements.txt
python docs/daily-tabu-variants-5d15129b/report_data.py
python docs/daily-tabu-variants-5d15129b/build_report.py
python docs/daily-tabu-variants-5d15129b/verify_report.py
```

The archives under `experiments/runs` are gitignored and must be present locally. Calibri on Windows or Carlito on Linux is required for the figures. Render every page after changes; LibreOffice and PyMuPDF were used for review.
