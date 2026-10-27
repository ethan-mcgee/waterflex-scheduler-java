# Report design contract

This report follows `../fairness-measures-2026-10-20/artifact.md`, which follows `../performance-work-2026-10-14/artifact.md`.

**Template.** The formatting authority is `../WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx` (SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`). Its styles.xml, stylesWithEffects.xml, numbering.xml, theme, fontTable.xml and footer parts are kept byte for byte, and the body is replaced.

**Page and type:**

- one portrait Letter section;
- Calibri;
- black headings;
- pale blue DCE6EF table headers;
- alternating FFFFFF and F4F6F8 rows.

**Tables:**

- Header rows repeat, and no table row splits.
- Tables of twelve or fewer rows stay on one page.
- Appendix and wide tables use 8.5 or 9 pt cell text; body tables use 10 pt.

**Narrative:** each table is explained by a figure next to it. The report runs in this order:

1. scope and the rule written before the run;
2. the mechanism (how the two measures count technicians);
3. the rule;
4. fleet and workload;
5. cost and acceptance;
6. speed and convergence;
7. the profile;
8. conclusions;
9. next steps;
10. complete appendices.

**Figures:**

- Each arm keeps one color across every chart, the same as the fairness measures report: b teal, load balance rose. Each workload also keeps one color.
- The two diagrams use the diagram-design default skin, with burnt-tangerine as the single accent on the policy under test.
- Captions say how to read the figure, not only what it shows.

**Wording:**

- Every ratio is load balance's value over b's in the same case, then a median over cases, with its 95 percent bootstrap interval where the rule reads it.
- Every speed is a median of paired ratios.
- Every variance is in thousandths.
- Every cost is in cents unless it says dollars.
- Lower is fairer on every measure.
- The verdict comes from the rule written before the run; the policy change is the owner's.
