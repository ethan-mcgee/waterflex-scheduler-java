# Agent instructions

- Never add Codex as a co-author on a git commit or PR.
- Never use an em-dash.

When creating a plan, break implementation into explicit, actionable steps. When implementation begins, register those steps with the native plan tracking tool (`update_plan`, when available) so the status line's `task-progress` item can reflect progress. Keep exactly one step `in_progress` while actively working and mark steps `completed` as they finish, leaving future steps `pending`. Update the tracked plan at each step transition and whenever scope changes, including verification and PR creation where applicable. Mark verification complete only after the required checks pass, and leave unfinished work pending. If native plan tracking is unavailable, state that limitation and maintain a visible checklist without claiming the status line was updated.

During implementation, treat nullability and type safety as acceptance criteria. Validate external requests, responses, and persisted JSON before use. Declare intentional nulls explicitly and check required database values and collection lookups. Never replace missing scheduling data with fabricated zero, false, or empty values. Resolve new nullability warnings before declaring work complete. Do not weaken checks or add broad suppressions to obtain a clean build. Add regression coverage for actual null failure paths and run the nullability, lint, typecheck, and relevant test gates.

Workspace hygiene (owner's rule, 2026-10-22): never write files outside this repository, and never into the parent `waterflex` folder, which syncs to OneDrive. Command output and scratch files go to the session scratchpad or the gitignored `.scratch/`; study and run output goes to `experiments/runs/<run-id>/`. Tokens and other secrets are never written to files. Create study and review worktrees under `C:\Projects\waterflex-worktrees\<name>`, outside OneDrive, so syncing does not add noise to benchmark runs. Before removing a worktree, check its gitignored files (`git status --ignored`) and move run output, recordings and logs into this checkout's `experiments/runs/`, because `git worktree remove` deletes ignored files without asking. Remove each worktree with `git worktree remove` as soon as its run or review ends, and list the remaining ones in the handoff. Once a branch is merged into `main` (feature, `bench/*`, `claude/*`, `codex/*` or any other agent or study branch), remove any worktree still on it and delete the branch locally and on `origin`. Before deleting, confirm with `git merge-base --is-ancestor <branch> main` or the merged PR. Unmerged `bench/*` branches stay until the study's write-up records their commits and the treatment is rejected, then delete them with `git branch -D`.

Benchmarks and studies (owner's rule, 2026-10-20): run paired solver studies on the local workstation harness (frozen trees from commits, one JVM per case, alternating order, repeated runs), with the promotion bars written before the run; a speed treatment is promoted at a median 1.05x paired moves per second on the phase it targets and not slower on the other. Then verify every promoted change on the VM rehearsal stack (`deploy/vm` with `rehearsal.override.yml`, 127.0.0.1:18080, shipped routing and admission defaults, images pinned to the commits under test) through the public API before calling it shipped. Never quote the harness's absolute rates as the service's; the paired ratios are what transfers. The owner starts long runs; prepare a start script.

## Markdown

These rules apply to every `.md` file except the frozen and generated files listed at the end.

### General

- One H1 per file, on the first line. Headings use sentence case, never skip a level and have no trailing punctuation.
- One paragraph per line; do not hard-wrap prose. Put a blank line between every block: headings, paragraphs, lists, tables, code and diagrams.
- No trailing spaces. Each file ends with a single newline.
- Link repo files with relative paths and URLs as `[text](url)`, never bare. Link text names the target, never "here".

### Lists

- Bullets use `-`, never `*` or `+`. Use an ordered list only when order matters, numbered `1.`, `2.`, `3.` in sequence.
- Indent nested items, continuation lines, tables and code inside an item to the parent's text: 2 spaces under `-`, 3 under `1.`. An unindented paragraph ends the list.
- A bold lead-in is written `- **Label:** text`, with the colon inside the bold.
- Items in one list are parallel: all sentences (each ending in a period) or all fragments (no periods).

### Tables

- Every row has a leading and trailing pipe and the same number of cells.
- Align the table in the source: pad every cell to its column's widest entry so the pipes line up in every row, with one space inside each pipe. The separator row's dashes fill the column width.
- Right-align numeric columns (`---:` at full width) and left-align text columns. Left-aligned cells pad on the right and right-aligned cells pad on the left.
- When a cell changes width, realign the whole table. Keep cells ASCII so character count matches display width.
- In new tables, put a shared unit in the header, as in `p95 (s)`, rather than in every cell.
- Escape `|` inside a cell as `\|`, including inside code.
- Write a missing value as `not measured` and a value that does not apply as `n/a`. Never write `0`, `-`, `none` or a blank cell for missing data.
- Introduce a table with a sentence that says what it shows. Prefer 7 columns or fewer; split wider data or move it to the evidence JSON.
- No lists, line breaks or HTML inside cells.

### Diagrams

- Draw diagrams in plain ASCII inside a `text` fence: `+`, `-` and `|` for boxes, `-->` and `<--` for flow, `v` and `^` for vertical flow, and labels inline as `--201-->`. No Unicode box characters, no tabs.
- Keep diagrams within 100 columns and use the component names from the code and Compose files (`scheduler-service`, `routing-service`).
- Introduce each diagram with a sentence that says what it shows.
- Use images only for generated report figures. Give them alt text that states what the figure shows and keep their source beside them.

### Code blocks

- Every fence names a language: `powershell` for workstation commands, `sh` for Linux, CI and containers, `json`, `yaml`, `sql`, `http`, and `text` for output, logs and diagrams.
- Commands have no prompt characters (`$`, `PS>`). Placeholders look like `<run-id>`. Show command output in its own `text` block.

### Generated and frozen files

- Change a generated report through its generator and regenerate it; never hand-edit the output. `docs/scheduler-field-validation.md` comes from `infra/report-field-validation.py` and `docs/scheduler-algorithm-comparison-2026-09-29.md` from `infra/build-scheduler-algorithm-comparison.py`.
- Never edit frozen evidence: `docs/timefold-audit/README.md`, `Timefold_Audit.md` and `Timefold_Second_Review.md` (byte-checked by `docs/timefold-audit/remediation/verify_baseline.py`) and the vendored `docs/timefold-documentation/`.
- Some docs are read by scripts. Rebuild and verify after editing them: `docs/booking-experiment-explainer/report.md` and `docs/scheduler-explainer/expansions.md` (their `build_report.py` and `verify_report.py`) and `docs/api-load.md` (`docs/president-briefing-2026-10-22/verify_report.py`).
