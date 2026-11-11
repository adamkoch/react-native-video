package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Uses a one-thread executor as the playback thread and a second as the main thread's queue:
 * like the real ones, each handles what it is sent in order. This checks the order of the
 * steps in {@link PooledVideoView}'s load, not the player itself.
 */
public class PlaybackHoldTest {

    @Test
    public void nothingRunsBetweenTheCommandsQueuedDuringAHold() throws Exception {
        ExecutorService playback = Executors.newSingleThreadExecutor();
        List<String> handled = new ArrayList<>();
        PlaybackHold hold = new PlaybackHold();

        playback.execute(hold::await);
        playback.execute(() -> handled.add("source"));
        // The main thread is held up here. Without the hold the playback thread would
        // already have replaced the source, and could do other work before the marker.
        Thread.sleep(50);
        playback.execute(() -> handled.add("prepare"));
        playback.execute(() -> handled.add("marker"));
        playback.execute(() -> handled.add("later work"));
        hold.release();

        playback.shutdown();
        assertTrue(playback.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("source", "prepare", "marker", "later work"), handled);
    }

    @Test
    public void aPreviousSourceFrameRenderedWhileTheMainThreadIsHeldUpIsIgnored()
            throws Exception {
        ExecutorService playback = Executors.newSingleThreadExecutor();
        ExecutorService main = Executors.newSingleThreadExecutor();
        FirstFrameGate gate = new FirstFrameGate();
        PlaybackHold hold = new PlaybackHold();
        boolean[] sourceReplaced = {false};

        // The previous source is looping: the playback thread renders a first frame of it
        // whenever it gets the chance before the source is replaced.
        Runnable previousSourceRenders = () -> {
            if (!sourceReplaced[0]) {
                main.execute(gate::onFirstFrame);
            }
        };

        int load = gate.onLoad();
        playback.execute(hold::await);
        playback.execute(previousSourceRenders);
        Thread.sleep(50); // the main thread is held up before it replaces the source
        playback.execute(() -> sourceReplaced[0] = true);
        playback.execute(() -> main.execute(() -> gate.onMarker(load)));
        playback.execute(previousSourceRenders);
        hold.release();

        playback.shutdown();
        assertTrue(playback.awaitTermination(5, TimeUnit.SECONDS));
        main.shutdown();
        assertTrue(main.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(gate.frameRendered());
    }

    @Test
    public void aThreadInterruptedWhileHeldStaysInterrupted() throws Exception {
        PlaybackHold hold = new PlaybackHold();
        boolean[] interrupted = {false};
        Thread thread = new Thread(() -> {
            hold.await();
            interrupted[0] = Thread.currentThread().isInterrupted();
        });
        thread.start();
        thread.interrupt();
        thread.join(5_000);
        assertTrue(interrupted[0]);
    }
}
