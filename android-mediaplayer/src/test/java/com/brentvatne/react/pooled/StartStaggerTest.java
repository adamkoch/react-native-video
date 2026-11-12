package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class StartStaggerTest {

    private static final long DELAY_MS = 300;
    private static final long MAX_WAIT_MS = 1500;
    /** Paced from the first frame of the video before. */
    private static final StartStagger.Pacing FRAME = new StartStagger.Pacing(true, DELAY_MS);
    /** Paced from the start of the video before. */
    private static final StartStagger.Pacing FIXED = new StartStagger.Pacing(false, DELAY_MS);

    /** A main thread whose time only moves when the test moves it. */
    private static final class FakeMainThread implements StartStagger.Scheduler {
        private static final class Posted {
            final Runnable runnable;
            final long atMs;

            Posted(Runnable runnable, long atMs) {
                this.runnable = runnable;
                this.atMs = atMs;
            }
        }

        long nowMs = 1000;
        private final List<Posted> mPosted = new ArrayList<>();

        @Override
        public long nowMs() {
            return nowMs;
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            mPosted.add(new Posted(runnable, nowMs + delayMs));
        }

        /** Moves time forward, running what falls due in time order. */
        void advance(long ms) {
            long end = nowMs + ms;
            while (true) {
                Posted next = null;
                for (Posted posted : mPosted) {
                    if (posted.atMs <= end && (next == null || posted.atMs < next.atMs)) {
                        next = posted;
                    }
                }
                if (next == null) {
                    break;
                }
                mPosted.remove(next);
                nowMs = Math.max(nowMs, next.atMs);
                next.runnable.run();
            }
            nowMs = end;
        }
    }

    private final FakeMainThread mMain = new FakeMainThread();
    private final StartStagger mStagger = new StartStagger(mMain, MAX_WAIT_MS);
    private final List<String> mStarted = new ArrayList<>();
    private final List<Long> mStartedAt = new ArrayList<>();

    private Runnable start(String name) {
        return () -> {
            mStarted.add(name);
            mStartedAt.add(mMain.nowMs);
        };
    }

    // ---- No stagger ----

    @Test
    public void videosWithoutPacingAllStartInsideTheirRequests() {
        for (String name : Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h")) {
            mStagger.request(name, start(name), null);
        }

        assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h"), mStarted);
        assertEquals(0, mStagger.waiting());
    }

    @Test
    public void aVideoWithoutPacingDoesNotWaitForPacedOnesNorHoldThemUp() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("b", start("b"), FRAME);
        mStagger.request("plain", start("plain"), null);
        assertEquals(Arrays.asList("a", "plain"), mStarted);

        mStagger.settled("plain");
        mMain.advance(DELAY_MS);
        assertEquals(Arrays.asList("a", "plain"), mStarted);

        mStagger.settled("a");
        mMain.advance(DELAY_MS);
        assertEquals(Arrays.asList("a", "plain", "b"), mStarted);
    }

    @Test
    public void askingAgainWithoutPacingStartsAtOnceAndDropsTheWaitingStart() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("b", start("b paced"), FRAME);
        mStagger.request("b", start("b at once"), null);

        mStagger.settled("a");
        mMain.advance(MAX_WAIT_MS);

        assertEquals(Arrays.asList("a", "b at once"), mStarted);
        assertEquals(0, mStagger.waiting());
    }

    // ---- Paced from the first frame ----

    @Test
    public void aSinglePacedVideoStartsInsideTheRequest() {
        mStagger.request("a", start("a"), FRAME);

        assertEquals(Collections.singletonList("a"), mStarted);
        assertEquals(0, mStagger.waiting());
    }

    @Test
    public void eachVideoStartsTheDelayAfterTheOneBeforeShowedItsFirstFrame() {
        for (String name : Arrays.asList("a", "b", "c")) {
            mStagger.request(name, start(name), FRAME);
        }
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(400);
        assertEquals(Collections.singletonList("a"), mStarted);
        mStagger.settled("a");
        mMain.advance(DELAY_MS - 1);
        assertEquals(Collections.singletonList("a"), mStarted);
        mMain.advance(1);
        assertEquals(Arrays.asList("a", "b"), mStarted);

        mMain.advance(200);
        mStagger.settled("b");
        mMain.advance(DELAY_MS);

        assertEquals(Arrays.asList("a", "b", "c"), mStarted);
        assertEquals(Arrays.asList(1000L, 1700L, 2200L), mStartedAt);
    }

    @Test
    public void aVideoThatNeverShowsAFrameHoldsUpTheNextForTheLongestWaitOnly() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("b", start("b"), FRAME);
        mStagger.request("c", start("c"), FRAME);

        mMain.advance(MAX_WAIT_MS - 1);
        assertEquals(Collections.singletonList("a"), mStarted);
        mMain.advance(1);
        assertEquals(Arrays.asList("a", "b"), mStarted);
        mMain.advance(MAX_WAIT_MS);

        assertEquals(Arrays.asList("a", "b", "c"), mStarted);
    }

    @Test
    public void aLateFirstFrameOfAnEarlierVideoChangesNothing() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("b", start("b"), FRAME);
        mStagger.request("c", start("c"), FRAME);
        mMain.advance(MAX_WAIT_MS);
        assertEquals(Arrays.asList("a", "b"), mStarted);

        mStagger.settled("a");
        mMain.advance(DELAY_MS);
        assertEquals(Arrays.asList("a", "b"), mStarted);

        mStagger.settled("b");
        mMain.advance(DELAY_MS);
        assertEquals(Arrays.asList("a", "b", "c"), mStarted);
    }

    @Test
    public void aVideoAskedForLongAfterTheLastSettledStartsInsideTheRequest() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.settled("a");
        mMain.advance(DELAY_MS);
        mStagger.request("b", start("b"), FRAME);

        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aVideoAskedForSoonAfterAFirstFrameWaitsOutTheRestOfTheDelay() {
        mStagger.request("a", start("a"), FRAME);
        mMain.advance(100);
        mStagger.settled("a");
        mMain.advance(100);
        mStagger.request("b", start("b"), FRAME);
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(DELAY_MS - 100);
        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aDelayOfZeroStartsTheNextVideoOnTheFirstFrame() {
        StartStagger.Pacing noDelay = new StartStagger.Pacing(true, 0);
        mStagger.request("a", start("a"), noDelay);
        mStagger.request("b", start("b"), noDelay);
        assertEquals(Collections.singletonList("a"), mStarted);

        mStagger.settled("a");

        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    // ---- Paced from the start ----

    @Test
    public void withoutWaitingForFramesVideosStartTheDelayApart() {
        for (String name : Arrays.asList("a", "b", "c", "d")) {
            mStagger.request(name, start(name), FIXED);
        }

        mMain.advance(5000);

        assertEquals(Arrays.asList("a", "b", "c", "d"), mStarted);
        assertEquals(Arrays.asList(1000L, 1300L, 1600L, 1900L), mStartedAt);
    }

    @Test
    public void eachVideoIsPacedByItsOwnSetting() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("b", start("b"), new StartStagger.Pacing(false, 100));
        mStagger.request("c", start("c"), new StartStagger.Pacing(true, 50));

        mMain.advance(100);
        assertEquals(Arrays.asList("a", "b"), mStarted);
        mMain.advance(500);
        assertEquals(Arrays.asList("a", "b"), mStarted);
        mStagger.settled("b");
        mMain.advance(50);

        assertEquals(Arrays.asList("a", "b", "c"), mStarted);
    }

    // ---- Cancelling and asking again ----

    @Test
    public void aCancelledStartNeverRunsAndDoesNotHoldUpTheOthers() {
        mStagger.request("a", start("a"), FIXED);
        mStagger.request("b", start("b"), FIXED);
        mStagger.request("c", start("c"), FIXED);
        assertTrue(mStagger.isWaiting("b"));
        mStagger.cancel("b");
        mStagger.cancel("not waiting");
        assertFalse(mStagger.isWaiting("b"));

        mMain.advance(DELAY_MS);

        assertEquals(Arrays.asList("a", "c"), mStarted);
        assertEquals(0, mStagger.waiting());
    }

    @Test
    public void aVideoCancelledBeforeItsFirstFrameIsNotWaitedFor() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("b", start("b"), FRAME);
        mMain.advance(100);
        mStagger.cancel("a");
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(DELAY_MS - 100);

        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aStartThatCancelsItselfLetsTheNextOneStartAfterTheDelay() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("no player", () -> mStagger.cancel("no player"), FRAME);
        mStagger.request("c", start("c"), FRAME);
        mStagger.settled("a");
        mMain.advance(DELAY_MS);
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(DELAY_MS);

        assertEquals(Arrays.asList("a", "c"), mStarted);
    }

    @Test
    public void cancellingEveryWaitingStartLeavesTheNextRequestWorking() {
        mStagger.request("a", start("a"), FIXED);
        mStagger.request("b", start("b"), FIXED);
        mStagger.cancel("b");
        mMain.advance(DELAY_MS);

        mStagger.request("c", start("c"), FIXED);
        mStagger.request("d", start("d"), FIXED);
        mMain.advance(DELAY_MS);

        assertEquals(Arrays.asList("a", "c", "d"), mStarted);
    }

    @Test
    public void askingAgainKeepsThePlaceAndRunsOnlyTheLatestStart() {
        mStagger.request("a", start("a"), FIXED);
        mStagger.request("b", start("b first source"), FIXED);
        mStagger.request("c", start("c"), FIXED);
        mStagger.request("b", start("b second source"), FIXED);

        mMain.advance(1000);

        assertEquals(Arrays.asList("a", "b second source", "c"), mStarted);
    }

    @Test
    public void askingAgainWithAnotherPacingAppliesIt() {
        mStagger.request("a", start("a"), FRAME);
        mStagger.request("b", start("b"), FRAME);
        mStagger.request("b", start("b"), new StartStagger.Pacing(false, 100));

        mMain.advance(100);

        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aVideoGivenANewSourceBeforeItsFirstFrameIsNotWaitedForTwice() {
        mStagger.request("a", start("a first source"), FRAME);
        mStagger.request("b", start("b"), FRAME);
        mMain.advance(100);
        mStagger.request("a", start("a second source"), FRAME);

        mMain.advance(DELAY_MS);
        assertEquals(Arrays.asList("a first source", "b"), mStarted);
        mStagger.settled("b");
        mMain.advance(DELAY_MS);

        assertEquals(Arrays.asList("a first source", "b", "a second source"), mStarted);
    }

    @Test
    public void aStartMayAskForAnotherStart() {
        mStagger.request("a", () -> {
            mStarted.add("a");
            mStagger.request("b", start("b"), FIXED);
        }, FIXED);
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(DELAY_MS);

        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aStartThatThrowsDoesNotStrandTheOthers() {
        mStagger.request("a", start("a"), FIXED);
        mStagger.request("b", () -> {
            throw new IllegalStateException("player failed");
        }, FIXED);
        mStagger.request("c", start("c"), FIXED);

        boolean thrown = false;
        try {
            mMain.advance(DELAY_MS);
        } catch (IllegalStateException e) {
            thrown = true;
        }
        assertTrue(thrown);
        mMain.advance(DELAY_MS);

        assertEquals(Arrays.asList("a", "c"), mStarted);
    }
}
