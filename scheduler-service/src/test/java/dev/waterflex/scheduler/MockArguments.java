package dev.waterflex.scheduler;

import org.mockito.ArgumentMatchers;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.time.LocalDate;
import org.springframework.jdbc.core.RowMapper;

/** Mockito matchers return sentinels. Use only as arguments of mocked invocations. */
public final class MockArguments {
    private MockArguments() { }
    // Mockito consumes its null sentinel before production code executes; HTTP tests verify the stub.
    @SuppressWarnings("null")
    public static String equalText(String value) { return ArgumentMatchers.eq(value); }
    @SuppressWarnings("null")
    public static String startsText(String value) { return ArgumentMatchers.startsWith(value); }
    @SuppressWarnings("null")
    public static <T> Class<T> equalType(Class<T> value) { return ArgumentMatchers.eq(value); }
    @SuppressWarnings("null")
    public static <T> RowMapper<T> rowMapper() { return ArgumentMatchers.any(); }
    @SuppressWarnings("null")
    public static RowCallbackHandler callback() { return ArgumentMatchers.<@Nullable RowCallbackHandler>any(); }
    @SuppressWarnings("null")
    public static LocalDate day() { return ArgumentMatchers.<@Nullable LocalDate>any(); }
}

