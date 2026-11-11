package com.brentvatne.react.pooled;

/**
 * Decides whether a rendered-first-frame event belongs to the source a pooled view loaded last.
 *
 * <p>The player sends that event from its playback thread, so one for the previous source can
 * still be on its way when the next source is loaded. The event does not say which source it
 * is for, so each load sends a marker through the playback thread and back, and a frame event
 * counts only once the marker has returned.
 *
 * <p>That is right only if the playback thread handles "replace the source", "prepare" and
 * the marker one after the other, with nothing in between. {@link PooledVideoView} makes it
 * so by holding the playback thread ({@link PlaybackHold}) until all three are queued. Then:
 *
 * <ul>
 *   <li>The previous source can render only before the playback thread replaces it, which is
 *       before it sends the marker. Events come back in the order they were sent, so every
 *       frame event of the previous source arrives before the marker.
 *   <li>The new source cannot render until its media has been prepared, and that needs work
 *       the playback thread queues for itself behind the marker. So every frame event of the
 *       new source arrives after the marker, and the video cannot stay hidden.
 * </ul>
 *
 * <p>All methods are called on the main thread.
 */
final class FirstFrameGate {

    private int mLoad = 0;
    private boolean mMarkerReturned = false;
    private boolean mFrameRendered = false;

    /** A new source was loaded. Returns the number its marker must come back with. */
    int onLoad() {
        mMarkerReturned = false;
        mFrameRendered = false;
        return ++mLoad;
    }

    /** The marker sent with the given load came back from the playback thread. */
    void onMarker(int load) {
        if (load == mLoad) {
            mMarkerReturned = true;
        }
    }

    void onFirstFrame() {
        if (mMarkerReturned) {
            mFrameRendered = true;
        }
    }

    /** A frame of the source loaded last has been rendered. */
    boolean frameRendered() {
        return mFrameRendered;
    }
}
