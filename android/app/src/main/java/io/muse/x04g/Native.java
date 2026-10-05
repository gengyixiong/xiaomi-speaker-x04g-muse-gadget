package io.muse.x04g;

final class Native {
    static { System.loadLibrary("muse"); }
    static native long create();
    static native void destroy(long handle);
    static native byte[] hello(long handle);
    static native byte[] finish(long handle, byte[] reply);
    static native byte[][] request(long handle, long id, String path, String requestId, byte[] body, boolean end);
    static native byte[][] body(long handle, long id, byte[] data, boolean end);
    static native byte[][] reset(long handle, long id);
    static native Frame receive(long handle, byte[] data);
    static native void avatar(android.graphics.Bitmap bitmap,int mode,float seconds,float modeSeconds,float level,float happy);
    static final class Frame {
        final long id;
        final int kind, status;
        final boolean end;
        final byte[] data;
        Frame(long id, int kind, int status, boolean end, byte[] data) {
            this.id=id; this.kind=kind; this.status=status; this.end=end; this.data=data;
        }
    }
}
