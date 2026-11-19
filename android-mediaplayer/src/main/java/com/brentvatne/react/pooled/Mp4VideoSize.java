package com.brentvatne.react.pooled;

import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The coded picture size of an MP4 file's first video track, read from its sample description
 * ({@code stsd}) without a decoder. Coded size, not display size: a 720x480 clip with wide
 * pixels is 720x480 here, which is what the decoder is configured for.
 *
 * Sizes are packed into one long by {@link #pack}; 0 means unknown. Only local files are read;
 * anything else is unknown.
 */
final class Mp4VideoSize {

    /** Random access to the bytes of a file. */
    interface Source {
        long length() throws IOException;

        /** Reads exactly {@code len} bytes at {@code position}, or throws. */
        void read(long position, byte[] into, int len) throws IOException;
    }

    private static final int MAX_BOXES = 512;
    private static final int CACHE_SIZE = 256;

    private static final Map<String, Long> sCache = new LinkedHashMap<String, Long>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private Mp4VideoSize() {
    }

    static long pack(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        return ((long) width << 32) | (height & 0xffffffffL);
    }

    static int width(long size) {
        return (int) (size >>> 32);
    }

    static int height(long size) {
        return (int) size;
    }

    static String describe(long size) {
        return size == 0 ? "unknown" : width(size) + "x" + height(size);
    }

    /** The size of a local file:// source, cached by path, length and modification time. */
    static long forUri(Uri uri) {
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
            return 0;
        }
        File file = new File(uri.getPath());
        String key = file.getPath() + '|' + file.length() + '|' + file.lastModified();
        synchronized (sCache) {
            Long cached = sCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        long size = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            size = read(new Source() {
                @Override
                public long length() throws IOException {
                    return raf.length();
                }

                @Override
                public void read(long position, byte[] into, int len) throws IOException {
                    raf.seek(position);
                    raf.readFully(into, 0, len);
                }
            });
        } catch (IOException | RuntimeException e) {
            size = 0;
        }
        synchronized (sCache) {
            sCache.put(key, size);
        }
        return size;
    }

    /** The coded size of the first video track, or 0 if there is none or the file is not MP4. */
    static long read(Source source) throws IOException {
        int[] budget = {MAX_BOXES};
        return findInContainer(source, 0, source.length(), "moov", budget);
    }

    /** Looks through the boxes in [start, end) for {@code want}, descending into the path to stsd. */
    private static long findInContainer(Source s, long start, long end, String want, int[] budget)
            throws IOException {
        long pos = start;
        byte[] header = new byte[16];
        while (pos + 8 <= end && budget[0]-- > 0) {
            s.read(pos, header, 8);
            long size = u32(header, 0);
            String type = type(header, 4);
            int headerLen = 8;
            if (size == 1) {
                if (pos + 16 > end) {
                    return 0;
                }
                s.read(pos + 8, header, 8);
                size = u64(header, 0);
                headerLen = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < headerLen || pos + size > end) {
                return 0;
            }
            long bodyStart = pos + headerLen;
            long bodyEnd = pos + size;
            if (type.equals(want)) {
                long found = descend(s, type, bodyStart, bodyEnd, budget);
                if (found != 0) {
                    return found;
                }
                if (!"trak".equals(type)) {
                    // Only trak repeats: keep looking at the next track.
                    return 0;
                }
            }
            pos = bodyEnd;
        }
        return 0;
    }

    private static long descend(Source s, String type, long start, long end, int[] budget) throws IOException {
        switch (type) {
            case "moov":
                return findInContainer(s, start, end, "trak", budget);
            case "trak":
                return findInContainer(s, start, end, "mdia", budget);
            case "mdia":
                if (!isVideoHandler(s, start, end, budget)) {
                    return 0;
                }
                return findInContainer(s, start, end, "minf", budget);
            case "minf":
                return findInContainer(s, start, end, "stbl", budget);
            case "stbl":
                return findInContainer(s, start, end, "stsd", budget);
            case "stsd":
                return firstSampleEntrySize(s, start, end);
            default:
                return 0;
        }
    }

    /** Whether the mdia box at [start, end) has a 'vide' handler. */
    private static boolean isVideoHandler(Source s, long start, long end, int[] budget) throws IOException {
        long pos = start;
        byte[] header = new byte[8];
        while (pos + 8 <= end && budget[0]-- > 0) {
            s.read(pos, header, 8);
            long size = u32(header, 0);
            if (size < 8 || pos + size > end) {
                return false;
            }
            if ("hdlr".equals(type(header, 4))) {
                // version/flags (4), pre_defined (4), handler_type (4)
                if (size < 8 + 12) {
                    return false;
                }
                byte[] body = new byte[12];
                s.read(pos + 8, body, 12);
                return "vide".equals(type(body, 8));
            }
            pos += size;
        }
        return false;
    }

    /** Width and height of the first visual sample entry in an stsd body. */
    private static long firstSampleEntrySize(Source s, long start, long end) throws IOException {
        // version/flags (4), entry_count (4), then the entry: size (4), format (4),
        // reserved (6), data_reference_index (2), pre_defined/reserved (16), width (2), height (2)
        if (start + 8 + 36 > end) {
            return 0;
        }
        byte[] entry = new byte[36];
        s.read(start + 8, entry, 36);
        int width = ((entry[32] & 0xff) << 8) | (entry[33] & 0xff);
        int height = ((entry[34] & 0xff) << 8) | (entry[35] & 0xff);
        return pack(width, height);
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xffL) << 24) | ((b[off + 1] & 0xffL) << 16) | ((b[off + 2] & 0xffL) << 8)
                | (b[off + 3] & 0xffL);
    }

    private static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | u32(b, off + 4);
    }

    private static String type(byte[] b, int off) {
        return new String(b, off, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}
