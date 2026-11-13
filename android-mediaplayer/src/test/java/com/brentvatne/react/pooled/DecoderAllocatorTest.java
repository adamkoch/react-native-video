package com.brentvatne.react.pooled;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DecoderAllocatorTest {

    private static final String A = "c2.vendor.avc.decoder";
    private static final String B = "c2.vendor.avc.decoder.lowlatency";

    @Test
    public void fillsTheFirstDecoderThenTheNext() {
        // Two decoders of four instances each, as the Google TV Streamer declares.
        assertEquals(
                Arrays.asList(A, A, A, A, B, B, B, B),
                DecoderAllocator.assign(Arrays.asList(A, B), Arrays.asList(4, 4), 8));
    }

    @Test
    public void givesNothingBeyondTheTotalCapacity() {
        DecoderAllocator allocator = new DecoderAllocator(Arrays.asList(A, B), Arrays.asList(4, 4));
        assertEquals(8, allocator.totalCapacity());
        for (int i = 0; i < 8; i++) {
            assertTrue(allocator.reserve() != null);
        }
        assertNull(allocator.reserve());
        assertNull(allocator.reserve());
        assertEquals(4, allocator.used(A));
        assertEquals(4, allocator.used(B));
        assertEquals(
                Arrays.asList(A, A, A, A, B, B, B, B, null, null),
                DecoderAllocator.assign(Arrays.asList(A, B), Arrays.asList(4, 4), 10));
    }

    @Test
    public void unevenCapacitiesKeepPlatformOrder() {
        assertEquals(
                Arrays.asList(A, A, B, B, B, null),
                DecoderAllocator.assign(Arrays.asList(A, B), Arrays.asList(2, 3), 6));
    }

    @Test
    public void noHardwareDecodersMeansNoReservations() {
        DecoderAllocator allocator =
                new DecoderAllocator(Collections.<String>emptyList(), Collections.<Integer>emptyList());
        assertEquals(0, allocator.totalCapacity());
        assertNull(allocator.reserve());
        assertFalse(allocator.knows(A));
    }

    @Test
    public void unknownCapacityCountsAsOneInstance() {
        assertEquals(DecoderAllocator.UNKNOWN_CAPACITY, DecoderAllocator.sanitizeCapacity(0));
        assertEquals(DecoderAllocator.UNKNOWN_CAPACITY, DecoderAllocator.sanitizeCapacity(-5));
        assertEquals(
                Arrays.asList(A, B, null),
                DecoderAllocator.assign(Arrays.asList(A, B), Arrays.asList(0, -1), 3));
    }

    @Test
    public void absurdCapacityIsClamped() {
        assertEquals(DecoderAllocator.MAX_CAPACITY_PER_DECODER, DecoderAllocator.sanitizeCapacity(Integer.MAX_VALUE));
        assertEquals(DecoderAllocator.MAX_CAPACITY_PER_DECODER, DecoderAllocator.sanitizeCapacity(1000));
        assertEquals(4, DecoderAllocator.sanitizeCapacity(4));
        DecoderAllocator allocator = new DecoderAllocator(Arrays.asList(A), Arrays.asList(1000));
        assertEquals(DecoderAllocator.MAX_CAPACITY_PER_DECODER, allocator.totalCapacity());
    }

    @Test
    public void fallbackMovesTheReservationToTheDecoderActuallyUsed() {
        DecoderAllocator allocator = new DecoderAllocator(Arrays.asList(A, B), Arrays.asList(4, 4));
        assertEquals(A, allocator.reserve());
        assertEquals(A, allocator.reserve());
        // The second player could not get A and fell back to B.
        allocator.reportActual(A, B);
        assertEquals(1, allocator.used(A));
        assertEquals(1, allocator.used(B));
        // A refused an instance, so it is treated as full from now on.
        assertEquals(1, allocator.capacity(A));
        assertEquals(5, allocator.totalCapacity());
        // Later players go to B until it is full.
        assertEquals(B, allocator.reserve());
        assertEquals(B, allocator.reserve());
        assertEquals(B, allocator.reserve());
        assertNull(allocator.reserve());
    }

    @Test
    public void reportingTheReservedDecoderChangesNothing() {
        DecoderAllocator allocator = new DecoderAllocator(Arrays.asList(A, B), Arrays.asList(4, 4));
        assertEquals(A, allocator.reserve());
        allocator.reportActual(A, A);
        assertEquals(1, allocator.used(A));
        assertEquals(4, allocator.capacity(A));
        assertEquals(8, allocator.totalCapacity());
    }

    @Test
    public void fallbackToAnUnknownDecoderFreesNothingElse() {
        DecoderAllocator allocator = new DecoderAllocator(Arrays.asList(A, B), Arrays.asList(4, 4));
        assertEquals(A, allocator.reserve());
        // A software decoder is not one the allocator counts.
        allocator.reportActual(A, "c2.android.avc.decoder");
        assertEquals(0, allocator.used(A));
        assertEquals(0, allocator.used(B));
        assertEquals(B, allocator.reserve());
    }

    @Test
    public void reservedDecoderIsTriedFirstAndTheRestKeepTheirOrder() {
        List<String> names = Arrays.asList(A, B, "c2.android.avc.decoder");
        assertEquals(
                Arrays.asList(B, A, "c2.android.avc.decoder"),
                DecoderAllocator.withFirst(names, names, B));
        assertEquals(names, DecoderAllocator.withFirst(names, names, A));
        assertEquals(names, DecoderAllocator.withFirst(names, names, "not.in.the.list"));
        assertEquals(names, DecoderAllocator.withFirst(names, names, null));
    }

    @Test
    public void aReleasedInstanceIsReservedAgainFirst() {
        DecoderAllocator allocator = new DecoderAllocator(Arrays.asList(A, B), Arrays.asList(4, 4));
        for (int i = 0; i < 8; i++) {
            allocator.reserve();
        }
        assertNull(allocator.reserve());
        allocator.release(B);
        assertEquals(3, allocator.used(B));
        assertEquals(B, allocator.reserve());
        assertNull(allocator.reserve());
        // Names the allocator does not know, and a decoder with nothing reserved, change nothing.
        allocator.release(null);
        allocator.release("c2.android.avc.decoder");
        assertEquals(8, allocator.used(A) + allocator.used(B));
        DecoderAllocator empty = new DecoderAllocator(Arrays.asList(A), Arrays.asList(4));
        empty.release(A);
        assertEquals(0, empty.used(A));
    }
}
