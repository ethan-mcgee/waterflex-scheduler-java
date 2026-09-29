<!-- before: 3 How booking finds an appointment -->
# Comparing schedules in the same day

Alex and Blair will help us follow the decisions. Both can perform maintenance visits A and B; only Alex can perform specialist visit C. For the utilization calculation, Alex has 480 regular minutes across morning and afternoon availability, while Blair has 240 in the afternoon. Other technicians may participate in the fleet. Every time, workload and dollar amount in this running example is illustrative, not a measured customer schedule.

## Baseline reference and proposal

The baseline is the day already on the books. It is the comparison point for deciding whether to change anything. The reference is the best overtime and cost combination discovered during the first search phase. It provides a target and a ceiling for the second phase. The proposal is the independently checked arrangement offered for dispatcher review. These roles remain distinct even when two of them happen to contain the same routes. [3, 4]

| Arrangement | Overtime | Fleet cost | Alex and Blair paid minutes |
| --- | --- | --- | --- |
| Existing baseline | 0 min | $1,000 | 240 and 240 |
| Discovered reference | 0 min | $970 | 360 and 120 |
| Fairness proposal | 0 min | $985 | 320 and 160 |
| Outside the ceiling | 0 min | $995 | 320 and 160 |
| Cheaper overtime option | 15 min | $960 | Not admissible |

The amounts summarize the whole illustrative fleet; the last column isolates our two technicians. They are not labor bills calculated from that column. With a $970 reference, the allowance produces a $989.40 ceiling. The $985 proposal fits; the $995 arrangement does not. The $960 option adds overtime to a zero-overtime baseline, so its attractive price never makes it an ordinary accepted proposal.

## Calculate the balance before comparing it

In the baseline, Alex uses 240 of 480 minutes, or 50 percent. Blair uses 240 of 240, or 100 percent. Their combined paid workload is 480 out of 720 available minutes, about 66.67 percent. In the reference, Alex uses 75 percent and Blair 50 percent. That is less spread out, although it still loads them differently. In the proposal, 320 out of 480 and 160 out of 240 both give 66.67 percent.

For these two participants alone, the capacity-weighted variance is about 0.05556 for the baseline, 0.01389 for the reference, and zero for the proposal. Appendix A shows the formula. A real fleet calculation includes every eligible positive-capacity participant, including idle technicians; these two-person figures are not asserted to be the whole fleet's variance. [4]

## The baseline still has a say

Now change just the baseline cost to $980 and suppose it is already more balanced than a candidate costing $975. Both sit below $989.40. At unchanged overtime, policy rejects the candidate's fairness regression even though it saves $5. This protects a fairer current day within the allowed cost band. If the baseline lies outside the band, reaching the band can permit a different balance. An overtime reduction has its own earlier acceptance branch. [4]

The ceiling is permission to consider a candidate, not a command to replace the baseline. A tie with no policy improvement also leaves the day alone. Qualification and promise checks happen before these comparisons: assigning C to Blair cannot be redeemed by low cost or perfect utilization.

<!-- before: 4 How Tabu Search improves a day -->
# Following the specialist booking request

The specialist customer asks for C in the 10:00 to 12:00 arrival window. Alex initially carries A and B; Blair can take ordinary maintenance. This trace describes the managed bounded-search path when both rollout flags are enabled. With the repository defaults, that staged rearrangement path is inactive. [1, 5]

## From a position to a complete candidate

First, insertion tests C before A, between A and B, and after B on Alex's route. Blair is excluded for C because Blair lacks the required skill. Suppose the first position makes A late, the middle position prevents B fitting its window, and the last position leaves no valid specialist arrival and return. These are failures of those placements, not proof that the day is full.

A relocation then moves B to Blair and repeats insertion for C. The new arrangement must still serve A and B exactly once on their promised date, within their existing windows. A swap is useful if Blair cannot simply accept B: an ordinary Blair visit might move to Alex while B moves the other way. Reversal changes order within a route; it does not make Blair qualified for C.

| Candidate check | Example reason for discarding it |
| --- | --- |
| Qualification and coverage | C assigned to Blair, or B accidentally duplicated |
| Optimistic timing bounds | Even the earliest possible arrival misses A's window |
| Complete route evaluation | Service fits, but buffered return crosses an absence |
| Search deduplication | The same arrangement was already examined |
| Beam retention | A valid arrangement ranks below those retained for expansion |

The last row is a resource decision, not a declaration of infeasibility. Bounds reuse unchanged route prefixes and suffixes to screen candidates cheaply. Passing the screen requires detailed timing and workload evaluation. A missing road response is an explicit unavailable-data outcome; it is never a zero-minute journey. [5, 6, 9]

## Width and depth describe different limits

Think of depth as consecutive rearrangement rounds before trying insertion again. Depth one can move B to Blair. Depth two can explore an additional rearrangement from a retained first-round arrangement. It is not two customers, two dates or two travel legs. Beam width is the number of promising first-round arrangements retained for expansion into the next round.

For a small teaching example, use width two. If twelve distinct first-round arrangements are generated and evaluated, only the two highest-ranked ones expand into round two. The others may have had insertion tested already, but they do not receive another rearrangement round. The implementation ranks retained arrangements by overtime, cost, fairness and a stable signature. Actual configured limits and route-shortlisting details are in Appendix A. [5]

## Finish the required work before publishing

The valid relocation can support a regular-hours offer for C. Publication must validate the complete obligations and reserve compatible capacity, then expose the customer window. It does not expose a guarantee about a particular technician's route order. Selecting the offer later needs current arrangement checks and atomic confirmation.

If the deadline arrives while required exploration is unfinished, regular capacity may remain undiscovered. Even two found windows and high confirmed utilization cannot authorize new overtime without the prescribed completed scarcity pass. Conversely, stopping optional quality refinement after the required pass has completed does not erase already valid regular offers. Neither result proves a global optimum or exhaustive fleet search. [1, 5]

<!-- before: 5 Moves and alternative algorithms -->
# Following several Tabu Search steps

Later, C is confirmed and Alex remains its only qualified technician. This illustrative reference-phase path uses valid schedules with zero overtime. Maintenance visits D, E and F provide other entities to move. The costs are not a recorded solver trace.

| Step | Small route change | Current fleet cost | Best retained cost |
| --- | --- | --- | --- |
| Start | Existing routes including C on Alex | $1,000 | $1,000 |
| 1 | Relocate B to Blair at a valid position | $980 | $980 |
| 2 | Reposition D in another route | $990 | $980 |
| 3 | Swap compatible visits E and F | $970 | $970 |
| Stop | Budget expires while exploring | $970 | $970 |

## Several candidate moves compete for one step

At step one, imagine three candidates. Moving C to Blair breaks qualification. A different placement of B yields zero overtime at $985. The shown placement yields zero overtime at $980. Among these feasible, non-tabu options, the cheaper candidate scores better in the reference phase. Hard feasibility, then overtime, then cost govern the score; cost is not a single number that absorbs every kind of violation. [7b]

The selector produces candidates, scoring measures them, the acceptor filters them, and the forager picks the next step. Search evaluates a bounded sample. Infeasible working candidates can receive hard penalties; this table shows a feasible path so its dollar comparisons are meaningful. Operational validation rejects hard violations. [7, 9, 12]

## A worse step can open a better neighborhood

At step two, assume the sampled eligible alternatives offer no immediate improvement. Repositioning D increases cost by $10 relative to the current route, but it changes which swaps become useful. Step three then discovers the $970 arrangement. A method requiring improvement at every step could decline step two and miss this particular path. Tabu permits deterioration; it does not promise that every deterioration will pay off.

The best retained cost stays $980 through step two. If time expires there, the solver can return that earlier best, rather than the current $990 position. After step three the best becomes $970. Keeping the best solution is separate from accepting a move as the next search position. [7, 12]

## Memory steers the path rather than freezing the day

The recently moved planning entities enter short-term tabu memory. An immediate attempted reversal of B's relocation is therefore normally restricted, helping avoid an out-and-back loop. Entity memory follows involved planning objects, not a permanent ban on a road connection or a customer. D and then E and F illustrate steps using other entities. Memory eventually changes as search continues; this table does not predict exactly when B will become eligible again.

At the phase boundary, $970 becomes the discovered cost reference with zero overtime. The fairness phase then searches under the $989.40 ceiling and that overtime target. Its balance objective can prefer the $985 proposal discussed earlier. That proposal still needs independent evaluation and baseline acceptance. A better reference-phase cost does not by itself decide which schedule the dispatcher will see. [3, 4]

<!-- before: 7 How the road engine works -->
# Timing the morning and afternoon work

Use Alex's 08:00 to 12:00 and 13:00 to 17:00 working blocks, separated by an approved absence. A is 45 minutes of maintenance with a 09:00 to 10:00 arrival window. C is 60 minutes with a 10:00 to 12:00 window. B, now assigned to Blair, and Alex's additional maintenance D each take 45 minutes with 14:00 to 16:00 windows. Blair works 13:00 to 17:00. All required start, inter-visit and return legs take ten raw minutes in this example.

## Build every segment including its return

The configured travel allowance makes each of those road legs 17 planned minutes. Alex departs at 08:43, reaches A at 09:00, finishes at 09:45, reaches C at 10:02, finishes at 11:02 and returns at 11:19. There is no paid waiting. The segment uses 156 paid minutes: 105 service minutes plus three planned legs of 17 minutes. It returns before the absence starts.

| Technician segment | Departure | Service arrivals | Return | Paid minutes |
| --- | --- | --- | --- | --- |
| Alex morning A then C | 08:43 | A 09:00; C 10:02 | 11:19 | 156 |
| Alex afternoon D | 13:43 | D 14:00 | 15:02 | 79 |
| Blair afternoon B | 13:43 | B 14:00 | 15:02 | 79 |

Each afternoon segment comprises 45 service minutes and two 17-minute legs. Alex's total is 235 paid minutes across two segments. Both depart from their configured start and return to their configured endpoint separately. The gap is not continuous paid route time. These visits explain part of the day, rather than replacing the complete utilization example workloads. [8, 9]

## Waiting is constrained by earlier promises

Keep A's window, but consider a separate timing variant where C's window opens at 11:00. The earliest morning placement reaches C at 10:02 and waits 58 minutes. Delaying departure by 58 minutes would put A at 09:58 and remove that waiting, but C's service and return still finish at 12:17. That whole morning segment is infeasible because it crosses the 12:00 block end. Delaying departure cannot repair its return requirement.

This is why inspecting just the specialist arrival is insufficient. The original 10:00 opening fits; the 11:00 variant requires another feasible placement or is rejected. The exclusive window end also means that A at exactly 10:00 fails even though a casual reading might treat it as on time.

## Reuse partial schedules without mixing different states

Dynamic programming carries completed prefixes across successive working blocks. It can try A alone in the morning, A and C together, or leaving the block unused, then extend those choices in the afternoon. It keeps visit order fixed. A state with only A completed cannot dominate one with both A and C completed because their remaining work differs. [8]

For the same completed prefix after the same block, imagine two partial timings with one departure each. P uses 150 paid minutes, zero overtime, no waiting and $90 modeled cost, departing at 08:40. Q uses 160 paid minutes, zero overtime, ten waiting minutes and $95, departing at 08:35. P cannot dominate Q under the implemented rule because P's departure is later. If P instead departed at 08:30 with those same illustrative metrics, it would be no worse in every compared dimension and Q could be removed. Departure count and ordering are part of the rule, not merely cost. [8]

<!-- before: 9 A worked example -->
# Following a proposal through preview and apply

The $985 fairness proposal is a search result in memory. To become a useful dispatcher action, it must retain the exact customer obligations and pass several checks against the facts that produced it. Alex's qualification for C, Blair's eligibility for B, the arrival windows and every return leg are all part of those facts. [3, 9]

## First validate the whole arrangement

The independent evaluator rebuilds timing and workload from the proposed assignment and order. It checks required visits, qualifications, windows, absences, endpoints and technician limits. If an optimistic score missed a return leg, this stage must fail the proposal rather than preserve its apparent savings. Policy then compares the checked metrics against the baseline and saved reference target.

Preview records the input versions, baseline, proposal, policy reference, routing identity and actual solver configuration. A dispatcher can review changes to assignment, route order, timing, overtime and cost before choosing Apply. The optimizer's search budget ending does not itself write those changes to appointments.

| Moment | Relevant facts | Result |
| --- | --- | --- |
| Search snapshot | A, B and C have known windows and versions | Search can compare fixed candidates |
| Independent validation | Every obligation and route total is recomputed | Invalid candidate is rejected |
| Preview | Checked $985 plan and its provenance are saved | Dispatcher can inspect the proposal |
| Concurrent edit | B's promise or Blair's availability changes | Saved facts may now be stale |
| Apply | Facts are reread and checked under locks | Commit only if still valid and admissible |

## A stale preview is a concrete conflict

Suppose Preview was created with Blair available until 17:00. Before Apply, approved time off removes 14:00 to 16:00. The earlier placement of B at 14:00 no longer fits. Even an unchanged route drawing and an attractive $985 total cannot authorize the old proposal. Apply must reject stale or incompatible facts and require a fresh preview. A customer window change can have the same effect. Version checks protect the dependency, while validation checks the resulting meaning. [3]

## Reservations protect capacity shared by customers

Before C was confirmed, imagine another specialist customer also seeking Alex's same remaining capacity. Two searches could each find room when evaluated independently, yet their offers might not fit together. The managed reservation arrangement validates confirmed work and active conservative hold placeholders together so later publication cannot silently promise that capacity twice. This is an enabled-path example, not a claim that repository defaults issue managed holds. [1]

One customer's alternative windows are handled as sibling offers in the managed lifecycle. Confirmation locks and checks the arrangement, replaces the chosen placeholder with the appointment, releases sibling alternatives and validates remaining obligations in the same transaction. Repeating a successful confirmation returns the existing appointment. Expiry, cancellation and release use lifecycle handling so an abandoned request cannot later publish a usable reservation.

Active holds consume protected capacity, but they are not confirmed demand for the overtime scarcity calculation. Ordinary daily optimization is blocked by relevant holds. Otherwise a route improvement might take space promised to a customer who has not finished confirming. These safeguards explain why a busy or conflicted response can be correct even when a single isolated route appears to have room. [1, 3]

<!-- before: Appendix A Technical reference -->
# Reading measurements without losing the demand

The evidence answers several different questions. Constructed cases show that a specific relocation, swap or timing choice can reveal capacity without breaking promises. Repeated solver fixtures compare discovered daily schedule quality. Booking measurements show response time and served requests under particular load and conflict conditions. None alone establishes the experience of a production deployment. [2, 11]

## Put quality and latency beside completion

The final booking fixture served 2,331 of 3,780 requests, about 61.67 percent. The remaining 1,449 were retryable outcomes: 815 schedule conflicts, 587 busy responses and 47 incomplete searches. Its pooled p95 was 3,975.5 milliseconds. A low pooled p95 with this completion fraction describes quick responses under that fixture; it does not mean almost every customer completed a booking in four seconds.

| Simultaneous requests | Served requests | Completion fraction |
| --- | --- | --- |
| 1 | 1,260 of 1,260 | 100 percent |
| 5 | 670 of 1,260 | 53.17 percent |
| 10 | 401 of 1,260 | 31.83 percent |

These cases direct concurrent requests at shared schedules. More simultaneous requests create reservation conflicts and admission pressure, rather than simply multiplying independent work. The counts show why a latency comparison needs a served-demand denominator. They do not justify increasing worker admission or predict hosted throughput. Retried preparations are also not new confirmed jobs. [11]

## Compare like demand for cost and overtime

The sequential original and final cases both serve all 1,260 requests, so that subset gives a clearer cost comparison. Incremental modeled cost falls by $4,167.94, from $23,200.94 to $19,033.00, about 17.96 percent of the original modeled total. Added overtime remains 1,800 minutes. Mean utilization variance rises from 0.00725836 to 0.01205339; on that measure the final booking arrangements are less balanced.

Lower cost is therefore supported for that matched subset, while booking-wide overtime or fairness improvement is not. Negative incremental costs in some component experiments can reflect removing paid waiting from existing routes. They are not negative customer bills or recovered payroll. If an experiment serves fewer jobs, its lower total overtime may merely reflect the missing demand. [11]

## Daily solver evidence addresses another decision

At seed 17, Tabu's aggregate reference cost is $260.77 below the uncapped comparison across 21 datasets, about 0.35 percent. That number concerns reference-phase totals. Accepted-plan cost and variance are separately recorded because the fairness phase can trade a small cost allowance for balance. Each dataset has its own reference and ceiling; an aggregate saving does not let one day exceed its own ceiling. [2]

Across retained seeds 17, 23 and 41, Tabu has lower aggregate reference cost and lower mean accepted variance than the two retained alternatives. All three end with zero overtime, which leaves no measured comparative overtime advantage to claim. The per-dataset variance is capacity weighted; the reported mean across datasets is unweighted. Larger fleets do not automatically receive larger weights in that reported mean.

## Match the evidence to its environment

The solver comparison uses synthetic directed legs; actual Omaha browser measurements are a separate artifact at their own scheduler and portal revisions. Shared workstation activity and different component pool sizes limit small timing comparisons. Zero audited violations is strong evidence for the tested runs, not proof that all future inputs will be safe. Runtime rollout flags, deployment revision, map identity and live load still need separate verification. The source appendix links the archived reports so a reader can inspect that provenance rather than treat these figures as fresh benchmarks of this document's checkout. [2, 11]
