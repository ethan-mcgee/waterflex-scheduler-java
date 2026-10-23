# WaterFlex scheduler explained (first edition 2026-10-14, brought up to date 2026-10-22): sources and methods

## Sources

The report is built from files in the repository, read and checked at build time. Where a sibling report already validates a source, its loader is imported rather than copied, so the same checks apply here.

| Source | Read through | What the report takes from it |
| --- | --- | --- |
| `docs/evidence/api-load-*.json` (11 load studies, #151 to #164; the report leaves study K, #164, the embedded engine against a remote solver pool, out at the owner's request), `solver-profiling-2026-10-14.json`, `routing-ch-omaha-2026-09-24.json`, `routing-ch-2026-10-14.json` | `docs/performance-work-2026-10-14/report_data.py` (imported) | Every load run, the two JFR profiles and the 24 paired solves, the two routing benchmarks |
| `experiments/runs/20261010T040557Z-daily-tabu-variants-p6-5d15129b` and its sequential confirmation | `docs/daily-tabu-variants-5d15129b/report_data.py` (imported) | 4,480 matched cases on 16 fixtures; 128 confirmation cases |
| `experiments/runs/20261013T052707Z-daily-search-followup-6454fb05`, the long budget arm and the confirmation | `docs/daily-search-followup-6454fb05/report_data.py` (imported) | 1,200 + 192 + 32 cases on 40 new fixtures; the decision record |
| `docs/booking-experiment-explainer/evidence.json` | `docs/booking-experiment-explainer/report_data.py` (imported: `verify`, `pairs`, `savings`, `outcomes`) | 360 cases, 3,600 requests; 60 paired sequential cases; p95 by concurrency |
| `docs/evidence/scheduler-field-2026-09-29/summary.json` | this folder's loader | Held-out booking service per variant; daily paired mean savings per budget and variant |
| `docs/evidence/sparse-routing-omaha-2026-09-23.json`, `booking-omaha-routing-2026-09-24.json` | this folder's loader | The sparse routing samples; 720 booking requests per graph |
| `docs/evidence/api-load-2026-10-16-dynamic-horizon.json` | this folder's loader | 24 runs: fixed against dynamic horizon, low and high demand, 1 and 5 desks, three seeds; no defect, no contract breach, no failed search |
| `docs/evidence/api-load-2026-10-19-booking-configuration.json.gz`, `-full.json.gz` | `docs/booking-configuration-study-2026-10-19/report_data.py` (imported) | The full-schedule refinement frontier (40 priced cases per fleet and cap) and searches per minute by pool layout (the 2:250 variant, four cells per layout) |
| `docs/evidence/solver-representation-2026-10-20.json` | this folder's loader | T, S and T+S paired summaries; a full run with no violation |
| `docs/evidence/solver-variance-2026-10-20.json` | `docs/fairness-measures-2026-10-20/report_data.py` (imported) | Formulas b and c at 15 and 60 s, their bars and the decision |
| `docs/evidence/solver-matrix-2026-10-20.json` | `docs/road-matrix-numbering-2026-10-20/report_data.py` (imported) | The numbering fix's paired speedups; formula b against the fixed production, case by case |
| `docs/evidence/api-load-2026-10-20-binary-variance.json`, `api-load-2026-10-22-long-score-money-w-a.json` | `docs/stack-verification-2026-10-20/report_data.py` (imported) and this folder's `stack_ratios` | Formula b (#189) and L, M, W and A (#191) through the public API, images pinned to the commits under test, 10, 20 and 50 technicians, three seeds |
| `docs/api/openapi-v1.yaml`, `docs/stateless-api-design.md`, `docs/integration-guide.md`, `docs/operations-runbook.md` | this folder's loader | Contract version, operations, error codes, version history; the parsed tables behind the surface, refusal, thin store and route cache figures |

Some statements are quoted from reports rather than recomputed, and the text says so: the fleet split of K opt's 15 s result in the September 29 comparison (its per-case daily results are not in `summary.json`); the October 1 budget study (`docs/daily-budget-331df1f1`), whose run archive is not in the repository; study 3 (L+M 1.288x, L+M+W 1.047x and W+A 1.051x over L+M, CR11 0.63x, 2,340 solves), quoted from `docs/solver-benchmarks.md` because its evidence lives on the branch `bench/study-3`, and recorded in `QUOTED` in `report_data.py`; and, from the same document and `docs/api-load.md`, T's clock arithmetic falling from 18.9 to 0.1 percent of cost-phase samples and the horizon study's one-desk p50s of 394 and 372 ms.

## Validation

`report_data.py` asserts, before anything is read into the document:
- **Documents:** every operation is `implemented`; 12 operations and 10 error codes; the first version in the history equals `info.version`; every error code of the enum appears in the guide's table; each parsed markdown table has the row count recorded in `DOCUMENT_TABLES` (a changed document changes the count and stops the build).
- **Daily studies:** the sibling loaders' own checks (manifest and case hashes, every raw file's SHA-256, matched pairs sharing a starting schedule, zero violations, fairness variance recomputed from paid minutes); then 4,480 and 1,200 cases, six parallel cases, Tabu as control, `TABU_KOPT_SIZE_3` as the recorded candidate.
- **September booking:** the explainer's `verify` (360 cases, hashes, ten attempts per case, all audit metrics present); 60 comparable pairs, all at concurrency 1; bounded never worse on paid minutes or cost.
- **Field validation:** execution complete; expected equals actual case counts; held-out variants at 480 requests and 48 cases with outcomes summing to the requests; 21 daily paired cells with two independent seeds; 240 daily cases.
- **Routing:** the sparse check on map 2026-09-16 with every pair routable; both Omaha booking graphs at 72 cases and 720 requests.
- **Load studies:** the performance loader's checks (statuses add up to calls, p50 to max ordered, no contract breach, confirms reconciled with the host's write conflicts, queued waits matching proposals, profile samples summing, paired summaries recomputed).

`build_report.py` asserts the narrative's claims before writing them: Tabu + K opt positive in every fleet and workload of both studies; size 3 ahead of Tabu + K opt from 10 technicians and behind at 5; 16 of 16 sequential wins; the late acceptance family negative at 50 technicians, finding its best schedule after 90 percent of its phase there while Tabu + K opt finds it before 75 percent; the long arm's sign changes at 50 technicians; the variance ordering of both studies; bounded served more than insertion, which served more than shared; no hold lost after #152 and every p95 under 5.5 s; equal service on both graphs in September and the J search ordering; remote booking p95 above 1.05 times embedded; the profile's BigDecimal share up and copies share down; 21 paired wins with no violation. For the later work: no horizon search empty at one desk or under low demand, over half of the fixed horizon's five-desk searches empty against under a tenth of the dynamic one's; the 250 ms cap no better than none within the noise at every fleet, 2000 ms better than 1000 and 3000 inside the noise everywhere; one replica with a slot per vCPU fastest at 3 and 4 vCPU and two single-slot replicas faster at 2; T at least 1.05 times in the cost phase and not slower in the fairness phase; S past 1.25 times with a case dearer beyond noise; formula b passing at both budgets with no case less fair, and c failing a bar; the numbering fix not slower in either phase; study 3's W+A above and W alone below 1.05; formula b above 2 times on the stack and the shipped engine above 1.05 times at every fleet. It also asserts that no paragraph or cell carries a dash or a bracketed citation marker, and that every generated figure is placed exactly once.

`verify_report.py` recomputes the chart datasets from the raw sources without the builder's or loader's helpers: the September 29 budget lines from `summary.json`; wins, ties, losses, best-found points, variance and the long-budget arm from every raw result file the experiment archives' provenance names (hashes checked); holds lost, J routing, attribution, progression, confirm and remote from the load evidence; the routing benchmarks; the paired fleets and profile groups from the profiling evidence; the September booking study; the horizon chart from the raw horizon runs; the engine chain's ratios from the representation, variance, matrix and profiling evidence; formula b's per-case variance ratios and cost differences from the matrix evidence's pairs; and the stack ratios from the two stack evidence files' per-seed rates. The refinement frontier and capacity charts come through the booking configuration study's own loader and verifier and are not recomputed here. It checks that the template's styles, numbering, theme, fonts and footer are byte-identical to the reference, that each figure listed in `generated/figures.txt` is placed once with a numbered description, that no table row may split, that no citation marker or Appendix C remains, and that no em or en dash appears in the document XML.

## Derived measures

- **Saving per case:** the control's accepted cost in cents minus the configuration's, over 100, for the matched case (same fleet, workload, fixture, seed and budget).
- **Mean saving by fleet:** the mean of the savings of every case of that configuration and fleet (160 per cell in the first study, 48 in the follow-up).
- **Wins / ties / losses:** savings above, equal to, or below zero in exact cents.
- **Best-found point:** `timeToBestMs / solveMs` of the cost phase, averaged over the group.
- **Variance:** the capacity-weighted variance of utilization recorded with each case (and recomputed by the sibling loaders), averaged over the group.
- **Long-budget arm:** saving against Tabu + K opt, the arm's own control, averaged over the 8 cases of each fleet, budget and configuration.
- **Paid minutes and cost saved (September booking):** insertion's audit value minus bounded's for the same case, averaged over the 60 comparable pairs; `p95` is the explainer's quantile of all attempts' `elapsed_ms` for that method and concurrency.
- **Searches per minute, holds lost, slot wait, per fetched leg, routing and daily CPU:** as defined in `docs/performance-work-2026-10-14/EVIDENCE.md`.
- **Paired moves-per-second ratio:** after over before on the same case, seed and budget; a study reports the median over its pairs. Ratios from different studies are never multiplied, and each is read against its own control.
- **Stack ratio:** for each fleet and seed, after over before in the phase at the kit's p50 moves per second; the median over the three seeds.
- **Gap to the uncapped search:** the mean extra modeled cost per booking of a cap against the same search with no cap, on full schedules, 40 priced cases per fleet; about $0.80 is the run-to-run noise the study measured.
- **Per-fleet moves per second (study L):** the median over the fleet's 12 cases of each side; the speedup is the median of the per-case ratios.

Means and percentiles of load runs are the load tool's own. Nothing missing is replaced with a value.

## Interpretation limits

- **Not customer traffic:** synthetic days with a synthetic travel matrix in the solver studies; random Omaha points and synthetic days in the load runs; fixture days on real Omaha roads only in the September booking runs.
- **Two halves of the load series:** #151 to #157 ran the scheduler jar alone on the workstation; #158 to #162 ran the `deploy/vm` rehearsal stack with its CPU limits beside five queued daily planners. The progression figure of section 12 marks the boundary and the text never joins the halves.
- **Different studies, different controls:** the September 29 comparison, the October 1 budget study, the Tabu variant study and the follow-up each have their own fixtures, budgets and degree of parallel execution (10 parallel cases in the budget study, 6 in the later two). Their savings are never pooled; each is read against its own control.
- **Seeds:** a load run on seeds used before leaves roads cached; every load comparison is between fresh-seed runs.
- **Shape, not capacity:** the rehearsal stack's limits are the compose defaults on a workstation; the real VM sets its own numbers, and the latency target at the WaterFlex caller across the network has not been measured.
- **Mechanism versus measurement:** how a contraction hierarchy, a tabu list or a K opt move works is from the engines' documentation, and the mechanism figures (moves, CH, RoadMatrix, slots, cores) are schematics with no data; every number is the project's own.
- **Sources in prose, not citations:** this edition carries no numbered citation markers and no reference appendix; Appendix A lists every study and evidence file, and the text names the design document, contract and guides where it relies on them.
- **Harness and stack:** the solver studies' ratios come from the workstation harness and transfer to the service; their absolute rates do not, and the stack's absolute rates are a shape of the rehearsal stack, so neither is quoted.
- **Multithreaded solving** is described as planned work with its gate and cost; the document makes no claim about which edition of the solver offers it.

## Rebuild

```text
python -m pip install -r docs/scheduler-explained-2026-10-14/requirements.txt
python docs/scheduler-explained-2026-10-14/report_data.py
python docs/scheduler-explained-2026-10-14/build_report.py
python docs/scheduler-explained-2026-10-14/verify_report.py
```

Every figure is an HTML page with inline SVG in the diagram-design format, under the report's own skin (Calibri and Consolas, steel blue nodes, one green accent). The document has 55 figures, in the order `generated/figures.txt` records, each placed at 6.85 inches wide. The topology is drawn by hand in `figures/topology.html`; the eleven original generated figures come from `figures/diagrams.py`, Part I from `figures/trail.py`, Parts II to V from `figures/thread.py`, and the six figures of the later work (horizon, refinement frontier, capacity, engine chain, formula b, stack) from `figures/later.py`, all on the helpers in `figures/svglib.py`. Every number a figure shows is recorded by `charts.py` in `generated/chart_data.json`, which `verify_report.py` recomputes. Every page passes the diagram-design skill's `self_check.py --offline`. `figures/render.py` rasterizes each page with Playwright, which needs its Chromium build once: `python -m playwright install chromium`. Calibri on Windows or Carlito on Linux is required. The sibling loaders need the experiment archives under `experiments/runs` and `.scratch/bench-2026-10-12/status.json`. Render every page after changes; LibreOffice and PyMuPDF were used for review.
