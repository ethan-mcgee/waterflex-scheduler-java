package dev.waterflex.scheduler;

import dev.waterflex.scheduler.admin.SchedulerCommands;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class SchedulerApplication {
    public static void main(String[] args) {
        // road_route_cache.fetchedAt is a timestamp without time zone holding UTC wall time.
        // JDBC Timestamp conversion must use the same zone on developer machines.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // Operator commands (--command=migrate, issue-token, ...) run without starting the server.
        if (SchedulerCommands.isCommand(args)) System.exit(SchedulerCommands.run(args, Required.value(System.getenv()), Required.value(System.out), Required.value(System.err)));
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
