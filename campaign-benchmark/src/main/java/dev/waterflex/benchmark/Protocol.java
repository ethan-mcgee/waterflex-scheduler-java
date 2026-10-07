package dev.waterflex.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import java.util.*;

/** Strict tree access and Python-compatible sorted ASCII request hashing. */
final class Protocol {
    private Protocol() { }
    static Object document(@org.jspecify.annotations.Nullable Object value) { return Required.value(value); }
    static JsonNode get(JsonNode node,String key) { return Required.value(node.get(key),"Missing campaign field: " + key); }
    static String text(JsonNode node) {
        if (!node.isTextual()) throw new IllegalArgumentException("Campaign string required");
        String value = Required.value(node.textValue()); CalculationJson.text(value); return value;
    }
    static long integer(JsonNode node,long minimum) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < minimum)
            throw new IllegalArgumentException("Campaign integer required");
        return node.longValue();
    }
    static boolean bool(JsonNode node) {
        if (!node.isBoolean()) throw new IllegalArgumentException("Campaign boolean required"); return node.booleanValue();
    }
    static void fields(JsonNode node,String... names) {
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(Set.of(names))) throw new IllegalArgumentException("Campaign fields differ: " + actual);
    }
    static <T> T record(JsonNode node,Class<T> type) { return CalculationJson.read(CalculationJson.write(node),type); }
    static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("Campaign array required");
        List<String> values = new ArrayList<>(); for (JsonNode item : node) values.add(text(Required.value(item)));
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("Duplicate campaign strings");
        return Required.value(List.copyOf(values));
    }
    static String hash(JsonNode value) { return CalculationJson.hash(canonical(value)); }
    static String canonical(JsonNode value) {
        if (value.isObject()) {
            List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
            return "{" + String.join(",",keys.stream().map(key -> quote(Required.value(key))+":"+canonical(get(value,Required.value(key)))).toList()) + "}";
        }
        if (value.isArray()) {
            List<String> parts = new ArrayList<>(); for (JsonNode item : value) parts.add(canonical(Required.value(item)));
            return "[" + String.join(",",parts) + "]";
        }
        if (value.isTextual()) return quote(Required.value(value.textValue()));
        if (value.isNull() || value.isBoolean() || value.isIntegralNumber()) return Required.value(value.toString());
        if (value.isFloatingPointNumber() && Double.isFinite(value.doubleValue())) {
            // Request floats are finite JSON numbers. Python's exponent spelling includes a two digit exponent.
            String number = value.toString().toLowerCase(Locale.ROOT);
            int exponent = number.indexOf('e');
            if (exponent >= 0) {
                String mantissa = number.substring(0,exponent);
                if (mantissa.endsWith(".0")) mantissa = mantissa.substring(0,mantissa.length()-2);
                int power = Integer.parseInt(number.substring(exponent+1));
                if (power >= -4 && power < 16) {
                    String plain = Required.value(new java.math.BigDecimal(number).stripTrailingZeros().toPlainString());
                    return plain.contains(".") ? plain : plain + ".0";
                }
                return mantissa + "e" + (power < 0 ? "-" : "+") + String.format(Locale.ROOT,"%02d",Math.abs(power));
            }
            return Required.value(number);
        }
        throw new IllegalArgumentException("Unsupported canonical JSON value");
    }
    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int index=0; index<text.length(); index++) {
            char value=text.charAt(index);
            switch(value) {
                case '"' -> result.append("\\\"");case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");case '\f' -> result.append("\\f");case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");case '\t' -> result.append("\\t");
                default -> { if(value < 32 || value >= 127) result.append(String.format(Locale.ROOT,"\\u%04x",(int)value)); else result.append(value); }
            }
        }
        result.append('"');
        return Required.value(result.toString());
    }
}
