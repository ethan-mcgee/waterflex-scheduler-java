# WaterFlex scheduler explainer

The expanded Word document explains the scheduler for technical newcomers in 20 rendered pages. It preserves the original narrative, furniture analogy, section order, appendices, Calibri typography and pale blue table headers. There are no runtime, API, configuration or deployment changes.

## Authoring

- `reference.docx` is a byte-for-byte retained copy of the original artifact. Its SHA-256 is `2d296b4ae319f04ab583a25a23df6e11e845e6d1ba3324f7dcad23d96d53df70`. The original under `artifacts/scheduler-explainer/` remains untouched.
- `expansions.md` contains the six major prose expansions and worked tables. The comments identify insertion points in the original section order.
- `build_report.py` loads the retained reference, reuses its styles and table component, inserts the expansions, updates navigation and glossary entries, and pins project sources to `bdbba246979ad98f2666960bf36613f0d41ea07d`. It also expands directed-routing rationale. It writes only the expanded document.
- `verify_report.py` checks preserved package parts and page geometry, original heading order, internal navigation anchors, pinned project source paths, punctuation and example arithmetic. It does not replace visual inspection.

Use Python 3.10 or newer and the pinned dependency:

```powershell
python -m pip install -r docs/scheduler-explainer/requirements.txt
python docs/scheduler-explainer/build_report.py
python docs/scheduler-explainer/verify_report.py
```

The build does not need the ignored artifact folder, external templates, network access or an Office installation. Stable metadata and ZIP timestamps make repeated builds byte reproducible with the pinned dependency. Source text within the retained reference can be edited in Word; changes to that template require an explicit hash update and renewed review.

## Scope and evidence

All example schedules, costs, people and job timings are illustrative. The utilization comparison concerns Alex and Blair; whole-fleet costs are not calculated from just their workload column. The timing trace covers part of the day and explicitly includes both working blocks and their separate return legs.

Repository claims were checked against the committed implementation, policy, configuration constructors, regressions and archived reports at the pinned revision. Reservation issuance, bounded search, CH routing and prewarming are disabled by default. That statement does not establish production settings. No production environment was inspected or changed, and no new performance benchmark was run.

The benchmark figures remain historical evidence at the revisions named by their reports. The document distinguishes reference and accepted solver metrics, matched served demand, retry responses, fixture roads, real-road browser evidence, and local versus production performance. External Timefold latest documentation currently describes 2.7.0; the repository uses 2.6.0, so implementation claims rely on project source rather than the moving latest documentation.

## Render verification

The final document was rendered with the documents skill's `render_docx.py` using an isolated Linux Docker image containing LibreOffice Writer, Poppler and Carlito, the Calibri-compatible font. Rendering ran with networking disabled and the authoring directory mounted read-only. Bundled Windows Python was used for authoring. This host has no native or bundled LibreOffice, so the packaged renderer used the isolated container toolchain.

All 20 page PNGs were visually inspected. Tables are readable, no row is clipped or split, no blank overflow page remains, page numbering is intact, and the navigation ranges match the rendered sections. Word pagination can vary slightly with fonts and renderer versions; rerender after changing content, template, dependency or fonts.

Render with the installed documents skill, placing QA images outside this tracked directory:

```powershell
python "$DocumentsSkill/render_docx.py" docs/scheduler-explainer/WaterFlex_Scheduler_and_Route_Optimization_Explained_Expanded.docx --output_dir artifacts/scheduler-explainer/revision-qa --emit_pdf
```

Inspect every PNG before delivering another revision. The PDF and PNG files are QA intermediates and are not part of the deliverable.
