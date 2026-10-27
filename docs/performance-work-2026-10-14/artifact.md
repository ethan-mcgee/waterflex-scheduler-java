# Report design contract

Follows `../daily-tabu-variants-5d15129b/artifact.md`. The formatting authority is `../WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx` (SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`). Its styles.xml, stylesWithEffects.xml, numbering.xml, theme, fontTable.xml and footer parts are preserved byte for byte; the body is replaced.

**Page and type:** one portrait Letter section, Calibri, black headings, pale blue DCE6EF table headers, and alternating FFFFFF and F4F6F8 rows.

**Tables:**

- Header rows repeat, and no table row splits.
- Tables of twelve or fewer rows stay on one page.
- Appendix tables use 9 pt cell text; body tables use 10 pt.

**Narrative:** the reference's sequence of scope, plain-language mechanisms, quantitative figures with reading instructions, variation, implications and complete appendices. This report explains each stage of the performance work through what the service recorded (slot waits, cold legs, refusals, resends, and from J0 the attributed time of each call), in the order the pull requests were measured; sections 12 to 14 cover J2, L and K and Appendix D lists every paired solver case.

**Figures:** each stage keeps one color across all twelve figures (#151 grey, #152 blue, #155 green, #157 rose, #158 gold, J0 navy, J flexible light blue, J CH red, J2 and the embedded engine teal, L purple, the remote solver pool orange). Latency axes show the 5.5 s bound where it applies.

**Wording:** every latency is the caller's, waits included, and the text says so. Comparisons are between fresh-seed runs only; the two halves of the series (one machine with desks alone, then the VM shape with daily planners) are never read as one curve. Interpretation beyond the recorded fields (for example why a replica's CPU went where it did) is labeled as such.
