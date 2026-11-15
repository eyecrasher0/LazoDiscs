package org.lwjgl.openal;

/** Public LWJGL context identity fixture; does not invoke a native audio device. */
public final class AL {
    public static Object capabilities = new Object();
    public static Object getCapabilities() { return capabilities; }
}
