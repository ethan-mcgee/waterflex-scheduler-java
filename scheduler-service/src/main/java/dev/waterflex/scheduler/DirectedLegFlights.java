package dev.waterflex.scheduler;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Bounded shared road work. A caller owns its wait budget, never the shared future. */
final class DirectedLegFlights implements AutoCloseable {
    private record Key(String identity, String pair) { }
    private final Map<Key, CompletableFuture<Optional<RoadClient.Leg>>> pending = new HashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "directed-road-legs"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    Map<String, RoadClient.Leg> resolve(List<RoadClient.Pair> pairs, String identity,
            Function<List<RoadClient.Pair>, Map<String, RoadClient.Leg>> fetch) {
        SearchDeadline.checkpoint();
        Map<String, CompletableFuture<Optional<RoadClient.Leg>>> awaited = new LinkedHashMap<>();
        List<RoadClient.Pair> owned = new ArrayList<>();
        synchronized (pending) {
            if (pending.size() + pairs.size() > 8192) throw new SearchAdmission.Busy("Shared routing capacity exhausted");
            for (RoadClient.Pair pair : pairs) {
                Key key = new Key(identity, pair.id());
                CompletableFuture<Optional<RoadClient.Leg>> future = pending.get(key);
                if (future == null) {
                    future = new CompletableFuture<>(); pending.put(key, future); owned.add(pair);
                }
                awaited.put(pair.id(), future);
            }
            if (!owned.isEmpty()) {
                try { workers.execute(() -> run(owned, identity, awaited, fetch)); }
                catch (RejectedExecutionException rejected) {
                    finish(owned, identity, awaited, null, new SearchAdmission.Busy("Shared routing queue is full"));
                }
            }
        }
        Map<String, RoadClient.Leg> result = new LinkedHashMap<>();
        for (var entry : awaited.entrySet()) {
            try {
                // Do not cancel on timeout: another customer can still need this result.
                var value = entry.getValue().get(SearchDeadline.networkTimeout(Required.value(Duration.ofSeconds(20))).toNanos(), TimeUnit.NANOSECONDS);
                SearchDeadline.checkpoint();
                RoadClient.Leg leg = value.orElse(null);
                if (leg != null) result.put(entry.getKey(), leg);
            } catch (TimeoutException timeout) {
                if (SearchDeadline.current() != null) throw new SearchDeadline.Expired();
                throw new RoadClient.RoadUnavailable("Shared routing wait timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); SearchDeadline.checkpoint();
                throw new RoadClient.RoadUnavailable("Shared routing wait interrupted");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new RoadClient.RoadUnavailable("Shared routing work failed");
            }
        }
        return Required.value(Map.copyOf(result));
    }

    private void run(List<RoadClient.Pair> owned, String identity,
            Map<String, CompletableFuture<Optional<RoadClient.Leg>>> futures,
            Function<List<RoadClient.Pair>, Map<String, RoadClient.Leg>> fetch) {
        try {
            // This worker has no caller ThreadLocal or transaction. Its bounded HTTP/SQL work only
            // populates caches, so finishing after a customer leaves cannot create a reservation.
            Map<String, RoadClient.Leg> values = fetch.apply(Required.value(List.copyOf(owned)));
            finish(owned, identity, futures, values, null);
        } catch (Throwable failure) {
            finish(owned, identity, futures, null, failure);
            if (failure instanceof Error error) throw error;
        }
    }

    private void finish(List<RoadClient.Pair> owned, String identity,
            Map<String, CompletableFuture<Optional<RoadClient.Leg>>> futures,
            @org.jspecify.annotations.Nullable Map<String, RoadClient.Leg> values,
            @org.jspecify.annotations.Nullable Throwable failure) {
        synchronized (pending) {
            for (RoadClient.Pair pair : owned) {
                var future = Required.value(futures.get(pair.id()), "shared road result");
                if (failure != null) future.completeExceptionally(failure);
                else future.complete(Optional.ofNullable(Required.value(values, "resolved road batch").get(pair.id())));
                pending.remove(new Key(identity, pair.id()), future);
            }
        }
    }
    int pendingCount() { synchronized (pending) { return pending.size(); } }
    @Override public void close() {
        workers.shutdownNow();
        synchronized (pending) {
            pending.values().forEach(future -> future.completeExceptionally(new RoadClient.RoadUnavailable("Routing service is stopping")));
            pending.clear();
        }
    }
}
