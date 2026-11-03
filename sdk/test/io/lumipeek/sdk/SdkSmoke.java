package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.PluginTestKit;
import java.nio.file.*;

/** Uses the official fake context only; never PluginTestKit.realContext. */
public final class SdkSmoke {
    private static int passed;
    private SdkSmoke() {}
    private static void check(boolean value) { if (!value) throw new AssertionError("SDK smoke check failed"); passed++; }
    public static void main(String[] args) throws Exception {
        check(java.awt.GraphicsEnvironment.isHeadless());
        Path temporary;
        try (PluginTestKit kit = PluginTestKit.start("lumipeek.probe")) {
            temporary = kit.dataDir();
            CompatibilityProbe plugin = new CompatibilityProbe();
            try {
                int calls = kit.calls().size();
                plugin.start(kit.context()); check(plugin.samples() == 0);
                kit.quiet(true); kit.publish("ready"); check(plugin.samples() == 1 && plugin.quiet());
                kit.quiet(false); kit.charactersHidden(false); kit.bubbleVisible(false);
                kit.publish("ready"); check(plugin.samples() == 2 && !plugin.quiet());
                kit.bubbleVisible(true); kit.publish("ready"); check(plugin.quiet());
                check(kit.calls().size() == calls); // read-only lifecycle must not touch shared features
                plugin.stop(); plugin.stop(); kit.publish("ready"); check(plugin.samples() == 3);
                plugin.start(kit.context()); kit.publish("ready"); check(plugin.samples() == 4);
            } finally { plugin.stop(); }
        }
        check(!Files.exists(temporary));
        System.out.println("PASS: official SDK " + args[0] + " fake-context smoke " + passed + " assertions");
    }
}
