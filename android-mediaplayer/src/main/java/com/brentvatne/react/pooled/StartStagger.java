package com.brentvatne.react.pooled;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Spaces out the moments at which pooled views start a video.
 *
 * <p>Starting a video is main-thread work, and a screen that shows eight videos asks for all
 * eight in the same frame. Doing them back to back holds up the main thread for the sum of
 * them; with a short gap between them the main thread can draw in between.
 *
 * <p>A start that is asked for when none has run within the gap runs at once, inside
 * {@link #request}, so a single video is not delayed. The others run in the order they were
 * asked for, at least the gap apart.
 *
 * <p>All methods are called on the main thread.
 */
final class StartStagger {

    /** The main thread's clock and message queue. */
    interface Scheduler {
        long nowMs();

        void postDelayed(Runnable runnable, long delayMs);
    }

    private static final class Pending {
        final Object owner;
        Runnable start;

        Pending(Object owner, Runnable start) {
            this.owner = owner;
            this.start = start;
        }
    }

    private final Scheduler mScheduler;
    private final long mGapMs;
    private final ArrayDeque<Pending> mPending = new ArrayDeque<>();
    private boolean mHasStarted = false;
    private long mLastStartMs;
    private boolean mDrainScheduled = false;

    private final Runnable mDrain = new Runnable() {
        @Override
        public void run() {
            mDrainScheduled = false;
            drain();
        }
    };

    StartStagger(Scheduler scheduler, long gapMs) {
        mScheduler = scheduler;
        mGapMs = gapMs;
    }

    /**
     * Runs {@code start} now if no start has run within the gap and none is waiting, otherwise
     * on its turn. An owner has one start at most: asking again keeps its place in the order
     * and replaces what will run.
     */
    void request(Object owner, Runnable start) {
        for (Pending pending : mPending) {
            if (pending.owner == owner) {
                pending.start = start;
                return;
            }
        }
        mPending.addLast(new Pending(owner, start));
        drain();
    }

    /** Forgets the owner's waiting start. Does nothing if it has none. */
    void cancel(Object owner) {
        for (Iterator<Pending> it = mPending.iterator(); it.hasNext(); ) {
            if (it.next().owner == owner) {
                it.remove();
                return;
            }
        }
    }

    /** How many starts are waiting for their turn. */
    int waiting() {
        return mPending.size();
    }

    private void drain() {
        if (mDrainScheduled || mPending.isEmpty()) {
            return;
        }
        long now = mScheduler.nowMs();
        long wait = mHasStarted ? mLastStartMs + mGapMs - now : 0;
        if (wait > 0) {
            mDrainScheduled = true;
            mScheduler.postDelayed(mDrain, Math.min(wait, mGapMs));
            return;
        }
        Pending next = mPending.pollFirst();
        mHasStarted = true;
        mLastStartMs = now;
        if (!mPending.isEmpty()) {
            // Scheduled before the start runs, so a start that throws does not strand the rest.
            mDrainScheduled = true;
            mScheduler.postDelayed(mDrain, mGapMs);
        }
        next.start.run();
    }
}
