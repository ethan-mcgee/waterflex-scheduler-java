package dev.waterflex.scheduler;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.waterflex.scheduler.optimizer.*;
import java.util.*;
/** Indexed, tagged booking roads. Both encodings seal the same canonical directed facts. */
public final class BookingDataset {
    public enum Encoding { SPARSE, DENSE }
    private BookingDataset() { }
    public static String encode(BookingCalculation.Input input,Encoding encoding) {
        ObjectNode calculation = (ObjectNode) CalculationJson.tree(CalculationJson.write(input));
        ObjectNode root = JsonNodeFactory.instance.objectNode(); root.put("schemaVersion",1).put("operation","BOOKING")
                .put("snapshotId",input.snapshot().metroId()+"@"+input.snapshot().capturedAt()).putNull("contentHash");
        root.putObject("versions").put("policy",SchedulingPolicy.VERSION).put("cost",Monetary.COST_MODEL).put("score",DailyDataset.SCORE_MODEL);
        root.set("calculation",calculation);
        var days = calculation.path("snapshot").path("days");
        for (var day : days.properties()) {
            var value = (ObjectNode) day.getValue(); var roads = value.path("roads"); Set<String> names = new TreeSet<>();
            value.path("technicians").properties().forEach(entry -> { names.add(entry.getKey()); names.add(entry.getKey()+":return"); });
            value.path("visits").properties().forEach(entry -> names.add(entry.getKey())); names.add(input.request().jobId());
            List<String> ids = new ArrayList<>(names); Map<String,Integer> indices = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) indices.put(ids.get(i),i);
            ObjectNode indexed = value.putObject("roads"); indexed.put("encoding","SPARSE").put("size",ids.size());
            ArrayNode locations = indexed.putArray("locationIds"); ids.forEach(locations::add); ArrayNode entries = indexed.putArray("entries");
            for (var road : roads.path("legs").properties()) {
                String[] pair = road.getKey().split(">",-1); PlanFacts.roadKey(Required.value(road.getKey()));
                entries.addObject().put("from",Required.value(indices.get(pair[0]),"road origin")).put("to",Required.value(indices.get(pair[1]),"road destination"))
                        .put("state","REACHABLE").put("seconds",road.getValue().path("seconds").longValue()).put("meters",road.getValue().path("meters").longValue());
            }
            for (var road : roads.path("unreachable")) {
                String key = text(Required.value(road)); PlanFacts.roadKey(key); String[] pair = key.split(">",-1);
                entries.addObject().put("from",Required.value(indices.get(pair[0]),"road origin")).put("to",Required.value(indices.get(pair[1]),"road destination"))
                        .put("state","UNREACHABLE").putNull("seconds").putNull("meters");
            }
            sortEntries(Required.value(entries));
        }
        ObjectNode sealed = normalize(root); sealed.remove("contentHash"); String hash = CalculationJson.hash(canonical(sealed)); root.put("contentHash",hash);
        if (encoding == Encoding.DENSE) for (var day : days.properties()) {
            ObjectNode roads = (ObjectNode) day.getValue().path("roads"); int size = roads.path("size").intValue();
            Map<Long,JsonNode> cells = new HashMap<>(); for (JsonNode entry : roads.path("entries")) cells.put(pair(entry.path("from").intValue(),entry.path("to").intValue()),entry);
            roads.remove("entries"); roads.put("encoding","DENSE"); ArrayNode rows = roads.putArray("cells");
            for (int from = 0; from < size; from++) { ArrayNode row = rows.addArray(); for (int to = 0; to < size; to++) {
                JsonNode state = cells.get(pair(from,to));
                if (state == null) row.addObject().put("state","NOT_REQUIRED").putNull("seconds").putNull("meters");
                else { ObjectNode cell = ((ObjectNode) state).deepCopy(); cell.remove(List.of("from","to")); row.add(cell); }
            } }
        }
        return canonical(root);
    }
    public static BookingCalculation.Input parse(String json) {
        JsonNode parsed = CalculationJson.tree(json); keys(parsed,"schemaVersion","operation","snapshotId","contentHash","versions","calculation");
        if (number(Required.value(parsed.path("schemaVersion")),1,1) != 1 || !text(Required.value(parsed.path("operation"))).equals("BOOKING")) throw new IllegalArgumentException("Unsupported booking dataset");
        text(Required.value(parsed.path("snapshotId"))); JsonNode versions = Required.value(parsed.path("versions")); keys(versions,"policy","cost","score");
        if (!text(Required.value(versions.path("policy"))).equals(SchedulingPolicy.VERSION) || !text(Required.value(versions.path("cost"))).equals(Monetary.COST_MODEL)
                || !text(Required.value(versions.path("score"))).equals(DailyDataset.SCORE_MODEL)) throw new IllegalArgumentException("Incompatible booking dataset models");
        String hash = text(Required.value(parsed.path("contentHash"))); ObjectNode normalized = normalize((ObjectNode) parsed); normalized.remove("contentHash");
        if (!hash.equals(CalculationJson.hash(canonical(normalized)))) throw new IllegalArgumentException("Booking dataset content hash mismatch");
        ObjectNode calculation = (ObjectNode) normalized.path("calculation");
        for (var day : calculation.path("snapshot").path("days").properties()) {
            ObjectNode value = (ObjectNode) day.getValue(); JsonNode roads = value.path("roads");
            List<String> ids = new ArrayList<>(); for (var id : roads.path("locationIds")) ids.add(text(Required.value(id)));
            ObjectNode decoded = value.putObject("roads"); ObjectNode legs = decoded.putObject("legs"); ArrayNode unreachable = decoded.putArray("unreachable");
            for (JsonNode entry : roads.path("entries")) {
                String key = ids.get(entry.path("from").intValue())+">"+ids.get(entry.path("to").intValue());
                if (entry.path("state").textValue().equals("REACHABLE")) legs.putObject(key).put("seconds",entry.path("seconds").longValue()).put("meters",entry.path("meters").longValue());
                else unreachable.add(key);
            }
        }
        BookingCalculation.Input input = CalculationJson.read(CalculationJson.write(calculation),BookingCalculation.Input.class);
        BookingCalculation.validateRoads(input); return input;
    }
    private static ObjectNode normalize(ObjectNode root) {
        ObjectNode copy = root.deepCopy(); JsonNode days = copy.path("calculation").path("snapshot").path("days");
        if (!days.isObject() || days.size() > 32) throw new IllegalArgumentException("Invalid booking horizon");
        for (var day : days.properties()) {
            JsonNode roads = day.getValue().path("roads"); String encoding = text(Required.value(roads.path("encoding")));
            keys(roads,"encoding","size","locationIds",encoding.equals("DENSE") ? "cells" : "entries");
            int size = (int)number(Required.value(roads.path("size")),0,10000); JsonNode ids = roads.path("locationIds");
            if (!ids.isArray() || ids.size() != size) throw new IllegalArgumentException("Invalid booking location dimensions");
            Set<String> unique = new HashSet<>(); for (JsonNode id : ids) {
                String identity = text(Required.value(id));
                PlanFacts.identity(identity.endsWith(":return") ? Required.value(identity.substring(0,identity.length()-7)) : identity,"location");
                if (!unique.add(identity)) throw new IllegalArgumentException("Duplicate booking location");
            }
            Set<String> expected = new HashSet<>();
            day.getValue().path("technicians").properties().forEach(entry -> { expected.add(entry.getKey()); expected.add(entry.getKey()+":return"); });
            day.getValue().path("visits").properties().forEach(entry -> expected.add(entry.getKey()));
            expected.add(text(Required.value(copy.path("calculation").path("request").path("jobId"))));
            if (!expected.equals(unique)) throw new IllegalArgumentException("Booking locations do not match declared facts");
            ObjectNode normalized = ((ObjectNode)day.getValue()).putObject("roads"); normalized.put("encoding","SPARSE").put("size",size).set("locationIds",ids); ArrayNode entries = normalized.putArray("entries"); Set<Long> pairs = new HashSet<>();
            if (encoding.equals("SPARSE")) {
                JsonNode values = roads.path("entries"); if (!values.isArray()) throw new IllegalArgumentException("Invalid booking roads");
                for (JsonNode entry : values) {
                    keys(Required.value(entry),"from","to","state","seconds","meters"); int from=(int)number(Required.value(entry.path("from")),0,size-1),to=(int)number(Required.value(entry.path("to")),0,size-1);
                    if (!pairs.add(pair(from,to))) throw new IllegalArgumentException("Duplicate booking directed road");
                    state(Required.value(entry),false); entries.add(entry);
                }
            } else if (encoding.equals("DENSE")) {
                JsonNode rows=roads.path("cells"); if (!rows.isArray() || rows.size()!=size) throw new IllegalArgumentException("Invalid dense booking roads");
                for (int from=0; from<size; from++) { JsonNode row=Required.value(rows.get(from)); if (!row.isArray() || row.size()!=size) throw new IllegalArgumentException("Invalid dense booking row");
                    for (int to=0; to<size; to++) { JsonNode cell=Required.value(row.get(to)); keys(cell,"state","seconds","meters"); state(cell,true);
                        if (!cell.path("state").textValue().equals("NOT_REQUIRED")) entries.addObject().put("from",from).put("to",to).setAll((ObjectNode)cell);
                    }
                }
            } else throw new IllegalArgumentException("Unsupported booking encoding");
            sortEntries(Required.value(entries));
        }
        return copy;
    }
    private static void state(JsonNode node,boolean dense) {
        String state=text(Required.value(node.path("state")));
        if (state.equals("REACHABLE")) { number(Required.value(node.path("seconds")),0,Long.MAX_VALUE); number(Required.value(node.path("meters")),0,Long.MAX_VALUE); }
        else if ((state.equals("UNREACHABLE") || dense && state.equals("NOT_REQUIRED")) && node.path("seconds").isNull() && node.path("meters").isNull()) { }
        else throw new IllegalArgumentException("Malformed booking road state");
    }
    private static long pair(int from,int to) { return ((long)from<<32)|(to&0xffffffffL); }
    private static long number(JsonNode node,long minimum,long maximum) { if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue()<minimum || node.longValue()>maximum) throw new IllegalArgumentException("Invalid booking integer"); return node.longValue(); }
    private static String text(JsonNode node) { if (!node.isTextual()) throw new IllegalArgumentException("Missing booking string"); String value=Required.value(node.textValue()); CalculationJson.text(value); return value; }
    private static void keys(JsonNode node,String... expected) { Set<String> actual=new HashSet<>(); node.fieldNames().forEachRemaining(actual::add); if (!node.isObject() || !actual.equals(Set.of(expected))) throw new IllegalArgumentException("Unexpected booking dataset fields"); }
    private static void sortEntries(ArrayNode entries) { List<JsonNode> sorted=new ArrayList<>(); entries.forEach(sorted::add); sorted.sort(Comparator.comparingLong(value -> pair(value.path("from").intValue(),value.path("to").intValue()))); entries.removeAll(); sorted.forEach(entries::add); }
    private static String canonical(JsonNode node) {
        if (node.isObject()) { ObjectNode sorted=JsonNodeFactory.instance.objectNode(); TreeMap<String,JsonNode> fields=new TreeMap<>(); node.properties().forEach(entry -> fields.put(entry.getKey(),entry.getValue())); fields.forEach((name,value) -> sorted.set(name,CalculationJson.tree(canonical(Required.value(value))))); return CalculationJson.write(Required.value(sorted)); }
        if (node.isArray()) { ArrayNode values=JsonNodeFactory.instance.arrayNode(); for (JsonNode value : node) values.add(CalculationJson.tree(canonical(Required.value(value)))); return CalculationJson.write(Required.value(values)); }
        return CalculationJson.write(node);
    }
}
