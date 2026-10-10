package dev.waterflex.scheduler;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
/** A strict private protocol. Invalid external facts never become fabricated primitive defaults. */
public final class CalculationJson {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = create(true);
    /** Same strictness, but an absent property arrives as null; the target type must validate every required value. */
    private static final ObjectMapper OMITTABLE = create(false);
    private CalculationJson() { }
    private static ObjectMapper create(boolean everyPropertyPresent) {
        var factory = new JsonFactory(); factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        ObjectMapper mapper = Required.value(JsonMapper.builder(factory).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build());
        mapper.registerModule(new JavaTimeModule());
        var decimal = new SimpleModule();
        // Protocol decimals also include signed fairness deltas, which are not monetary rates.
        decimal.addSerializer(BigDecimal.class, new JsonSerializer<@org.jspecify.annotations.NonNull BigDecimal>() {
            @Override public void serialize(@Nullable BigDecimal value, @Nullable JsonGenerator generator,
                    @Nullable SerializerProvider provider) throws java.io.IOException {
                Required.value(generator).writeString(canonicalDecimal(Required.value(value)));
            }
        });
        decimal.addDeserializer(BigDecimal.class, new JsonDeserializer<@org.jspecify.annotations.NonNull BigDecimal>() {
            @Override public BigDecimal deserialize(@Nullable JsonParser parser, @Nullable DeserializationContext context) throws java.io.IOException {
                JsonParser input = Required.value(parser);
                if (input.currentToken() != JsonToken.VALUE_STRING) throw new IllegalArgumentException("Decimal must be a canonical string");
                String text = Required.value(input.getText()); BigDecimal value = new BigDecimal(text);
                if (!canonicalDecimal(value).equals(text)) throw new IllegalArgumentException("Noncanonical decimal");
                return value;
            }
        });
        mapper.registerModule(decimal);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        if (everyPropertyPresent) mapper.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        return mapper;
    }
    public static String write(Object value) {
        try { String json = Required.value(JSON.writeValueAsString(value)); checkSize(json); return json; }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot encode calculation", failure); }
    }
    public static <T> T read(String json, Class<T> type) {
        checkSize(json);
        try { return Required.value(JSON.readValue(json, type), "calculation document"); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid calculation document", failure); }
    }
    /**
     * Strict read for external documents with optional properties: unknown fields, duplicate keys, trailing tokens,
     * numeric or noncanonical decimals and scalar coercion still fail. Absent properties become null, so target types
     * must use boxed numbers and reject every missing required value themselves.
     */
    public static <T> T readOmittable(String json, Class<T> type) {
        checkSize(json);
        @Nullable T value;
        try { value = OMITTABLE.readValue(json, type); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid external document", failure); }
        if (value == null) throw new IllegalArgumentException("External document must not be null");
        return value;
    }
    public static JsonNode tree(String json) {
        checkSize(json);
        try { return Required.value(JSON.readTree(json)); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid calculation JSON", failure); }
    }
    public static String hash(String json) {
        try { return Required.value(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void text(String value) { if (Required.value(value).isBlank()) throw new IllegalArgumentException("Blank protocol identity"); }
    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal normalized = Required.value(value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros());
        if (Math.abs((long)normalized.scale()) > MAX_BYTES || normalized.precision() > MAX_BYTES)
            throw new IllegalArgumentException("Calculation decimal exceeds limit");
        String text = Required.value(normalized.toPlainString()); checkSize(text); return text;
    }
    public static void checkSize(String json) { if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Calculation payload exceeds limit"); }
}
