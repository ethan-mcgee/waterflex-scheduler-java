# Report design contract

Follows `../daily-tabu-variants-5d15129b/artifact.md`. The formatting authority is `../WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx` (SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`). Its styles.xml, stylesWithEffects.xml, numbering.xml, theme, fontTable.xml and footer parts are preserved byte for byte; the body is replaced.

**Page and type:** one portrait Letter section, Calibri, black headings, pale blue DCE6EF table headers, and alternating FFFFFF and F4F6F8 rows.

**Tables:**
- Header rows repeat, and no table row splits.
- Tables of twelve or fewer rows stay on one page.
- Appendix and long reference tables use 9 pt cell text; body tables use 10 pt.
- Every table that describes the contract or the service is read from its source document when the report is built, and the verifier parses the same sources again on its own.

**Narrative:** the reference's sequence of scope, plain-language mechanisms, worked sequences with explicit reading instructions, implications and complete appendices. This report is descriptive rather than experimental: it explains what the service is, how a host uses it safely, what is underneath, how it was built, and what remains, in the integration guide's order.

**Figures:** five diagrams drawn by the builder (topology, the booking sequence, the daily and queued sequence, the route cache tiers, and the pull request history by phase). The service's components keep one color (scheduler blue 7DAFCB, host DCE6EF, stores C9C9C9, refusals red B22222).

**Wording:** facts about the contract come from the spec; facts about behavior from the design document and the guide; the pull request list is the one typed input and names its source and date. Nothing is claimed about performance beyond pointing to the performance report.
