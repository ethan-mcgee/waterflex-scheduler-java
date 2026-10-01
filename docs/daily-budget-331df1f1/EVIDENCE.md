# Daily budget run 331df1f1 evidence and methods

## Source and scope

The immutable input is `experiments/runs/20261001T065847Z-daily-budget-331df1f1/`. Its manifest records revision `bfa0cfec45ce98c770796d8aecdc35c9692b8357`, canonical configuration hash `07523a50de3fbee490849e8a20e247e237dcfa72dd08e7269992506e229310e4`, and canonical case list hash `46ee6eb7f4e9ef12a4f4c40ce2c3b7633c05648d37f71769b743d2b7696ca08b`. Canonical hashes use `infra/experiment_config.py`, rather than raw file byte hashes. The report source verifies them and each of the 1,280 raw file byte hashes listed in the retained analysis provenance.

The configuration specifies four solvers, fleets of 5, 10, 20, and 50 technicians, clustered and dispersed workloads, ten search seeds, budgets of 60, 90, 120, and 240 seconds, and ten parallel cases. This is 4 x 4 x 2 x 10 x 4 = 1,280 cases. Eight fixed synthetic fixture fingerprints are present. Search seeds vary the solver path on fixed geography, not the territory sample. Routing identity is `deterministic-directed-fixtures-v1`, with no real road evidence. The archive's JVM flags include `-XX:ActiveProcessorCount=2` and `-XX:+UseSerialGC`; ten simultaneous cases may contend for shared resources.

The retained analysis directory is `analysis/20261001T113647Z-4d5d82e8/`. Its HTML chart captions contain a stale five seed statement. This report recalculates all six figures for ten seeds; no archived chart or stale caption is carried into the Word file.

## Pairing and fields

A pair holds fixture fingerprint, fleet, workload, seed, and budget fixed, then compares an alternative with Tabu. There are 320 matched cases per alternative. Positive saving is `Tabu accepted cost - alternative accepted cost`, in cents. Wins, ties, and losses use this sign. Group means are arithmetic averages of paired differences. No result is substituted for a missing value.

`reference.costCents` is the low cost result of the reference phase. `after.costCents` is the accepted schedule cost after the fairness phase. Fairness is the accepted schedule's capacity weighted utilization variance, where lower means more even utilization. Move rate is recorded phase move evaluations divided by that phase's solver milliseconds. Elapsed time is the whole case wall clock measurement. These measures answer different questions and should not be interchanged.

## Validation results

`report_data.py` checks the canonical manifest hashes; the complete configuration cross product; unique case identifiers; all 1,280 raw provenance SHA-256 hashes; one result per raw file; fixture, accepted cost, reference cost, and fairness agreement with the analysis case CSV; pairing status; and presence of the reported measurements. It found 1,280 paired cases, eight fixtures, zero recorded violations, and no failed or missing case results. All 1,280 reference and fairness phases ended at their time limits. The acceptance reasons are 794 fairness improvements and 486 cost reductions. The analysis has no own 15 second improvement values, so the report does not use them.

The mean accepted modeled cost is $1,848.54 for Tabu, $1,842.39 for K opt, $1,842.85 for late acceptance, and $1,842.13 for sublist. Mean paired savings against Tabu are $6.15, $5.69, and $6.41 respectively. Cost wins, ties, and losses are 257/11/52, 252/8/60, and 261/0/59. Case savings range from a $96.91 loss to a $51.14 gain for K opt, a $100.17 loss to a $45.58 gain for late acceptance, and a $97.75 loss to a $49.05 gain for sublist. These are modeled cents, not realized cash flow.

The 50 technician dispersed group has 40 losses for each alternative. Mean paired savings are negative $52.10, negative $46.85, and negative $47.40. Tabu's mean accepted cost is $4,476.81 in this group. Mean accepted fairness variance is 0.02246 for Tabu, 0.01317 for K opt, 0.01774 for late acceptance, and 0.01349 for sublist. Thus the cost loss can coincide with a fairness improvement. Overall mean fairness variance is 0.01218 for Tabu, 0.02069 for K opt, 0.02460 for late acceptance, and 0.02207 for sublist. These pooled means are descriptive because they mix fleet sizes.

## Rebuild and interpretation

Use Python 3.10 or newer and install `requirements.txt` in this directory. Calibri on Windows or Carlito on Linux is required for the figures. From the repository root:

```text
python -m pip install -r docs/daily-budget-331df1f1/requirements.txt
python docs/daily-budget-331df1f1/report_data.py
python docs/daily-budget-331df1f1/build_report.py
python docs/daily-budget-331df1f1/verify_report.py
```

Run checks without Python's `-O` flag. The builder recalculates the report tables and figures from validated case rows and raw results. It writes under `docs/daily-budget-331df1f1/` and leaves the run archive unchanged. Repeated builds are byte-identical in the same Python, plotting and font environment; cross-platform font rendering can change image bytes and pagination.

The expanded report follows the user-selected `docs/WaterFlex_Scheduler_Algorithm_Comparison_Explained.docx`, SHA-256 `5366f3017d6f18c15de295ba5fb9c1a014b6222911040f1c5e044845e57f832d`. It copies that document's styles, numbering, theme, font table, footer and page geometry. Black headings, italic figure captions, pale blue table headers, numbered explanatory sections, measured examples and full fleet appendices carry its explanation style into this run. Historical results from the reference document are never pooled with the daily archive. `artifact.md` records the design contract.

## Figures and calculation checks

`generated/chart_data.json` retains the numerical inputs of every plotted series. `charts.py` produces six PNG figures, embedded in the editable Word report:

| Figure | Measure and denominator |
| --- | --- |
| Cost groups | Mean paired accepted cost saving, 40 pairs per fleet/workload/alternative |
| Budgets | Mean saving within each workload and budget, 40 pairs per point |
| Variation | Ten seed means over 32 pairs each, plus all 320 case savings per alternative |
| Measured example | Accepted utilization for five technicians, clustered, seed 17, 60 seconds |
| Fairness | Mean paired cost saving and variance reduction, 40 pairs per group |
| Throughput | Mean of per-case phase move rates, 80 cases per solver/fleet point |

Additional raw-data checks reconcile positive elapsed and solve times, both phase move rates, the 2% integer cost ceiling, zero recorded overtime, workload cardinality and unique technician IDs, positive capacity, nonnegative paid minutes and diagnostics, and identical starting metrics and appointment counts across each matched comparison. Accepted weighted variance is recomputed from raw workloads. No missing field is replaced by a fabricated default.

`verify_report.py` independently selects raw groups and checks every numerical result table, all six chart datasets, plotted denominators, the measured example, embedded image hashes, template parts, section geometry, figure captions, table structure and the no-em-dash requirement. Means use unrounded case values; monetary inputs remain cents until unit conversion, and display uses Python's two-decimal formatting. A render review remains necessary to detect layout defects. The final report was rendered with LibreOffice using the packaged document renderer and all 16 pages were inspected. It contains six figures and 24 tables.

The fixture study supports comparative follow-up on this synthetic run. It does not measure customer territory distributions, actual payroll savings, production routing, request admission, or production capacity. A default solver change needs those checks and a focused explanation of the 50 technician dispersed results.
