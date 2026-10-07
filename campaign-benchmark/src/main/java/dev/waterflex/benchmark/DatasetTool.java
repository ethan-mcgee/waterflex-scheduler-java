package dev.waterflex.benchmark;

import static dev.waterflex.benchmark.Protocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.waterflex.scheduler.*;
import dev.waterflex.scheduler.optimizer.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit JSON setup jobs. Synthetic geography and road durations are declared models, never live routing. */
public final class DatasetTool {
    public record Family(String id, int technicians, int visits, int skills, double radiusDegrees,
            int durationMin, int durationMax, int windowMinutes, int windowStartSpreadMinutes,
            double absenceProbability, int absenceMinutes, double unassignedFraction, int pinnedPrefix) {
        public Family {
            CalculationJson.text(id);
            if (!id.matches("[a-z0-9-]{1,60}") || technicians < 1 || visits < 1 || visits > 2000 || technicians > 100
                    || skills < 1 || skills > technicians || !Double.isFinite(radiusDegrees) || radiusDegrees <= 0
                    || durationMin < 1 || durationMax < durationMin || windowMinutes < durationMax
                    || windowStartSpreadMinutes < 0 || !Double.isFinite(absenceProbability) || absenceProbability < 0 || absenceProbability > 1
                    || absenceMinutes < 1 || !Double.isFinite(unassignedFraction) || unassignedFraction < 0 || unassignedFraction > 1 || pinnedPrefix < 0)
                throw new IllegalArgumentException("Invalid corpus family");
        }
    }
    public record Corpus(int version, String kind, String outputDirectory, Instant shiftStart, int shiftMinutes,
            double latitude, double longitude, double roadMetersPerDegree, double roadMetersPerSecond, double directionBias,
            BigDecimal regularHourly, BigDecimal overtimeHourly, BigDecimal mileagePerMile, BigDecimal travelBufferPct,
            long travelBufferMinutes, List<Long> datasetSeeds, List<String> cohorts, List<@org.jspecify.annotations.NonNull Family> families,
            String solverVariant, long solverSeed, long solverBudgetMs) {
        public Corpus {
            if (version != 1 || !kind.equals("corpus") || shiftMinutes < 1 || Math.abs(latitude) > 85 || Math.abs(longitude) > 170
                    || !Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(roadMetersPerDegree)
                    || !Double.isFinite(roadMetersPerSecond) || roadMetersPerDegree <= 0 || roadMetersPerSecond <= 0
                    || !Double.isFinite(directionBias) || directionBias < 0 || directionBias >= 1 || datasetSeeds.isEmpty()
                    || datasetSeeds.stream().anyMatch(value -> Required.value(value) < 0) || new HashSet<>(datasetSeeds).size() != datasetSeeds.size()
                    || cohorts.isEmpty() || !Set.of("assigned","cold","partial","repair","invalid-input").containsAll(cohorts)
                    || new HashSet<>(cohorts).size() != cohorts.size() || families.isEmpty() || solverSeed < 0 || solverBudgetMs < 1)
                throw new IllegalArgumentException("Invalid corpus job");
            Required.value(outputDirectory); Required.value(shiftStart);Required.value(regularHourly);Required.value(overtimeHourly);Required.value(mileagePerMile);Required.value(travelBufferPct);Required.value(solverVariant);
            datasetSeeds=Required.value(List.copyOf(datasetSeeds));cohorts=Required.value(List.copyOf(cohorts));families=Required.value(List.copyOf(families));
        }
    }
    private DatasetTool() { }
    public static Object execute(JsonNode job) throws Exception {
        String kind=text(get(job,"kind"));
        return switch(kind) {
            case "corpus" -> corpus(record(job,Corpus.class));
            case "seal" -> {
                fields(job,"version","kind","input","output");
                if (integer(get(job,"version"),1)!=1) throw new IllegalArgumentException("Setup version");
                String sealed=DailyDataset.seal(SnapshotFileIO.readText(Path.of(text(get(job,"input"))).toFile()));
                SnapshotFileIO.writeText(Path.of(text(get(job,"output"))).toFile(),sealed);
                yield Required.value(Map.of("kind",kind,"contentHash",DailyDataset.parse(sealed).contentHash(),"sha256",CalculationJson.hash(sealed)));
            }
            case "export", "import", "anonymize" -> historical(job,kind);
            case "freeze-target" -> freeze(job);
            default -> throw new IllegalArgumentException("Unknown dataset setup job");
        };
    }
    private static Object corpus(Corpus spec) throws Exception {
        Path directory=Path.of(spec.outputDirectory()).toAbsolutePath();Files.createDirectory(directory);
        List<Map<String,Object>> manifest=new ArrayList<>();
        for (Family family : spec.families()) for (Long boxedSeed : spec.datasetSeeds()) for (String cohort : spec.cohorts()) {
            long seed=Required.value(boxedSeed);String id=family.id()+"-"+seed+"-"+cohort;
            DailyDataset dataset=generate(spec,family,seed,cohort.equals("invalid-input") ? "assigned" : cohort);
            String json=dataset.json(DailyDataset.Encoding.SPARSE);
            if (cohort.equals("invalid-input")) {
                ObjectNode broken=(ObjectNode)CalculationJson.tree(json);broken.putNull("rates");json=CalculationJson.write(broken);
            }
            SnapshotFileIO.writeText(directory.resolve(id+".json").toFile(),json);
            Map<String,Object> item=new TreeMap<>();item.put("id",id);item.put("family",family.id());item.put("cohort",cohort);
            item.put("origin","synthetic");item.put("datasetSeed",seed);item.put("input",Map.of("path",id+".json","sha256",CalculationJson.hash(json)));
            item.put("contentHash",cohort.equals("invalid-input") ? null : dataset.contentHash());
            item.put("initialOutcome",cohort.equals("invalid-input") ? null : DailyOutcome.assess(dataset.toDayPlan()));
            item.put("expectedInputFailure",cohort.equals("invalid-input"));manifest.add(item);
        }
        var result=Required.value(Map.<String,@org.jspecify.annotations.NonNull Object>of("version",1,"kind","corpus","specification",spec,"datasets",manifest));
        SnapshotFileIO.writeText(directory.resolve("manifest.json").toFile(),CalculationJson.write(result));return result;
    }
    static DailyDataset generate(Corpus spec,Family family,long seed,String cohort) {
        // Separate stable random streams prevent solver seed or unrelated dimensions changing the corpus.
        var geometry=random(seed,family.id()+":geometry");var skills=random(seed,family.id()+":skills");var windows=random(seed,family.id()+":windows");
        var durations=random(seed,family.id()+":durations");var absences=random(seed,family.id()+":absences");var assignments=random(seed,family.id()+":assignments");
        List<TechRoute> routes=new ArrayList<>();Map<String,RoadPoint> points=new TreeMap<>();Map<String,Integer> revisions=new TreeMap<>();
        for(int index=0;index<family.technicians();index++) {
            String id=Required.value(String.format(Locale.ROOT,"t%04d",index));
            Set<String> qualified=new TreeSet<>();qualified.add("s"+(index%family.skills()));
            for(int skill=0;skill<family.skills();skill++) if(skills.nextBoolean()) qualified.add("s"+skill);
            var route=new TechRoute(id,spec.shiftStart(),instant(spec.shiftStart(),spec.shiftMinutes()*60L),spec.shiftMinutes(),0,qualified);
            if(absences.nextDouble()<family.absenceProbability()) {
                int offset=absences.nextInt(Math.max(1,spec.shiftMinutes()-family.absenceMinutes()+1));
                List<TechRoute.Unavailable> unavailable=new ArrayList<>();
                unavailable.add(new TechRoute.Unavailable(instant(spec.shiftStart(),offset*60L),instant(spec.shiftStart(),(offset+family.absenceMinutes())*60L)));
                route.setUnavailable(unavailable);
            }
            routes.add(route);revisions.put(id,0);points.put(id,point(spec,family,geometry));points.put(id+":return",point(spec,family,geometry));
        }
        List<PlanVisit> visits=new ArrayList<>();
        for(int index=0;index<family.visits();index++) {
            String id=Required.value(String.format(Locale.ROOT,"v%04d",index));String service="s"+skills.nextInt(family.skills());
            List<TechRoute> qualified=routes.stream().filter(route -> Required.value(route).getQualifiedServiceIds().contains(service)).toList();
            TechRoute selected=Required.value(qualified.get(assignments.nextInt(qualified.size())));
            boolean assign=!cohort.equals("cold") && (!cohort.equals("partial") || assignments.nextDouble()>=family.unassignedFraction());
            Instant start=instant(spec.shiftStart(),windows.nextInt(family.windowStartSpreadMinutes()+1)*60L);
            var visit=new PlanVisit(id,service,start,instant(start,family.windowMinutes()*60L),durations.nextInt(family.durationMin(),family.durationMax()+1),
                    assign ? selected.getId() : null,assign ? start : null);
            if(assign) { selected.getVisits().add(visit);visit.setTechnician(selected); }
            visits.add(visit);points.put(id,point(spec,family,geometry));
        }
        for(TechRoute route:routes) {
            route.getVisits().sort(Comparator.comparing(visit -> Required.value(visit).getWindowStart()));
            route.setPinnedPrefix(Math.min(family.pinnedPrefix(),route.getVisits().size()));
        }
        Map<String,DayPlan.RoadLeg> roads=new TreeMap<>();
        for(String from:points.keySet()) for(String to:points.keySet()) if(!from.equals(to)) {
            RoadPoint left=Required.value(points.get(from)),right=Required.value(points.get(to));
            double distance=Math.hypot(left.lat()-right.lat(),left.lng()-right.lng())*spec.roadMetersPerDegree();
            long meters=Math.round(distance),seconds=Math.round(distance/spec.roadMetersPerSecond()*(1+(left.lng()>right.lng() ? spec.directionBias() : -spec.directionBias())));
            roads.put(from+">"+to,new DayPlan.RoadLeg(seconds,meters));
        }
        DayPlan plan=new DayPlan(routes,visits,roads,spec.regularHourly(),spec.overtimeHourly(),spec.mileagePerMile(),spec.travelBufferPct(),spec.travelBufferMinutes());
        plan.setMode(DayPlan.Mode.valueOf(Required.value(cohort.toUpperCase(Locale.ROOT))));
        if(cohort.equals("repair")) plan=PlanCopies.withAbsence(plan,routes.getFirst().getId(),new TechRoute.Unavailable(spec.shiftStart(),instant(spec.shiftStart(),spec.shiftMinutes()*60L)));
        return DailyDataset.capture(plan,points,"synthetic-"+family.id()+"-"+seed+"-"+cohort,"synthetic-revision-1","synthetic-euclidean-directed-v1",CalculationJson.hash(CalculationJson.write(spec)),revisions,spec.solverVariant(),spec.solverSeed(),spec.solverBudgetMs());
    }
    private static Random random(long seed,String channel) { return new Random(seed ^ Long.parseUnsignedLong(CalculationJson.hash(channel).substring(0,16),16)); }
    private static Instant instant(Instant start,long seconds) { return Required.value(start.plusSeconds(seconds)); }
    private static RoadPoint point(Corpus spec,Family family,Random random) { return new RoadPoint(spec.latitude()+(random.nextDouble()*2-1)*family.radiusDegrees(),spec.longitude()+(random.nextDouble()*2-1)*family.radiusDegrees()); }
    private static Object historical(JsonNode job,String kind) {
        fields(job,"version","kind","input","output","dateShiftDays","latitude","longitude");
        if(integer(get(job,"version"),1)!=1) throw new IllegalArgumentException("Historical job version");
        String source=checkedArtifactForImport(get(job,"input"));DailyDataset original=DailyDataset.parse(source);
        ObjectNode transformed=(ObjectNode)CalculationJson.tree(original.json(DailyDataset.Encoding.SPARSE));
        long days=integer(get(job,"dateShiftDays"),0);
        if(kind.equals("anonymize")) {
            double latitude=get(job,"latitude").doubleValue(),longitude=get(job,"longitude").doubleValue();
            if(!get(job,"latitude").isNumber() || !get(job,"longitude").isNumber() || !Double.isFinite(latitude) || !Double.isFinite(longitude)) throw new IllegalArgumentException("Anonymization anchor required");
            double baseLat=get(Required.value(get(transformed,"locations").get(0)),"latitude").doubleValue(),baseLng=get(Required.value(get(transformed,"locations").get(0)),"longitude").doubleValue();
            for(String collection:List.of("locations","services","technicians","visits")) {
                List<String> identities=new ArrayList<>();
                for(JsonNode row:get(transformed,Required.value(collection))) identities.add(text(get(Required.value(row),"id")));
                Collections.sort(identities);Map<String,String> replacements=new HashMap<>();
                for(int index=0;index<identities.size();index++) replacements.put(identities.get(index),collection+String.format(Locale.ROOT,"%06d",index));
                for(JsonNode row:get(transformed,Required.value(collection))) {
                    var item=(ObjectNode)row;item.put("id",Required.value(replacements.get(text(get(item,"id")))));
                    if(collection.equals("locations")) {item.put("latitude",get(item,"latitude").doubleValue()-baseLat+latitude);item.put("longitude",get(item,"longitude").doubleValue()-baseLng+longitude);}
                    for(String time:List.of("shiftStart","shiftEnd","windowStart","windowEnd","originalPlannedStart")) if(item.has(time) && !get(item,Required.value(time)).isNull()) item.put(time,Instant.parse(text(get(item,Required.value(time)))).plusSeconds(days*86400L).toString());
                    if(item.has("absences")) for(JsonNode absence:get(item,"absences")) for(String time:List.of("start","end")) ((ObjectNode)absence).put(time,Instant.parse(text(get(Required.value(absence),Required.value(time)))).plusSeconds(days*86400L).toString());
                }
            }
            transformed.put("snapshotId","anonymized");((ObjectNode)get(transformed,"routing")).put("provider","anonymized").put("identity","anonymized-road-matrix-v1");
            ((ObjectNode)get(transformed,"revisions")).put("configuration","anonymized").put("reservation","anonymized");
            var schedules=((ObjectNode)get(transformed,"revisions")).putArray("schedule");for(int index=0;index<original.facts().technicians().size();index++) schedules.add("anonymized-"+index);
        } else if(days!=0 || !get(job,"latitude").isNull() || !get(job,"longitude").isNull()) throw new IllegalArgumentException("Export/import transformations must be explicit null");
        transformed.putNull("contentHash");String output=DailyDataset.seal(CalculationJson.write(transformed));DailyDataset validated=DailyDataset.parse(output);
        SnapshotFileIO.writeText(Path.of(text(get(job,"output"))).toFile(),output);
        return document(Map.of("kind",kind,"sourceHash",original.contentHash(),"contentHash",validated.contentHash(),"sha256",CalculationJson.hash(output),"outcome",DailyOutcome.assess(validated.toDayPlan())));
    }
    private static String checkedArtifactForImport(JsonNode artifact) { return CampaignMain.checkedArtifact(artifact); }
    private static Object freeze(JsonNode job) {
        fields(job,"version","kind","input","output","configuration","seed","budgetMs","policy");
        if(integer(get(job,"version"),1)!=1) throw new IllegalArgumentException("Target setup version");
        DailyDataset dataset=DailyDataset.parse(checkedArtifactForImport(get(job,"input")));
        var settings=record(get(job,"configuration"),CampaignSolverConfiguration.Settings.class);long seed=integer(get(job,"seed"),0),budget=integer(get(job,"budgetMs"),1);
        var policy=record(get(job,"policy"),SchedulingPolicy.Rules.class);DayPlan input=dataset.toDayPlan();
        if(input.getMode()==DayPlan.Mode.REPAIR) throw new IllegalArgumentException("Repair does not use fairness target");
        long started=System.nanoTime();var definition=CampaignSolverConfiguration.definition(settings,seed,!input.getUnassignedVisitIds().isEmpty(),budget);
        DayPlan solved=SolverEngine.solve(definition,input,Required.value(Duration.ofMillis(budget))).plan();
        DayPlan reference=DailyOutcome.assess(input).policyEligible() && (!DailyOutcome.assess(solved).policyEligible() || SchedulingPolicy.measure(input).costCents()<=SchedulingPolicy.measure(solved).costCents()) ? input : solved;
        if(!DailyOutcome.assess(reference).policyEligible()) throw new IllegalArgumentException("No complete validated zero-overtime reference");
        var target=new FrozenTarget(1,dataset.contentHash(),DailyDataset.SCORE_MODEL,DailyDataset.COST_MODEL,text(get(get(CalculationJson.tree(dataset.json(DailyDataset.Encoding.SPARSE)),"routing"),"identity")),
                policy,SnapshotFileIO.proposal(reference),policy.costCeiling(SchedulingPolicy.measure(reference).costCents()),definition.fingerprint(),definition.configurationXml(),seed,budget,(System.nanoTime()-started)/1_000_000);
        target.bind(dataset);String json=CalculationJson.write(target);SnapshotFileIO.writeText(Path.of(text(get(job,"output"))).toFile(),json);
        return document(Map.of("kind","freeze-target","target",target,"sha256",CalculationJson.hash(json)));
    }
}
