package com.brentvatne.react.pooled;

/**
 * Which free player a view should get so that no decoder changes picture size.
 *
 * Some hardware decoders (an Amlogic decoder on a Chromecast with Google TV, November 2026) fail
 * when a reused codec moves from one coded size to another: a hung stop, a codec service crash,
 * even a kernel panic. Same-size changes of clip, and changes of frame rate, were fine for hours.
 * So a view about to play a clip of another size than its player last decoded is given a free
 * player that last decoded that size, or one that has decoded nothing yet.
 */
final class SizeAffinity {

    private SizeAffinity() {
    }

    /**
     * The index of the player to use among {@code lastSizes} (each the size that player last
     * decoded, 0 for none): one that decoded {@code wanted}, else one that decoded nothing, else
     * -1. With {@code wanted} unknown (0), any player will do: the first.
     */
    static int choose(long[] lastSizes, long wanted) {
        if (lastSizes.length == 0) {
            return -1;
        }
        if (wanted == 0) {
            return 0;
        }
        int unused = -1;
        for (int i = 0; i < lastSizes.length; i++) {
            if (lastSizes[i] == wanted) {
                return i;
            }
            if (lastSizes[i] == 0 && unused < 0) {
                unused = i;
            }
        }
        return unused;
    }

    /** Whether a player that last decoded {@code last} would change size to play {@code wanted}. */
    static boolean wouldSwitch(long last, long wanted) {
        return last != 0 && wanted != 0 && last != wanted;
    }
}
