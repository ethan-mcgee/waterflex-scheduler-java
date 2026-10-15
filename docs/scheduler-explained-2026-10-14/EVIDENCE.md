# WaterFlex scheduler explained (2026-10-14): sources and methods

## Sources

The report is built from files in the repository, read and checked at build time. Where a sibling report already validates a source, its loader is imported rather than copied, so the same checks apply here.

| Source | Read through | What the report takes from it |
| --- | --- | --- |
| `docs/evidence/api-load-*.json` (11 load studies, #151 to #164), `solver-profiling-2026-10-14.json`, `routing-ch-omaha-2026-09-24.json`, `routing-ch-2026-10-14.json` | `docs/performance-work-2026-10-14/report_data.py` (imported) | Every load run, the two JFR profiles and the 24 paired solves, the two routing benchmarks |
| `experiments/runs/20261010T040557Z-daily-tabu-variants-p6-5d15129b` and its sequential confirmation | `docs/daily-tabu-variants-5d15129b/report_data.py` (imported) | 4,480 matched cases on 16 fixtures; 128 confirmation cases |
| `experiments/runs/20261013T052707Z-daily-search-followup-6454fb05`, the long budget arm and the confirmation | `docs/daily-search-followup-6454fb05/report_data.py` (imported) | 1,200 + 192 + 32 cases on 40 new fixtures; the decision record |
| `docs/booking-experiment-explainer/evidence.json` | `docs/booking-experiment-explainer/report_data.py` (imported: `verify`, `pairs`, `savings`, `outcomes`) | 360 cases, 3,600 requests; 60 paired sequential cases; p95 by concurrency |
| `docs/evidence/scheduler-field-2026-09-29/summary.json` | this folder's loader | Held-out booking service per variant; daily paired mean savings per budget and variant |
| `docs/evidence/sparse-routing-omaha-2026-09-23.json`, `booking-omaha-routing-2026-09-24.json` | this folder's loader | The sparse routing samples; 720 booking requests per graph |
| `docs/api/openapi-v1.yaml`, `docs/stateless-api-design.md`, `docs/integration-guide.md`, `docs/operations-runbook.md` | this folder's loader | Contract version, operations, error codes, version history; the endpoint, contract change, thin store, lifetime and route cache tables; the ownership and error tables; the settings table |
| `git show 08dae8b:docs/stateless-api-design.md` | typed into `report_data.py` (`BEFORE_AFTER`) with that source | The "since the demonstration" comparison of section 1 |

Two statements are quoted from reports rather than recomputed, and the text says so: the fleet split of K opt's 15 s result in the September 29 comparison (its per-case daily results are not in `summary.json`), and the October 1 budget study (`docs/daily-budget-331df1f1`), whose run archive is not in the repository.

## Validation

`report_data.py` asserts, before anything is read into the document:
- **Documents:** every operation is `implemented`; 12 operations and 10 error codes; the first version in the history equals `info.version`; every error code of the enum appears in the guide's table; each parsed markdown table has the row count recorded in `DOCUMENT_TABLES` (a changed document changes the count and stops the build).
- **Daily studies:** the sibling loaders' own checks (manifest and case hashes, every raw file's SHA-256, matched pairs sharing a starting schedule, zero violations, fairness variance recomputed from paid minutes); then 4,480 and 1,200 cases, six parallel cases, Tabu as control, `TABU_KOPT_SIZE_3` as the recorded candidate.
- **September booking:** the explainer's `verify` (360 cases, hashes, ten attempts per case, all audit metrics present); 60 comparable pairs, all at concurrency 1; bounded never worse on paid minutes or cost.
- **Field validation:** execution complete; expected equals actual case counts; held-out variants at 480 requests and 48 cases with outcomes summing to the requests; 21 daily paired cells with two independent seeds; 240 daily cases.
- **Routing:** the sparse check on map 2026-09-16 with every pair routable; both Omaha booking graphs at 72 cases and 720 requests.
- **Load studies:** the performance loader's checks (statuses add up to calls, p50 to max ordered, no contract breach, confirms reconciled with the host's write conflicts, queued waits matching proposals, profile samples summing, paired summaries recomputed).

`build_report.py` asserts the narrative's claims before writing them: Tabu + K opt positive in every fleet and workload of both studies; size 3 ahead of Tabu + K opt from 10 technicians and behind at 5; 16 of 16 sequential wins; the late acceptance family negative at 50 technicians; bounded served more than insertion, which served more than shared; remote booking p95 above 1.05 times embedded; 21 paired wins with no violation.

`verify_report.py` recomputes every numerical table and every chart dataset from the raw sources without the builder's or loader's helpers, compares them cell for cell with the document, and checks that the template's styles, numbering, theme, fonts and footer are byte-identical to the reference, that every figure has a description, that no table row may split, and that no em or en dash appears in the document XML.

## Derived measures

- **Saving per case:** the control's accepted cost in cents minus the configuration's, over 100, for the matched case (same fleet, workload, fixture, seed and budget).
- **Mean saving by fleet:** the mean of the savings of every case of that configuration and fleet (160 per cell in the first study, 48 in the follow-up).
- **Wins / ties / losses:** savings above, equal to, or below zero in exact cents.
- **Best at percent of phase:** `timeToBestMs / solveMs` of the cost phase, averaged over the group.
- **Variance:** the capacity-weighted variance of utilization recorded with each case (and recomputed by the sibling loaders), averaged over the group.
- **Paid minutes and cost saved (September booking):** insertion's audit value minus bounded's for the same case, averaged over the 60 comparable pairs; `p95` is the explainer's quantile of all attempts' `elapsed_ms` for that method and concurrency.
- **Searches per minute, holds lost, slot wait, per fetched leg, routing and daily CPU:** as defined in `docs/performance-work-2026-10-14/EVIDENCE.md`.
- **Per-fleet moves per second (study L):** the median over the fleet's 12 cases of each side; the speedup is the median of the per-case ratios.

Means and percentiles of load runs are the load tool's own. Nothing missing is replaced with a value.

## Interpretation limits

- **Not customer traffic:** synthetic days with a synthetic travel matrix in the solver studies; random Omaha points and synthetic days in the load runs; fixture days on real Omaha roads only in the September booking runs.
- **Two halves of the load series:** #151 to #157 ran the scheduler jar alone on the workstation; #158 to #164 ran the `deploy/vm` rehearsal stack with its CPU limits beside five queued daily planners. Figure 10 marks the boundary and the text never joins the halves.
- **Different studies, different controls:** the September 29 comparison, the October 1 budget study, the Tabu variant study and the follow-up each have their own fixtures, budgets and degree of parallel execution (10 parallel cases in the budget study, 6 in the later two). Their savings are never pooled; each is read against its own control.
- **Seeds:** a load run on seeds used before leaves roads cached; every load comparison is between fresh-seed runs.
- **Shape, not capacity:** the rehearsal stack's limits are the compose defaults on a workstation; the real VM sets its own numbers, and the latency target at the WaterFlex caller across the network has not been measured.
- **Mechanism versus measurement:** how a contraction hierarchy or a tabu list works is from the engines' documentation; every number is the project's own.
- **Multithreaded solving** is described as planned work with its gate and cost; the document makes no claim about which edition of the solver offers it.

## Rebuild

```text
python -m pip install -r docs/scheduler-explained-2026-10-14/requirements.txt
python docs/scheduler-explained-2026-10-14/report_data.py
python docs/scheduler-explained-2026-10-14/build_report.py
python docs/scheduler-explained-2026-10-14/verify_report.py
```

Every figure is an HTML page with inline SVG in the diagram-design format, under the report's own skin (Calibri and Consolas, steel blue nodes, one green accent). Figure 1 is drawn by hand in `figures/topology.html`; figures 2 to 12 are generated by `figures/diagrams.py` from the numbers `charts.py` records in `generated/chart_data.json`, which `verify_report.py` recomputes. `figures/render.py` rasterizes each page with Playwright, which needs its Chromium build once: `python -m playwright install chromium`. Calibri on Windows or Carlito on Linux is required. The sibling loaders need the experiment archives under `experiments/runs` and `.scratch/bench-2026-10-12/status.json`. Render every page after changes; LibreOffice and PyMuPDF were used for review.
