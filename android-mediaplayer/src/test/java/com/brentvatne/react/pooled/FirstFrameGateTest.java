package com.brentvatne.react.pooled;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FirstFrameGateTest {

    @Test
    public void acceptsAFrameAfterTheMarker() {
        FirstFrameGate gate = new FirstFrameGate();
        gate.onMarker(gate.onLoad());
        assertFalse(gate.frameRendered());
        gate.onFirstFrame();
        assertTrue(gate.frameRendered());
    }

    @Test
    public void ignoresThePreviousSourcesFrameArrivingAfterTheNextLoad() {
        FirstFrameGate gate = new FirstFrameGate();
        gate.onMarker(gate.onLoad());
        gate.onFirstFrame();
        int second = gate.onLoad();
        gate.onFirstFrame(); // the first source's, still on its way when the second was loaded
        assertFalse(gate.frameRendered());
        gate.onMarker(second);
        assertFalse(gate.frameRendered());
        gate.onFirstFrame();
        assertTrue(gate.frameRendered());
    }

    @Test
    public void ignoresAnEarlierLoadsMarker() {
        FirstFrameGate gate = new FirstFrameGate();
        int first = gate.onLoad();
        int second = gate.onLoad();
        gate.onMarker(first);
        gate.onFirstFrame(); // may still be the first source's
        assertFalse(gate.frameRendered());
        gate.onMarker(second);
        gate.onFirstFrame();
        assertTrue(gate.frameRendered());
    }
}
