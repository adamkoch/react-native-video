package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SizeAffinityTest {

    private static final long WIDE = Mp4VideoSize.pack(854, 480);
    private static final long NARROW = Mp4VideoSize.pack(720, 480);

    @Test
    public void aPlayerAtTheWantedSizeIsChosenFirst() {
        assertEquals(2, SizeAffinity.choose(new long[] {NARROW, 0, WIDE}, WIDE));
    }

    @Test
    public void anUnusedPlayerIsNextBest() {
        assertEquals(1, SizeAffinity.choose(new long[] {NARROW, 0, 0}, WIDE));
    }

    @Test
    public void noPlayerIsChosenWhenEveryFreeOneWouldChangeSize() {
        assertEquals(-1, SizeAffinity.choose(new long[] {NARROW, NARROW}, WIDE));
        assertEquals(-1, SizeAffinity.choose(new long[] {}, WIDE));
    }

    @Test
    public void anUnknownClipSizeTakesTheFirstFreePlayer() {
        assertEquals(0, SizeAffinity.choose(new long[] {NARROW, WIDE}, 0));
    }

    @Test
    public void onlyAKnownDifferentSizeCountsAsASwitch() {
        assertTrue(SizeAffinity.wouldSwitch(NARROW, WIDE));
        assertFalse(SizeAffinity.wouldSwitch(WIDE, WIDE));
        assertFalse(SizeAffinity.wouldSwitch(0, WIDE));
        assertFalse(SizeAffinity.wouldSwitch(WIDE, 0));
    }
}
