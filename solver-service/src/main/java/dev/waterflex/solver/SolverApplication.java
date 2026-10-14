package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import dev.waterflex.scheduler.optimizer.DailySolvers;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
/** Private calculation service. Its dependency graph has no JDBC driver, database or RoadClient. */
@SpringBootApplication
public class SolverApplication {
    public static void main(String[] args) { SpringApplication.run(SolverApplication.class,args); }
    /**
     * The pool's slots. A solver pool serves the scheduler replicas' searches and their daily calculations: its capacity
     * should be the sum of the replicas' search capacities, and its background limit (daily calculations at once) the
     * sum of the daily replicas' background limits; 0 opens every slot to daily work, since the pool has nothing else
     * to protect. A background limit of one would run one daily solve at a time whatever the capacity, and a daily
     * replica that admitted two would see the second refused once it could no longer finish in time.
     */
    @Bean SearchAdmission admission(@Value("${solver.capacity:2}") int capacity,@Value("${solver.queue-limit:16}") int queue,
                                    @Value("${solver.background-limit:0}") int background) {
        if (background < 0) throw new IllegalArgumentException("solver.background-limit must be 0 (the capacity) or positive");
        return new SearchAdmission(capacity,queue,background == 0 ? capacity : background);
    }
    /** Every client-selectable daily search; each request names the one it was computed for. */
    @Bean DailySolvers dailySolvers(@Value("${solver.seed:17}") long seed) { return new DailySolvers(seed); }
    @Bean EmbeddedCalculation calculation(DailySolvers solvers) { return new EmbeddedCalculation(solvers); }
}
