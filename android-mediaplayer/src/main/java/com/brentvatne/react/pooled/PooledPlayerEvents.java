package com.brentvatne.react.pooled;

/**
 * What the pooled players are doing, for an app that wants to record it. Nothing is recorded
 * here: without a sink, or with one that is switched off, every call is a single read.
 *
 * Text handed to the sink never holds a file path or URL; the source is passed separately so
 * that the app can decide how much of it to keep.
 */
public final class PooledPlayerEvents {

    public interface Sink {
        /** Checked before anything is put together for the sink. */
        boolean enabled();

        /** view is 0 for something the pool did; source may be null. */
        void event(String name, int view, String text, String source);

        /** A view gave up on its source and told JS. */
        void failed(int view, String text, String source);

        /** Whether the view should now be showing a moving picture. */
        void expectFrames(int view, boolean expected, String source, Object viewObject);

        /** A new picture reached the view. Called for every frame. */
        void frame(int view);
    }

    /**
     * The few events worth knowing about on every display, whatever the {@link Sink} is doing:
     * a pool rebuild, a replaced or failed player, a full pool, a decoder that had to change
     * picture size. Rare by design (not per frame or per clip), so an app can keep them all,
     * for example as crash-report breadcrumbs.
     */
    public interface KeySink {
        void key(String name, String detail);
    }

    private static volatile Sink sSink;
    private static volatile KeySink sKeySink;

    private PooledPlayerEvents() {
    }

    public static void setSink(Sink sink) {
        sSink = sink;
    }

    public static void setKeySink(KeySink sink) {
        sKeySink = sink;
    }

    /** A key event; see {@link KeySink}. */
    static void key(String name, String detail) {
        KeySink sink = sKeySink;
        if (sink != null) {
            try {
                sink.key(name, detail);
            } catch (Throwable ignored) {
                // Reporting must never get in the way of playing.
            }
        }
    }

    static boolean on() {
        Sink sink = sSink;
        return sink != null && sink.enabled();
    }

    static void event(String name, int view, String text, String source) {
        Sink sink = sSink;
        if (sink != null && sink.enabled()) {
            try {
                sink.event(name, view, text, source);
            } catch (Throwable ignored) {
                // Recording must never get in the way of playing.
            }
        }
    }

    static void failed(int view, String text, String source) {
        Sink sink = sSink;
        if (sink != null && sink.enabled()) {
            try {
                sink.failed(view, text, source);
            } catch (Throwable ignored) {
            }
        }
    }

    static void expectFrames(int view, boolean expected, String source, Object viewObject) {
        Sink sink = sSink;
        if (sink != null && sink.enabled()) {
            try {
                sink.expectFrames(view, expected, source, viewObject);
            } catch (Throwable ignored) {
            }
        }
    }

    static void frame(int view) {
        Sink sink = sSink;
        if (sink != null && sink.enabled()) {
            try {
                sink.frame(view);
            } catch (Throwable ignored) {
            }
        }
    }
}
