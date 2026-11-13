package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;

import com.brentvatne.react.pooled.StallWatch.Verdict;

import org.junit.Test;

public class StallWatchTest {

    private final StallWatch watch = new StallWatch();
    private long now = 1000;
    private long frames = 0;

    /** One check after {@link StallWatch#CHECK_MS}, with or without new frames. */
    private Verdict tick(boolean expected, boolean newFrames) {
        now += StallWatch.CHECK_MS;
        if (newFrames) {
            frames += 60;
        }
        return watch.check(expected, frames, now);
    }

    /** Checks without new frames until the verdict is no longer OK, or for a minute. */
    private Verdict stallUntilVerdict() {
        for (int i = 0; i < 30; i++) {
            Verdict verdict = tick(true, false);
            if (verdict != Verdict.OK) {
                return verdict;
            }
        }
        return Verdict.OK;
    }

    @Test
    public void aPlayingVideoIsLeftAlone() {
        for (int i = 0; i < 1000; i++) {
            assertEquals(Verdict.OK, tick(true, true));
        }
        assertEquals(0, watch.replacements());
    }

    @Test
    public void aVideoThatShouldNotPlayIsNeverStalled() {
        for (int i = 0; i < 1000; i++) {
            assertEquals(Verdict.OK, tick(false, false));
        }
    }

    @Test
    public void noFramesForTheStallTimeReplacesThePlayer() {
        tick(true, true);
        long stalledAt = now;
        assertEquals(Verdict.REPLACE, stallUntilVerdict());
        assertEquals(StallWatch.STALL_MS, now - stalledAt);
    }

    @Test
    public void aVideoThatNeverShowsAFrameIsStalledToo() {
        long startedAt = now + StallWatch.CHECK_MS;
        assertEquals(Verdict.REPLACE, stallUntilVerdict());
        assertEquals(StallWatch.STALL_MS, now - startedAt);
    }

    @Test
    public void aShortGapIsNotAStall() {
        tick(true, true);
        for (int i = 0; i < 100; i++) {
            assertEquals(Verdict.OK, tick(true, false));
            assertEquals(Verdict.OK, tick(true, false));
            assertEquals(Verdict.OK, tick(true, false));
            assertEquals(Verdict.OK, tick(true, true));
        }
    }

    @Test
    public void timeSpentPausedDoesNotCount() {
        tick(true, true);
        tick(true, false);
        tick(true, false);
        tick(true, false);
        for (int i = 0; i < 100; i++) {
            assertEquals(Verdict.OK, tick(false, false));
        }
        // Playing again: the stall time starts from here.
        long resumedAt = now + StallWatch.CHECK_MS;
        assertEquals(Verdict.REPLACE, stallUntilVerdict());
        assertEquals(StallWatch.STALL_MS, now - resumedAt);
    }

    @Test
    public void givesUpAfterTheAllowedReplacements() {
        for (int i = 0; i < StallWatch.MAX_REPLACEMENTS; i++) {
            assertEquals(Verdict.REPLACE, stallUntilVerdict());
        }
        assertEquals(Verdict.FAIL, stallUntilVerdict());
    }

    @Test
    public void aFewFramesAfterAReplacementDoNotEarnMoreReplacements() {
        for (int i = 0; i < StallWatch.MAX_REPLACEMENTS; i++) {
            assertEquals(Verdict.REPLACE, stallUntilVerdict());
            assertEquals(Verdict.OK, tick(true, true));
            assertEquals(Verdict.OK, tick(true, true));
        }
        assertEquals(Verdict.FAIL, stallUntilVerdict());
    }

    @Test
    public void aReplacementThatWorkedIsForgotten() {
        for (int round = 0; round < 10; round++) {
            assertEquals(Verdict.REPLACE, stallUntilVerdict());
            for (long played = 0; played <= StallWatch.HEALTHY_MS + StallWatch.CHECK_MS; played += StallWatch.CHECK_MS) {
                assertEquals(Verdict.OK, tick(true, true));
            }
            assertEquals(0, watch.replacements());
        }
    }

    @Test
    public void aNewSourceStartsAgain() {
        for (int i = 0; i < StallWatch.MAX_REPLACEMENTS; i++) {
            assertEquals(Verdict.REPLACE, stallUntilVerdict());
        }
        watch.reset();
        assertEquals(Verdict.REPLACE, stallUntilVerdict());
    }

    @Test
    public void errorsAndStallsShareTheReplacements() {
        assertEquals(Verdict.REPLACE, watch.giveUpOnPlayer());
        assertEquals(Verdict.REPLACE, stallUntilVerdict());
        assertEquals(Verdict.FAIL, watch.giveUpOnPlayer());
    }

    @Test
    public void aFrameCountThatStartsAgainIsStillNewFrames() {
        tick(true, true);
        frames = 0;
        assertEquals(Verdict.OK, watch.check(true, frames, now += StallWatch.CHECK_MS));
        assertEquals(Verdict.OK, tick(true, true));
    }
}
