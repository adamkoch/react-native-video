package com.brentvatne.react.pooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which hardware decoder each pooled player should use, for one MIME type.
 *
 * A device has a few hardware decoders for a MIME type, each with a limit on concurrent
 * instances, and creating one instance too many fails. Players are therefore given a decoder
 * when they are created: the first decoder in platform order is filled to its capacity, then the
 * next. A reservation lasts as long as its player, which is normally the whole process; a
 * player that is replaced gives its reservation back with {@link #release}.
 *
 * This class has no Android dependencies so that it can be tested on the JVM. It is thread-safe.
 */
public final class DecoderAllocator {

    /** Used when a device reports no usable limit: assume only one instance is safe. */
    public static final int UNKNOWN_CAPACITY = 1;

    /** No decoder is trusted with more instances than this, whatever the device reports. */
    public static final int MAX_CAPACITY_PER_DECODER = 16;

    private final String[] mNames;
    private final int[] mCapacity;
    private final int[] mUsed;

    /**
     * @param names decoder names in platform preference order.
     * @param reportedCapacities the concurrent instance limit each one reports, in the same
     *     order; see {@link #sanitizeCapacity}.
     */
    public DecoderAllocator(List<String> names, List<Integer> reportedCapacities) {
        if (names.size() != reportedCapacities.size()) {
            throw new IllegalArgumentException("names and capacities differ in length");
        }
        mNames = names.toArray(new String[0]);
        mCapacity = new int[mNames.length];
        mUsed = new int[mNames.length];
        for (int i = 0; i < mNames.length; i++) {
            Integer reported = reportedCapacities.get(i);
            mCapacity[i] = sanitizeCapacity(reported == null ? 0 : reported);
        }
    }

    /**
     * Turns a reported instance limit into one to rely on: a missing, zero or negative value
     * becomes {@link #UNKNOWN_CAPACITY}, and anything above {@link #MAX_CAPACITY_PER_DECODER} is
     * reduced to it.
     */
    public static int sanitizeCapacity(int reported) {
        if (reported <= 0) {
            return UNKNOWN_CAPACITY;
        }
        return Math.min(reported, MAX_CAPACITY_PER_DECODER);
    }

    /**
     * Reserves an instance of the first decoder that has one left.
     *
     * @return the decoder name, or null if every decoder is full.
     */
    public synchronized String reserve() {
        for (int i = 0; i < mNames.length; i++) {
            if (mUsed[i] < mCapacity[i]) {
                mUsed[i]++;
                return mNames[i];
            }
        }
        return null;
    }

    /**
     * Gives back an instance of a decoder, reserved or reported as actually used, because its
     * player is gone. Does nothing for a null or unknown name.
     */
    public synchronized void release(String name) {
        int i = indexOf(name);
        if (i >= 0) {
            mUsed[i] = Math.max(0, mUsed[i] - 1);
        }
    }

    /**
     * Corrects the accounting when a player turned out to run on another decoder than the one
     * reserved for it (decoder fallback, because the device or another app left no instance).
     *
     * The reserved decoder is treated as full from now on, since it evidently could not take
     * this player; its remaining players keep their reservations. The decoder actually used
     * gains a player if it is one of ours, even beyond its reported capacity.
     *
     * @param reserved the name that was reserved, or null if there was none.
     * @param actual the decoder the player really uses; may be a decoder unknown to this
     *     allocator, such as a software decoder.
     */
    public synchronized void reportActual(String reserved, String actual) {
        if (actual == null || actual.equals(reserved)) {
            return;
        }
        int from = indexOf(reserved);
        if (from >= 0) {
            mUsed[from] = Math.max(0, mUsed[from] - 1);
            mCapacity[from] = mUsed[from];
        }
        int to = indexOf(actual);
        if (to >= 0) {
            mUsed[to]++;
        }
    }

    /** The number of players all decoders can take together. */
    public synchronized int totalCapacity() {
        int total = 0;
        for (int capacity : mCapacity) {
            total += capacity;
        }
        return total;
    }

    /** How many players are accounted to a decoder; 0 for an unknown name. */
    public synchronized int used(String name) {
        int i = indexOf(name);
        return i < 0 ? 0 : mUsed[i];
    }

    /** The capacity relied on for a decoder; 0 for an unknown name. */
    public synchronized int capacity(String name) {
        int i = indexOf(name);
        return i < 0 ? 0 : mCapacity[i];
    }

    public boolean knows(String name) {
        return indexOf(name) >= 0;
    }

    /**
     * Capacities in, assignments out: the decoder each of {@code players} players gets, in
     * creation order, with null for players beyond the total capacity.
     */
    public static List<String> assign(List<String> names, List<Integer> reportedCapacities, int players) {
        DecoderAllocator allocator = new DecoderAllocator(names, reportedCapacities);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            out.add(allocator.reserve());
        }
        return out;
    }

    /**
     * Returns {@code names} with {@code first} moved to the front and the rest in their original
     * order. The list is returned unchanged if {@code first} is null or not in it.
     */
    public static <T> List<T> withFirst(List<T> items, List<String> names, String first) {
        int index = first == null ? -1 : names.indexOf(first);
        if (index <= 0) {
            return items;
        }
        List<T> out = new ArrayList<>(items.size());
        out.add(items.get(index));
        for (int i = 0; i < items.size(); i++) {
            if (i != index) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    private int indexOf(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < mNames.length; i++) {
            if (mNames[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mNames.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(mNames[i]).append(' ').append(mUsed[i]).append('/').append(mCapacity[i]);
        }
        return sb.toString();
    }
}
