package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.Json;
import io.lumipeek.core.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import static io.lumipeek.core.Failure.Code.*;

/** Local HTTP only. Constructing this client does not connect, install or start Ollama. */
public final class LocalOllama implements AutoCloseable {
    private static final Set<String> MODELS = Set.of("qwen3-vl:2b-instruct", "qwen3-vl:4b-instruct", "qwen3-vl:8b-instruct");
    private static final Set<String> UNCERTAIN_SERVERS = ConcurrentHashMap.newKeySet();
    private final URI base;
    private final String serverKey;
    private String model;
    private final ExecutorService io = Executors.newFixedThreadPool(2, r -> daemon(r, "lumipeek-http"));
    private final ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "lumipeek-http-deadline"));
    private final HttpClient http;
    private Call<?> active;
    private boolean closed, uncertainInference, uncertainDownload;
    public record Model(String name, long bytes) {}
    public static LocalOllama localDefault(String tag) { return new LocalOllama(URI.create("http://127.0.0.1:11434"),tag); }
    public record Progress(long completed, long total) {}
    public synchronized boolean uncertainWork() { return uncertainInference || uncertainDownload || UNCERTAIN_SERVERS.contains(serverKey); }
    public synchronized void selectModel(String tag) {
        ModelCatalog.require(tag);
        if (active != null || uncertainWork() || closed) throw new IllegalStateException("MODEL_CHANGE_BLOCKED");
        model=tag;
    }
    /** No call without an explicit user approval. Only the fixed official catalog is accepted. */
    public synchronized CompletableFuture<Void> pull(String tag, boolean approved, java.util.function.Consumer<Progress> progress) {
        ModelCatalog.require(tag);
        if (!approved) return CompletableFuture.failedFuture(new Failure(PATH_DENIED));
        if (uncertainWork()) return CompletableFuture.failedFuture(new Failure(CLEANUP_FAILED));
        return start(Duration.ofMinutes(5), c -> send(c,"/api/status",null).thenCompose(status -> {
            if (!Boolean.TRUE.equals(Json.get(status,"cloud","disabled"))) throw denied();
            return pullStream(c,tag,progress);
        }));
    }
    private synchronized CompletableFuture<Void> pullStream(Call<Void> call, String tag, java.util.function.Consumer<Progress> progress) {
        check(call); call.pullSent=true;
        var request=HttpRequest.newBuilder(base.resolve("/api/pull"))
            .timeout(Duration.ofNanos(Math.max(1,call.deadline-System.nanoTime())))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("model",tag,"stream",true)))).build();
        var network=http.sendAsync(request, info -> {
            PullBody body=new PullBody(info.statusCode()==200, value -> {
                if (!call.result.isDone() && System.nanoTime() < call.deadline) progress.accept(value);
            });
            call.pullBody=body; if(call.result.isDone()) body.abort(); return body;
        });
        call.network=network;
        return network.thenApply(reply -> {
            check(call);
            synchronized(LocalOllama.this) { check(call); call.pullFinished=reply.body()!=0; }
            if(reply.statusCode()!=200 || reply.body()!=1) throw invalid();
            return null;
        });
    }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    public LocalOllama(URI endpoint, String model) {
        String host = endpoint.getHost();
        if (!"http".equals(endpoint.getScheme()) || !Set.of("127.0.0.1", "[::1]").contains(host == null ? "" : host)
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !(endpoint.getRawPath().isEmpty() || endpoint.getRawPath().equals("/")) || model == null || !MODELS.contains(model))
            throw new IllegalArgumentException("LOCAL_ENDPOINT_OR_MODEL_REQUIRED");
        base = endpoint; serverKey=host+":"+endpoint.getPort(); this.model = model;
        clock.setRemoveOnCancelPolicy(true);
        http = HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            }).build();
    }
    public CompletableFuture<String> version(Duration timeout) {
        return start(timeout, c -> send(c, "/api/version", null).thenApply(root -> {
            String value = Json.getString(root, "version");
            if (value == null || value.isBlank() || value.length() > 64) throw invalid();
            return value;
        }));
    }
    public CompletableFuture<List<Model>> models(Duration timeout) {
        return start(timeout, c -> send(c, "/api/tags", null).thenApply(root -> {
            Object array = Json.get(root, "models");
            if (!(array instanceof List<?> list) || list.size() > 256) throw invalid();
            List<Model> result = new ArrayList<>();
            for (Object item : list) {
                String name = Json.getString(item, "name");
                Object size = Json.get(item, "size");
                if (MODELS.contains(name == null ? "" : name) && local(item) && size instanceof Number n
                        && n.longValue() >= 0 && n.longValue() <= 1_000_000_000_000L)
                    result.add(new Model(name, n.longValue()));
            }
            return List.copyOf(result);
        }));
    }
    public synchronized CompletableFuture<Optional<Reaction>> analyze(PreparedPhoto photo, Duration timeout) {
        if (uncertainWork()) return CompletableFuture.failedFuture(new Failure(CLEANUP_FAILED));
        return start(timeout, c -> send(c, "/api/status", null).thenCompose(status -> {
            // No image bytes leave this process until cloud-off and local vision metadata are confirmed.
            if (!Boolean.TRUE.equals(Json.get(status, "cloud", "disabled"))) throw denied();
            return send(c, "/api/show", Map.of("model", model));
        }).thenCompose(metadata -> {
            if (!local(metadata) || !"gguf".equals(Json.getString(metadata, "details", "format"))
                    || !"qwen3vl".equals(Json.getString(metadata, "details", "family"))
                    || !(Json.get(metadata, "capabilities") instanceof List<?> caps) || !caps.contains("vision")) throw denied();
            check(c);
            byte[] png = photo.copyPng();
            String encoded;
            try { encoded = Base64.getEncoder().encodeToString(png); } finally { Arrays.fill(png, (byte)0); }
            Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("reaction","observation","tone","safeToShow"), "properties", Map.of(
                    "reaction", Map.of("type","string","maxLength",120),
                    "observation",Map.of("type","string","maxLength",240),
                    "tone",Map.of("type","string","enum",List.of("calm","curious","cheerful")),
                    "safeToShow",Map.of("type","boolean")));
            Object request = Map.of("model",model,"stream",false,"keep_alive",0,"format",schema,
                "options",Map.of("num_predict",256,"num_ctx",4096,"num_batch",32,"temperature",0.6), "messages", List.of(
                    Map.of("role","system","content",Reaction.SYSTEM_PROMPT),
                    Map.of("role","user","content","사진에 대한 짧은 감상을 남겨줘.","images",List.of(encoded))));
            return send(c, "/api/chat", request);
        }).thenApply(reply -> {
            if (!local(reply) || !Boolean.TRUE.equals(Json.get(reply, "done"))
                    || !"assistant".equals(Json.getString(reply, "message", "role"))) throw invalid();
            Object tools = Json.get(reply,"message","tool_calls");
            if (tools != null && (!(tools instanceof List<?> list) || !list.isEmpty())) throw invalid();
            synchronized (LocalOllama.this) { check(c); c.chatFinished = true; }
            try { return Reaction.parse(Json.getString(reply, "message", "content")); }
            catch (Failure failure) { throw new CompletionException(failure); }
        }));
    }
    private static boolean local(Object value) {
        return Json.get(value,"remote_host") == null && Json.get(value,"remote_model") == null;
    }
    private static CompletionException invalid() { return new CompletionException(new Failure(RESPONSE_INVALID)); }
    private static CompletionException denied() { return new CompletionException(new Failure(PATH_DENIED)); }
    private static final class Call<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final long deadline;
        volatile CompletableFuture<?> network;
        volatile LimitedBody body;
        volatile PullBody pullBody;
        ScheduledFuture<?> timer;
        boolean chatSent, chatFinished, pullSent, pullFinished;
        Call(long deadline) { this.deadline = deadline; }
    }
    private synchronized <T> CompletableFuture<T> start(Duration timeout, Function<Call<T>, CompletableFuture<T>> action) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("timeout");
        if (closed || active != null) return CompletableFuture.failedFuture(new Failure(closed ? CANCELLED : BUSY));
        Call<T> call = new Call<>(System.nanoTime() + timeout.toNanos()); active = call;
        call.timer = clock.schedule(() -> finish(call, null, new Failure(TIMEOUT)), timeout.toNanos(), TimeUnit.NANOSECONDS);
        call.result.whenComplete((v,e) -> { if (call.result.isCancelled()) finish(call,null,new Failure(CANCELLED)); });
        io.execute(() -> {
            try { check(call); action.apply(call).whenComplete((value,error) -> finish(call,value,error)); }
            catch (Exception | StackOverflowError error) { finish(call,null,error); }
        });
        return call.result;
    }
    private synchronized void check(Call<?> call) {
        if (active != call || call.result.isDone() || closed) throw new CompletionException(new Failure(CANCELLED));
        if (System.nanoTime() >= call.deadline) throw new CompletionException(new Failure(TIMEOUT));
    }
    private synchronized CompletableFuture<Object> send(Call<?> call, String path, Object request) {
        check(call);
        var builder = HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofNanos(Math.max(1, call.deadline - System.nanoTime()))).header("Accept","application/json");
        if (request == null) builder.GET();
        else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(request),StandardCharsets.UTF_8));
        if (path.equals("/api/chat")) call.chatSent = true;
        var exchange = http.sendAsync(builder.build(), info -> {
            LimitedBody body = new LimitedBody(); call.body = body;
            if (call.result.isDone()) body.abort();
            return body;
        });
        call.network = exchange;
        return exchange.thenApply(response -> {
            check(call);
            if (response.statusCode() != 200) throw new CompletionException(new Failure(WORK_FAILED));
            byte[] bytes = response.body();
            String text = new String(bytes,StandardCharsets.UTF_8);
            if (!Arrays.equals(bytes,text.getBytes(StandardCharsets.UTF_8))) throw invalid();
            int depth = 0; boolean string = false, escape = false;
            for (char c : text.toCharArray()) {
                if (string) { if (escape) escape=false; else if (c=='\\') escape=true; else if(c=='"') string=false; }
                else if(c=='"') string=true;
                else if(c=='{' || c=='[') { if(++depth > 16) throw invalid(); }
                else if(c=='}' || c==']') { if(--depth < 0) throw invalid(); }
            }
            if (string || depth != 0) throw invalid();
            try { return Json.parse(text); } catch (RuntimeException | StackOverflowError error) { throw invalid(); }
        });
    }
    private <T> void finish(Call<T> call, T value, Throwable error) {
        synchronized(this) {
            if (active != call) return;
            if (System.nanoTime() >= call.deadline) error = new Failure(TIMEOUT);
            active = null;
            if (error != null && call.chatSent && !call.chatFinished) uncertainInference = true;
            if (error != null && call.pullSent && !call.pullFinished) uncertainDownload = true;
            if (uncertainInference || uncertainDownload) UNCERTAIN_SERVERS.add(serverKey);
            if (call.timer != null) call.timer.cancel(false);
        }
        // Subscriber cancellation and client callbacks must never execute under our state lock.
        if (call.body != null && error != null) call.body.abort();
        if (call.pullBody != null && error != null) call.pullBody.abort();
        if (call.network != null && error != null) call.network.cancel(true);
        if (error == null) call.result.complete(value);
        else {
            while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
            if (error instanceof HttpTimeoutException) error = new Failure(TIMEOUT);
            call.result.completeExceptionally(error instanceof Failure ? error : new Failure(WORK_FAILED));
        }
    }
    public void cancel() { Call<?> call; synchronized(this) { call=active; } if(call!=null) call.result.cancel(false); }
    int pendingDeadlines() { return clock.getQueue().size(); }
    @Override public void close() {
        synchronized(this) { if (closed) return; closed = true; }
        cancel(); http.shutdownNow(); clock.shutdownNow(); io.shutdown();
    }
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        long start=System.nanoTime();
        if (!http.awaitTermination(timeout)) return false;
        long remaining=Math.max(0,timeout.toNanos()-(System.nanoTime()-start));
        if (!io.awaitTermination(remaining,TimeUnit.NANOSECONDS)) return false;
        return clock.awaitTermination(Math.max(0,timeout.toNanos()-(System.nanoTime()-start)),TimeUnit.NANOSECONDS);
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> value = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final AtomicBoolean ended = new AtomicBoolean();
        volatile Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return value; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription=s; if(ended.get()) s.cancel(); else s.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (ended.get()) return;
            long added=0; for(ByteBuffer b:buffers) added+=b.remaining();
            if(added > 65536-bytes.size()) { abort(); return; }
            for(ByteBuffer buffer:buffers) { byte[] part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
            if(!ended.get() && subscription!=null) subscription.request(1);
        }
        @Override public void onError(Throwable error) { if(ended.compareAndSet(false,true)) value.completeExceptionally(new Failure(WORK_FAILED)); }
        @Override public void onComplete() { if(ended.compareAndSet(false,true)) value.complete(bytes.toByteArray()); }
        void abort() {
            if(ended.compareAndSet(false,true)) { if(subscription!=null) subscription.cancel(); value.completeExceptionally(new Failure(WORK_FAILED)); }
        }
    }
    /** Bounded NDJSON parsing. No server status/error text reaches UI or logs. */
    private static final class PullBody implements HttpResponse.BodySubscriber<Integer> {
        final CompletableFuture<Integer> value=new CompletableFuture<>();
        final ByteArrayOutputStream line=new ByteArrayOutputStream();
        final java.util.function.Consumer<Progress> progress;
        Flow.Subscription subscription;
        volatile boolean ended;
        boolean success, failed;
        int totalBytes, events;
        PullBody(boolean accepted, java.util.function.Consumer<Progress> progress) {
            this.progress=progress;
            if(!accepted) { ended=true; value.completeExceptionally(new Failure(WORK_FAILED)); }
        }
        @Override public CompletionStage<Integer> getBody() { return value; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription=s; if(ended) s.cancel(); else s.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if(ended) return;
            try {
                for(ByteBuffer buffer:buffers) while(buffer.hasRemaining()) {
                    int b=buffer.get()&255;
                    if(++totalBytes>8*1024*1024) throw invalid();
                    if(b==10) readLine();
                    else { if(line.size()>=16384) throw invalid(); line.write(b); }
                }
                if(!ended) subscription.request(1);
            } catch(RuntimeException | StackOverflowError ex) { abort(); }
        }
        private void readLine() {
            if(line.size()==0) return;
            if(++events>65536 || success || failed) throw invalid();
            byte[] bytes=line.toByteArray(); line.reset();
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(!Arrays.equals(bytes,text.getBytes(StandardCharsets.UTF_8))) throw invalid();
            // Progress records are flat. Prevent unbounded parser recursion before Json.parse.
            boolean quoted=false, escape=false; int depth=0;
            for(char ch:text.toCharArray()) {
                if(quoted) { if(escape) escape=false; else if(ch=='\\') escape=true; else if(ch=='"') quoted=false; }
                else if(ch=='"') quoted=true;
                else if(ch=='{' || ch=='[') { if(++depth>2) throw invalid(); }
                else if(ch=='}' || ch==']') { if(--depth<0) throw invalid(); }
            }
            if(quoted || depth!=0) throw invalid();
            Object root=Json.parse(text);
            if(!(root instanceof Map<?,?>)) throw invalid();
            if(Json.get(root,"error") instanceof String) { failed=true; return; }
            String status=Json.getString(root,"status");
            if(status==null || status.length()>256) throw invalid();
            if(status.equals("success")) { success=true; return; }
            Object total=Json.get(root,"total"), completed=Json.get(root,"completed");
            // Ollama omits zero-valued counters (including the first completed=0).
            long t=counter(total), c=counter(completed);
            if(t>0) { if(c>t) throw invalid(); progress.accept(new Progress(c,t)); }
        }
        private static long counter(Object value) {
            if(value==null) return 0;
            if(!(value instanceof Number number) || number.doubleValue()!=number.longValue()
                    || number.longValue()<0 || number.longValue()>100_000_000_000L) throw invalid();
            return number.longValue();
        }
        @Override public void onComplete() {
            if(ended) return;
            try { readLine(); ended=true; value.complete(success?1:failed?-1:0); }
            catch(RuntimeException | StackOverflowError ex) { abort(); }
        }
        @Override public void onError(Throwable error) { abort(); }
        void abort() {
            if(!ended) { ended=true; if(subscription!=null) subscription.cancel(); value.completeExceptionally(new Failure(WORK_FAILED)); }
        }
    }
}
