package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class Mp4VideoSizeTest {

    private static byte[] box(String type, byte[]... children) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] child : children) {
            body.write(child);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size = 8 + body.size();
        out.write(new byte[] {(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        out.write(type.getBytes(StandardCharsets.ISO_8859_1));
        body.writeTo(out);
        return out.toByteArray();
    }

    private static byte[] hdlr(String handler) {
        byte[] b = new byte[20];
        System.arraycopy(handler.getBytes(StandardCharsets.ISO_8859_1), 0, b, 8, 4);
        return b;
    }

    /** stsd body: version/flags, entry count, one sample entry with width and height. */
    private static byte[] stsd(String format, int width, int height) throws IOException {
        byte[] entryBody = new byte[78];
        entryBody[24] = (byte) (width >>> 8);
        entryBody[25] = (byte) width;
        entryBody[26] = (byte) (height >>> 8);
        entryBody[27] = (byte) height;
        byte[] entry = box(format, entryBody);
        byte[] head = {0, 0, 0, 0, 0, 0, 0, 1};
        byte[] out = new byte[head.length + entry.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(entry, 0, out, head.length, entry.length);
        return out;
    }

    private static byte[] track(String handler, String format, int width, int height) throws IOException {
        return box("trak", box("tkhd", new byte[84]),
                box("mdia", box("mdhd", new byte[24]), box("hdlr", hdlr(handler)),
                        box("minf", box("stbl", box("stsd", stsd(format, width, height)), box("stts", new byte[8])))));
    }

    private static long read(byte[] file) throws IOException {
        return Mp4VideoSize.read(new Mp4VideoSize.Source() {
            @Override
            public long length() {
                return file.length;
            }

            @Override
            public void read(long position, byte[] into, int len) throws IOException {
                if (position < 0 || position + len > file.length) {
                    throw new IOException("past end");
                }
                System.arraycopy(file, (int) position, into, 0, len);
            }
        });
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p);
        }
        return out.toByteArray();
    }

    @Test
    public void readsTheCodedSizeOfTheVideoTrack() throws IOException {
        byte[] file = concat(box("ftyp", new byte[8]), box("moov", box("mvhd", new byte[100]),
                track("vide", "avc1", 720, 480)), box("mdat", new byte[32]));
        assertEquals(Mp4VideoSize.pack(720, 480), read(file));
    }

    @Test
    public void skipsAnAudioTrackBeforeTheVideoTrack() throws IOException {
        byte[] file = concat(box("ftyp", new byte[8]), box("moov",
                track("soun", "mp4a", 0, 0), track("vide", "avc1", 854, 480)));
        assertEquals(Mp4VideoSize.pack(854, 480), read(file));
    }

    @Test
    public void findsTheMoovBoxAfterTheMediaData() throws IOException {
        byte[] file = concat(box("ftyp", new byte[8]), box("mdat", new byte[4000]),
                box("moov", track("vide", "avc1", 1920, 1080)));
        assertEquals(Mp4VideoSize.pack(1920, 1080), read(file));
    }

    @Test
    public void unknownForAFileWithoutVideoOrNotMp4() throws IOException {
        assertEquals(0, read(concat(box("ftyp", new byte[8]), box("moov", track("soun", "mp4a", 0, 0)))));
        assertEquals(0, read("not an mp4 file at all".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0, read(new byte[0]));
    }

    @Test
    public void unknownForATruncatedBox() throws IOException {
        byte[] file = box("moov", track("vide", "avc1", 854, 480));
        byte[] cut = new byte[file.length - 20];
        System.arraycopy(file, 0, cut, 0, cut.length);
        assertEquals(0, read(cut));
    }

    @Test
    public void packsAndUnpacks() {
        long size = Mp4VideoSize.pack(854, 480);
        assertEquals(854, Mp4VideoSize.width(size));
        assertEquals(480, Mp4VideoSize.height(size));
        assertEquals("854x480", Mp4VideoSize.describe(size));
        assertEquals(0, Mp4VideoSize.pack(0, 480));
        assertEquals("unknown", Mp4VideoSize.describe(0));
    }
}
