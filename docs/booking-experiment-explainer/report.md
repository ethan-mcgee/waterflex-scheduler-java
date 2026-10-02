# WaterFlex Booking Experiment Results Explained
An accessible guide to route efficiency booking outcomes and response time

September 30 2026

Bounded search is the stronger candidate when the priority is reducing paid route time and modeled operating cost. In all 60 sequential comparisons, both methods booked the same ten requests. Bounded search used an average of 47.83 fewer paid minutes and $24.42 less modeled cost per case. It never increased either measure in these matched cases. Those are local modeled results, not a prediction of payroll savings. [1]

The benefit depends on the workload. Clustered cases saved 93.75 paid minutes and $46.88 per case on average. Dispersed cases saved 49.65 minutes and $26.30. Sparse cases saved only 0.10 minute and $0.08. Most measured time savings were reductions in configured travel buffers, with much smaller reductions in road driving. [1, 3]

There is a substantial response-time tradeoff. With one request at a time, the pooled search p95 rose from 0.68 seconds for insertion to 3.59 seconds for bounded search. At ten simultaneous requests, it rose from 4.01 to 26.30 seconds. A p95 of about 26.30 seconds means roughly 95% of recorded search observations finished within that time; it does not mean that every customer waited that long. [1]

[[summary]]

Prefer bounded search for a controlled, sequential booking workflow where these delays are acceptable. Retain insertion as the practical lower-latency option for interactive concurrent use until the incomplete-search behavior is addressed and comparable served-customer evidence exists. This is a recommendation for evaluation and review, not a request to change a production setting.

Read the teaching example first if the methods are new to you. The middle sections explain the charts and units. The final appendix lists all 36 fleet, workload and concurrency groups, including cases that cannot support a route-efficiency comparison.

[[page]]
## What insertion and bounded search do

Insertion starts with the existing schedule and tests places to add the new visit. It can choose a technician and feasible timing, but it preserves the relative order and assignment of existing work for that insertion candidate. It is like finding room for one more appointment in a calendar that is already arranged. [3]

Bounded search also considers small rearrangements around the affected work. Moving an existing visit or changing its order can create a less expensive place for the new booking. The search is bounded because it has limits on the neighborhood and time it explores. Completing that prescribed neighborhood is not proof that it found the best possible schedule. [3]

### A teaching example with Alex and Blair

The following numbers and people are illustrative. They are not observations from the archive. Alex and Blair already have service visits. A new 30-minute visit can fit on either route while keeping the same customer promises and avoiding overtime.

[[teaching]]

Insertion adds the visit to Alex's route. Bounded search finds a permitted rearrangement that groups nearby work, so the fleet needs 20 fewer paid minutes. With no overtime and the same mileage, the modeled labor saving is 20 minutes divided by 60, multiplied by $30 per hour, or $10. The total is across both technicians; choosing only Alex's row would miss the effect on Blair.

The arithmetic does not establish that bounded search will always win. A rearrangement can fail a qualification, availability or promised-window check. A limited search can also finish without improving insertion. The experiment asks how often useful improvements occurred under its particular fixtures, and what happened to booking outcomes and response times.

Both benchmark modes used managed reservations. The recorded settings enabled the booking framework and then selected INSERTION or BOUNDED as the search variant. This comparison is not a test of the older legacy booking path, and neither method here is the daily Tabu optimizer. [1, 3]

[[page]]
## What the experiment actually tested

The booking archive contains 360 expected cases, 360 raw result files and 360 inputs in the saved analysis. Every selected file matched its recorded SHA-256 hash. There were no additional raw attempts outside that selected set. Each case recorded ten request attempts, giving 3,600 observations in total and 1,800 per method. [1, 2]

[[setup]]

A scenario group fixes the fleet, workload and concurrency. There are 4 times 3 times 3, or 36 groups. Each group contains two methods and five seeds, giving ten cases and 100 requests. Within each method, the group therefore has 50 requests. A case is a batch of ten requests, not an entire production day.

Clustered fixtures place technician homes and new requests at two alternating locations. Dispersed fixtures draw new requests from a small fixed pool of Omaha-area points. In both, roughly half the technicians begin with three visits per day and the others with one. Sparse fixtures begin with no existing visits. All new requests require 30 minutes of service. These controlled fixtures are not a representative sample of customer addresses. [3]

The five seeds are 17, 23, 41, 59 and 83. They vary seeded fixture generation where the workload uses randomness. The clustered location pattern is deterministic, so five seed labels do not imply five independent geographies. They still capture variation between local runs.

Warm cache means the harness warmed the scheduler's routing cache before timing. The road-provider cache was not reset between cases. Cache order, local hardware and background activity can influence timings. The frozen calendar used ten weekdays from October 1 through October 14 2026, with the same recorded routing identity across the comparison. These are advance-booking observations, not the separate daily experiment. [1, 2]

[[page]]
## What the measurements mean

Paid route time is the time counted inside paid working segments, including service and modeled travel and waiting. It includes the return legs within those segments. It is not the sum of customer service durations, nor the full scheduled shift. Road driving is only one part of paid time. [3]

[[units]]

The audit totals cover all technicians and all audited dates, including existing appointments and the new confirmed bookings. An after value is the final whole-schedule total. An after-minus-before value is the net change during the ten-request case. It is not necessarily the isolated cost of the new visits, because existing work may be rearranged.

For a matched pair, savings equal insertion after minus bounded-search after. Positive savings favor bounded search. With the same starting audit and the same served request identities, subtracting the two net changes produces the same difference. Comparisons at concurrency 5 and 10 fail the served-identity condition and are excluded, even when their booking counts match.

The recorded cost formula is regular paid minutes divided by 60 times $30, plus overtime minutes divided by 60 times $45, plus road miles times $0.67. Regular paid minutes equal total paid minutes minus overtime. The evaluator rounds each audited day's total to cents, then the report sums the days. Mileage is not itemized in these saved audits, so this report preserves recorded cost rather than inventing miles. [1, 3]

Configured travel adds 20% of road seconds plus five minutes per included leg, followed by rounding to whole minutes. Consequently, fewer modeled legs can reduce paid time even when road seconds do not change. The fixture contains colocated work; a buffer reduction should not be described as an equivalent reduction in time spent driving on a road.

[[page]]
## Where route efficiency improved

The table uses only concurrency 1. Every row averages five matched seeds, with the same ten bookings served by both approaches in each seed. The measures are savings per case across the whole audited schedule. [1]

[[efficiency]]

[[efficiency_chart]]

The 60 matched pairs saved 2,870 paid minutes and $1,465.12 in total across the experimental repetitions. Dividing by 60 gives 47.83 minutes and $24.42 per case. These repetitions are alternative test schedules; adding them is a descriptive calculation, not an estimate of recurring business savings.

[[page]]
## Follow one measured case from start to finish

Consider the dispersed fleet of five technicians with seed 17 and one request at a time. Both methods started with 110 confirmed appointments across the ten audited weekdays and ended with 120. There were no reserved stops left in either final audit. This is an observed example, separate from Alex and Blair's teaching example. [1]

[[worked]]

Insertion added 443 paid minutes to the starting schedule: 4,543 minus 4,100. Bounded search added 408: 4,508 minus 4,100. Their difference is 35 paid minutes saved, even though each method added 300 minutes of new service. The remaining change comes from travel and its buffers and rounding.

The modeled cost increase was $246.42 for insertion and $219.73 for bounded search. The comparable saving is $26.69. Using the final totals alone gives the same result: $2,296.42 minus $2,269.73. Dividing $26.69 by the insertion final total gives about 1.16%; dividing by insertion's added cost gives about 10.83%. Both calculations are valid, but they answer different questions and must identify their denominator.

Across all 60 matched pairs, average road driving fell by 1.64 minutes per case, configured buffers by 45.99 minutes, and travel rounding by 0.20 minute. These components sum to the average 47.83 paid-minute saving because service was identical and recorded waiting was zero. Recorded overtime was also zero. [1]

This is why the report recommends bounded search as a modeled-efficiency candidate without promising equivalent on-road or payroll savings. Whether the five-minute-per-leg allowance reflects real operational overhead is a separate calibration question that this experiment does not settle.

Bounded search improved paid time in 36 pairs and tied in 24. It improved cost in 37 pairs and tied in 23. A small mileage-related cost improvement can occur without a change in whole paid minutes. The workload pattern is more useful than one pooled headline: sparse cases offered almost no improvement, while clustered cases consistently did.

[[page]]
## Booking success under simultaneous demand

At concurrency 1, both methods served every request. At concurrency 5 and 10, both had many incomplete searches. Bounded search served slightly more requests overall, but these totals do not establish a reliable production advantage. The individual customers differed in every concurrent matched-condition pair. [1]

[[outcomes]]

[[outcomes_chart]]

Each row above has 600 attempts: four fleets times three workloads times five seeds times ten requests. At concurrency 5, bounded search served nine more requests, a 1.50 percentage-point difference. At concurrency 10, it served six more, a 1.00 percentage-point difference. These are exploratory local differences, not confidence bounds or production forecasts.

Incomplete means the prescribed search did not finish; it does not mean the customer's request was proven impossible. Failed here means a selection conflict or search error. All 15 failures in this archive were selection conflicts; none was a SEARCH_ERROR. There were no unknown completion flags. Served and incomplete are conceptually separate measures and should not be stacked, even though they do not overlap in these observations.

Concurrency 10 did not produce fewer bookings than concurrency 5 in the pooled results. That does not prove that higher load improves reliability. Request grouping and timing changed, and this small experiment is not a monotonic capacity test. All saved cases passed their independent audit and recorded zero promise violations. That validates these final schedules, not all possible future behavior.

[[page]]
## How long the search took

The latency measure is the harness's individual search observation. It includes the search path as observed by that local client; selection was timed separately. It excludes customer reading time and is not a browser's complete booking journey. Never relabel these figures as end-to-end customer booking time. [1, 3]

[[latency]]

[[latency_chart]]

The median, or p50, is the middle-ranked observation. The p95 describes the slower tail: roughly one observation in twenty lies above it. For the 600 observations in a table row, the nearest-rank p50 is sorted observation 300 and the p95 is observation 570. For a single scenario and method with 50 observations, they are observations 25 and 48.

These percentiles were recomputed from individual requests. Averaging five seed-level p95 values would not produce the pooled p95. All 3,600 search times were available after checking for the historical search/selection mixing condition; zero were excluded here. Failed and incomplete outcomes remain in the distribution because they also consume time.

The recorded policy has a five-second booking deadline, yet client observations can exceed five seconds. That deadline is not a measured end-to-end service-level guarantee. The archive alone does not attribute the excess to a specific bottleneck. Long tails are a real user-experience tradeoff, even when bounded search saves paid route time.

[[page]]
## Read an original outcome graph

This is the outcome panel from original figure 001: five technicians, clustered workload, concurrency 1 and warm caches. It is cropped from the preserved original image to make its labels readable, without changing the plotted data. [2]

[[original_001_left]]

The horizontal axis names the method. The vertical axis is the percentage of requests. A circle means served, a square means incomplete, and a triangle means failed. Orange represents bounded search; teal represents insertion. The horizontal offsets separate the three symbols within a method and have no numerical meaning.

Each method has 50 observations from five ten-request cases. Both circles sit at 100%, and both squares and triangles sit at 0%. All five seeds had those same rates, so there is no visible vertical seed range. This graph shows equal booking success; it says nothing by itself about paid route time or modeled cost.

[[page]]
## Read an original cumulative curve

This is the latency panel from the same original figure 001. Its sample is exactly the same five-technician clustered sequential group, not the pooled 600-observation latency table. [2]

[[original_001_right]]

The horizontal axis is search time in milliseconds: 1,000 milliseconds is one second. The vertical axis is the percentage of observations that finished by that time. A step upward occurs when one or more observations finish. The teal dashed insertion curve lies to the left of the orange bounded-search curve, showing faster responses in this group.

To read p50, move horizontally from 50% until you first meet a method's curve, then move down to the time axis. Use 95% for p95. The curve reaches 100% at the slowest recorded observation; that maximum is not the p95. A large horizontal gap between steps means no observations finished in that interval, not that measurements are missing.

[[page]]
## Read variation between seeds

This outcome panel comes from original figure 003, using the same five-technician clustered workload at concurrency 10. [2]

[[original_003_left]]

Bounded search served 35 of 50 requests, or 70%. Insertion served 34 of 50, or 68%. Insertion's circle has a vertical line from 60% to 70% because one seed served six requests and four seeds served seven. That is the observed minimum and maximum, not a confidence interval and not a standard error.

Both incomplete squares sit at 30%. Insertion's failure triangle averages 2%, with a seed range of 0% to 10%, because one of the 50 requests encountered a selection conflict. Bounded search had no failures in this group. These rates describe outcomes. They cannot justify subtracting the two route costs, because the served request identities differ for every seed.

[[page]]
## Read the slower tail under concurrency

This latency panel is the companion to original figure 003. Each curve contains 50 individual search observations pooled across five seeds. [2]

[[original_003_right]]

Compared with the sequential graph, both distributions move right. Insertion finishes its observations within roughly 3.2 seconds, while bounded search extends to roughly 7.8 seconds. These readings are approximate values from this chart; the appendix gives the computed p95 values for this group.

The chart shows why one average response time would be insufficient. Many bounded-search observations finish considerably earlier than its slowest ones, and a tail of slower responses remains. A cumulative curve makes both facts visible. It also explains why the original figure's tail can be shorter than the pooled concurrency-10 p95 of 26.30 seconds: the pooled result includes other fleets and workloads.

[[page]]
## Recommendation and practical limits

Choose bounded search as the preferred efficiency candidate for controlled sequential bookings. That choice follows the user's priority: paid route time and modeled operating cost. In the 60 comparable pairs, both methods served the identical demand, bounded search never worsened paid time or cost, and the larger benefits appeared in clustered and some dispersed fixtures. A latency-only ranking would miss that benefit.

For a concurrent interactive workflow, insertion is the more defensible present option when the measured bounded-search waits are unacceptable. Neither approach demonstrated high booking completion under concurrency. Bounded search's small aggregate booking advantage does not remove its long latency tail, and unmatched customer sets prevent an efficiency verdict for those cases. A deployment decision needs an explicit acceptable search-wait threshold and stronger concurrent evidence.

No scheduler setting is changed by this report. A useful next comparison would hold served customer identities fixed under representative concurrent demand and record request search and selection separately. Its decision criterion should remain paid route time and modeled cost, with booking completion and response-time limits agreed in advance. This report does not start that experiment.

### How the calculations were checked

The report reads the preserved raw JSONL files through the archive's frozen analyzer, whose hash matches the saved analysis provenance. It reconciles expected case IDs, fixture fingerprints, routing identity, dates, calendar reference, seeds, method, fleet, workload, cache and concurrency. Paired before audits and operating rates must match. Served identities are the request indices within the same generated fixture, rather than case-specific database IDs. The existing served-identity exclusions are reproduced: 60 comparable pairs and 120 excluded pairs. [1, 2]

Metrics are summed over every audited day. Paid minutes come from the policy workload entries. A day with an empty workload list contributes zero only when its recorded appointment count, reserved-stop count, cost, waiting, road, buffer and rounding measures all explicitly show an empty schedule. Missing or null entries otherwise remain unavailable. In this archive, all final audit totals are recoverable. The paid total also reconciles to 30 minutes per confirmed appointment plus recorded road, buffer, rounding and waiting time.

Means weight the five seeds equally within each scenario. Pooled headline means weight the 60 comparable pairs equally. Pooled percentiles use every available individual request. Figures show descriptive ranges, not statistical significance. Fixed fixtures, warm caches, local hardware, serial case order and only five seeds limit generalization to a real customer population.

[[page]]
## Glossary and source guide

[[glossary]]

Source 1 is the immutable local booking archive: experiments/runs/20260930T135624Z-booking-comparison-a752ef8e under C:/Projects/waterflex-scheduler-java. The report's evidence.json contains compact extracted observations and a relative path and SHA-256 receipt for every raw input. The original raw files and original analysis remain unchanged.

Source 2 is that archive's analysis/20260930T154451Z-5a2c0070 directory, particularly analysis-provenance.json, cases.csv, summary.json and figures 001 and 003. Preserved copies of those two original figures accompany the authoring materials. Their panels are reproduced above; no original analysis output was overwritten.

Source 3 is measured revision e9c01d132f3b99fa03611ee889b7012e8846e3aa. Relevant files are web/scripts/benchmark-scheduling.ts for fixtures and timing; SchedulingBenchmarkController.java for audits; optimizer/SchedulingPolicy.java and optimizer/RouteEvaluator.java for paid time and cost; and BoundedBookingSearch.java for booking search. Java paths are under scheduler-service/src/main/java/dev/waterflex/scheduler. The archive also retains frozen source files.

The visual reference is docs/scheduler-explainer/reference.docx, WaterFlex Scheduler and Route Optimization Explained. This report reuses its Calibri typography, page geometry, pale blue table headers and accessible worked-example style. It is a new explanation of the September 30 booking archive, not an update to the historical results in that reference.

[[appendix]]
