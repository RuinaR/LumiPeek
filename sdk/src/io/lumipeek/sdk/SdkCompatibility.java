package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.*;
import io.lumipeek.core.PhotoFlow;
import java.util.Objects;

/** Only public 1.5.0 methods. No unverified 1.5.1 feature is called reflectively. */
final class SdkCompatibility {
    static final boolean DESKTOP_PREVIEW_VERIFIED = false;
    static final boolean BUSY_OWNERSHIP_VERIFIED = false;
    private final PluginContext context;
    SdkCompatibility(PluginContext context) { this.context = context; }
    PhotoFlow.Observation observe(int id, String set) {
        MascotState target=context.mascotById(id);
        return new PhotoFlow.Observation(context.quietNow(),context.charactersHidden(),context.bubbleVisible(),
            id>0 && target!=null && Objects.equals(target.imageSet(),set));
    }
    boolean sayIfClear(int id, String set, String reaction) {
        // Caller is on EDT. This is a conservative snapshot guard, not an atomic speech lease.
        var observed=observe(id,set);
        if (observed.quiet() || observed.hidden() || observed.bubbleVisible() || !observed.targetPresent() || context.queueLength()!=0) return false;
        context.sayTo(id,set,reaction,6000);
        return true;
    }
}
