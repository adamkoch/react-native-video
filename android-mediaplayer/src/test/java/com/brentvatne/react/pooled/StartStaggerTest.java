package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class StartStaggerTest {

    private static final long GAP_MS = 60;

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
    private final StartStagger mStagger = new StartStagger(mMain, GAP_MS);
    private final List<String> mStarted = new ArrayList<>();
    private final List<Long> mStartedAt = new ArrayList<>();

    private Runnable start(String name) {
        return () -> {
            mStarted.add(name);
            mStartedAt.add(mMain.nowMs);
        };
    }

    @Test
    public void aSingleVideoStartsInsideTheRequest() {
        mStagger.request("a", start("a"));

        assertEquals(Collections.singletonList("a"), mStarted);
        assertEquals(0, mStagger.waiting());
    }

    @Test
    public void videosAskedForTogetherStartInOrderOneGapApart() {
        for (String name : Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h")) {
            mStagger.request(name, start(name));
        }
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(1000);

        assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h"), mStarted);
        for (int i = 0; i < mStartedAt.size(); i++) {
            assertEquals(1000 + i * GAP_MS, (long) mStartedAt.get(i));
        }
    }

    @Test
    public void aVideoAskedForSoonAfterAnotherWaitsOutTheRestOfTheGap() {
        mStagger.request("a", start("a"));
        mMain.advance(20);
        mStagger.request("b", start("b"));
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(39);
        assertEquals(Collections.singletonList("a"), mStarted);
        mMain.advance(1);
        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aVideoAskedForAfterTheGapStartsInsideTheRequest() {
        mStagger.request("a", start("a"));
        mMain.advance(GAP_MS);
        mStagger.request("b", start("b"));

        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aCancelledStartNeverRunsAndDoesNotHoldUpTheOthers() {
        mStagger.request("a", start("a"));
        mStagger.request("b", start("b"));
        mStagger.request("c", start("c"));
        mStagger.cancel("b");
        mStagger.cancel("not waiting");

        mMain.advance(GAP_MS);

        assertEquals(Arrays.asList("a", "c"), mStarted);
        assertEquals(0, mStagger.waiting());
    }

    @Test
    public void cancellingEveryWaitingStartLeavesTheNextRequestWorking() {
        mStagger.request("a", start("a"));
        mStagger.request("b", start("b"));
        mStagger.cancel("b");
        mMain.advance(GAP_MS);

        mStagger.request("c", start("c"));
        mStagger.request("d", start("d"));
        mMain.advance(GAP_MS);

        assertEquals(Arrays.asList("a", "c", "d"), mStarted);
    }

    @Test
    public void askingAgainKeepsThePlaceAndRunsOnlyTheLatestStart() {
        mStagger.request("a", start("a"));
        mStagger.request("b", start("b first source"));
        mStagger.request("c", start("c"));
        mStagger.request("b", start("b second source"));

        mMain.advance(1000);

        assertEquals(Arrays.asList("a", "b second source", "c"), mStarted);
    }

    @Test
    public void aStartMayAskForAnotherStart() {
        mStagger.request("a", () -> {
            mStarted.add("a");
            mStagger.request("b", start("b"));
        });
        assertEquals(Collections.singletonList("a"), mStarted);

        mMain.advance(GAP_MS);

        assertEquals(Arrays.asList("a", "b"), mStarted);
    }

    @Test
    public void aStartThatThrowsDoesNotStrandTheOthers() {
        mStagger.request("a", start("a"));
        mStagger.request("b", () -> {
            throw new IllegalStateException("player failed");
        });
        mStagger.request("c", start("c"));

        boolean thrown = false;
        try {
            mMain.advance(GAP_MS);
        } catch (IllegalStateException e) {
            thrown = true;
        }
        assertTrue(thrown);
        mMain.advance(GAP_MS);

        assertEquals(Arrays.asList("a", "c"), mStarted);
    }
}
