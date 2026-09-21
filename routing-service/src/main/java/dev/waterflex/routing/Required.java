package dev.waterflex.routing;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

final class Required {
    private Required() { }
    static <T> @NonNull T value(@Nullable T value) {
        if (value == null) throw new IllegalStateException("Missing required routing value");
        return value;
    }
}
