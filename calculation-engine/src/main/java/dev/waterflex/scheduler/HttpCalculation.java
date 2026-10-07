package dev.waterflex.scheduler;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;
/** Bounded transport under the caller's existing clock, with no retries or second-solve fallback. */
public final class HttpCalculation implements CalculationAdapter {
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Unavailable(String message,@Nullable Throwable cause) { super(message,cause); }
    }
    private final URI base;
    private final String token;
    private final HttpClient client;
    public HttpCalculation(String url,String token) {
        URI uri = URI.create(url); String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !SetOfSchemes.allowed(uri) || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Invalid private solver URL");
        if (token.length() < 32 || token.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("Invalid solver token");
        base = uri; this.token = token;
        client = Required.value(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    private static final class SetOfSchemes {
        static boolean allowed(URI uri) {
            return "https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                    && java.util.Set.of("127.0.0.1","localhost","[::1]","solver-service").contains(uri.getHost());
        }
    }
    @Override public CalculationProtocol.Response calculate(CalculationProtocol.Request request) {
        SearchDeadline clock = Required.value(SearchDeadline.current(),"caller calculation deadline");
        String body = CalculationJson.write(request);
        var timeout = clock.timeout(Required.value(Duration.ofMillis(request.remainingMillis())));
        var http = HttpRequest.newBuilder(base.resolve("/v1/solve/"+request.operation().toLowerCase(java.util.Locale.ROOT)))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        CompletableFuture<HttpResponse<String>> flight = client.sendAsync(http,_ -> new LimitedBody());
        try {
            HttpResponse<String> response;
            while (true) {
                clock.requireTime();
                try { response = Required.value(flight.get(Math.min(clock.remainingNanos(),50_000_000L),TimeUnit.NANOSECONDS)); break; }
                catch (TimeoutException waiting) { clock.requireTime(); }
            }
            clock.requireTime();
            if (response.statusCode() != 200) throw new Unavailable("Solver HTTP status "+response.statusCode(),null);
            var result = CalculationJson.read(Required.value(response.body()),CalculationProtocol.Response.class); result.match(request);
            clock.requireTime(); return result;
        } catch (ExecutionException failure) { cancel(request.requestId()); throw new Unavailable("Solver transport failed",failure); }
          catch (InterruptedException interrupted) { flight.cancel(true); cancel(request.requestId()); Thread.currentThread().interrupt(); throw new SearchDeadline.Expired(); }
          catch (SearchDeadline.Expired expired) { flight.cancel(true); cancel(request.requestId()); throw expired; }
    }
    /** Cancellation is best effort cleanup of this request, never a retry of its calculation. */
    public void cancel(String requestId) {
        UUIDCheck.valid(requestId);
        var request = HttpRequest.newBuilder(base.resolve("/v1/solves/"+requestId)).header("Authorization","Bearer "+token)
                .timeout(Duration.ofSeconds(1)).DELETE().build();
        client.sendAsync(request,HttpResponse.BodyHandlers.discarding()).exceptionally(_ -> null);
    }
    private static final class UUIDCheck { static void valid(String id) { java.util.UUID.fromString(id); } }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.@Nullable Subscription subscription;
        @Override public CompletionStage<String> getBody() { return body; }
        @Override public void onSubscribe(Flow.@Nullable Subscription subscription) { var checked = Required.value(subscription); this.subscription = checked; checked.request(1); }
        @Override public void onNext(@Nullable List<ByteBuffer> items) {
            var subscribed = Required.value(subscription);
            for (ByteBuffer unchecked : Required.value(items)) {
                ByteBuffer item = Required.value(unchecked);
                if ((long)bytes.size()+item.remaining() > CalculationJson.MAX_BYTES) {
                    subscribed.cancel(); body.completeExceptionally(new java.io.IOException("Solver response exceeds payload limit")); return;
                }
                byte[] value = new byte[item.remaining()]; item.get(value); bytes.writeBytes(value);
            }
            subscribed.request(1);
        }
        @Override public void onError(@Nullable Throwable failure) { body.completeExceptionally(Required.value(failure)); }
        @Override public void onComplete() { body.complete(Required.value(bytes.toString(StandardCharsets.UTF_8))); }
    }
}
