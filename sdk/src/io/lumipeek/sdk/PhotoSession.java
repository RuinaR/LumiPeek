package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.*;
import io.lumipeek.core.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One session owns the worker, HTTP client, photo flow and UI updates. No host feature is changed. */
final class PhotoSession implements AutoCloseable {
    record Folder(UUID id, String label, PhotoSource.Folder access) { @Override public String toString() { return label; } }
    record Status(String message, boolean busy, boolean paused, boolean automatic, List<Folder> folders,
                  List<LocalOllama.Model> models, PhotoSettings settings, PhotoFlow.View flow, boolean blocked) {}
    interface Preview {
        void show(PreparedPhoto photo, boolean dots, int pixels);
        void hide();
        void close();
        default void follow(MascotState actor, List<java.awt.Rectangle> screens, double elapsed) { }
    }
    private final PluginContext context;
    private final SdkCompatibility sdk;
    private final PhotoSource source;
    private final LocalOllama ai;
    private final Preview preview;
    private final LongSupplier now;
    private final PhotoFlow flow=new PhotoFlow(Duration.ofMinutes(3));
    private final List<Folder> folders=new ArrayList<>();
    private final Set<UUID> seen=new HashSet<>();
    private final Set<PreparedPhoto> incoming=new HashSet<>();
    private final PhotoIdentity identity=new PhotoIdentity();
    private PhotoSettings settings;
    private List<LocalOllama.Model> models=List.of();
    private Consumer<Status> listener=s -> {};
    private CompletableFuture<?> pending;
    private PreparedPhoto photo;
    private int target, folderIndex;
    private String imageSet="", message="폴더를 추가하고 AI 연결을 확인해 주세요.";
    private long epoch, nextAuto, lastFrame;
    private LocalOllama.Progress latestProgress;
    private boolean progressQueued;
    private boolean closed, busy, viewing, paused, automatic, tickQueued, speechSending, blocked;
    PhotoSession(PluginContext context, PhotoSource source, LocalOllama ai, Preview preview, PhotoSettings settings, LongSupplier now) {
        this.context=context; sdk=new SdkCompatibility(context); this.source=source; this.ai=ai; this.preview=preview; this.settings=settings; this.now=now;
    }
    synchronized void listen(Consumer<Status> update) { listener=update; emit(); }
    synchronized Status status() {
        return new Status(message,busy,paused,automatic,List.copyOf(folders),models,settings,flow.tick(now.getAsLong(),sdk.observe(target,imageSet)),blocked || ai.uncertainWork());
    }
    private void emit() { if(!closed) listener.accept(status()); }
    private boolean available() { return !closed && !busy && !blocked && !ai.uncertainWork(); }
    synchronized void configure(PhotoSettings value) {
        if(!available()) { message="작업 종료 후 설정을 변경해 주세요."; emit(); return; }
        try { ai.selectModel(value.model()); settings=value; context.offEdt(() -> value.save(context.prefs())); message="설정을 저장했습니다."; }
        catch(RuntimeException ex) { message="설정을 적용할 수 없습니다."; }
        nextAuto=now.getAsLong()+interval(); emit();
    }
    synchronized void addFolder(Path root) {
        if(!available() || folders.size()>=16) return;
        busy=true; long request=++epoch; message="폴더 접근 권한을 검사하고 있습니다."; emit();
        try {
            CompletableFuture<PhotoSource.Folder> granting=source.grant(root); pending=granting;
            granting.whenComplete((grant,error) -> post(() -> {
                if(!valid(request)) return;
                busy=false; pending=null;
                if(error!=null) fail(error); else { folders.add(new Folder(UUID.randomUUID(),root.toString(),grant)); message="폴더를 등록했습니다. 경로는 이번 세션에만 유지됩니다."; }
                emit();
            }));
        } catch(Failure ex) { busy=false; fail(ex); emit(); }
    }
    synchronized void removeFolder(UUID id) { cancel(); folders.removeIf(f -> f.id().equals(id)); emit(); }
    synchronized void automatic(boolean enabled) { automatic=enabled; nextAuto=now.getAsLong()+interval(); emit(); }
    synchronized void pause(boolean value) { paused=value; if(value) cancel(); nextAuto=now.getAsLong()+interval(); emit(); }
    private long interval() { return Duration.ofMinutes(settings.intervalMinutes()).toNanos(); }
    synchronized void connect() {
        if(!available()) return;
        busy=true; long request=++epoch; message="로컬 AI 연결 확인 중"; emit();
        var version=ai.version(Duration.ofSeconds(5)); pending=version;
        version.whenComplete((v,error) -> post(() -> {
            if(!valid(request)) return;
            if(error!=null) { busy=false; pending=null; fail(error); emit(); return; }
            var list=ai.models(Duration.ofSeconds(5)); pending=list;
            list.whenComplete((installed,problem) -> post(() -> {
                if(!valid(request)) return;
                busy=false; pending=null;
                if(problem!=null) fail(problem); else { models=installed; message="로컬 Ollama 연결됨 · 지원 모델 "+installed.size()+"개"; }
                emit();
            }));
        }));
    }
    synchronized void download(String tag, boolean consent) {
        if(!consent || !available()) return;
        busy=true; long request=++epoch; message="공식 모델 다운로드 준비 중"; emit();
        var download=ai.pull(tag,true,progress -> progress(request,progress)); pending=download;
        download.whenComplete((v,error) -> post(() -> {
            if(!valid(request)) return;
            busy=false; pending=null;
            if(error!=null) fail(error); else message="다운로드 완료 · 연결 확인으로 설치 목록을 갱신하세요.";
            emit();
        }));
    }
    private synchronized void progress(long request, LocalOllama.Progress value) {
        if(!valid(request)) return;
        latestProgress=value;
        if(progressQueued) return; progressQueued=true;
        post(() -> {
            progressQueued=false; LocalOllama.Progress current=latestProgress; latestProgress=null;
            if(valid(request) && current!=null) { message="모델 다운로드 "+(int)(100.0*current.completed()/current.total())+"% · 현재 레이어"; emit(); }
        });
    }
    synchronized void request(Integer mascotId, String set) {
        if(!available() || paused) return;
        MascotState actor=mascotId==null ? context.mascots().stream().findFirst().orElse(null) : context.mascotById(mascotId);
        if(actor==null || (set!=null && !actor.imageSet().equals(set))) { message="사진을 볼 꼬미가 없습니다."; emit(); return; }
        var guard=sdk.observe(actor.id(),actor.imageSet());
        if(guard.quiet() || guard.hidden() || guard.bubbleVisible() || context.queueLength()!=0) { message="기존 대화와 조용한 상태를 우선합니다."; emit(); return; }
        if(folders.isEmpty() || models.stream().noneMatch(m -> m.name().equals(settings.model()))) { message="폴더와 설치된 모델을 먼저 선택해 주세요."; emit(); return; }
        target=actor.id(); imageSet=actor.imageSet(); busy=true; viewing=true; long request=++epoch;
        long token=flow.select(UUID.randomUUID(),now.getAsLong()); message="안전한 사진을 찾고 있습니다."; emit();
        Folder folder=folders.get(Math.floorMod(folderIndex++,folders.size()));
        try {
            var discovery=folder.access().discover(settings.recursive()); pending=discovery;
            discovery.whenComplete((paths,error) -> post(() -> {
                if(!valid(request)) return;
                if(error!=null || paths.isEmpty()) { endFailure(token,error); return; }
                Path path=paths.get(ThreadLocalRandom.current().nextInt(paths.size()));
                try {
                    var preparation=folder.access().prepare(path); pending=preparation;
                    preparation.whenComplete((image,problem) -> postPhoto(request,token,image,problem));
                } catch(Failure failure) { endFailure(token,failure); }
            }));
        } catch(Failure error) { endFailure(token,error); }
    }
    private synchronized void postPhoto(long request, long token, PreparedPhoto image, Throwable error) {
        if(!valid(request)) { if(image!=null) image.close(); return; }
        if(image!=null) incoming.add(image);
        post(() -> {
            incoming.remove(image);
            if(!valid(request)) { if(image!=null) image.close(); return; }
            if(error!=null) { endFailure(token,error); return; }
            byte[] bytes=image.copyPng(); UUID id;
            try { id=identity.identify(bytes); } finally { Arrays.fill(bytes,(byte)0); }
            if(seen.contains(id)) { image.close(); flow.cancel(); complete("최근에 본 사진은 건너뛰었습니다."); return; }
            if(seen.size()>=2048) seen.clear(); seen.add(id);
            photo=image; flow.prepared(token,image); message="사진 분석 중";
            refresh();
            if(!valid(request)) return;
            var analysis=ai.analyze(image,Duration.ofSeconds(150)); pending=analysis;
            analysis.whenComplete((reaction,problem) -> post(() -> {
                if(!valid(request)) return;
                pending=null;
                if(problem!=null) { endFailure(token,problem); return; }
                flow.analyzed(token,reaction); message=reaction.isPresent()?"감상 표시를 기다리고 있습니다.":"이번 사진의 감상은 생략했습니다.";
                refresh();
            }));
        });
    }
    synchronized void scheduleTick() {
        if(closed || tickQueued) return;
        tickQueued=true; context.onEdt(() -> { synchronized(PhotoSession.this) { tickQueued=false; if(!closed) refresh(); } });
    }
    private void refresh() {
        var observed=sdk.observe(target,imageSet); long time=now.getAsLong();
        var view=flow.tick(time,observed);
        if(view.state()==PhotoFlow.State.FINISHED && viewing) { cancelResources(); complete("사진 감상 종료 · "+view.end()); return; }
        if(view.visible() && photo!=null) {
            preview.show(photo,settings.thinking() && view.localDots(),settings.previewPixels());
            MascotState actor=context.mascotById(target);
            if(actor!=null && actor.imageSet().equals(imageSet)) preview.follow(actor,context.screens(),Math.max(0,(time-lastFrame)/1_000_000_000.0));
        } else preview.hide();
        lastFrame=time;
        if(view.reactionEligible()) {
            var fresh=sdk.observe(target,imageSet);
            if(flow.shown(view.generation(),time,fresh)) {
                speechSending=true;
                try {
                    if(!sdk.sayIfClear(target,imageSet,view.reaction())) { flow.otherSpeech(); preview.hide(); }
                } finally { speechSending=false; }
            }
        }
        if(automatic && !busy && !paused && time-nextAuto>=0) { nextAuto=time+interval(); request(null,null); }
        emit();
    }
    synchronized void interaction() { if(!closed && viewing && !speechSending) { flow.otherSpeech(); cancelResources(); complete("기존 대화·사용자 상호작용을 우선합니다."); } }
    synchronized void cancel() { if(closed) return; flow.cancel(); cancelResources(); complete("사진 작업을 취소했습니다."); }
    private void cancelResources() { ++epoch; if(pending!=null) pending.cancel(false); pending=null; source.cancel(); ai.cancel(); preview.hide(); photo=null; incoming.forEach(PreparedPhoto::close); incoming.clear(); }
    private void complete(String text) { busy=false; viewing=false; nextAuto=now.getAsLong()+interval(); message=text; emit(); }
    private void endFailure(long token, Throwable error) { flow.failed(token); preview.hide(); photo=null; pending=null; busy=false; viewing=false; if(error!=null) fail(error); else message="사진을 찾지 못했습니다."; nextAuto=now.getAsLong()+interval(); emit(); }
    private void fail(Throwable error) {
        while((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause()!=null) error=error.getCause();
        Failure.Code code=error instanceof Failure f ? f.code() : Failure.Code.WORK_FAILED;
        if(code==Failure.Code.CLEANUP_FAILED) blocked=true;
        message=switch(code) {
            case PATH_DENIED -> "허용 폴더와 로컬 AI의 클라우드 비활성 설정을 확인해 주세요.";
            case LINK_DENIED -> "링크·정션이 포함된 경로는 읽을 수 없습니다.";
            case FILE_LIMIT, PIXEL_LIMIT -> "안전한 크기 한도를 넘은 사진을 건너뛰었습니다.";
            case FORMAT_DENIED -> "지원되는 PNG·JPEG 사진이 아닙니다.";
            case DECODE_FAILED -> "사진을 안전하게 처리하지 못했습니다.";
            case TIMEOUT -> "처리 시간을 초과해 사진 작업을 중단했습니다.";
            case CANCELLED -> "사진 작업을 취소했습니다.";
            case RESPONSE_INVALID -> "AI 응답을 안전하게 표시할 수 없습니다.";
            case BUSY -> "이전 작업이 정리 중입니다. 잠시 후 다시 시도해 주세요.";
            case CLEANUP_FAILED -> "이전 작업의 종료를 확인하지 못해 새 작업을 차단했습니다.";
            case WORK_FAILED -> "파일 처리 또는 로컬 AI 연결에 실패했습니다.";
        };
        if(ai.uncertainWork()) message+=" 서버 작업 종료 불확실 · 추가 작업 차단";
    }
    private boolean valid(long request) { return !closed && request==epoch && busy; }
    private void post(Runnable work) { context.onEdt(() -> { synchronized(PhotoSession.this) { work.run(); } }); }
    @Override public synchronized void close() {
        if(closed) return; closed=true; ++epoch; if(pending!=null) pending.cancel(false);
        source.close(); ai.close(); flow.close(); incoming.forEach(PreparedPhoto::close); incoming.clear(); preview.close(); photo=null; folders.clear(); seen.clear(); listener=s -> {};
    }
}
