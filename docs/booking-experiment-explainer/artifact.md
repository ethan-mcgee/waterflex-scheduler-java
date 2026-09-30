# Reference and authoring contract

Reference: `../scheduler-explainer/reference.docx`, SHA-256 `2d296b4ae319f04ab583a25a23df6e11e845e6d1ba3324f7dcad23d96d53df70`.

This is a new explanatory report using the reference's visual language. All original body content is replaced. Page geometry, source styles, footer page field, numbering and theme are retained. Existing public reference and expanded document remain untouched.

Observed OOXML: one portrait Letter section, 8.5 by 11 inches; top 0.70, bottom 0.65, left/right 0.80 inch margins. Calibri Normal 11 pt, Title 28 pt, Heading 1 21 pt, Heading 2 13 pt, Caption 9 pt. Pale blue table header DCE6EF, alternating FFFFFF/F4F6F8 body, D9D9D9 borders. Footer reads `WaterFlex scheduler |` followed by a page field. New report reuses these components, with expanded multi-column tables and seven explanatory chart panels.

Editable slots: entire document body, core title/subject/date metadata, added chart image relationships, table rows and columns, Word updateFields setting. Heading text is black. Original numbering, theme and footer XML are preserve-only and checked byte-for-byte. Chart panels are cropped from unchanged source figures for legibility, with their source figure and group identified in prose.

Render status: complete. All 18 final pages were rendered with the documents skill's `render_docx.py` and visually inspected for chart legibility, table layout, clipping and pagination. Internal QA output is `artifacts/booking-experiment-explainer/qa-3`. The user authorized building the booking report while the daily experiment continues; the isolated renderer used networking disabled, 0.5 CPU and 1 GB memory limits. PDF and page PNGs remain internal QA artifacts. The final rebuild matched the rendered Word document byte-for-byte.
