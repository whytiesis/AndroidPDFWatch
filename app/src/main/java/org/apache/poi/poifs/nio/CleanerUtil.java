package org.apache.poi.poifs.nio;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Android adapter: use POI's unsupported-unmap branch; DOC input is stream backed. */
public final class CleanerUtil {
    public static final boolean UNMAP_SUPPORTED=false;
    public static final String UNMAP_NOT_SUPPORTED_REASON="Desktop mapped-buffer unmapping is unavailable on Android";
    private CleanerUtil() { }
    public interface BufferCleaner { void freeBuffer(ByteBuffer buffer) throws IOException; }
    public static BufferCleaner getCleaner() { return null; }
}
