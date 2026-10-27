# Report design contract

Follows `../scheduler-explained-2026-10-14/artifact.md` in form and changes the voice for its reader, the President of WaterFlex: semi-technical, strong in finance and economics, learns best from charts with detailed explanations. The formatting authority is `../WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx` (SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`).
Its styles, numbering, theme, fonts and footer are preserved byte for byte; the body is replaced.

**Level (owner, 2026-10-22):** technical, woven through the body rather than set apart in an appendix: the score model and formulas, the algorithms and their settings, the statistics behind each decision (bootstrap intervals over whole days, sign tests, pairing, noise floors, latency percentiles), and the system's admission, deadline and CPU budget.
Still no pull request numbers, commit hashes, class names, configuration keys or JVM flags.

**Page and type:** one portrait Letter section, Calibri, black headings, pale blue DCE6EF table headers, alternating FFFFFF and F4F6F8 rows. About 40 pages. Section numbers are assigned by the builder in document order.

**Every chart has two captions** under its bold title: "How to read this chart" (axes, units, what a mark stands for) and "What it means for WaterFlex" (the business consequence). Formulas and settings sit in shaded "How it works" boxes that can be skipped. Tables carry the daily methods' settings, the latency profile, the illustrative year and the VM's limits.

**Narrative:** a summary with a headline panel, then seven parts: what the scheduler is in business terms (I), how the evidence is produced (II), the booking methods tested (III), the daily-plan methods tested (IV), the performance work and why speed is money (V), capacity and the cost to run (VI), and what comes next as decisions (VII).
Appendix A is a plain-language glossary and Appendix B the study index. Self-contained: the reader does not need the October 14 report. No pull request numbers, class names or JVM flags.

**Framing for an economist:** paired studies as controlled experiments, promotion bars as decision rules written in advance, follow-ups on new days as out-of-sample validation, the 95th percentile wait as tail risk, trade-offs drawn as frontiers (served against wait, cost against polish time, saving against seconds, throughput against cores).

**Money:** modeled operating cost only, said so at the start and wherever it matters. Savings from different studies are never added together, and speed ratios from different studies are never multiplied. One illustrative-year box scales one study's measured per-day saving by stated working-day assumptions, shows the formula and is labeled as no forecast.

**Rejected work is shown,** in grey beside what shipped, each with its reason: the larger booking searches, the late acceptance family, fairness formula c, the whole-number score (S) and the route-walk cascade (CR11). The W change, which shipped on the owner's decision under its bar, is labeled as a judgment call.

**Figures:** 32, all HTML with inline SVG in the diagram-design grammar under the October 14 report's skin (white paper, ink #1F2A33, steel blue service nodes, grey stores, one green accent #287A4C for the choice made), 980-wide canvases placed at 6.85 inches, or 5.6 inches for charts with open space so two fit on a page.
The technical figures are the score ladder, the booking deadline, admission, the distribution of paired savings, convergence, and the forest plot of intervals in and out of sample. Five are reused unchanged from the October 14 report's figure library (two_phase, booking_methods, ch_mechanism, wtl, engine); the rest are drawn by `briefing_figures.py`. Schematic figures say they carry no data.

**Wording:** no em or en dash anywhere. Multithreaded solving is described as planned work with its gate.
