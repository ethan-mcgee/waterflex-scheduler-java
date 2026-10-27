# Report design contract

Follows `../performance-work-2026-10-14/artifact.md`. The formatting authority is `../WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx` (SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`). Its styles.xml, stylesWithEffects.xml, numbering.xml, theme, fontTable.xml and footer parts are preserved byte for byte; the body is replaced.

**Page and type:** one portrait Letter section, Calibri, black headings, pale blue DCE6EF table headers, and alternating FFFFFF and F4F6F8 rows.

**Tables:**

- Header rows repeat, and no table row splits.
- Tables of twelve or fewer rows stay on one page.
- Appendix tables use 9 pt cell text; body tables use 10 pt, or 9 pt where a table has more than eight columns.

**Narrative:** the reference's sequence of scope, plain-language mechanisms, quantitative figures with reading instructions, variation, implications and complete appendices.
This report explains each setting of the booking search (the optional refinement cap, the window threshold, the booking pool layout and the publish reserve) through what the scheduler recorded: how each refinement ended, how many regular windows the first pass found, and the time each search spent waiting, routing, in the engine and committing.
Section 12 states each conclusion as what was measured, why, and what follows; Appendices A to D list every cell the report draws on.

**Figures:** eleven single-diagram figures, each one chart type with one message (a flowchart of the search over its deadline, heatmap, dot plot, dumbbell, swarm, line chart, stacked bar), authored as HTML with inline SVG in `figures.py` on a 1000-wide canvas and rasterized at scale 3 by `render.py` for the 6.85 in column.
The skin is the report's: white paper, Calibri for labels and Consolas for axis values and eyebrows, black ink #1F2A33, muted #444444, and a single accent red #B22222 that marks one focal element per figure (today's cap, the uncapped routing segment, the commit time). Where a figure needs several series the lines use ink, blue, green and olive; the heatmap and the stacked parts use an ink ramp.
Every figure carries an eyebrow heading, an accessible title and description, data-* bindings on each mark, a bottom legend strip and a one-line source note. Latency axes show the 5 s deadline where it applies; the reserve figure shows the 800 ms floor and the 1000 ms default. The skill's self check passes on every generated page.

**Wording:** every latency is the caller's unless the text names the scheduler's own time. Full-schedule quality comes from the rerun only. Gaps under the measured noise floor are called ties. Mechanisms read from the code are labeled as such, inferences as inferences, and results the study could not explain are said to be unexplained.
