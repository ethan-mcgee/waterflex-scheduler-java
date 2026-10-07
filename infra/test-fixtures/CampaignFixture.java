// Lifecycle fixture only. This executable never loads Timefold or measures a solver.
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class CampaignFixture {
    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String match(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) throw new IllegalArgumentException("Fixture input mismatch");
        return matcher.group(1);
    }

    public static void main(String[] args) throws Exception {
        Path request = Path.of(args[0]);
        String input = Files.readString(request);
        String caseId = match(input, "\"id\"\\s*:\\s*\"([a-f0-9]{64})\"");
        // A fixture can echo the orchestrator's hash. Real adapters must parse,
        // validate and hash the request themselves; identity rejection has its own tests.
        String requestHash = match(Files.readString(request.getParent().resolve("dispatch.json")),
                "\"requestHash\"\\s*:\\s*\"([a-f0-9]{64})\"");
        long started = System.nanoTime();
        Thread.sleep(15); // Test campaign declares 10 ms, reference path only.
        double warmupMs = (System.nanoTime() - started) / 1e6;
        long pid = ProcessHandle.current().pid();
        String cpus;
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process probe = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                    "$m=(Get-Process -Id " + pid + ").ProcessorAffinity.ToInt64(); $a=@(); "
                    + "for($i=0;$i -lt 63;$i++){if($m -band (1L -shl $i)){$a+=$i}}; '['+($a -join ',')+']'").start();
            cpus = new String(probe.getInputStream().readAllBytes()).strip();
            if (probe.waitFor() != 0) throw new IllegalStateException("Affinity probe failed");
        } else {
            String list = Files.readString(Path.of("/proc/self/status")).lines()
                    .filter(line -> line.startsWith("Cpus_allowed_list:")).findFirst().orElseThrow()
                    .split(":")[1].strip();
            cpus = "[" + list + "]"; // Test requests one CPU, never a range.
        }
        String flags = "[" + ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .map(CampaignFixture::quote).collect(Collectors.joining(",")) + "]";
        String json = "{\"protocol\":\"waterflex-campaign-jvm-v1\",\"caseId\":" + quote(caseId)
                + ",\"requestHash\":" + quote(requestHash) + ",\"state\":\"SUCCEEDED\","
                + "\"evidenceKind\":\"contract-fixture\",\"warmup\":{\"paths\":[\"reference\"],"
                + "\"disposableInputs\":true,\"elapsedMsByPath\":{\"reference\":" + warmupMs + "}},"
                + "\"runtime\":{\"inputArguments\":" + flags + ",\"availableProcessors\":"
                + Runtime.getRuntime().availableProcessors() + ",\"affinityCpus\":" + cpus + ",\"pid\":" + pid
                + "},\"instrumentation\":{\"enabledStatistics\":[],\"unavailableStatistics\":[],"
                + "\"internalDiagnosticsEnabled\":false,\"internalDiagnosticsFailureReason\":null},"
                + "\"result\":{\"fixtureOnly\":true}}";
        Files.writeString(Path.of(args[1]), json, StandardOpenOption.CREATE_NEW);
        if (input.contains("\"configurationId\": \"la\"")) {
            System.err.println("Explicit fixture failure after warmup; no retry permitted.");
            System.exit(2);
        }
    }
}
