package com.brentvatne.react.pooled;

/**
 * How often the pool may throw away all its players and start again on a new playback thread.
 *
 * <p>The first rebuild is allowed at once. If the pool is asked again, the rebuild did not
 * help for long, so each further one has to wait twice as long as the one before, from
 * {@link #FIRST_WAIT_MS} up to {@link #MAX_WAIT_MS}: the pool never stops trying, but it does
 * not rebuild in a loop either. Once {@link #QUIET_MS} have passed without a rebuild, the
 * next one is allowed at once again.
 *
 * <p>This class has no Android dependencies so that it can be tested on the JVM.
 */
final class RebuildBackoff {

    static final long FIRST_WAIT_MS = 30_000;
    static final long MAX_WAIT_MS = 10 * 60_000;
    static final long QUIET_MS = 15 * 60_000;

    private long mLastMs = -1;
    private long mWaitMs;

    /** Whether a rebuild may happen now; if so, it is counted as happening. */
    boolean allow(long nowMs) {
        if (mLastMs >= 0) {
            long since = nowMs - mLastMs;
            if (since >= QUIET_MS) {
                mWaitMs = 0;
            } else if (since < mWaitMs) {
                return false;
            }
        }
        mLastMs = nowMs;
        mWaitMs = mWaitMs == 0 ? FIRST_WAIT_MS : Math.min(mWaitMs * 2, MAX_WAIT_MS);
        return true;
    }

    /** How long until the next rebuild is allowed; 0 if it is allowed now. */
    long waitMs(long nowMs) {
        if (mLastMs < 0 || nowMs - mLastMs >= QUIET_MS) {
            return 0;
        }
        return Math.max(0, mWaitMs - (nowMs - mLastMs));
    }
}
