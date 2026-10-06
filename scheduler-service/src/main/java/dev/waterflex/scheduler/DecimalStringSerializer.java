package dev.waterflex.scheduler;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.math.BigDecimal;

/** Monetary decimal wire values use canonical plain strings. */
public final class DecimalStringSerializer extends JsonSerializer<BigDecimal> {
    @Override public void serialize(@Nullable BigDecimal value, @Nullable JsonGenerator generator,
            @Nullable SerializerProvider provider) throws IOException {
        Required.value(generator, "decimal generator").writeString(Monetary.canonical(Required.value(value, "decimal value")));
    }
}
