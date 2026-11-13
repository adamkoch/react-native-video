package com.brentvatne.react.pooled;

/**
 * Decides when a pooled view's player has stopped producing pictures and what to do about it.
 *
 * <p>A hardware decoder can stop without the player reporting an error: the player still says
 * it is playing, and the view stays black. Preparing the same player again does not always
 * help either. So a view that should be showing a moving picture is checked every
 * {@link #CHECK_MS}: if its player's decoder has put out no new frame for {@link #STALL_MS},
 * the view replaces the player with a new one, up to {@link #MAX_REPLACEMENTS} times, and
 * after that gives up and reports an error, as it does for an error the player reports.
 *
 * <p>The replacements are counted per source. They are forgotten once frames have kept
 * coming for {@link #HEALTHY_MS}, so that a clip which plays for hours may recover from a
 * stall any number of times, as long as each recovery works.
 *
 * <p>This class has no Android dependencies so that it can be tested on the JVM. All methods
 * are called on the main thread.
 */
final class StallWatch {

    /** How often a view checks its player. */
    static final long CHECK_MS = 2000;
    /** A player that should be playing and has put out no frame for this long has stalled. */
    static final long STALL_MS = 8000;
    /** Frames that keep coming for this long mean the last replacement worked. */
    static final long HEALTHY_MS = 30000;
    /** New players tried for one source before the view gives up. */
    static final int MAX_REPLACEMENTS = 2;

    enum Verdict {
        /** Nothing to do. */
        OK,
        /** Give the view a new player and load the source on it. */
        REPLACE,
        /** Report the error to JS. */
        FAIL
    }

    /** When the frame count last changed, or the watch began; -1 while not watching. */
    private long mSinceMs = -1;
    /** Since when every check has seen new frames; -1 if the last one did not. */
    private long mHealthySinceMs = -1;
    private long mLastFrames;
    private int mReplacements;

    /** A new source: nothing is known about it yet. */
    void reset() {
        mSinceMs = -1;
        mHealthySinceMs = -1;
        mReplacements = 0;
    }

    /** The source is being loaded on a player: the stall time starts from the next check. */
    void restart() {
        mSinceMs = -1;
        mHealthySinceMs = -1;
    }

    /**
     * @param expected the view should be showing a moving picture now.
     * @param frames how many frames the player's decoder has put out; only a change matters,
     *     so the count may start again from any number.
     */
    Verdict check(boolean expected, long frames, long nowMs) {
        if (!expected) {
            mSinceMs = -1;
            mHealthySinceMs = -1;
            return Verdict.OK;
        }
        if (mSinceMs < 0) {
            mSinceMs = nowMs;
            mLastFrames = frames;
            return Verdict.OK;
        }
        if (frames != mLastFrames) {
            mSinceMs = nowMs;
            mLastFrames = frames;
            if (mHealthySinceMs < 0) {
                mHealthySinceMs = nowMs;
            } else if (nowMs - mHealthySinceMs >= HEALTHY_MS) {
                mReplacements = 0;
            }
            return Verdict.OK;
        }
        mHealthySinceMs = -1;
        if (nowMs - mSinceMs < STALL_MS) {
            return Verdict.OK;
        }
        return giveUpOnPlayer();
    }

    /**
     * The player cannot go on: it has stalled, or it reported an error that preparing it again
     * did not clear. The watch starts again with whatever comes next.
     */
    Verdict giveUpOnPlayer() {
        mSinceMs = -1;
        mHealthySinceMs = -1;
        if (mReplacements >= MAX_REPLACEMENTS) {
            return Verdict.FAIL;
        }
        mReplacements++;
        return Verdict.REPLACE;
    }

    int replacements() {
        return mReplacements;
    }
}
