package dev.waterflex.scheduler;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class SchedulerApplication {
    public static void main(String[] args) {
        // Prisma stores DateTime in PostgreSQL timestamp columns as UTC wall time.
        // JDBC Timestamp conversion must use the same zone on developer machines.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
