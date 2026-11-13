package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RebuildBackoffTest {

    private final RebuildBackoff backoff = new RebuildBackoff();

    @Test
    public void theFirstRebuildIsAllowedAtOnce() {
        assertEquals(0, backoff.waitMs(5000));
        assertTrue(backoff.allow(5000));
    }

    @Test
    public void eachFurtherRebuildWaitsTwiceAsLongUpToTheMaximum() {
        long now = 5000;
        assertTrue(backoff.allow(now));
        long wait = RebuildBackoff.FIRST_WAIT_MS;
        for (int i = 0; i < 12; i++) {
            assertFalse(backoff.allow(now + wait - 1));
            assertEquals(1, backoff.waitMs(now + wait - 1));
            now += wait;
            assertTrue(backoff.allow(now));
            wait = Math.min(wait * 2, RebuildBackoff.MAX_WAIT_MS);
        }
        assertEquals(RebuildBackoff.MAX_WAIT_MS, wait);
    }

    @Test
    public void aRefusedRebuildDoesNotMakeTheWaitLonger() {
        assertTrue(backoff.allow(0));
        for (long now = 1000; now < RebuildBackoff.FIRST_WAIT_MS; now += 1000) {
            assertFalse(backoff.allow(now));
        }
        assertTrue(backoff.allow(RebuildBackoff.FIRST_WAIT_MS));
    }

    @Test
    public void aQuietSpellForgetsTheWait() {
        long now = 0;
        assertTrue(backoff.allow(now));
        now += RebuildBackoff.FIRST_WAIT_MS;
        assertTrue(backoff.allow(now));
        now += RebuildBackoff.QUIET_MS;
        assertTrue(backoff.allow(now));
        // Back to the first wait, not the third.
        assertFalse(backoff.allow(now + RebuildBackoff.FIRST_WAIT_MS - 1));
        assertTrue(backoff.allow(now + RebuildBackoff.FIRST_WAIT_MS));
    }
}
