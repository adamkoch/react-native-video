package com.brentvatne.react.pooled;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Spaces out the moments at which pooled views start a video.
 *
 * <p>Starting a video costs the device a burst of work: the main thread loads the source, and
 * a decoder is configured and fed until its first frame is out. A screen that shows eight
 * videos asks for all eight in the same frame, and on a slow device the eight bursts together
 * stall drawing for seconds. Started one after another, each with a pause after it, the device
 * can draw in between.
 *
 * <p>Only a view that asks for it is spaced out: a request without a {@link Pacing} runs at
 * once, inside {@link #request}, whatever else is waiting. A request with one runs in the
 * order it was asked for, its delay after the paced start before it. The delay counts from
 * the moment that start {@linkplain #settled settled}, or from the start itself if the
 * request does not wait for first frames. A start that never settles holds up the next one
 * for the longest wait only. A paced request with nothing before it to wait for also runs
 * inside {@link #request}, so a single video is not delayed.
 *
 * <p>All methods are called on the main thread.
 */
final class StartStagger {

    /** The main thread's clock and message queue. */
    interface Scheduler {
        long nowMs();

        void postDelayed(Runnable runnable, long delayMs);
    }

    /** How a start is spaced from the paced start before it. */
    static final class Pacing {
        /** Count the delay from the first frame of the video before, not from its start. */
        final boolean waitForFirstFrame;
        final long delayMs;

        Pacing(boolean waitForFirstFrame, long delayMs) {
            this.waitForFirstFrame = waitForFirstFrame;
            this.delayMs = Math.max(0, delayMs);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pacing
                    && ((Pacing) other).waitForFirstFrame == waitForFirstFrame
                    && ((Pacing) other).delayMs == delayMs;
        }

        @Override
        public int hashCode() {
            return (int) (delayMs * 2 + (waitForFirstFrame ? 1 : 0));
        }
    }

    private static final class Pending {
        final Object owner;
        Runnable start;
        Pacing pacing;

        Pending(Object owner, Runnable start, Pacing pacing) {
            this.owner = owner;
            this.start = start;
            this.pacing = pacing;
        }
    }

    private final Scheduler mScheduler;
    private final long mMaxWaitMs;
    private final ArrayDeque<Pending> mPending = new ArrayDeque<>();
    private boolean mHasStarted = false;
    private long mLastStartMs;
    /** The owner of the last paced start, until it settles or is cancelled. */
    private Object mUnsettledOwner;
    /** When the last paced start settled; meaningful while {@link #mUnsettledOwner} is null. */
    private long mSettledMs;
    /** Counts scheduled drains, so that one replaced by a later one does nothing. */
    private int mDrainGeneration = 0;

    /**
     * @param maxWaitMs the longest a start that has not settled holds up one that waits for
     *                  its first frame
     */
    StartStagger(Scheduler scheduler, long maxWaitMs) {
        mScheduler = scheduler;
        mMaxWaitMs = maxWaitMs;
    }

    /**
     * Runs {@code start} now, or on its turn if {@code pacing} is given and a paced start
     * before it is still to be waited for. An owner has one start at most: asking again keeps
     * its place in the order and replaces what will run and how it is paced. With no pacing
     * the owner's waiting start, if any, is dropped and {@code start} runs inside this call.
     */
    void request(Object owner, Runnable start, Pacing pacing) {
        if (pacing == null) {
            cancel(owner);
            start.run();
            return;
        }
        if (mUnsettledOwner == owner) {
            // Its earlier start is replaced, so there is nothing of it left to wait for.
            mUnsettledOwner = null;
            mSettledMs = mScheduler.nowMs();
        }
        Pending waiting = null;
        for (Pending pending : mPending) {
            if (pending.owner == owner) {
                waiting = pending;
                break;
            }
        }
        if (waiting != null) {
            waiting.start = start;
            waiting.pacing = pacing;
        } else {
            mPending.addLast(new Pending(owner, start, pacing));
        }
        drain();
    }

    /** Forgets the owner's waiting start, and stops waiting for its last one to settle. */
    void cancel(Object owner) {
        boolean changed = false;
        for (Iterator<Pending> it = mPending.iterator(); it.hasNext(); ) {
            if (it.next().owner == owner) {
                it.remove();
                changed = true;
                break;
            }
        }
        if (mUnsettledOwner == owner) {
            mUnsettledOwner = null;
            mSettledMs = mLastStartMs;
            changed = true;
        }
        if (changed) {
            drain();
        }
    }

    /**
     * The owner's start has come to rest: its video shows a frame, or it has failed. The
     * starts that wait for first frames count their delay from now. Does nothing unless the
     * owner's start was the last paced one.
     */
    void settled(Object owner) {
        if (mUnsettledOwner != owner) {
            return;
        }
        mUnsettledOwner = null;
        mSettledMs = mScheduler.nowMs();
        drain();
    }

    /** Whether the owner has a start waiting for its turn. */
    boolean isWaiting(Object owner) {
        for (Pending pending : mPending) {
            if (pending.owner == owner) {
                return true;
            }
        }
        return false;
    }

    /** How many starts are waiting for their turn. */
    int waiting() {
        return mPending.size();
    }

    /** How long the first waiting start still has to wait; zero or less if it may run. */
    private long waitMs(Pacing pacing, long now) {
        if (!mHasStarted) {
            return 0;
        }
        if (!pacing.waitForFirstFrame) {
            return mLastStartMs + pacing.delayMs - now;
        }
        if (mUnsettledOwner != null) {
            return mLastStartMs + mMaxWaitMs - now;
        }
        return mSettledMs + pacing.delayMs - now;
    }

    private void schedule(long delayMs) {
        final int generation = ++mDrainGeneration;
        mScheduler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation == mDrainGeneration) {
                    drain();
                }
            }
        }, Math.max(0, delayMs));
    }

    /** Runs the first waiting start if it is due, and schedules the look at the one after. */
    private void drain() {
        // Whatever was scheduled was worked out before the change that led here.
        mDrainGeneration++;
        if (mPending.isEmpty()) {
            return;
        }
        long now = mScheduler.nowMs();
        long wait = waitMs(mPending.peekFirst().pacing, now);
        if (wait > 0) {
            schedule(wait);
            return;
        }
        Pending next = mPending.pollFirst();
        mHasStarted = true;
        mLastStartMs = now;
        mUnsettledOwner = next.owner;
        if (!mPending.isEmpty()) {
            // Scheduled before the start runs, so a start that throws does not strand the rest.
            schedule(waitMs(mPending.peekFirst().pacing, now));
        }
        next.start.run();
    }
}
