# Report design contract

Follows `../daily-tabu-variants-5d15129b/artifact.md`. The formatting authority is `../WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx` (SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`). Its styles.xml, stylesWithEffects.xml, numbering.xml, theme, fontTable.xml and footer parts are preserved byte for byte; the body is replaced.

**Page and type:** one portrait Letter section, Calibri, black headings, pale blue DCE6EF table headers, and alternating FFFFFF and F4F6F8 rows.

**Tables:**
- Header rows repeat, and no table row splits.
- Tables of twelve or fewer rows stay on one page.
- Appendix and dense tables use 9 pt cell text; body tables use 10 pt.

**Narrative:** three parts in the order a newcomer needs them: the system as it stands (what it is, where it runs, one request end to end, the contract, many clients, routing), then each optimization thread told as first iteration, what the measurement showed, what changed and what the numbers justified (daily search, booking search, routing, admission, the engine), then what comes next (performance, solver quality). The portal is not described. Every quantitative claim is computed from a validated source at build time; the two claims that are quoted from reports rather than recomputed (the Sept 29 fleet split of K opt, the Oct 1 budget study) say so in the text and in EVIDENCE.md.

**Figures:** one color per thread across all twelve figures (daily search green, booking blue, routing red, admission gold, engine purple, the remote pool orange, the pre-change state grey, the VM baseline navy). Diagrams reuse the stateless report's drawings; study charts reuse the drawing code of the studies they come from.

**Wording:** every latency is the caller's, waits included, and the text says so. Comparisons are between fresh-seed runs only; the two halves of the load series are never read as one curve; savings from different solver studies are never pooled, each study's own control is its comparison. Multithreaded solving is described as planned work with its gate and its cost, with no edition classification. No em or en dash anywhere.
