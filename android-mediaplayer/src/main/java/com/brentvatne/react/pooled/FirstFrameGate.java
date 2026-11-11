package com.brentvatne.react.pooled;

/**
 * Decides whether a rendered-first-frame event belongs to the source a pooled view loaded last.
 *
 * <p>The player sends that event from its playback thread, so one for the previous source can
 * still be on its way when the next source is loaded. Each load therefore sends a marker
 * through the playback thread and back, just ahead of the new source. Events come back in the
 * order the playback thread sent them, so a frame event that arrives before the marker is for
 * the previous source. The playback thread goes from the marker straight to the new source, so
 * a frame event that arrives after the marker is for the new one. The marker goes first, not
 * last, so that it can never overtake a frame of the new source: that would leave the video
 * hidden.
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
