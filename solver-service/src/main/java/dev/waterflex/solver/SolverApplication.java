package dev.waterflex.solver;
import dev.waterflex.scheduler.*;
import dev.waterflex.scheduler.optimizer.DailySolver;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
/** Private calculation service. Its dependency graph has no JDBC driver, database or RoadClient. */
@SpringBootApplication
public class SolverApplication {
    public static void main(String[] args) { SpringApplication.run(SolverApplication.class,args); }
    @Bean SearchAdmission admission(@Value("${solver.capacity:2}") int capacity,@Value("${solver.queue-limit:16}") int queue) { return new SearchAdmission(capacity,queue); }
    @Bean DailySolver dailySolver(@Value("${solver.variant:TABU}") String variant,@Value("${solver.seed:17}") long seed) { return new DailySolver(variant,seed); }
    @Bean EmbeddedCalculation calculation(DailySolver solver) { return new EmbeddedCalculation(solver); }
}
