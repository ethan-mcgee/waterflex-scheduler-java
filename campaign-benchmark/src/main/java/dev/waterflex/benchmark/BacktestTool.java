package dev.waterflex.benchmark;

import static dev.waterflex.benchmark.Protocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Monetary;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.SearchAdmission;
import dev.waterflex.scheduler.optimizer.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Backtest jobs over a sealed {@link DailyDataset}: {@code backtest-score} reads a given plan (a dispatcher's day as
 * planned or as executed, or a proposal) in numbers and never refuses a day that does not hold,
 * {@code backtest-score-days} reads one plan file of many days in one JVM, and
 * {@code backtest-solve} runs the production daily calculation on the dataset's own assignment. Road legs come from
 * the dataset alone, so a day reads the same on every machine that has the file.
 * <p>{@code backtest-score-days} names each day by its local date, so it alone can place a day in its week, and pays
 * its rows by the dealership's {@link PayRule} (owner, 2026-11-09): an unpaid lunch, overtime past 40 paid hours in a
 * week, and weekend work as overtime. A dataset holds instants and no time zone, so the other jobs, which name no
 * date, keep the engine's pay: every minute from depot to depot, with the minutes after the shift end as overtime.
 */
public final class BacktestTool {
    /** The plan shape the importer and the replay write: each route's visit ids in order, plus the unassigned ids. */
    public record Plan(int version, Map<String,List<String>> routes, List<String> unassigned) {
        public Plan {
            if (version != 1) throw new IllegalArgumentException("Backtest plan version");
            Map<String,List<String>> copy = new TreeMap<>();
            routes.forEach((id, visits) -> copy.put(Required.value(id), Required.value(List.copyOf(Required.value(visits)))));
            routes = Required.value(Map.copyOf(copy)); unassigned = Required.value(List.copyOf(unassigned));
        }
        public static Plan of(DayPlan plan) {
            var proposal = SnapshotFileIO.proposal(plan);
            return new Plan(1, proposal.routes(), proposal.unassigned());
        }
    }
    /**
     * The pay rule of {@code backtest-score-days} (owner, 2026-11-09). A technician-day longer than
     * {@code unpaidBreakOverMinutes} from depot to depot has {@code unpaidBreakMinutes} of unpaid lunch. A technician's
     * paid weekday minutes past {@code weeklyRegularMinutes} in one Monday-to-Friday week are overtime, on the day they
     * fall. With {@code weekendOvertime} every paid minute of a Saturday or a Sunday is overtime and stays out of the
     * week's count. The rows file carries the rule it was paid under, so a reader can refuse rows paid under another.
     */
    public record PayRule(long weeklyRegularMinutes, long unpaidBreakMinutes, long unpaidBreakOverMinutes, boolean weekendOvertime) { }
    public static final PayRule PAY_RULE = new PayRule(2400, 60, 360, true);

    /** The rates one day is priced at: the dataset's own, by the engine's fleet formula. */
    public record Rates(BigDecimal regularHourly, BigDecimal overtimeHourly, BigDecimal mileagePerMile) {
        public Rates { Required.value(regularHourly); Required.value(overtimeHourly); Required.value(mileagePerMile); }
        public static Rates of(DayPlan plan) { return new Rates(plan.getRegularHourly(), plan.getOvertimeHourly(), plan.getMileagePerMile()); }
        long cents(long paid, long overtime, long meters) { return Monetary.cents(paid, overtime, meters, regularHourly, overtimeHourly, mileagePerMile); }
    }

    /**
     * One route's reading; {@code arrivals} are ISO instants by visit id, empty when a road is missing.
     * {@code unpaidMinutes} is zero and {@code paidMinutes} runs from depot to depot until a {@link PayRule} is applied.
     */
    public record RouteRow(String technicianId, boolean holds, int visits, long qualifications, long unavailableRoads,
            long latenessSeconds, long lateVisits, long availabilitySeconds, long capacityMinutes, long paidMinutes,
            long overtimeMinutes, long unpaidMinutes, long driveMinutes, long waitingMinutes, long meters, long bufferSeconds, Map<String,String> arrivals) {
        public RouteRow { Required.value(technicianId); arrivals = Required.value(Map.copyOf(arrivals)); }
        /** This route with its pay replaced; everything else stays the engine's. */
        RouteRow paid(long unpaid, long paid, long overtime) {
            return new RouteRow(technicianId, holds, visits, qualifications, unavailableRoads, latenessSeconds, lateVisits, availabilitySeconds,
                    capacityMinutes, paid, overtime, unpaid, driveMinutes, waitingMinutes, meters, bufferSeconds, arrivals);
        }
    }
    /**
     * One plan's reading. {@code feasible} is the independent evaluator's verdict on the assigned work with every
     * visit assigned; {@code routesHold} is the score's. {@code pastShiftMinutes} rounds the availability violation up
     * to whole minutes. The fairness variance is the policy's own (DECIMAL128) over the routes it would measure, read
     * from the same timing whether or not the plan holds, since {@link SchedulingPolicy#measure} refuses an infeasible day.
     * {@code regularCents}, {@code overtimeCents} and {@code mileageCents} price the fleet's regular minutes, overtime
     * minutes and meters each on its own, rounded half up once each, so their sum may differ from {@code costCents} (the
     * fleet's exact total, rounded once) by a cent or two. As {@link #score} reads it, the pay is the engine's:
     * {@code paidMinutes} from depot to depot, {@code overtimeMinutes} after the shift end, {@code unpaidMinutes} zero.
     * In a {@code backtest-score-days} file the pay is the {@link PayRule}'s instead ({@link #pay}): {@code paidMinutes}
     * leave out {@code unpaidMinutes} of lunch, {@code overtimeMinutes} are the rule's, and the four cost fields are
     * priced on those. The other times, the violations, both verdicts and the fairness reading stay the engine's.
     */
    public record Row(String datasetHash, String planHash, boolean feasible, boolean routesHold, long unassigned, long hardPenalty,
            long costCents, long regularCents, long overtimeCents, long mileageCents, long paidMinutes, long overtimeMinutes, long unpaidMinutes,
            long driveMinutes, long waitingMinutes, long meters, long bufferSeconds,
            long qualifications, long unreachableLegs, long latenessSeconds, long lateVisits, long availabilitySeconds,
            long pastShiftMinutes, long capacityExcessMinutes, BigDecimal fairnessVariance, BigDecimal maximumUtilization, List<RouteRow> routes) {
        public Row {
            Required.value(datasetHash); Required.value(planHash); Required.value(fairnessVariance); Required.value(maximumUtilization);
            routes = Required.value(List.copyOf(routes));
        }
        /** This reading with its pay and its price replaced; everything else stays the engine's. */
        Row paid(long unpaid, long paid, long overtime, Rates rates, List<RouteRow> paidRoutes) {
            return new Row(datasetHash, planHash, feasible, routesHold, unassigned, hardPenalty,
                    rates.cents(paid, overtime, meters), rates.cents(paid - overtime, 0, 0), rates.cents(overtime, overtime, 0), rates.cents(0, 0, meters),
                    paid, overtime, unpaid, driveMinutes, waitingMinutes, meters, bufferSeconds, qualifications, unreachableLegs, latenessSeconds, lateVisits,
                    availabilitySeconds, pastShiftMinutes, capacityExcessMinutes, fairnessVariance, maximumUtilization, paidRoutes);
        }
    }
    private BacktestTool() { }

    static Object execute(JsonNode job, String kind) throws Exception {
        return switch (kind) {
            case "backtest-score" -> scoreJob(job);
            case "backtest-score-days" -> scoreDaysJob(job);
            case "backtest-solve" -> solveJob(job);
            case "backtest-day" -> dayJob(job);
            case "backtest-screen" -> screenJob(job);
            default -> throw new IllegalArgumentException("Unknown backtest job");
        };
    }

    /**
     * One day of the study in one JVM: seals the importer's two drafts (booked and actual durations), solves the booked
     * day, and reads the dispatcher's plans as planned and as executed, the proposal, and the proposal and the executed
     * plan played forward with the actual durations. Files land in {@code output}; the receipt names each with its hash.
     */
    private static Object dayJob(JsonNode job) {
        fields(job,"version","kind","directory","output","variant","seed","costMs","fairnessMs","policy");
        if (integer(get(job,"version"),1) != 1) throw new IllegalArgumentException("Backtest job version");
        Path directory = Required.value(Path.of(text(get(job,"directory")))), output = Required.value(Path.of(text(get(job,"output"))));
        String variant = text(get(job,"variant"));
        long seed = integer(get(job,"seed"),0), cost = integer(get(job,"costMs"),1), fairness = integer(get(job,"fairnessMs"),0);
        var policy = record(get(job,"policy"), SchedulingPolicy.Rules.class);
        try { java.nio.file.Files.createDirectories(output); } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot create the day's output", failure); }
        DailyDataset booked = sealed(directory, output, "facts");
        DailyDataset actual = sealed(directory, output, "facts-actual-durations");
        if (!booked.facts().demand().stream().map(visit -> Required.value(visit).id()).toList().equals(actual.facts().demand().stream().map(visit -> Required.value(visit).id()).toList()))
            throw new IllegalArgumentException("The two drafts of the day name different visits");
        String plannedJson = SnapshotFileIO.readText(directory.resolve("as-planned.plan.json").toFile());
        String executedJson = SnapshotFileIO.readText(directory.resolve("as-executed.plan.json").toFile());
        Plan planned = CalculationJson.read(plannedJson, Plan.class), executed = CalculationJson.read(executedJson, Plan.class);
        Map<String,Object> files = new TreeMap<>();
        write(output, "as-planned.row.json", score(booked, planned, CalculationJson.hash(plannedJson)), files);
        // The dispatcher's order played forward: what the day reads as when the product proposes no change.
        write(output, "as-planned-actual.row.json", score(actual, planned, CalculationJson.hash(plannedJson)), files);
        write(output, "as-executed.row.json", score(booked, executed, CalculationJson.hash(executedJson)), files);
        write(output, "as-executed-actual.row.json", score(actual, executed, CalculationJson.hash(executedJson)), files);
        Solved solved = solve(booked, variant, seed, cost, fairness, policy);
        String planJson = CalculationJson.write(solved.plan());
        SnapshotFileIO.writeText(output.resolve("solver.plan.json").toFile(), planJson);
        files.put("solver.plan.json", CalculationJson.hash(planJson));
        write(output, "solve.json", solved, files);
        write(output, "solver.row.json", solved.candidate(), files);
        write(output, "solver-actual.row.json", score(actual, solved.plan(), CalculationJson.hash(planJson)), files);
        var receipt = Required.value(Map.<String,@org.jspecify.annotations.NonNull Object>of("kind","backtest-day","bookedHash",booked.contentHash(),"actualHash",actual.contentHash(),
                "accepted",solved.accepted(),"reason",solved.reason(),"solveMs",solved.solveMs(),"wallMs",solved.wallMs(),"files",files));
        return document(receipt);
    }

    /** One day's screen: the dispatcher's booked plan read on the sealed booked draft, with the day's size. */
    public record ScreenRow(String day, String datasetHash, String planHash, boolean feasible, boolean routesHold, long unassigned,
            long hardPenalty, int visits, int technicians, long costCents) {
        public ScreenRow { Required.value(day); Required.value(datasetHash); Required.value(planHash); }
    }

    /**
     * Many days in one JVM: seals each day's booked draft in memory and reads its as-planned plan, so a study can pick
     * the days whose booked plan already holds without solving them. Writes nothing beside the importer's files.
     */
    private static Object screenJob(JsonNode job) {
        fields(job,"version","kind","days","output");
        if (integer(get(job,"version"),1) != 1) throw new IllegalArgumentException("Backtest job version");
        JsonNode days = get(job,"days");
        if (!days.isArray() || days.isEmpty()) throw new IllegalArgumentException("A screen needs at least one day");
        List<ScreenRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode element : days) {
            JsonNode entry = Required.value(element, "screen day");
            fields(entry,"day","directory");
            String day = text(get(entry,"day"));
            if (!seen.add(day)) throw new IllegalArgumentException("Day screened twice: " + day);
            Path directory = Required.value(Path.of(text(get(entry,"directory"))));
            DailyDataset dataset = DailyDataset.parse(DailyDataset.seal(SnapshotFileIO.readText(directory.resolve("facts.json").toFile())));
            String planJson = SnapshotFileIO.readText(directory.resolve("as-planned.plan.json").toFile());
            Row row = score(dataset, CalculationJson.read(planJson, Plan.class), CalculationJson.hash(planJson));
            DayPlan plan = dataset.toDayPlan();
            rows.add(new ScreenRow(day, row.datasetHash(), row.planHash(), row.feasible(), row.routesHold(), row.unassigned(), row.hardPenalty(),
                    plan.getVisits().size(), plan.getRoutes().size(), row.costCents()));
        }
        String json = CalculationJson.write(Required.value(Map.<String,@org.jspecify.annotations.NonNull Object>of("version",1,"kind","backtest-screen","rows",rows)));
        SnapshotFileIO.writeText(Path.of(text(get(job,"output"))).toFile(), json);
        long holding = 0;
        for (ScreenRow row : rows) if (row.feasible()) holding++;
        return document(Map.of("kind","backtest-screen","days",rows.size(),"holding",holding,"sha256",CalculationJson.hash(json)));
    }

    /** One day's reading of one plan file in a {@code backtest-score-days} run. */
    public record DayRow(String day, String plan, Row row) {
        public DayRow { Required.value(day); Required.value(plan); Required.value(row); }
    }

    /**
     * Many days in one JVM: seals each day's {@code dataset} draft in memory and reads its {@code plan} file (for Part 1,
     * the dispatcher's day as booked), in the job's day order. A day that does not hold is read in numbers, never
     * refused; a missing or malformed file is. The rows are then paid across the days by the {@link PayRule}, which
     * the output names. Writes the rows to {@code output} and nothing beside the importer's files.
     */
    private static Object scoreDaysJob(JsonNode job) {
        fields(job,"version","kind","dataset","plan","days","output");
        if (integer(get(job,"version"),1) != 1) throw new IllegalArgumentException("Backtest job version");
        String datasetName = text(get(job,"dataset")), planName = text(get(job,"plan"));
        for (String name : List.of(datasetName, planName))
            if (!name.matches("[A-Za-z0-9.-]+\\.json")) throw new IllegalArgumentException("A score-days file is a plain JSON file name: " + name);
        JsonNode days = get(job,"days");
        if (!days.isArray() || days.isEmpty()) throw new IllegalArgumentException("A score-days run needs at least one day");
        List<DayRow> scored = new ArrayList<>();
        List<Rates> rates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode element : days) {
            JsonNode entry = Required.value(element, "score day");
            fields(entry,"day","directory");
            String day = text(get(entry,"day"));
            if (!seen.add(day)) throw new IllegalArgumentException("Day scored twice: " + day);
            date(day);
            Path directory = Required.value(Path.of(text(get(entry,"directory"))));
            DailyDataset dataset = DailyDataset.parse(DailyDataset.seal(SnapshotFileIO.readText(directory.resolve(datasetName).toFile())));
            String planJson = SnapshotFileIO.readText(directory.resolve(planName).toFile());
            rates.add(Rates.of(dataset.toDayPlan()));
            scored.add(new DayRow(day, planName, score(dataset, CalculationJson.read(planJson, Plan.class), CalculationJson.hash(planJson))));
        }
        List<DayRow> rows = pay(scored, rates);
        // payRule names the rule the rows are paid under, so a reader can refuse rows paid under another or under none.
        String json = CalculationJson.write(Required.value(Map.<String,@org.jspecify.annotations.NonNull Object>of(
                "version",1,"kind","backtest-score-days","dataset",datasetName,"plan",planName,"payRule",PAY_RULE,"rows",rows)));
        SnapshotFileIO.writeText(Path.of(text(get(job,"output"))).toFile(), json);
        long holding = 0;
        for (DayRow row : rows) if (row.row().feasible()) holding++;
        return document(Map.of("kind","backtest-score-days","days",rows.size(),"holding",holding,"sha256",CalculationJson.hash(json)));
    }

    /**
     * A score day's date. The day is the local service date as an ISO date; anything else is refused, since a day that
     * cannot be placed in its week cannot be paid.
     */
    static LocalDate date(String day) {
        try { return Required.value(LocalDate.parse(day)); }
        catch (DateTimeParseException failure) { throw new IllegalArgumentException("A score day is an ISO date: " + day, failure); }
    }

    /** One technician's Monday-to-Friday week. */
    private record Week(String technicianId, LocalDate monday) { }

    /**
     * The engine's rows paid by the {@link PayRule}, returned in the order given. Each technician's week is walked in
     * date order whatever the order of {@code scored}, so the minutes past the weekly limit are overtime on the day they
     * fall and a period's overtime is the sum of its days. {@code rates} are each day's own. A technician is told from
     * one day to the next by the route's technician id, which must name one route a day. Days missing from
     * {@code scored} are days without work: the week's count runs over the days given.
     */
    static List<DayRow> pay(List<DayRow> scored, List<Rates> rates) {
        if (scored.size() != rates.size()) throw new IllegalArgumentException("Each scored day is priced at its own rates");
        List<LocalDate> dates = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (DayRow day : scored) { order.add(dates.size()); dates.add(date(day.day())); }
        if (new HashSet<>(dates).size() != dates.size()) throw new IllegalArgumentException("A day is paid once");
        order.sort((first, second) -> dates.get(first).compareTo(dates.get(second)));
        Map<Week,Long> weeks = new HashMap<>();
        List<DayRow> paid = new ArrayList<>(scored);
        for (int index : order) {
            DayRow day = scored.get(index);
            paid.set(index, new DayRow(day.day(), day.plan(), pay(day.row(), Required.value(dates.get(index)), Required.value(rates.get(index), "day rates"), weeks)));
        }
        return Required.value(List.copyOf(paid));
    }

    /** One day paid; {@code weeks} holds each technician's paid weekday minutes of the week so far and gains this day's. */
    private static Row pay(Row row, LocalDate date, Rates rates, Map<Week,Long> weeks) {
        DayOfWeek weekday = Required.value(date.getDayOfWeek());
        boolean weekend = weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY;
        LocalDate monday = Required.value(date.minusDays(weekday.getValue() - 1L));
        List<RouteRow> routes = new ArrayList<>();
        Set<String> technicians = new HashSet<>();
        long worked = 0, unpaid = 0, paid = 0, overtime = 0;
        for (RouteRow route : row.routes()) {
            if (!technicians.add(route.technicianId())) throw new IllegalArgumentException("Two routes of " + route.technicianId() + " on " + date);
            if (route.unpaidMinutes() != 0) throw new IllegalArgumentException("A route is paid once: " + route.technicianId() + " on " + date);
            long lunch = route.paidMinutes() > PAY_RULE.unpaidBreakOverMinutes() ? PAY_RULE.unpaidBreakMinutes() : 0;
            long routePaid = route.paidMinutes() - lunch, routeOvertime = routePaid;
            if (!(weekend && PAY_RULE.weekendOvertime())) {
                Week week = new Week(route.technicianId(), monday);
                Long before = weeks.get(week);
                long through = Math.addExact(before == null ? 0 : before, routePaid);
                weeks.put(week, through);
                routeOvertime = Math.max(0, Math.min(routePaid, through - PAY_RULE.weeklyRegularMinutes()));
            }
            routes.add(route.paid(lunch, routePaid, routeOvertime));
            worked = Math.addExact(worked, route.paidMinutes()); unpaid = Math.addExact(unpaid, lunch);
            paid = Math.addExact(paid, routePaid); overtime = Math.addExact(overtime, routeOvertime);
        }
        if (worked != row.paidMinutes() || row.unpaidMinutes() != 0) throw new IllegalArgumentException("The routes of " + date + " do not sum to the day's paid minutes");
        return row.paid(unpaid, paid, overtime, rates, routes);
    }

    /** The draft {@code <name>.json} of the day sealed into the output as {@code <name>.sealed.json}, reusing a seal already there. */
    private static DailyDataset sealed(Path directory, Path output, String name) {
        Path target = output.resolve(name + ".sealed.json");
        if (java.nio.file.Files.exists(target)) return DailyDataset.parse(SnapshotFileIO.readText(target.toFile()));
        String sealed = DailyDataset.seal(SnapshotFileIO.readText(directory.resolve(name + ".json").toFile()));
        SnapshotFileIO.writeText(target.toFile(), sealed);
        return DailyDataset.parse(sealed);
    }

    private static void write(Path output, String name, Object document, Map<String,Object> files) {
        String json = CalculationJson.write(document);
        SnapshotFileIO.writeText(output.resolve(name).toFile(), json);
        files.put(name, CalculationJson.hash(json));
    }

    private static Object scoreJob(JsonNode job) {
        fields(job,"version","kind","dataset","plan","output");
        if (integer(get(job,"version"),1) != 1) throw new IllegalArgumentException("Backtest job version");
        DailyDataset dataset = DailyDataset.parse(CampaignMain.checkedArtifact(get(job,"dataset")));
        String planJson = CampaignMain.checkedArtifact(get(job,"plan"));
        Row row = score(dataset, CalculationJson.read(planJson, Plan.class), CalculationJson.hash(planJson));
        String json = CalculationJson.write(row);
        SnapshotFileIO.writeText(Path.of(text(get(job,"output"))).toFile(), json);
        return document(Map.of("kind","backtest-score","row",row,"sha256",CalculationJson.hash(json)));
    }

    /** The plan's routes applied to the dataset's facts, then read. A plan naming unknown or missing visits is malformed input. */
    public static Row score(DailyDataset dataset, Plan plan, String planHash) {
        DayPlan restored = DailyCalculation.restore(dataset.toDayPlan(), plan.routes(), plan.unassigned());
        return score(restored, dataset.contentHash(), planHash);
    }

    /** The plan read in numbers, with the engine's pay: every minute from depot to depot, overtime after the shift end. */
    public static Row score(DayPlan plan, String datasetHash, String planHash) {
        var evaluation = DayScoreCalculator.evaluate(plan);
        var independent = RouteEvaluator.evaluateAssigned(plan);
        Map<String,PlanVisit> visits = new HashMap<>();
        plan.getVisits().forEach(visit -> visits.put(visit.getId(), visit));
        Set<String> services = new HashSet<>();
        plan.getVisits().forEach(visit -> services.add(visit.getServiceId()));
        List<RouteRow> rows = new ArrayList<>();
        List<SchedulingPolicy.Workload> workloads = new ArrayList<>();
        Map<String,TechRoute> routes = new HashMap<>();
        plan.getRoutes().forEach(route -> routes.put(route.getId(), route));
        boolean hold = true;
        long qualifications = 0, unreachable = 0, lateness = 0, late = 0, availability = 0, capacity = 0;
        for (DayScoreCalculator.RouteReading reading : DayScoreCalculator.routes(plan)) {
            TechRoute route = Required.value(routes.get(reading.technicianId()), "route for reading");
            Map<String,String> arrivals = new TreeMap<>();
            long lateHere = 0;
            for (var arrival : reading.arrivals().entrySet()) {
                PlanVisit visit = Required.value(visits.get(arrival.getKey()), "visit for arrival");
                if (!arrival.getValue().isBefore(visit.getWindowEnd())) lateHere++;
                arrivals.put(Required.value(arrival.getKey()), Required.value(arrival.getValue().toString()));
            }
            rows.add(new RouteRow(reading.technicianId(), reading.holds(), route.getVisits().size(), reading.qualifications(),
                    reading.unavailableRoads(), reading.latenessSeconds(), lateHere, reading.availabilitySeconds(), reading.capacityMinutes(),
                    reading.paidMinutes(), reading.overtimeMinutes(), 0, reading.driveMinutes(), reading.waitingMinutes(), reading.meters(),
                    reading.bufferSeconds(), arrivals));
            hold &= reading.holds();
            qualifications = Math.addExact(qualifications, reading.qualifications()); unreachable = Math.addExact(unreachable, reading.unavailableRoads());
            lateness = Math.addExact(lateness, reading.latenessSeconds()); late = Math.addExact(late, lateHere);
            availability = Math.addExact(availability, reading.availabilitySeconds()); capacity = Math.addExact(capacity, reading.capacityMinutes());
            // The routes the policy measures: regular capacity to fill and a qualification for some of the day's work.
            long regular = SchedulingPolicy.regularCapacity(route);
            if (regular > 0 && !Collections.disjoint(services, route.getQualifiedServiceIds()))
                workloads.add(new SchedulingPolicy.Workload(route.getId(), reading.paidMinutes(), regular, Required.value(BigDecimal.ZERO)));
        }
        var fairness = SchedulingPolicy.fairness(workloads);
        long unassigned = plan.getUnassignedVisitIds().size();
        long paid = evaluation.paidMinutes(), overtime = evaluation.overtimeMinutes(), meters = evaluation.meters();
        Rates rates = Rates.of(plan);
        return new Row(datasetHash, planHash, independent.feasible() && unassigned == 0, hold, unassigned, evaluation.hardPenalty(),
                evaluation.costCents(), rates.cents(paid - overtime, 0, 0), rates.cents(overtime, overtime, 0), rates.cents(0, 0, meters),
                paid, overtime, 0, evaluation.driveMinutes(), evaluation.waitingMinutes(),
                evaluation.meters(), evaluation.bufferSeconds(), qualifications, unreachable, lateness, late, availability,
                Math.floorDiv(Math.addExact(availability, 59), 60), capacity, fairness.variance(), fairness.maximumUtilization(), rows);
    }

    /** The receipt of one solve: the decision, the phases, and the baseline's and the proposal's readings. */
    public record Solved(String datasetHash, String variant, long seed, long costMs, long fairnessMs, boolean accepted, String reason,
            int solveMs, long wallMs, DailyOutcome baselineOutcome, DailyOutcome candidateOutcome, DailyOutcome referenceOutcome,
            Row baseline, Row candidate, Row reference, Plan plan, DailySolver.Diagnostics diagnostics) { }

    private static Object solveJob(JsonNode job) {
        fields(job,"version","kind","dataset","variant","seed","costMs","fairnessMs","policy","output","planOutput");
        if (integer(get(job,"version"),1) != 1) throw new IllegalArgumentException("Backtest job version");
        DailyDataset dataset = DailyDataset.parse(CampaignMain.checkedArtifact(get(job,"dataset")));
        String variant = text(get(job,"variant"));
        long seed = integer(get(job,"seed"),0), cost = integer(get(job,"costMs"),1), fairness = integer(get(job,"fairnessMs"),0);
        var policy = record(get(job,"policy"), SchedulingPolicy.Rules.class);
        Solved solved = solve(dataset, variant, seed, cost, fairness, policy);
        String planJson = CalculationJson.write(solved.plan());
        SnapshotFileIO.writeText(Path.of(text(get(job,"planOutput"))).toFile(), planJson);
        String json = CalculationJson.write(solved);
        SnapshotFileIO.writeText(Path.of(text(get(job,"output"))).toFile(), json);
        return document(Map.of("kind","backtest-solve","accepted",solved.accepted(),"reason",solved.reason(),"solveMs",solved.solveMs(),
                "sha256",CalculationJson.hash(json),"planSha256",CalculationJson.hash(planJson)));
    }

    /**
     * The production daily calculation (reference phase, fairness phase, acceptance) on the dataset's assignment,
     * inside a benchmark operation with the service's reserve, as {@link PolicyBenchmark} runs its campaigns.
     */
    public static Solved solve(DailyDataset dataset, String variant, long seed, long costMs, long fairnessMs, SchedulingPolicy.Rules policy) {
        var solver = new DailySolver(variant, seed);
        var allocation = new DailyCalculation.Allocation(costMs, fairnessMs, Math.addExact(costMs, fairnessMs), true);
        Duration search = Required.value(Duration.ofMillis(allocation.searchMs()));
        // The service's clock: the search plus its reserve may explore, and the reserve stays back for validation.
        Duration reserve = DailyOperation.Allowance.RESERVE;
        Duration allowance = Required.value(search.plus(reserve).plus(reserve));
        DayPlan baseline = dataset.toDayPlan();
        CompletableFuture<DailyOperation.Receipt> receipt = new CompletableFuture<>();
        long started = System.nanoTime();
        var result = DailyOperation.executeBenchmark(new SearchAdmission(1,0), allowance, search, reserve,
                () -> DailyCalculation.run(baseline, policy, solver, allocation), receipt::complete);
        try { Required.value(receipt.get(5,TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Backtest receipt interrupted",interrupted); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) { throw new IllegalStateException("Backtest cleanup receipt missing",failed); }
        long wall = (System.nanoTime() - started) / 1_000_000;
        String hash = dataset.contentHash();
        Plan plan = Plan.of(result.plan());
        return new Solved(hash, variant, seed, costMs, fairnessMs, result.accepted(), result.reason(), result.solveMs(), wall,
                DailyOutcome.assess(baseline), DailyOutcome.assess(result.plan()), DailyOutcome.assess(result.reference()),
                score(baseline, hash, "baseline"), score(result.plan(), hash, CalculationJson.hash(CalculationJson.write(plan))),
                score(result.reference(), hash, "reference"), plan, result.diagnostics());
    }
}
