package com.bu.cc.tv;


public final class NativeH5eDecryptor {
    static { System.loadLibrary("h5estream"); }
    private NativeH5eDecryptor() {}
    public static native byte[] decryptTransportStream(byte[] packets);
    public static native boolean supportsStreamingTransportStream();
    public static native byte[] decryptTransportStreamPart(byte[] packets, boolean first);
    public static native void setSpsCompatibilityMode(boolean enabled);
    public static native void cancelPendingDecrypts();
    public static native void releaseThreadContext();
}
