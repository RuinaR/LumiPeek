package io.lumipeek.core;

/** Native test DLL only; the production binary exports none of these methods. */
final class WindowsFixtures {
    private WindowsFixtures() {}
    static native boolean register0(String root);
    static native boolean junction0(String link, String target);
    static native void arm0(int stage);
    static native boolean await0();
    static native void release0();
    static native boolean offline0(String path, boolean set);
}
