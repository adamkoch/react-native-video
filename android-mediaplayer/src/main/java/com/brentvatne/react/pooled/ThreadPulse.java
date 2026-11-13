package com.brentvatne.react.pooled;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tells whether the thread that every pooled player plays on still answers.
 *
 * <p>A decoder call can block that thread for good. Nothing reports it: every player still says
 * it is playing, no player shows a frame, and a player created to replace one of them never
 * starts, because it would start on the same thread. So while views are being checked for
 * stalls, the thread is asked a question at most every {@link #ASK_EVERY_MS}, and a question
 * that has gone unanswered for {@link #STUCK_MS} means the thread is stuck.
 *
 * <p>This class has no Android dependencies so that it can be tested on the JVM. Times are from
 * a clock that stops while the device sleeps. All methods are called on the main thread, except
 * {@link #answer}.
 */
final class ThreadPulse {

    /** The least time between two questions. */
    static final long ASK_EVERY_MS = 2000;
    /** A question unanswered for this long means the thread is stuck. */
    static final long STUCK_MS = 5000;

    private long mAskedMs = -1;
    private int mAsked;
    private final AtomicInteger mAnswered = new AtomicInteger();

    /**
     * @return the number of the question to put to the thread now, or 0 if none is due: the
     *     last one is not answered yet, or was asked too recently.
     */
    int ask(long nowMs) {
        if (mAsked != mAnswered.get()) {
            return 0;
        }
        if (mAskedMs >= 0 && nowMs - mAskedMs < ASK_EVERY_MS) {
            return 0;
        }
        mAskedMs = nowMs;
        return ++mAsked;
    }

    /** Called on the thread that was asked. An answer to an old question changes nothing. */
    void answer(int question) {
        mAnswered.accumulateAndGet(question, Math::max);
    }

    /** How long the thread has left a question unanswered; 0 if it owes no answer. */
    long silentMs(long nowMs) {
        return mAsked != mAnswered.get() ? nowMs - mAskedMs : 0;
    }

    boolean stuck(long nowMs) {
        return silentMs(nowMs) >= STUCK_MS;
    }

    /** The questions go to a new thread from now on; the old one owes nothing. */
    void reset() {
        mAnswered.accumulateAndGet(mAsked, Math::max);
        mAskedMs = -1;
    }
}
