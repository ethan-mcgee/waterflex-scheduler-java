package dev.waterflex.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks the replica's database pool against its search capacity at startup. A running search holds a connection,
 * a confirm takes one, and the refinement's routes and the road workers take more while the searches run, so a pool no
 * larger than the capacity refuses refinements their connection ({@code REFINEMENT_BUSY}) under load. The size is
 * {@code spring.datasource.hikari.maximum-pool-size} ({@code SCHEDULER_DB_POOL_SIZE}).
 */
@Component
public final class DatabasePool {
    private static final Logger LOG = Required.value(LoggerFactory.getLogger(DatabasePool.class));
    /** A search and the connection its commit needs beside it. */
    static final int MIN_POOL_SIZE = 2;
    private final int poolSize;

    @Autowired
    public DatabasePool(@Value("${spring.datasource.hikari.maximum-pool-size}") int poolSize, SearchAdmission admission) {
        this(poolSize, admission.capacity(), admission.confirmSlots());
    }

    public DatabasePool(int poolSize, int capacity, int confirmSlots) {
        if (poolSize < MIN_POOL_SIZE) throw new IllegalArgumentException("spring.datasource.hikari.maximum-pool-size must be at least " + MIN_POOL_SIZE);
        if (poolSize <= capacity + confirmSlots)
            throw new IllegalArgumentException("spring.datasource.hikari.maximum-pool-size (" + poolSize + ") must exceed the search capacity ("
                    + capacity + ") and confirm slots (" + confirmSlots + ") together, with headroom for refinements and route fetches");
        this.poolSize = poolSize;
        LOG.info("Database pool of {} connections for a search capacity of {} and {} confirm slots", poolSize, capacity, confirmSlots);
    }

    public int poolSize() { return poolSize; }
}
