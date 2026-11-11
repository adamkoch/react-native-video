package com.brentvatne.react.pooled;

import java.util.concurrent.CountDownLatch;

/**
 * Keeps a player's playback thread waiting while the main thread queues several commands, so
 * that the playback thread then handles them one after the other with nothing in between.
 *
 * <p>The main thread sends {@link #await} to the playback thread, queues its commands, and
 * calls {@link #release} in a {@code finally}. None of those commands may wait for the
 * playback thread.
 */
final class PlaybackHold {

    private final CountDownLatch mReleased = new CountDownLatch(1);

    /** Runs on the playback thread. Returns once {@link #release} has been called. */
    void await() {
        try {
            mReleased.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void release() {
        mReleased.countDown();
    }
}
