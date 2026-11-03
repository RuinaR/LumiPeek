package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.*;
import com.sun.net.httpserver.*;
import io.lumipeek.core.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Project-owned loopback server, synthetic frames and official fake SDK. No real apps or model. */
public final class FeatureTests {
    private static int passed;
    private static final String TAG="qwen3-vl:4b-instruct";
    private static void check(boolean value) { if(!value) throw new AssertionError("Feature check failed #"+(passed+1)); passed++; }
    private static void waitFor(BooleanSupplier value) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(6).toNanos();
        while(!value.getAsBoolean()) { if(System.nanoTime()>deadline) throw new AssertionError("Feature wait expired"); Thread.sleep(10); }
    }
    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor=Executors.newFixedThreadPool(2);
        final AtomicInteger pulls=new AtomicInteger(), chats=new AtomicInteger(), unknown=new AtomicInteger();
        volatile int mode;
        volatile CountDownLatch entered=new CountDownLatch(1);
        Server() throws IOException {
            server=HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0),0);
            server.setExecutor(executor); server.createContext("/",this::handle); server.start();
        }
        URI endpoint() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()); }
        void handle(HttpExchange exchange) {
            try(exchange) {
                String path=exchange.getRequestURI().getPath(); Object reply;
                if(path.equals("/api/version")) reply=Map.of("version","fake-only");
                else if(path.equals("/api/tags")) reply=Map.of("models",List.of(Map.of("name",TAG,"size",3_300_000_000L)));
                else if(path.equals("/api/status")) reply=Map.of("cloud",Map.of("disabled",mode!=4));
                else if(path.equals("/api/show")) reply=Map.of("details",Map.of("format","gguf","family","qwen3vl"),"capabilities",List.of("vision"));
                else if(path.equals("/api/pull")) {
                    pulls.incrementAndGet();
                    Object request=Json.parse(new String(exchange.getRequestBody().readNBytes(2048),StandardCharsets.UTF_8));
                    if(!TAG.equals(Json.getString(request,"model")) || !Boolean.TRUE.equals(Json.get(request,"stream"))) { unknown.incrementAndGet(); return; }
                    int selected=mode; entered.countDown();
                    if(selected==2) Thread.sleep(400);
                    String stream=selected==1?"{\"error\":\"synthetic failure\"}\n":selected==3?"{\"status\":\"pulling\",\"total\":4,\"completed\":8}\n":
                        selected==5?" ".repeat(16385):"{\"status\":\"pulling\",\"total\":100,\"completed\":20}\n{\"status\":\"success\"}\n";
                    if(selected==6) stream="{\"status\":\"pulling\",\"total\":100}\n{\"status\":\"success\"}\n";
                    exchange.sendResponseHeaders(200,0);
                    byte[] bytes=stream.getBytes(StandardCharsets.UTF_8);
                    // Split records across buffers; the parser must not assume line-aligned delivery.
                    for(int i=0;i<bytes.length;i+=7) { exchange.getResponseBody().write(bytes,i,Math.min(7,bytes.length-i)); }
                    return;
                } else if(path.equals("/api/chat")) {
                    chats.incrementAndGet(); exchange.getRequestBody().readNBytes(7*1024*1024); entered.countDown();
                    if(mode==2) Thread.sleep(400);
                    reply=Map.of("done",true,"message",Map.of("role","assistant","content",Json.write(Map.of("reaction","포근한 느낌이네","observation","작은 그림","tone","calm","safeToShow",true))));
                } else { unknown.incrementAndGet(); exchange.sendResponseHeaders(404,-1); return; }
                byte[] bytes=Json.write(reply).getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            } catch(IOException ex) { /* own canceled connection */ }
            catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
    private static final class Photos implements PhotoSource {
        boolean closed, recursive;
        int prepares, cancels;
        CompletableFuture<PreparedPhoto> delayed;
        final Path path=Path.of("synthetic.png");
        @Override public CompletableFuture<Folder> grant(Path ignored) {
            return CompletableFuture.completedFuture(new Folder() {
                @Override public CompletableFuture<List<Path>> discover(boolean nested) { recursive=nested; return CompletableFuture.completedFuture(List.of(path)); }
                @Override public CompletableFuture<PreparedPhoto> prepare(Path ignoredPhoto) {
                    prepares++;
                    if(delayed!=null) return delayed;
                    try { return CompletableFuture.completedFuture(SyntheticPhoto.create()); }
                    catch(Exception error) { return CompletableFuture.failedFuture(error); }
                }
            });
        }
        @Override public void cancel() { cancels++; }
        @Override public void close() { closed=true; }
    }
    private static final class Preview implements PhotoSession.Preview {
        PreparedPhoto photo;
        int shows, hides;
        boolean closed;
        @Override public void show(PreparedPhoto image, boolean dots, int pixels) { photo=image; shows++; }
        @Override public void hide() { hides++; }
        @Override public void close() { closed=true; }
    }
    private static MascotState actor() { return new MascotState(73,"Synthetic",new Rectangle(200,200,80,100),new Point(240,300),true,""); }
    private static void pullTests(Server server) throws Exception {
        LocalOllama ai=new LocalOllama(server.endpoint(),TAG);
        try(ai) {
            try { ai.pull(TAG,false,p -> {}).get(); throw new AssertionError("missing consent"); } catch(ExecutionException expected) { check(server.pulls.get()==0); }
            List<LocalOllama.Progress> progress=new CopyOnWriteArrayList<>();
            ai.pull(TAG,true,progress::add).get(4,TimeUnit.SECONDS);
            check(progress.equals(List.of(new LocalOllama.Progress(20,100)))); check(!ai.uncertainWork());
            server.mode=1;
            try { ai.pull(TAG,true,p -> {}).get(4,TimeUnit.SECONDS); throw new AssertionError("error accepted"); } catch(ExecutionException expected) { check(!ai.uncertainWork()); }
            server.mode=0; ai.pull(TAG,true,p -> {}).get(4,TimeUnit.SECONDS); check(!ai.uncertainWork());
            server.mode=6; progress.clear(); ai.pull(TAG,true,progress::add).get(4,TimeUnit.SECONDS);
            check(progress.equals(List.of(new LocalOllama.Progress(0,100))));
            server.mode=4; int before=server.pulls.get();
            try { ai.pull(TAG,true,p -> {}).get(4,TimeUnit.SECONDS); throw new AssertionError("cloud on"); } catch(ExecutionException expected) { check(server.pulls.get()==before); }
            server.mode=2; server.entered=new CountDownLatch(1); var pending=ai.pull(TAG,true,p -> {});
            check(server.entered.await(2,TimeUnit.SECONDS)); ai.cancel(); check(pending.isCancelled()); check(ai.uncertainWork());
            try { ai.selectModel("qwen3-vl:2b-instruct"); throw new AssertionError("uncertain bypass"); } catch(IllegalStateException expected) { passed++; }
        }
        check(ai.awaitStopped(Duration.ofSeconds(3)));
        LocalOllama replacement=new LocalOllama(server.endpoint(),TAG);
        try(replacement; PreparedPhoto photo=SyntheticPhoto.create()) {
            check(replacement.uncertainWork()); int chats=server.chats.get();
            try { replacement.analyze(photo,Duration.ofSeconds(1)).get(); throw new AssertionError("new client bypass"); }
            catch(ExecutionException expected) { check(expected.getCause() instanceof Failure f && f.code()==Failure.Code.CLEANUP_FAILED && server.chats.get()==chats); }
        }
        check(replacement.awaitStopped(Duration.ofSeconds(3)));
        for(int mode:new int[]{3,5}) {
            try(Server malformed=new Server()) {
                malformed.mode=mode; LocalOllama bad=new LocalOllama(malformed.endpoint(),TAG);
                try(bad) { try { bad.pull(TAG,true,p -> {}).get(4,TimeUnit.SECONDS); throw new AssertionError("bad stream accepted"); } catch(ExecutionException expected) { check(bad.uncertainWork()); } }
                check(bad.awaitStopped(Duration.ofSeconds(3)));
            }
        }
        server.mode=0;
    }
    private static void sessionTests(Server server) throws Exception {
        try(PluginTestKit kit=PluginTestKit.start("lumipeek.feature.test")) {
            kit.mascots(actor()); Photos photos=new Photos(); Preview preview=new Preview(); AtomicLong clock=new AtomicLong();
            LocalOllama ai=new LocalOllama(server.endpoint(),TAG);
            PhotoSession session=new PhotoSession(kit.context(),photos,ai,preview,PhotoSettings.defaults(),clock::get);
            try(session) {
                check(!session.status().automatic() && !session.status().paused());
                session.addFolder(Path.of("synthetic")); check(session.status().folders().size()==1);
                session.connect(); waitFor(() -> !session.status().busy()); check(session.status().models().size()==1);
                session.configure(new PhotoSettings(true,5,180,true,TAG));
                kit.quiet(true); session.request(null,null); check(photos.prepares==0); kit.quiet(false);
                kit.bubbleVisible(true); session.request(null,null); check(photos.prepares==0); kit.bubbleVisible(false);
                session.pause(true); session.request(null,null); check(photos.prepares==0); session.pause(false);
                session.request(73,"Synthetic"); waitFor(() -> session.status().flow().state()==PhotoFlow.State.REACTION_SHOWN);
                check(photos.prepares==1 && photos.recursive && preview.shows>0);
                PreparedPhoto selected=preview.photo; check(kit.calls("say").size()==1);
                check(session.status().flow().reaction().equals("포근한 느낌이네"));
                session.scheduleTick(); check(preview.photo==selected);
                clock.set(Duration.ofSeconds(7).toNanos()); session.scheduleTick(); check(!session.status().busy());
                try { selected.copyRgb(); throw new AssertionError("photo leak"); } catch(IllegalStateException expected) { passed++; }
                session.request(null,null); waitFor(() -> !session.status().busy()); check(kit.calls("say").size()==1); // dedupe
                int count=photos.prepares; session.automatic(true); clock.addAndGet(Duration.ofMinutes(5).toNanos()); session.scheduleTick();
                waitFor(() -> !session.status().busy()); check(photos.prepares==count+1);
                session.removeFolder(session.status().folders().getFirst().id()); check(session.status().folders().isEmpty());
                check(kit.calls("showBusy").isEmpty() && kit.calls("hideBubble").isEmpty() && kit.hotkeys().isEmpty());
            }
            check(photos.closed && preview.closed); check(ai.awaitStopped(Duration.ofSeconds(3)));
        }
        try(PluginTestKit kit=PluginTestKit.start("lumipeek.cancel.test")) {
            kit.mascots(actor()); Photos photos=new Photos(); Preview preview=new Preview(); AtomicLong clock=new AtomicLong();
            LocalOllama ai=new LocalOllama(server.endpoint(),TAG);
            PhotoSession session=new PhotoSession(kit.context(),photos,ai,preview,PhotoSettings.defaults(),clock::get);
            try(session) {
                session.addFolder(Path.of("synthetic")); session.connect(); waitFor(() -> !session.status().busy());
                photos.delayed=new CompletableFuture<>(); session.request(null,null); check(session.status().busy());
                session.interaction(); check(!session.status().busy() && preview.hides>0);
                PreparedPhoto late=SyntheticPhoto.create(); check(!photos.delayed.complete(late)); late.close();
                photos.delayed=new CompletableFuture<>(); session.request(null,null); kit.mascots(List.of()); session.scheduleTick();
                check(session.status().flow().end()==PhotoFlow.End.REMOVED && !session.status().busy());
                kit.mascots(actor()); photos.delayed=new CompletableFuture<>(); session.request(null,null);
                clock.set(Duration.ofMinutes(4).toNanos()); session.scheduleTick(); check(session.status().flow().end()==PhotoFlow.End.TIMEOUT);
                photos.delayed=new CompletableFuture<>() { @Override public boolean cancel(boolean interrupt) { return false; } };
                session.request(null,null); session.cancel(); PreparedPhoto stale=SyntheticPhoto.create(); photos.delayed.complete(stale);
                try { stale.copyRgb(); throw new AssertionError("stale preparation leak"); } catch(IllegalStateException expected) { passed++; }
                photos.delayed=new CompletableFuture<>() { @Override public boolean cancel(boolean interrupt) { return false; } };
                session.request(null,null);
            }
            PreparedPhoto stopped=SyntheticPhoto.create(); photos.delayed.complete(stopped);
            try { stopped.copyRgb(); throw new AssertionError("stopped preparation leak"); } catch(IllegalStateException expected) { passed++; }
            check(ai.awaitStopped(Duration.ofSeconds(3)));
        }
        try(PluginTestKit kit=PluginTestKit.start("lumipeek.speech.test")) {
            kit.mascots(actor()); Photos photos=new Photos(); Preview preview=new Preview(); AtomicLong clock=new AtomicLong();
            LocalOllama ai=new LocalOllama(server.endpoint(),TAG);
            try(PhotoSession session=new PhotoSession(kit.context(),photos,ai,preview,PhotoSettings.defaults(),clock::get)) {
                session.addFolder(Path.of("synthetic")); session.connect(); waitFor(() -> !session.status().busy());
                server.mode=2; server.entered=new CountDownLatch(1); session.request(null,null); check(server.entered.await(2,TimeUnit.SECONDS));
                kit.bubbleVisible(true); waitFor(() -> session.status().flow().state()==PhotoFlow.State.REACTION_READY);
                check(kit.calls("say").isEmpty()); session.scheduleTick(); check(!session.status().flow().visible());
                kit.bubbleVisible(false); session.scheduleTick(); check(kit.calls("say").size()==1);
                session.interaction(); check(session.status().flow().end()==PhotoFlow.End.SUPPRESSED);
            }
            check(ai.awaitStopped(Duration.ofSeconds(3))); server.mode=0;
        }
    }
    private static void lifecycleAndUi(Server server) throws Exception {
        try(PluginTestKit kit=PluginTestKit.start("lumipeek.plugin.test")) {
            kit.mascots(actor()); Photos photos=new Photos(); List<LocalOllama> clients=new ArrayList<>();
            LumiPeekPlugin plugin=new LumiPeekPlugin(() -> photos, tag -> { var value=new LocalOllama(server.endpoint(),tag); clients.add(value); return value; },System::nanoTime);
            int before=server.chats.get(); plugin.start(kit.context()); check(kit.menuRows().size()==5); check(server.chats.get()==before);
            plugin.start(kit.context()); check(kit.menuRows().size()==5); plugin.stop(); plugin.stop();
            check(kit.menuRows().stream().allMatch(PluginUi.MenuHandle::removed)); check(photos.closed);
            kit.tick(1000); kit.publish("click"); check(kit.calls("say").isEmpty());
            plugin.start(kit.context()); plugin.stop(); check(clients.size()==2);
            for(LocalOllama client:clients) check(client.awaitStopped(Duration.ofSeconds(3)));
        }
        try(PluginTestKit kit=PluginTestKit.start("lumipeek.ui.test")) {
            LocalOllama ai=new LocalOllama(server.endpoint(),TAG); PhotoPreview preview=new PhotoPreview();
            try(PhotoSession session=new PhotoSession(kit.context(),new Photos(),ai,preview,PhotoSettings.defaults(),System::nanoTime)) {
                AtomicReference<Throwable> problem=new AtomicReference<>();
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    try {
                        SettingsPanel panel=new SettingsPanel(kit.context(),session,preview);
                        check(!panel.automatic.isSelected() && !panel.preview.isSelected());
                        panel.paused.doClick(); check(session.status().paused()); panel.paused.doClick();
                        panel.interval.setValue(30); check(session.status().settings().intervalMinutes()==30);
                        panel.addFolder(Path.of("synthetic")); check(panel.folderModel.size()==1);
                        panel.folders.setSelectedIndex(0); panel.remove.doClick(); check(panel.folderModel.isEmpty());
                        try { preview.createDesktop(); throw new AssertionError("unverified desktop enabled"); } catch(IllegalStateException expected) { passed++; }
                        try(PreparedPhoto image=SyntheticPhoto.create()) {
                            preview.enabled(true); preview.show(image,true,144); preview.inline.setSize(196,220);
                            BufferedImage canvas=new BufferedImage(196,220,BufferedImage.TYPE_INT_RGB); Graphics2D graphics=canvas.createGraphics();
                            try { preview.inline.paint(graphics); } finally { graphics.dispose(); }
                            check(preview.inline.isVisible() && preview.inline.photoSize().width>0);
                            preview.hide(); check(!preview.inline.isVisible() && preview.inline.photoSize().width==0); canvas.flush();
                        }
                        PhotoSettings saved=new PhotoSettings(true,30,180,true,TAG); saved.save(kit.context().prefs());
                        check(PhotoSettings.load(kit.context().prefs()).equals(saved));
                        kit.context().prefs().set("interval","-1"); check(PhotoSettings.load(kit.context().prefs()).equals(PhotoSettings.defaults()));
                    } catch(Throwable ex) { problem.set(ex); }
                });
                if(problem.get()!=null) throw new AssertionError("Headless UI",problem.get());
            }
            check(ai.awaitStopped(Duration.ofSeconds(3)));
        }
        check(!SdkCompatibility.BUSY_OWNERSHIP_VERIFIED && !SdkCompatibility.DESKTOP_PREVIEW_VERIFIED);
        check(ModelCatalog.ENTRIES.size()==3 && ModelCatalog.require(TAG).consentText().contains("Apache-2.0"));
        try { ModelCatalog.require("https://example.invalid/model"); throw new AssertionError("arbitrary model"); } catch(IllegalArgumentException expected) { passed++; }
    }
    private static void realWorker(Server server) throws Exception {
        // Simulated protected-path layout only. The SDK engine/consent copier is never launched.
        Path root=Files.createTempDirectory("lumipeek-feature-synthetic-");
        Path image=root.resolve("synthetic.png");
        BufferedImage pixels=new BufferedImage(32,24,BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(10,10,0xff5577);
        javax.imageio.ImageIO.write(pixels,"png",image.toFile()); pixels.flush(); byte[] original=Files.readAllBytes(image);
        try(PluginTestKit kit=PluginTestKit.start("lumipeek.worker.integration")) {
            kit.mascots(actor()); LocalOllama ai=new LocalOllama(server.endpoint(),TAG); Preview preview=new Preview();
            try(PhotoSession session=new PhotoSession(kit.context(),PhotoSource.production(),ai,preview,PhotoSettings.defaults(),System::nanoTime)) {
                session.addFolder(root); waitFor(() -> !session.status().busy()); check(session.status().folders().size()==1);
                session.connect(); waitFor(() -> !session.status().busy());
                session.request(73,"Synthetic"); waitFor(() -> session.status().flow().state()==PhotoFlow.State.REACTION_SHOWN || !session.status().busy());
                check(session.status().flow().state()==PhotoFlow.State.REACTION_SHOWN && preview.shows>0);
                check(kit.calls("say").size()==1); check(Arrays.equals(original,Files.readAllBytes(image)));
                session.cancel(); check(!session.status().busy());
            }
            check(ai.awaitStopped(Duration.ofSeconds(3)));
        } finally { Files.deleteIfExists(image); Files.deleteIfExists(root); Arrays.fill(original,(byte)0); }
        System.out.println("PASS: packaged production worker -> fake Ollama -> fake SDK; real SDK protected loading NOT VERIFIED");
    }
    public static void main(String[] args) throws Exception {
        check(GraphicsEnvironment.isHeadless());
        try(Server server=new Server()) { pullTests(server); check(server.unknown.get()==0); }
        try(Server server=new Server()) { sessionTests(server); lifecycleAndUi(server); if(args.length>0 && args[0].equals("worker")) realWorker(server); check(server.unknown.get()==0); }
        System.out.println("PASS: SDK 1.5.0 feature integration / consent / streaming / headless widgets "+passed+" assertions");
    }
}
