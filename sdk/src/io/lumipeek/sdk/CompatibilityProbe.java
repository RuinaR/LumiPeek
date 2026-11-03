package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.LumiPlugin;
import com.group_finity.mascot.lumi.plugin.PluginContext;
import java.util.Map;
import java.util.function.Consumer;

/** Inert SDK integration probe. No UI, model, file reads, speech or worker launch. */
public final class CompatibilityProbe implements LumiPlugin {
    private PluginContext context;
    private final Consumer<Map<String, Object>> ready = ignored -> observe();
    private int samples;
    private boolean quiet;
    @Override public synchronized void start(PluginContext ctx) {
        stop();
        context = ctx;
        ctx.on("ready", ready);
    }
    private synchronized void observe() {
        if (context == null) return;
        quiet = context.quietNow() || context.charactersHidden() || context.bubbleVisible();
        samples++;
    }
    @Override public synchronized void stop() {
        PluginContext previous = context; context = null;
        if (previous != null) previous.off("ready", ready);
    }
    public synchronized int samples() { return samples; }
    public synchronized boolean quiet() { return quiet; }
}
