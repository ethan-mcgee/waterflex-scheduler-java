# Scheduler algorithm comparison | 29 September 2026

Modeled fixture results. Keep current booking selection and daily TABU default pending further tests.

## Booking

| Stage | Variant | Cases | Served | Mean case p95 s | Incomplete |
| --- | --- | ---: | ---: | ---: | ---: |
| original | BOUNDED | 6 | 18/18 | 0.8 | 0 |
| original | INSERTION | 6 | 18/18 | 0.6 | 0 |
| screen | BOUNDED | 6 | 18/18 | 3.4 | 0 |
| screen | EXPANDED | 6 | 18/18 | 20.0 | 0 |
| screen | INSERTION | 6 | 18/18 | 0.7 | 0 |
| screen | RUIN_RECREATE | 6 | 18/18 | 23.4 | 0 |
| screen | SHARED | 6 | 18/18 | 19.5 | 0 |
| held | BOUNDED | 48 | 360/480 | 15.7 | 120 |
| held | INSERTION | 48 | 346/480 | 2.9 | 132 |
| held | SHARED | 48 | 334/480 | 64.1 | 153 |
| stress | BOUNDED | 4 | 21/40 | 6.4 | 9 |
| stress | INSERTION | 4 | 17/40 | 3.6 | 12 |
| browser | INSERTION | 24 | 141/240 | 3.5 | 53 |

Final cost totals contain existing workload and cannot be treated as savings when served customer sets differ.

### Same-customer cost pairs

| Stage | Variant vs INSERTION | Matched / changed cases | Served delta | Mean savings per stream | Exploratory interval |
| --- | --- | ---: | ---: | ---: | --- |
| screen | BOUNDED | 6/0 | 0 | $12.50 | n/a |
| screen | EXPANDED | 6/0 | 0 | $15.00 | n/a |
| screen | RUIN_RECREATE | 6/0 | 0 | $20.00 | n/a |
| screen | SHARED | 6/0 | 0 | $20.00 | n/a |
| held | BOUNDED | 15/33 | 14 | $23.44 | $23.28 to $23.59 |
| held | EXPANDED | 0/0 | 0 | n/a | n/a |
| held | RUIN_RECREATE | 0/0 | 0 | n/a | n/a |
| held | SHARED | 16/32 | -12 | $31.36 | $29.84 to $32.87 |

## Daily solvers

Each configuration has 6 screen and 24 held-out cases. All daily cases reported zero violations. The table gives held-out paired modeled savings against TABU, in dollars per case; positive favors the candidate.

| Configuration | 15 s | 30 s | 60 s |
| --- | ---: | ---: | ---: |
| CURRENT_CAPPED | -1.18 | -4.80 | -4.79 |
| CURRENT_UNCAPPED | +6.38 | +3.68 | +3.99 |
| LATE_ACCEPTANCE_CHANGE | -21.85 | -10.42 | -0.74 |
| LATE_ACCEPTANCE | -13.09 | +0.30 | +5.48 |
| TABU | baseline | baseline | baseline |
| SUBLIST | -15.06 | -6.68 | +6.67 |
| KOPT | -15.36 | -3.35 | +8.38 |
| RUIN_RECREATE | -39.98 | -36.72 | -24.41 |

## How to read the graphs

- Figure 1 plots served requests and mean case p95 search latency at each tested stage. The latency axis is logarithmic. Missing stages are untested, and connecting lines do not describe a continuous trend.
- Figure 1b compares held-out served requests and incomplete searches across the three tested variants. Each variant has 480 attempted requests across 48 cases.
- Figure 2 uses only identical served customer sets within a seeded stream. The 6 screen matched cases collapse to one clustered stream; held-out BOUNDED and SHARED have only 15 and 16 matched cases.
- Figure 3 plots visible-incumbent coverage above mean incremental cost among visible incumbents. The lower panel has a changing denominator and cannot represent all requests.
- Figure 4 shows accepted before-to-after cost change beside paired final-cost savings against TABU on identical held-out datasets and budgets. Figure 5 shows elapsed time; all 240 daily cases reported zero violations.

## Algorithm comparisons

### Booking search

**INSERTION.** This is the policy-only search control: it tries eligible routes and insertion positions without rearranging existing visits. It is the quickest held-out option at 2.9 seconds mean case p95 and served 346 of 480 requests. Its 19.37 dollar visible incremental cost at 60 seconds is above BOUNDED's 16.94 dollars among visible searches. Those visible-cost means condition on visibility, while the matched-case table provides the fairer final cost comparison.

**BOUNDED.** Relocation, swap, and reversal are limited to six routes, depth two, beam eight, and 500 arrangements per window. It served 360 of 480 held-out requests, 14 more than INSERTION, and yielded 23.44 dollars mean savings per matched stream. That result comes with 15.7 seconds mean case p95 and 120 incomplete searches. Only 15 of 48 held-out cases served identical customer sets, so its cost result describes a narrow subset.

**EXPANDED.** Wider route and beam limits and ranked moves add search depth. In the small clustered screen it served all 18 requests and saved 15 dollars per matched stream, compared with 12.50 dollars for BOUNDED. Its screen mean case p95 rose from BOUNDED's 3.4 seconds to 20.0 seconds. No held-out, stress, or browser test was retained, so a larger search budget has no demonstrated dispersed-service benefit here.

**RUIN_RECREATE.** This adds complete reconstruction after removing related visits from EXPANDED's search. It saved 20 dollars per matched clustered stream, a further five dollars over EXPANDED, and served all 18 screen requests. Mean case p95 was 23.4 seconds. The reconstruction hypothesis is promising for cost but lacks dispersed and concurrent coverage; its screen result does not establish the same gain under field-like routing.

**SHARED.** Shared route evaluation reduced screen mean case p95 to 19.5 seconds while matching RUIN_RECREATE's 20 dollar clustered savings. In held-out conditions, equal-customer pairs saved 31.36 dollars per stream, but total service fell to 334 of 480 and 153 searches were incomplete. Mean case p95 reached 64.1 seconds, and the original audit returned HTTP 409. Cost improvement on the matched subset is insufficient evidence for promotion while service and audit behavior remain unresolved.

### Daily solver

**CURRENT_CAPPED.** Relocation and swap stop after the cap, producing about three seconds elapsed at every budget. This is the speed reference when a full solver budget is unavailable. Its paired cost was 1.18, 4.80, and 4.79 dollars worse than TABU at 15, 30, and 60 seconds.

**CURRENT_UNCAPPED.** The same move family without the step cap consumed the full budget and improved paired cost against TABU by 6.38, 3.68, and 3.99 dollars. The capped comparison suggests that additional work can pay on these fixtures, while the two held-out seeds leave its generality open.

**LATE_ACCEPTANCE_CHANGE.** Relocation-only late acceptance improved as time increased, from 21.85 dollars worse than TABU at 15 seconds to 0.74 dollars worse at 60 seconds. Its trajectory shows that this configuration needs time to approach the baseline; the result does not show an advantage at the tested budgets.

**LATE_ACCEPTANCE.** Adding swap to the late-acceptance configuration improved its paired result relative to change-only at every budget. It was 13.09 dollars worse than TABU at 15 seconds, near even at 30, and 5.48 dollars better at 60. This conditional comparison is informative because the acceptor is shared; it still has only two independent held-out seeds.

**TABU.** Relocation and swap with a tabu acceptor are the production reference. Accepted cleanup averaged 55.64 dollars at 15 seconds and 59.26 dollars at 30 and 60, with zero reported violations. Its flat 30-to-60-second mean suggests limited additional benefit on these fixtures, while other configurations may need the longer time to catch up.

**SUBLIST.** Adding sublist movement and reversal to late acceptance yielded a 6.67 dollar paired advantage at 60 seconds after trailing TABU by 15.06 and 6.68 dollars at 15 and 30. This favors a longer-budget follow-up, not a claim that one specific move caused the gain.

**KOPT.** The sublist configuration plus k-opt had the largest 60-second paired advantage in this matrix, 8.38 dollars, after deficits of 15.36 and 3.35 dollars at shorter budgets. It is a candidate for replicated long-budget tests because its advantage appears only after consuming the full 60 seconds.

**RUIN_RECREATE.** Adding list ruin/recreate to the k-opt configuration improved its own accepted cleanup from 15.66 to 34.85 dollars as the budget rose, but it trailed TABU by 39.98, 36.72, and 24.41 dollars. The bigger neighborhood did not repay its search cost at these budgets and fixture sizes.

## Report design decisions

Modeled cost leads because the decision concerns operating efficiency. Served demand, feasibility, and elapsed time are adjacent because a cheaper schedule can serve fewer customers or take longer than the available decision window. Booking savings use identical served customer indices on the same seeded dataset; final inventory cost includes existing work and cannot be compared as savings when customer sets differ.

Stages remain separate because original policy changes multiple behaviors, screen geography repeats, held-out booking roads are dispersed, stress adds concurrent near-capacity work, and browser results cover only INSERTION. Daily paired comparisons use TABU, the retained default, at the same fingerprint and budget. Exploratory intervals resample two independent held-out seeds, so the report leaves algorithm promotion open for replication.


## Pros and cons

### Booking

| Variant | Pros | Cons |
| --- | --- | --- |
| INSERTION | Fast control; held-out 346/480 served with 2.9 s mean case p95; browser 141/240 served. | Higher matched-customer modeled cost than bounded or shared; stress served 17/40. |
| BOUNDED | Held-out 360/480 served and matched-pair savings of $23.44 per stream; 21/40 stress served. | Held-out mean case p95 15.7 s; 120 incomplete searches; changed customer sets in 33/48 comparisons. |
| EXPANDED | Screen matched-pair savings $15 per clustered stream; 18/18 served. | Screen only; 20.0 s mean case p95; no held-out or stress evidence. |
| RUIN_RECREATE | Screen matched-pair savings $20 per clustered stream; 18/18 served. | Screen only; 23.4 s mean case p95; reconstruction cost and no independent held-out evidence. |
| SHARED | Screen tied ruin/recreate at $20 matched-pair savings with lower screen latency; held matched pairs show $31.36 per stream. | Held-out service fell to 334/480, latency reached 64.1 s mean case p95, and original SHARED audit failed. |

### Daily

| Configuration | Pros | Cons |
| --- | --- | --- |
| CURRENT_CAPPED | About 3 s runtime, zero violations. | Slightly worse paired cost than TABU at all held budgets. |
| CURRENT_UNCAPPED | Paired cost better than TABU by $6.38, $3.68, $3.99 at 15/30/60 s. | Consumes full budget; only two held-out seeds. |
| LATE_ACCEPTANCE_CHANGE | Simple relocation-only comparison, zero violations. | Worse paired cost at 15 and 30 s; near TABU at 60 s. |
| LATE_ACCEPTANCE | Improves with time; $5.48 paired advantage at 60 s. | Worse at 15 s and no clear 30 s advantage. |
| TABU | Production baseline; $55.64 to $59.26 mean accepted cleanup; zero violations. | Consumes full budget; alternatives sometimes have lower modeled cost. |
| SUBLIST | $6.67 paired advantage at 60 s. | Worse at 15 and 30 s; combined move effect is not isolated. |
| KOPT | $8.38 paired advantage at 60 s, largest in this held matrix. | Worse at 15 and 30 s; consumes full 60 s. |
| RUIN_RECREATE | Improves accepted cost as budget rises; zero violations. | Worse paired cost than TABU at all budgets, by $24.41 at 60 s. |

## Decision matrix for next tests

| Candidate | Cost signal | Service and feasibility | Speed | Confidence and next test |
| --- | --- | --- | --- | --- |
| Booking BOUNDED | Positive matched cost | 360/480 held served | 15.7 s case p95 | Repeat dispersed concurrent streams and inspect incomplete results |
| Booking SHARED | Positive matched cost | 334/480 held served; original audit failed | 64.1 s case p95 | Investigate audit conflict before promotion |
| Daily CURRENT_UNCAPPED | Positive paired cost at all budgets | 0 violations | Full budget | Repeat on independent geographies |
| Daily KOPT | Strongest 60 s paired signal | 0 violations | 60 s | Confirm on more seeds and operational workloads |
| Daily TABU | Reference | 0 violations | Full budget | Retain baseline until replicated evidence |

## Reproduce

Install `reportlab`, `matplotlib`, and `pymupdf` for Python 3, then run `py -3 infra/build-scheduler-algorithm-comparison.py` from the repository root. Run the same command with `--check` to verify retained file hashes, denominators, paired daily values, and the generated PDF and Markdown outputs.

## Evidence limits and test priorities

- Two held-out seeds make intervals exploratory. Screen clustered geometry repeats.
- Original SHARED audit failed with HTTP 409; the recovery run is separate. Eight original stress audits omitted overflow dates and are excluded from validated stress results.
- Browser tests cover INSERTION only. Original policy comparison bundles multiple policy and lifecycle changes.
- Prioritize further dispersed concurrent BOUNDED booking tests, SHARED audit diagnosis, and broader daily CURRENT_UNCAPPED and 60-second KOPT/TABU pairs.

Sources: [retained summary](evidence/scheduler-field-2026-09-29/summary.json), [manifest](evidence/scheduler-field-2026-09-29/experiment-manifest.json), [validation](evidence/scheduler-field-2026-09-29/validation.json), [full field report](scheduler-field-validation.md).
