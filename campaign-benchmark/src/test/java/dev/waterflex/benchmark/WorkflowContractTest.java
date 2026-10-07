package dev.waterflex.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import dev.waterflex.scheduler.CalculationJson;
import dev.waterflex.scheduler.Required;
import dev.waterflex.scheduler.optimizer.*;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transport fixture only. Hosted caller/database tests provide business workflow evidence. */
class WorkflowContractTest {
    @TempDir @org.jspecify.annotations.Nullable Path directory;
    @Test void pacedArrivalsKeepGeneratorMissesAndRealCancellationReceipts() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        AtomicInteger active=new AtomicInteger(),cancelled=new AtomicInteger(),measured=new AtomicInteger();
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().getPath();Object response;
            if(path.equals("/internal/benchmark/statistics")) response=Map.of("configuration",Map.of("scheduler.optimizer.variant","TABU","scheduler.optimizer.seed","17","scheduler.calculation.mode","EMBEDDED"),
                    "solver",Map.of("configurationXml",SolverEngine.configuration(SolverEngine.Variant.TABU,17).configurationXml()),
                    "admission",Map.of("active",active.get(),"queuedBookings",0,"queuedBackground",0),"persistedCounts",Map.of("fixture",measured.get()));
            else if(path.equals("/v1/offers/cancel-search")) { cancelled.incrementAndGet();response=Map.of("success",true); }
            else if(path.equals("/v1/offers")) {
                measured.incrementAndGet();active.incrementAndGet();
                try { Thread.sleep(150); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { active.decrementAndGet(); }
                response=Map.of("fixtureOnly",true);
            } else if(path.equals("/internal/benchmark/audit")) {
                assertEquals(0,active.get(),"Audit must follow worker cleanup");
                response=CalculationJson.tree("{\"independentlyValidated\":true,\"costModelVersion\":\""+DailyDataset.COST_MODEL+"\",\"days\":[{\"policy\":{\"overtimeMinutes\":0}}]}");
            } else response=Map.of("fixtureOnly",true);
            byte[] bytes=CalculationJson.write(Required.value(response)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.equals("/warm-failure") ? 400 : 200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var settings=new CampaignSolverConfiguration.Settings("test","TABU",7,1000,10000,
                    Required.value(List.of(new CampaignSolverConfiguration.Move("listChange",45),new CampaignSolverConfiguration.Move("listSwap",45))),
                    "NO_ASSERT","NONE",1,new CampaignSolverConfiguration.Termination("fixed","local-search",true,null,null,null,null));
            List<WorkflowBenchmark.Request> requests=new ArrayList<>();
            for(int index=0;index<4;index++) requests.add(new WorkflowBenchmark.Request(call("measured-"+index,"/v1/offers"),20L,call("cancel-"+index,"/v1/offers/cancel-search"),Required.value(List.of())));
            var envelope=new WorkflowBenchmark.Envelope(1,BenchmarkContractTest.dataset().json(DailyDataset.Encoding.SPARSE),"http://127.0.0.1:"+server.getAddress().getPort(),"transport-fixture",
                    Required.value(Map.of()),Required.value(List.of(call("prepare","/internal/benchmark/cache"))),Required.value(Map.of("booking-offer",List.of())),requests,
                    Required.value(List.of(call("audit","/internal/benchmark/audit"))),1000,10);
            Map<String,Object> campaign=new TreeMap<>();campaign.put("configuration",settings);campaign.put("case",Map.of("solverSeed",17));
            campaign.put("policy",SchedulingPolicy.Rules.defaults());campaign.put("budget",new PolicyBenchmark.Budget("booking","production","booking",5000,4000,0,0,0,1000,false));
            campaign.put("dataset",Map.of("scoreVersion",DailyDataset.SCORE_MODEL,"modelVersion",DailyDataset.COST_MODEL,"routingIdentity","asymmetric-v1","cohort","assigned"));
            Map<String,Object> load=new TreeMap<>();
            load.put("operation","booking-offer");load.put("mode","paced-arrival");load.put("requestsPerCase",4);load.put("requestsPerSecond",100);load.put("concurrency",1);
            load.put("schedulerCache","cold");load.put("providerCache","cold");load.put("timeoutMs",5000);load.put("observeCancellation",true);load.put("deployment","embedded");load.put("endpointIdentity","transport-fixture");
            campaign.put("applicationLoad",load);
            campaign.put("warmup",Map.of("millisecondsPerFreshJvm",0,"paths",List.of("booking-offer"),"disposableInputs",true,"calibrationMs",List.of(1),"calibrationRepetitions",1,"stabilityTolerancePercent",5));
            var result=WorkflowBenchmark.run(CalculationJson.tree(CalculationJson.write(campaign)),CalculationJson.write(envelope),Required.value(directory));
            var rows=Protocol.get(CalculationJson.tree(CalculationJson.write(result)),"observations");
            assertEquals(4,rows.size());assertEquals(1,measured.get());assertEquals(1,cancelled.get());
            assertEquals("RESPONSE",Protocol.text(Protocol.get(Required.value(rows.get(0)),"outcome")));
            assertEquals("GENERATOR_CAPACITY",Protocol.text(Protocol.get(Required.value(rows.get(1)),"outcome")));
            assertFalse((Boolean)result.get("independentlyValid"));assertEquals(true,result.get("cleanupComplete"));
            var failedWarm=new WorkflowBenchmark.Envelope(envelope.version(),envelope.datasetJson(),envelope.baseUrl(),envelope.endpointIdentity(),
                    envelope.expectedConfiguration(),envelope.preparation(),Required.value(Map.of("booking-offer",List.of(
                    new WorkflowBenchmark.Request(call("failed-warm","/warm-failure"),null,null,Required.value(List.of()))))),
                    envelope.requests(),envelope.verification(),envelope.cleanupObservationMs(),envelope.cleanupPollMs());
            campaign.put("warmup",Map.of("millisecondsPerFreshJvm",1,"paths",List.of("booking-offer"),"disposableInputs",true,"calibrationMs",List.of(1),"calibrationRepetitions",1,"stabilityTolerancePercent",5));
            Path failedDirectory=Required.value(java.nio.file.Files.createDirectory(Required.value(directory).resolve("failed-warm")));
            var failure=assertThrows(IllegalArgumentException.class,() -> WorkflowBenchmark.run(CalculationJson.tree(CalculationJson.write(campaign)),CalculationJson.write(failedWarm),failedDirectory));
            assertTrue(Required.value(failure.getMessage()).contains("Caller warmup failed"));
            assertTrue(java.nio.file.Files.readString(failedDirectory.resolve("caller-warmup.json")).contains("UNEXPECTED_HTTP_STATUS"));
            assertEquals(1,measured.get(),"Failed warmup must stop before measured calls");
        } finally { server.stop(0);executor.close(); }
    }
    private static WorkflowBenchmark.Call call(String id,String path) { return new WorkflowBenchmark.Call(id,"POST",path,CalculationJson.tree("{}"),200); }
}
