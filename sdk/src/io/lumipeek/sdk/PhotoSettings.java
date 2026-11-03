package io.lumipeek.sdk;

import com.group_finity.mascot.lumi.plugin.PluginPrefs;

/** No photo paths, prompts or responses are persisted. Automatic operation requires session opt-in. */
public record PhotoSettings(boolean recursive, int intervalMinutes, int previewPixels, boolean thinking, String model) {
    public PhotoSettings {
        if (intervalMinutes < 5 || intervalMinutes > 240 || previewPixels < 96 || previewPixels > 180)
            throw new IllegalArgumentException("SETTINGS_RANGE");
        ModelCatalog.require(model);
    }
    public static PhotoSettings defaults() { return new PhotoSettings(false, 60, 144, false, "qwen3-vl:4b-instruct"); }
    public static PhotoSettings load(PluginPrefs prefs) {
        try { return new PhotoSettings(prefs.getBoolean("recursive",false), prefs.getInt("interval",60),
            prefs.getInt("preview",144),prefs.getBoolean("thinking",false),prefs.get("model",defaults().model())); }
        catch (IllegalArgumentException ex) { return defaults(); }
    }
    public void save(PluginPrefs prefs) {
        prefs.set("recursive",Boolean.toString(recursive)); prefs.set("interval",Integer.toString(intervalMinutes));
        prefs.set("preview",Integer.toString(previewPixels)); prefs.set("thinking",Boolean.toString(thinking)); prefs.set("model",model);
    }
}
