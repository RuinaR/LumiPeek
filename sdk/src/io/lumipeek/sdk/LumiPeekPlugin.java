package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.*;
import java.util.*;
import java.util.function.*;
import javax.swing.*;

/** Additive SDK plugin. No host patches, actions, click claims, speech clearing or app launching. */
public final class LumiPeekPlugin implements LumiPlugin {
    private final List<PluginUi.MenuHandle> rows=new ArrayList<>();
    private final Map<String,Consumer<Map<String,Object>>> events=new HashMap<>();
    private final Supplier<PhotoSource> source;
    private final Function<String,LocalOllama> client;
    private final LongSupplier now;
    private PluginContext context;
    private PhotoSession session;
    private PhotoPreview preview;
    private ScheduledHandle timer;
    private JDialog window;
    private SettingsPanel panel;
    private long generation;
    public LumiPeekPlugin() {
        this(LazyPhotos::new, LocalOllama::localDefault,System::nanoTime);
    }
    LumiPeekPlugin(Supplier<PhotoSource> source, Function<String,LocalOllama> client, LongSupplier now) { this.source=source; this.client=client; this.now=now; }
    @Override public synchronized void start(PluginContext ctx) {
        if(context!=null) return; context=ctx; long start=++generation;
        // prefs can touch disk; neither that nor JNI/network work belongs on the UI thread.
        ctx.offEdt(() -> {
            PhotoSettings loaded=PhotoSettings.load(ctx.prefs());
            ctx.onEdt(() -> { synchronized(LumiPeekPlugin.this) {
                if(context!=ctx || generation!=start) return;
                preview=new PhotoPreview(); session=new PhotoSession(ctx,source.get(),client.apply(loaded.model()),preview,loaded,now);
                rows.add(ctx.addTrayItem("LumiPeek 설정",this::settings));
                rows.add(ctx.addTrayItem("사진 한 장 구경하기",() -> withSession(s -> s.request(null,null))));
                rows.add(ctx.addTrayItem("LumiPeek 일시정지 / 재개",() -> withSession(s -> s.pause(!s.status().paused()))));
                rows.add(ctx.addCharacterMenuItem("사진 한 장 구경하기",set -> true,(set,id) -> withSession(s -> s.request(id,set))));
                rows.add(ctx.addSettingsButton("LumiPeek 설정",this::settings));
                for(String topic:List.of("click","doubleclick","drag","bubble","choice")) {
                    Consumer<Map<String,Object>> event=ignored -> ctx.onEdt(() -> { synchronized(LumiPeekPlugin.this) { if(generation==start && session!=null) session.interaction(); } });
                    events.put(topic,event); ctx.on(topic,event);
                }
                timer=ctx.every(100,100,() -> { synchronized(LumiPeekPlugin.this) { if(generation==start && session!=null) session.scheduleTick(); } });
            } });
        });
    }
    private synchronized void withSession(Consumer<PhotoSession> action) { if(session!=null) action.accept(session); }
    private synchronized void settings() {
        if(context==null || session==null || java.awt.GraphicsEnvironment.isHeadless()) return;
        if(window==null) {
            window=new JDialog((java.awt.Frame)null,"LumiPeek 설정",false); panel=new SettingsPanel(context,session,preview);
            PhotoPreview ownedPreview=preview; SettingsPanel ownedPanel=panel;
            window.setContentPane(new JScrollPane(panel)); window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            window.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) { ownedPreview.enabled(false); ownedPanel.preview.setSelected(false); }
            });
            window.setSize(690,760); context.manageWindow(window,"lumipeek-settings");
        }
        window.setVisible(true);
    }
    @Override public synchronized void stop() {
        if(context==null) return; PluginContext old=context; context=null; ++generation;
        if(timer!=null) timer.cancel(); timer=null;
        events.forEach(old::off); events.clear(); rows.forEach(PluginUi.MenuHandle::remove); rows.clear();
        PhotoSession ending=session; session=null; JDialog closing=window; window=null; panel=null; preview=null;
        // Use Swing dispatch directly: a released SDK context may refuse cleanup callbacks.
        if(ending!=null) ending.close();
        Runnable cleanup=() -> { if(closing!=null) closing.dispose(); };
        if(SwingUtilities.isEventDispatchThread() || java.awt.GraphicsEnvironment.isHeadless()) cleanup.run(); else SwingUtilities.invokeLater(cleanup);
    }
}
