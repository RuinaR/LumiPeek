package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.Json;
import com.sun.net.httpserver.*;
import io.lumipeek.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.lumipeek.core.Failure.Code.*;

/** Private loopback fake only. Never contacts an installed Ollama or loads a model. */
public final class OllamaTests {
    private static int passed;
    private static final Duration TWO=Duration.ofSeconds(2);
    private static final String MODEL="qwen3-vl:4b-instruct";
    private OllamaTests() {}
    private static void check(boolean value) { if(!value) throw new AssertionError("Ollama check failed"); passed++; }
    private static void failure(CompletableFuture<?> future, Failure.Code code) throws Exception {
        try { future.get(4,TimeUnit.SECONDS); throw new AssertionError("expected "+code); }
        catch(ExecutionException ex) { check(ex.getCause() instanceof Failure f && f.code()==code && f.getCause()==null); }
    }
    private static final class Fake implements AutoCloseable {
        final HttpServer server;
        final ExecutorService threads=Executors.newFixedThreadPool(2);
        final AtomicInteger chats=new AtomicInteger(), unexpected=new AtomicInteger();
        volatile int mode;
        volatile Object request;
        volatile CountDownLatch entered=new CountDownLatch(1);
        Fake() throws IOException {
            server=HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0),0);
            server.setExecutor(threads); server.createContext("/",this::handle); server.start();
        }
        URI endpoint() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }
        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String path=exchange.getRequestURI().getPath(); Object response;
                int selected=mode;
                if (path.equals("/api/version")) response=Map.of("version","0.12.7-test");
                else if(path.equals("/api/tags")) response=Map.of("models",List.of(
                    Map.of("name",MODEL,"size",3_300_000_000L),
                    Map.of("name","qwen3-vl:8b-instruct","size",1,"remote_host","https://example.invalid")));
                else if(path.equals("/api/status")) response=Map.of("cloud",Map.of("disabled",selected!=1));
                else if(path.equals("/api/show")) {
                    exchange.getRequestBody().readNBytes(1024);
                    if(selected==2) response=Map.of("remote_host","https://example.invalid");
                    else if(selected==3) {
                        exchange.getResponseHeaders().add("Location",endpoint()+"/redirect-target");
                        exchange.sendResponseHeaders(302,-1); return;
                    } else if(selected==4) {
                        byte[] large=new byte[65537]; Arrays.fill(large,(byte)' ');
                        exchange.sendResponseHeaders(200,large.length); exchange.getResponseBody().write(large); return;
                    } else response=Map.of("details",Map.of("format","gguf","family","qwen3vl"),"capabilities",List.of("vision"));
                } else if(path.equals("/api/chat")) {
                    request=Json.parse(new String(exchange.getRequestBody().readNBytes(7*1024*1024),StandardCharsets.UTF_8));
                    chats.incrementAndGet(); entered.countDown();
                    if(selected==5 || selected==6) try { Thread.sleep(500); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
                    String content=selected==7 ? "malformed" : Json.write(Map.of(
                        "reaction","귀여운 사진이네","observation","작은 그림","tone","calm","safeToShow",selected!=8));
                    response=Map.of("done",true,"message",Map.of("role","assistant","content",content));
                } else { unexpected.incrementAndGet(); exchange.sendResponseHeaders(404,-1); return; }
                byte[] body=Json.write(response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
            } catch(IOException ignored) { /* cancellation closes only this synthetic connection */ }
        }
        @Override public void close() { server.stop(0); threads.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        for(String denied:List.of("http://example.invalid:11434","http://localhost:11434","https://127.0.0.1:11434",
                "http://127.0.0.1:11434/path","http://user@127.0.0.1:11434","http://127.0.0.1:11434/?x=1")) {
            try { new LocalOllama(URI.create(denied),MODEL); throw new AssertionError("remote endpoint accepted"); }
            catch(IllegalArgumentException expected) { passed++; }
        }
        try(Fake fake=new Fake(); PreparedPhoto photo=SyntheticPhoto.create()) {
            LocalOllama client=new LocalOllama(fake.endpoint(),MODEL);
            try(client) {
                check(client.version(TWO).get(4,TimeUnit.SECONDS).equals("0.12.7-test"));
                check(client.pendingDeadlines()==0);
                check(client.models(TWO).get(4,TimeUnit.SECONDS).equals(List.of(new LocalOllama.Model(MODEL,3_300_000_000L))));
                var result=client.analyze(photo,TWO).get(4,TimeUnit.SECONDS);
                check(result.orElseThrow().reaction().equals("귀여운 사진이네"));
                check(Boolean.FALSE.equals(Json.get(fake.request,"stream")) && ((Number)Json.get(fake.request,"keep_alive")).intValue()==0);
                check(Json.get(fake.request,"tools")==null && Json.get(fake.request,"messages",1,"images") instanceof List<?>);
                fake.mode=1; int before=fake.chats.get(); failure(client.analyze(photo,TWO),PATH_DENIED); check(fake.chats.get()==before);
                fake.mode=2; failure(client.analyze(photo,TWO),PATH_DENIED); check(fake.chats.get()==before);
                fake.mode=3; failure(client.analyze(photo,TWO),WORK_FAILED); check(fake.unexpected.get()==0);
                fake.mode=4; failure(client.analyze(photo,TWO),WORK_FAILED); check(fake.chats.get()==before);
                fake.mode=7; failure(client.analyze(photo,TWO),RESPONSE_INVALID);
                fake.mode=8; check(client.analyze(photo,TWO).get(4,TimeUnit.SECONDS).isEmpty());
                fake.mode=0;
                try(PhotoFlow flow=new PhotoFlow(Duration.ofMinutes(3))) {
                    PreparedPhoto selected=SyntheticPhoto.create(); UUID imageId=UUID.randomUUID();
                    long token=flow.select(imageId,0); flow.prepared(token,selected);
                    client.analyze(selected,TWO).thenAccept(reply -> flow.analyzed(token,reply)).get(4,TimeUnit.SECONDS);
                    var clear=new PhotoFlow.Observation(false,false,false,true);
                    var ready=flow.tick(Duration.ofSeconds(1).toNanos(),clear);
                    check(ready.photoId().equals(imageId) && ready.reactionEligible());
                    check(flow.shown(token,Duration.ofSeconds(1).toNanos(),clear));
                    check(flow.tick(Duration.ofSeconds(7).toNanos(),clear).end()==PhotoFlow.End.COMPLETE);
                }
                fake.mode=6; fake.entered=new CountDownLatch(1);
                var pending=client.analyze(photo,TWO); check(fake.entered.await(2,TimeUnit.SECONDS));
                failure(client.version(TWO),BUSY); client.cancel();
                check(pending.isCancelled()); failure(client.analyze(photo,TWO),CLEANUP_FAILED);
                check(client.version(TWO).get(4,TimeUnit.SECONDS).equals("0.12.7-test"));
            }
            check(client.awaitStopped(Duration.ofSeconds(3)));
            // A new client must not reset the first server's uncertain-work latch.
            // Use a separate synthetic server to exercise an independent timeout.
            try(Fake timedServer=new Fake()) {
                timedServer.mode=5;
                LocalOllama timed=new LocalOllama(timedServer.endpoint(),MODEL);
                try(timed) { failure(timed.analyze(photo,Duration.ofMillis(200)),TIMEOUT); }
                check(timed.awaitStopped(Duration.ofSeconds(3)));
            }
            check(fake.unexpected.get()==0);
        }
        System.out.println("PASS: loopback fake Ollama "+passed+" assertions (no real model)");
    }
}
