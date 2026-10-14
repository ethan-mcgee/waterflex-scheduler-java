package dev.waterflex.scheduler;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What this replica is deployed for ({@code scheduler.role}). Every replica answers every route, so a load balancer that
 * sends a call to the wrong pool still gets an answer; the role decides only which background work runs here. A booking
 * replica runs no daily queue workers, so a queued day never takes a slot its bookings need; a daily replica runs
 * them; {@code all} (the default) does both, as one replica alone must.
 */
@Component
public final class ReplicaRole {
    public enum Role { BOOKING, DAILY, ALL }

    private final Role role;

    @Autowired
    public ReplicaRole(@Value("${scheduler.role:all}") String configured) { this(parse(configured)); }

    public ReplicaRole(Role role) { this.role = role; }

    static Role parse(String configured) {
        String value = configured.trim().toUpperCase(Locale.ROOT);
        for (Role candidate : Role.values()) if (candidate.name().equals(value)) return candidate;
        throw new IllegalArgumentException("scheduler.role must be booking, daily or all (was \"" + configured + "\")");
    }

    public Role role() { return role; }

    /** The role as configured and reported: booking, daily or all. */
    public String label() { return Required.value(role.name().toLowerCase(Locale.ROOT)); }

    /** Whether this replica takes jobs from the asynchronous daily queue. */
    public boolean runsDailyQueue() { return role != Role.BOOKING; }
}
