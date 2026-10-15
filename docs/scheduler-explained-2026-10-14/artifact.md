# Report design contract

Follows `../daily-tabu-variants-5d15129b/artifact.md`. The formatting authority is `../WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx` (SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`). Its styles.xml, stylesWithEffects.xml, numbering.xml, theme, fontTable.xml and footer parts are preserved byte for byte; the body is replaced.

**Page and type:** one portrait Letter section, Calibri, black headings, pale blue DCE6EF table headers, and alternating FFFFFF and F4F6F8 rows.

**Tables:**
- Header rows repeat, and no table row splits.
- Tables of twelve or fewer rows stay on one page.
- Tables appear only in the appendices: the studies index and the definitions and measures. The body uses figures and prose instead.
- Appendix tables use 9 pt cell text.

**Narrative:** a trail in six parts, read like breadcrumbs: the scheduler at the September demonstration (Part I); the stateless API it became, explained figure by figure from ownership to routing (Part II); how the work was measured (Part III); the five optimization threads in the order the evidence arrived, each told as first iteration, measurement, change, evidence and decision and closing with what it handed on (Part IV); the system as it stands (Part V); and what comes next, performance and features (Part VI). A front timeline and scoreboard give the whole trail first. Appendix A indexes the studies and Appendix B defines terms and measures; there is no reference appendix and no numbered citation. The portal is not described. Every quantitative claim is computed from a validated source at build time; the two claims that are quoted from reports rather than recomputed (the Sept 29 fleet split of K opt, the Oct 1 budget study) say so in the text and in EVIDENCE.md.

**Figures:** all 52 follow the diagram-design skill's grammar (architecture, architecture delta, sequence, flowchart, state machine, layer stack, timeline, data model, heatmap, bar, diverging bar, line, dumbbell, stacked bar and scatter types) on the 1280 canvas under the report skin: white paper, ink #1F2A33, steel blue #7DAFCB service nodes, grey #C9C9C9 stores, and one green accent #287A4C reserved for the focal element of each figure (the choice made, the build that shipped, the headline reply). Comparisons are greys and muted blues so the accent stays the only signal. Every bar axis starts at zero, signed charts draw their zero line, the routing chart is a labelled log scale, and ticks are round; every legend is a strip under the plot.

**Wording:** every latency is the caller's, waits included, and the text says so. Comparisons are between fresh-seed runs only; the two halves of the load series are never read as one curve; savings from different solver studies are never pooled, each study's own control is its comparison. Multithreaded solving is described as planned work with its gate and its cost, with no edition classification. No em or en dash anywhere.
