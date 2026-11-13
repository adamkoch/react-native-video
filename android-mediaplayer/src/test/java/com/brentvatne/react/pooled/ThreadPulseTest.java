package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ThreadPulseTest {

    private final ThreadPulse pulse = new ThreadPulse();

    @Test
    public void aThreadThatAnswersIsNotStuck() {
        long now = 1000;
        for (int i = 0; i < 100; i++) {
            int question = pulse.ask(now);
            assertTrue(question > 0);
            pulse.answer(question);
            now += ThreadPulse.ASK_EVERY_MS;
            assertEquals(0, pulse.silentMs(now));
            assertFalse(pulse.stuck(now));
        }
    }

    @Test
    public void questionsAreSpacedOut() {
        int question = pulse.ask(1000);
        pulse.answer(question);
        assertEquals(0, pulse.ask(1000 + ThreadPulse.ASK_EVERY_MS - 1));
        assertTrue(pulse.ask(1000 + ThreadPulse.ASK_EVERY_MS) > 0);
    }

    @Test
    public void anUnansweredQuestionIsNotAskedAgainAndMeansStuckInTime() {
        assertTrue(pulse.ask(1000) > 0);
        assertEquals(0, pulse.ask(1000 + 10 * ThreadPulse.ASK_EVERY_MS));
        assertFalse(pulse.stuck(1000 + ThreadPulse.STUCK_MS - 1));
        assertTrue(pulse.stuck(1000 + ThreadPulse.STUCK_MS));
        assertEquals(ThreadPulse.STUCK_MS, pulse.silentMs(1000 + ThreadPulse.STUCK_MS));
    }

    @Test
    public void aLateAnswerEndsTheSilence() {
        int question = pulse.ask(1000);
        assertTrue(pulse.stuck(60_000));
        pulse.answer(question);
        assertFalse(pulse.stuck(60_000));
        assertTrue(pulse.ask(60_000) > 0);
    }

    @Test
    public void afterAResetTheOldThreadsAnswerChangesNothing() {
        int old = pulse.ask(1000);
        assertTrue(pulse.stuck(10_000));
        pulse.reset();
        assertFalse(pulse.stuck(10_000));
        int fresh = pulse.ask(10_000);
        assertTrue(fresh > old);
        // The thread that was given up on comes back to life.
        pulse.answer(old);
        assertTrue(pulse.stuck(10_000 + ThreadPulse.STUCK_MS));
        pulse.answer(fresh);
        assertFalse(pulse.stuck(10_000 + ThreadPulse.STUCK_MS));
        // And an old answer after a newer one does not undo it.
        pulse.answer(old);
        assertEquals(0, pulse.silentMs(10_000 + ThreadPulse.STUCK_MS));
    }
}
