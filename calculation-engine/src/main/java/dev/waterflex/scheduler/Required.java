package dev.waterflex.scheduler;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Required calculation facts fail closed; the caller owns transaction handling. */
public final class Required {
    private Required() { }
    public static <T> @NonNull T value(@Nullable T value, String name) {
        if (value == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Incomplete scheduling data: " + name);
        return value;
    }
    public static <T> @NonNull T value(@Nullable T value) { return value(value, "required value"); }
}
