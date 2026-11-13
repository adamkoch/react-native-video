package com.brentvatne.react.pooled;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide pool of ExoPlayers that are not released while they work.
 *
 * Some hardware decoder services (seen on MediaTek Google TV devices) occasionally fail to
 * destroy a decoder that the app released, and each failure permanently uses up one of a small
 * fixed number of hardware decoders until the device reboots. The pool avoids the problem by
 * never destroying a decoder: every player keeps its codec and its output surface for the life of
 * the process, and views only borrow a player.
 *
 * All four of these are needed for the codec to survive a change of view and of clip:
 * <ol>
 *   <li>players are never released, a view borrows one and gives it back;</li>
 *   <li>each player owns one SurfaceTexture/Surface, set once, that views display through
 *       {@code TextureView.setSurfaceTexture};</li>
 *   <li>the codec is configured for {@link #CODEC_MAX_WIDTH}x{@link #CODEC_MAX_HEIGHT}, so a
 *       clip of another size reuses it;</li>
 *   <li>decoder fallback is on, as a safety net for a decoder that cannot be created.</li>
 * </ol>
 *
 * <h3>Which decoder a player uses</h3>
 * Hardware decoders have a per-name limit on concurrent instances, so each player has a decoder
 * reserved for it when it is created (see {@link DecoderAllocator}). The decoders and their
 * limits are read from the device: the non-secure hardware decoders that media3 lists for the
 * MIME type, in platform order, each with the capacity from
 * {@code CodecCapabilities.getMaxSupportedInstances()}. Nothing is keyed on device model.
 *
 * <h3>How many players there are</h3>
 * <ul>
 *   <li>Hardware players: up to the total hardware capacity for {@link #PRIMARY_MIME_TYPE}
 *       (at most {@link #MAX_HARDWARE_PLAYERS}), created on demand, or ahead of the demand
 *       with {@link #prewarm}.</li>
 *   <li>Software players: a view that has waited {@link #OVERFLOW_GRACE_MS} for a hardware
 *       player gets one that prefers a software decoder, up to
 *       {@link #MAX_SOFTWARE_PLAYERS}. Software decoding of the small clips this backend is
 *       meant for is cheap and uses none of the scarce hardware instances, and showing the clip
 *       is better than an empty view or an error. The wait is there because during a screen
 *       change the new views are often created just before the old ones give their players
 *       back.</li>
 *   <li>Beyond both, a view waits (shows nothing) until a player is given back.</li>
 *   <li>A device with no hardware decoder for the MIME type gets
 *       {@link #PLAYERS_WITHOUT_HARDWARE} players in the default decoder order.</li>
 * </ul>
 *
 * <h3>The one time a player is released</h3>
 * A decoder can stop putting out frames, with or without an error, and stay that way however
 * often its player is prepared again. A view that sees this ({@link StallWatch}) has its player
 * replaced with {@link #replace}: the old player, its codec and its surface are released and a
 * new player takes its place. That is the release the pool otherwise avoids, so it is done
 * only for a player that is no use as it is.
 *
 * <h3>When the playback thread itself stops</h3>
 * All the players play on one thread, and a decoder call can block it for good. Then no player
 * shows a frame and a replacement never starts. The pool notices ({@link ThreadPulse}) and
 * {@link #rebuild}s: every player is given up, and the views get new ones on a new thread.
 *
 * Must be used from the main thread only, except where noted.
 */
public final class PooledPlayerPool {

    private static final String TAG = "PooledPlayerPool";

    /** The MIME type whose hardware capacity sets the number of hardware players. */
    public static final String PRIMARY_MIME_TYPE = MimeTypes.VIDEO_H264;

    /** Upper bound on hardware players, whatever capacity the device reports. */
    public static final int MAX_HARDWARE_PLAYERS = 16;

    /** Players that prefer a software decoder, for views beyond the hardware capacity. */
    public static final int MAX_SOFTWARE_PLAYERS = 4;

    /** Pool size on a device that has no hardware decoder for {@link #PRIMARY_MIME_TYPE}. */
    public static final int PLAYERS_WITHOUT_HARDWARE = 8;

    /** How long a view waits for a hardware player before it is given a software one. */
    public static final long OVERFLOW_GRACE_MS = 300;

    /**
     * The longest a staggered start that shows no frame holds up the next one; see
     * {@link StartStagger}.
     */
    public static final long START_MAX_WAIT_MS = 1500;

    /** The pause between two players created by {@link #prewarm}. */
    public static final long PREWARM_GAP_MS = 50;

    static final int CODEC_MAX_WIDTH = 1920;
    static final int CODEC_MAX_HEIGHT = 1088;
    static final int CODEC_MAX_INPUT_SIZE = 2 * 1024 * 1024;

    /** Something that wants a player; called on the main thread when one is free. */
    public interface Borrower {
        void onPlayerAvailable(Slot slot);

        /**
         * The player this borrower holds has been given up by {@link #rebuild}. The borrower
         * must let go of it, without giving it back, and may borrow again at once.
         */
        void onPlayerLost();
    }

    /** The pause between two of the old players that {@link #rebuild} releases. */
    static final long RETIRE_GAP_MS = 250;

    /** How much of the playback thread's stack goes into a report. */
    private static final int STACK_FRAMES = 12;

    private static HandlerThread sPlaybackThread;
    private static Handler sPlaybackHandler;
    private static int sPlaybackThreads;
    private static final ThreadPulse sPulse = new ThreadPulse();

    /**
     * One playback thread for every pooled player. By default each ExoPlayer starts its own, and a
     * grid of eight then has eight threads waking independently many times a second; on a Chromecast
     * that costs about twice the CPU of a single thread doing the same work, and the UI loses frames
     * to it. The pool's players live for the whole process, so the thread is not stopped; it is
     * only left behind, by {@link #rebuild}, if it stops answering.
     */
    static synchronized Looper playbackLooper() {
        if (sPlaybackThread == null) {
            sPlaybackThreads++;
            // The priority ExoPlayer gives its own playback thread.
            sPlaybackThread = new HandlerThread(
                    sPlaybackThreads == 1 ? "PooledPlayback" : "PooledPlayback:" + sPlaybackThreads,
                    Process.THREAD_PRIORITY_AUDIO);
            sPlaybackThread.start();
            sPlaybackHandler = new Handler(sPlaybackThread.getLooper());
        }
        return sPlaybackThread.getLooper();
    }

    /** The next player is created on a new playback thread. Returns the thread left behind. */
    private static synchronized HandlerThread leavePlaybackThread() {
        HandlerThread old = sPlaybackThread;
        sPlaybackThread = null;
        sPlaybackHandler = null;
        sPulse.reset();
        return old;
    }

    /**
     * Asks the playback thread whether it still answers, if it has not been asked lately. Views
     * call this from their stall checks, so the thread is only asked while a video is loaded.
     */
    void checkPlaybackThread() {
        int question = sPulse.ask(SystemClock.uptimeMillis());
        if (question == 0) {
            return;
        }
        Handler handler;
        synchronized (PooledPlayerPool.class) {
            handler = sPlaybackHandler;
        }
        if (handler == null) {
            // No player yet, so nothing to be stuck.
            sPulse.answer(question);
            return;
        }
        handler.post(() -> sPulse.answer(question));
    }

    /**
     * How long the playback thread has left {@link #checkPlaybackThread} unanswered, if that is
     * long enough to call it stuck; 0 otherwise.
     */
    long playbackThreadStuckMs() {
        long now = SystemClock.uptimeMillis();
        return sPulse.stuck(now) ? sPulse.silentMs(now) : 0;
    }

    /** What the playback thread is doing. Class and method names only. */
    private static String playbackThreadStack() {
        HandlerThread thread;
        synchronized (PooledPlayerPool.class) {
            thread = sPlaybackThread;
        }
        if (thread == null) {
            return "no playback thread";
        }
        StringBuilder text = new StringBuilder(thread.getName()).append(' ').append(thread.getState());
        try {
            StackTraceElement[] stack = thread.getStackTrace();
            for (int i = 0; i < stack.length && i < STACK_FRAMES; i++) {
                text.append(" < ").append(stack[i].getClassName()).append('.').append(stack[i].getMethodName());
            }
        } catch (RuntimeException e) {
            text.append(" (no stack: ").append(e.getClass().getSimpleName()).append(')');
        }
        return text.toString();
    }

    /** How far ahead of the picture a player reads its source. */
    static final int BUFFER_AHEAD_MS = 5000;
    /** How much of a file a player reads before asking again whether it may read more. */
    static final int READ_CHECK_BYTES = 128 * 1024;

    /**
     * By default a player reads 50 seconds ahead, and only asks whether it has read enough once
     * per megabyte. A looping clip is queued again each time the copy before it has been read to
     * the end, so a clip under a megabyte was queued up to a hundred times over, each copy with
     * its own loader thread and its own copy of the file in memory: a grid of eight held about
     * 400 MB and several hundred threads. Clips are short and nearly always local files, so a
     * few seconds ahead is plenty.
     */
    static DefaultLoadControl loadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        BUFFER_AHEAD_MS,
                        BUFFER_AHEAD_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .build();
    }

    private static ExecutorService sLoaders;

    /**
     * The threads every pooled player reads its sources on. A looping clip is read again on each
     * loop, and by default each read starts a thread of its own and ends it. These threads are
     * kept for a minute after a read and reused. There is no limit on how many, because a read
     * waits on its thread while its player has enough buffered, and must not hold up another's.
     */
    static synchronized ExecutorService loaders() {
        if (sLoaders == null) {
            AtomicInteger count = new AtomicInteger();
            sLoaders = Executors.newCachedThreadPool(
                    task -> new Thread(task, "PooledLoader:" + count.incrementAndGet()));
        }
        return sLoaders;
    }

    /**
     * Clips are MP4 files. The default factory creates an extractor for every container format
     * it knows on each read, which for a looping clip is each loop. A source whose name says MP4
     * gets only the two MP4 extractors; any other source gets the default set.
     */
    static final class Mp4FirstExtractors implements ExtractorsFactory {
        private final SubtitleParser.Factory subtitles = new DefaultSubtitleParserFactory();
        private final DefaultExtractorsFactory all = new DefaultExtractorsFactory();

        @Override
        public Extractor[] createExtractors() {
            return all.createExtractors();
        }

        @Override
        public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
            if (!namesMp4(uri.getLastPathSegment())) {
                return all.createExtractors(uri, responseHeaders);
            }
            return new Extractor[] {new Mp4Extractor(subtitles), new FragmentedMp4Extractor(subtitles)};
        }

        static boolean namesMp4(String name) {
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov");
        }
    }

    /** The player's own source for a file or URL, asking sooner whether to keep reading. */
    static MediaSource.Factory mediaSourceFactory(Context context) {
        return new ProgressiveMediaSource.Factory(
                        new DefaultDataSource.Factory(context), new Mp4FirstExtractors())
                .setContinueLoadingCheckIntervalBytes(READ_CHECK_BYTES)
                // Never shut down: the pool's players live for the whole process.
                .setDownloadExecutor(PooledPlayerPool::loaders, executor -> { });
    }

    /** A player together with the decoder output surface it keeps for the whole process. */
    public static final class Slot {
        public final ExoPlayer player;
        public final SurfaceTexture texture;
        /** False for a player that prefers a software decoder. */
        public final boolean hardware;
        final Surface surface;
        final int index;
        final ReservingSelector selector;
        /** The video decoder the player last created, for error reports. Main thread. */
        String decoder;
        /** Who has the player now; null while it is free. Main thread. */
        Borrower holder;

        Slot(Context context, int index, boolean hardware, Decoders decoders) {
            this.index = index;
            this.hardware = hardware;
            selector = new ReservingSelector(decoders, !hardware);
            if (hardware) {
                // Reserve now, so that decoders fill up in the order players are created.
                selector.reservationFor(PRIMARY_MIME_TYPE);
            }
            // Created detached from any GL context, so that a TextureView can adopt it.
            texture = new SurfaceTexture(false);
            texture.setDefaultBufferSize(854, 480);
            surface = new Surface(texture);
            player = new ExoPlayer.Builder(context, new ReusableCodecRenderersFactory(context, selector))
                    .setLooper(Looper.getMainLooper())
                    .setPlaybackLooper(playbackLooper())
                    .setLoadControl(loadControl())
                    .setMediaSourceFactory(mediaSourceFactory(context))
                    .build();
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onVideoDecoderInitialized(
                        EventTime eventTime,
                        String decoderName,
                        long initializedTimestampMs,
                        long initializationDurationMs) {
                    Slot.this.decoder = decoderName;
                    if (PooledPlayerEvents.on()) {
                        PooledPlayerEvents.event("decoder", 0,
                                "player " + Slot.this.index + " " + decoderName + " in " + initializationDurationMs + "ms", null);
                    }
                    selector.onDecoderInitialized(Slot.this.index, decoderName);
                }
            });
            // Set once and never changed: a new surface would make the player reconfigure or
            // recreate its codec.
            player.setVideoSurface(surface);
        }

        /** Releases the player with its codec and surface. The slot is of no use afterwards. */
        void retire() {
            try {
                // Waits for the playback thread, for half a second at most.
                player.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "player " + index + " did not release cleanly", e);
            }
            selector.release();
            surface.release();
            texture.release();
        }
    }

    /** The device's hardware decoders and who has reserved them, per MIME type. Thread-safe. */
    static final class Decoders {
        private final Map<String, DecoderAllocator> mAllocators = new HashMap<>();

        /** The decoders may have room again for the players they turned away. */
        synchronized void restoreCapacities() {
            for (DecoderAllocator allocator : mAllocators.values()) {
                allocator.restoreCapacities();
            }
        }

        synchronized DecoderAllocator allocatorFor(String mimeType) {
            DecoderAllocator allocator = mAllocators.get(mimeType);
            if (allocator == null) {
                List<String> names = new ArrayList<>();
                List<Integer> capacities = new ArrayList<>();
                try {
                    for (MediaCodecInfo info : MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, false, false)) {
                        if (info.hardwareAccelerated && !info.softwareOnly && !info.secure && !names.contains(info.name)) {
                            names.add(info.name);
                            capacities.add(reportedCapacity(info));
                        }
                    }
                } catch (MediaCodecUtil.DecoderQueryException e) {
                    Log.w(TAG, "cannot list decoders for " + mimeType, e);
                    PooledPlayerEvents.event("decoders_unknown", 0, mimeType, null);
                }
                allocator = new DecoderAllocator(names, capacities);
                mAllocators.put(mimeType, allocator);
                Log.i(TAG, "hardware decoders for " + mimeType + ": " + allocator);
                if (PooledPlayerEvents.on()) {
                    PooledPlayerEvents.event("decoders", 0, mimeType + " " + allocator, null);
                }
            }
            return allocator;
        }

        /** The instance limit the device reports, or 0 if it reports none. */
        private static int reportedCapacity(MediaCodecInfo info) {
            CodecCapabilities capabilities = info.capabilities;
            if (capabilities == null || Build.VERSION.SDK_INT < 23) {
                return 0;
            }
            try {
                return capabilities.getMaxSupportedInstances();
            } catch (RuntimeException e) {
                return 0;
            }
        }
    }

    /**
     * The default decoder list with this player's decoder moved to the front. Called on the
     * player's playback thread as well as the main thread.
     */
    static final class ReservingSelector implements MediaCodecSelector {
        private static final String NONE = "";

        private final Decoders mDecoders;
        private final boolean mPreferSoftware;
        /** MIME type to the decoder this player should use; NONE if there is none for it. */
        private final Map<String, String> mPreferred = new ConcurrentHashMap<>();
        private volatile String mLastVideoMimeType;
        /** The player is gone; guarded by mPreferred. */
        private boolean mReleased;

        ReservingSelector(Decoders decoders, boolean preferSoftware) {
            mDecoders = decoders;
            mPreferSoftware = preferSoftware;
        }

        /** Returns the decoder name this player has for the MIME type, reserving one if needed. */
        String reservationFor(String mimeType) {
            synchronized (mPreferred) {
                String name = mPreferred.get(mimeType);
                if (name == null && mReleased) {
                    return null;
                }
                if (name == null) {
                    name = mPreferSoftware ? null : mDecoders.allocatorFor(mimeType).reserve();
                    mPreferred.put(mimeType, name == null ? NONE : name);
                }
                return name == null || name.equals(NONE) ? null : name;
            }
        }

        /** The player is gone: its decoders are free for the player that replaces it. */
        void release() {
            synchronized (mPreferred) {
                if (mReleased) {
                    return;
                }
                mReleased = true;
                for (Map.Entry<String, String> entry : mPreferred.entrySet()) {
                    if (!NONE.equals(entry.getValue())) {
                        mDecoders.allocatorFor(entry.getKey()).release(entry.getValue());
                    }
                }
                mPreferred.clear();
            }
        }

        @Override
        public List<MediaCodecInfo> getDecoderInfos(
                String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (requiresSecureDecoder || requiresTunnelingDecoder || !MimeTypes.isVideo(mimeType)) {
                return infos;
            }
            mLastVideoMimeType = mimeType;
            String preferred = reservationFor(mimeType);
            List<String> names = new ArrayList<>(infos.size());
            for (MediaCodecInfo info : infos) {
                names.add(info.name);
            }
            if (preferred == null && mPreferSoftware) {
                for (MediaCodecInfo info : infos) {
                    if (info.softwareOnly) {
                        preferred = info.name;
                        break;
                    }
                }
            }
            return DecoderAllocator.withFirst(infos, names, preferred);
        }

        /** Main thread: the player created a video decoder. */
        void onDecoderInitialized(int playerIndex, String decoderName) {
            String mimeType = mLastVideoMimeType;
            if (mimeType == null || decoderName == null) {
                return;
            }
            String reserved;
            synchronized (mPreferred) {
                if (mReleased) {
                    return;
                }
                reserved = mPreferred.get(mimeType);
                if (NONE.equals(reserved)) {
                    reserved = null;
                }
                if (decoderName.equals(reserved)) {
                    return;
                }
                if (reserved == null && !mDecoders.allocatorFor(mimeType).knows(decoderName)) {
                    // No reservation and not a hardware decoder: nothing to account for.
                    Log.i(TAG, "player " + playerIndex + " uses " + decoderName + " for " + mimeType);
                    return;
                }
                // Keep using the decoder the player really has from now on.
                mPreferred.put(mimeType, decoderName);
            }
            DecoderAllocator allocator = mDecoders.allocatorFor(mimeType);
            allocator.reportActual(reserved, decoderName);
            Log.w(TAG, "player " + playerIndex + " is on " + decoderName + " instead of its reserved "
                    + reserved + " for " + mimeType + "; now " + allocator);
            if (PooledPlayerEvents.on()) {
                PooledPlayerEvents.event("decoder_not_reserved", 0,
                        "player " + playerIndex + " on " + decoderName + " reserved " + reserved, null);
            }
        }
    }

    private static PooledPlayerPool sInstance;
    private static int sHardwarePlayerLimit = 0;

    private final Context mContext;
    private final Decoders mDecoders = new Decoders();
    private final ArrayList<Slot> mAll = new ArrayList<>();
    private final ArrayDeque<Slot> mFree = new ArrayDeque<>();
    private final ArrayDeque<Borrower> mWaiting = new ArrayDeque<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mHardwarePlayers;
    private int mSoftwarePlayers;
    private boolean mOverflowScheduled;
    private int mPrewarmTarget;
    private boolean mPrewarmScheduled;
    private final RebuildBackoff mRebuilds = new RebuildBackoff();

    private final StartStagger mStarts = new StartStagger(new StartStagger.Scheduler() {
        @Override
        public long nowMs() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            mHandler.postDelayed(runnable, delayMs);
        }
    }, START_MAX_WAIT_MS);

    private final Runnable mPrewarmStep = new Runnable() {
        @Override
        public void run() {
            mPrewarmScheduled = false;
            if (mHardwarePlayers >= Math.min(mPrewarmTarget, hardwarePlayerCeiling())) {
                return;
            }
            mFree.addLast(create(true));
            if (!mWaiting.isEmpty()) {
                serveWaiting(false);
            }
            mPrewarmScheduled = true;
            mHandler.postDelayed(this, PREWARM_GAP_MS);
        }
    };

    private final Runnable mServeWaiting = new Runnable() {
        @Override
        public void run() {
            serveWaiting(false);
        }
    };

    private final Runnable mServeOverflow = new Runnable() {
        @Override
        public void run() {
            mOverflowScheduled = false;
            serveWaiting(true);
        }
    };

    private PooledPlayerPool(Context context) {
        mContext = context.getApplicationContext();
    }

    public static PooledPlayerPool get(Context context) {
        if (sInstance == null) {
            sInstance = new PooledPlayerPool(context);
        }
        return sInstance;
    }

    /**
     * Optional native-side override, off by default: caps the number of hardware players below
     * what the device reports. 0 or less removes the cap. It cannot raise the number above the
     * reported capacity, and it does not release players that already exist.
     */
    public static void setHardwarePlayerLimit(int limit) {
        sHardwarePlayerLimit = limit;
    }

    /** The order in which views start their videos, shared by every pooled view. */
    StartStagger starts() {
        return mStarts;
    }

    /**
     * Creates players ahead of the views that will borrow them, until the pool has
     * {@code count} hardware players or as many as the device allows, whichever is less.
     * Creating a player is main-thread work, so they are created one per main-thread message
     * with {@link #PREWARM_GAP_MS} in between, not all at once when a screen of videos
     * appears. A view that asks for a player meanwhile gets one as before.
     *
     * Players are not released for this, so a count below the number that already exist does nothing.
     */
    public void prewarm(int count) {
        mPrewarmTarget = Math.max(mPrewarmTarget, count);
        if (!mPrewarmScheduled && mHardwarePlayers < Math.min(mPrewarmTarget, hardwarePlayerCeiling())) {
            mPrewarmScheduled = true;
            mHandler.post(mPrewarmStep);
        }
    }

    /** How many hardware players (or, without hardware decoders, players) the pool may create. */
    int hardwarePlayerCeiling() {
        int capacity = mDecoders.allocatorFor(PRIMARY_MIME_TYPE).totalCapacity();
        if (capacity <= 0) {
            return PLAYERS_WITHOUT_HARDWARE;
        }
        capacity = Math.min(capacity, MAX_HARDWARE_PLAYERS);
        return sHardwarePlayerLimit > 0 ? Math.min(capacity, sHardwarePlayerLimit) : capacity;
    }

    private int softwarePlayerCeiling() {
        boolean hasHardware = mDecoders.allocatorFor(PRIMARY_MIME_TYPE).totalCapacity() > 0;
        return hasHardware ? MAX_SOFTWARE_PLAYERS : 0;
    }

    /**
     * Gives the borrower a hardware player now if one is free or can still be created. Otherwise
     * the borrower waits, first come first served, and is called back when a player is given
     * back or, after {@link #OVERFLOW_GRACE_MS}, with a software player if one is available.
     *
     * @return the player, or null if the borrower was queued.
     */
    public Slot borrow(Borrower borrower) {
        // A view that is already waiting goes first.
        if (mWaiting.isEmpty()) {
            Slot slot = takeFree(true);
            if (slot == null && mHardwarePlayers < hardwarePlayerCeiling()) {
                slot = create(true);
            }
            if (slot != null) {
                slot.holder = borrower;
                return slot;
            }
        }
        if (!mWaiting.contains(borrower)) {
            mWaiting.addLast(borrower);
        }
        if (!mOverflowScheduled) {
            mOverflowScheduled = true;
            mHandler.postDelayed(mServeOverflow, OVERFLOW_GRACE_MS);
        }
        return null;
    }

    /** Stops waiting for a player. Does nothing if the borrower is not waiting. */
    public void cancel(Borrower borrower) {
        mWaiting.remove(borrower);
    }

    /**
     * Returns a player. The caller must already have removed its listeners and detached the
     * texture from its TextureView.
     */
    public void giveBack(Slot slot) {
        // Pause only. stop() and clearMediaItems() would release the codec, which is exactly
        // what the pool exists to avoid; the next borrower replaces the media item instead.
        slot.holder = null;
        if (!mAll.contains(slot)) {
            // Given up by rebuild() while the borrower still had it.
            return;
        }
        slot.player.setPlayWhenReady(false);
        mFree.addLast(slot);
        if (!mWaiting.isEmpty()) {
            // Hand over after the current view operation has finished.
            mHandler.removeCallbacks(mServeWaiting);
            mHandler.post(mServeWaiting);
        }
    }

    /**
     * Releases a borrowed player that has stopped working and returns a new one of the same
     * kind in its place, already the caller's. The caller must have removed its listeners and
     * detached the texture from its TextureView, as for {@link #giveBack}.
     *
     * Releasing waits for the playback thread and creating a player is main-thread work, so
     * this is for a player that is no use as it is, not for every error.
     */
    public Slot replace(Slot old) {
        int at = mAll.indexOf(old);
        mFree.remove(old);
        // First, so that the new player can have the old one's decoder.
        old.retire();
        Slot fresh = new Slot(mContext, old.index, old.hardware, mDecoders);
        fresh.holder = old.holder;
        old.holder = null;
        if (at >= 0) {
            mAll.set(at, fresh);
        } else {
            mAll.add(fresh);
        }
        Log.w(TAG, "replaced player " + old.index + (old.hardware
                ? " reserved " + fresh.selector.reservationFor(PRIMARY_MIME_TYPE) : " preferring software"));
        if (PooledPlayerEvents.on()) {
            PooledPlayerEvents.event("player_replaced", 0, "player " + old.index + (old.hardware
                    ? " hardware " + fresh.selector.reservationFor(PRIMARY_MIME_TYPE) : " software"), null);
        }
        return fresh;
    }

    /**
     * The last resort for a playback thread that has stopped answering: gives up every player
     * and lets the views that had one borrow again. The players they get are new ones, on a
     * new playback thread. Each holder is told with {@link Borrower#onPlayerLost}, the caller
     * included, before this returns.
     *
     * The old players are released afterwards, one at a time: each release waits half a second
     * for the thread that does not answer. The old thread is told to end, which it does if it
     * ever comes back. Until then it keeps its players' decoders, so the new players may get
     * fewer hardware decoders and some views may wait for a player.
     *
     * @return false if the pool was rebuilt too recently ({@link RebuildBackoff}); nothing
     *     was done.
     */
    public boolean rebuild(String why) {
        long now = SystemClock.elapsedRealtime();
        String state = why + "; " + describe() + "; " + playbackThreadStack();
        if (!mRebuilds.allow(now)) {
            Log.w(TAG, "not rebuilding for another " + mRebuilds.waitMs(now) + "ms: " + state);
            PooledPlayerEvents.event("pool_rebuild_refused", 0, state, null);
            return false;
        }
        Log.w(TAG, "rebuilding: " + state);
        PooledPlayerEvents.event("pool_rebuilding", 0, state, null);

        final HandlerThread oldThread = leavePlaybackThread();
        final ArrayList<Slot> old = new ArrayList<>(mAll);
        mAll.clear();
        mFree.clear();
        mHardwarePlayers = 0;
        mSoftwarePlayers = 0;
        ArrayList<Borrower> holders = new ArrayList<>();
        for (Slot slot : old) {
            // Now, so that the new players can reserve the decoders.
            slot.selector.release();
            if (slot.holder != null) {
                holders.add(slot.holder);
                slot.holder = null;
            }
        }
        for (Borrower holder : holders) {
            try {
                holder.onPlayerLost();
            } catch (RuntimeException e) {
                Log.w(TAG, "a view did not take the rebuild", e);
            }
        }
        mHandler.postDelayed(new Runnable() {
            private int next;

            @Override
            public void run() {
                if (next < old.size()) {
                    old.get(next++).retire();
                    mHandler.postDelayed(this, RETIRE_GAP_MS);
                } else if (oldThread != null) {
                    // Runs only if the old thread comes back, after it has let its decoders go.
                    new Handler(oldThread.getLooper()).post(() -> mHandler.post(() -> {
                        Log.w(TAG, oldThread.getName() + " came back; its decoders are free again");
                        PooledPlayerEvents.event("old_playback_thread_back", 0, oldThread.getName(), null);
                        mDecoders.restoreCapacities();
                        serveWaiting(true);
                    }));
                    oldThread.quitSafely();
                }
            }
        }, RETIRE_GAP_MS);
        return true;
    }

    /** Every player's state, for a report. */
    private String describe() {
        StringBuilder text = new StringBuilder("players");
        for (Slot slot : mAll) {
            text.append(' ').append(slot.index).append(slot.hardware ? "" : "sw")
                    .append(slot.holder == null ? ":free" : ":state" + slot.player.getPlaybackState());
        }
        return text.toString();
    }

    private void serveWaiting(boolean allowSoftware) {
        while (!mWaiting.isEmpty()) {
            Slot slot = takeFree(true);
            if (slot == null && mHardwarePlayers < hardwarePlayerCeiling()) {
                slot = create(true);
            }
            if (slot == null && allowSoftware) {
                slot = takeFree(false);
                if (slot == null && mSoftwarePlayers < softwarePlayerCeiling()) {
                    slot = create(false);
                }
            }
            if (slot == null && !allowSoftware && !mOverflowScheduled) {
                mOverflowScheduled = true;
                mHandler.postDelayed(mServeOverflow, OVERFLOW_GRACE_MS);
                return;
            }
            if (slot == null) {
                Log.w(TAG, "all " + mAll.size() + " players are in use, " + mWaiting.size() + " views waiting");
                if (PooledPlayerEvents.on()) {
                    PooledPlayerEvents.event("pool_full", 0,
                            mAll.size() + " players in use, " + mWaiting.size() + " views waiting", null);
                }
                return;
            }
            Borrower borrower = mWaiting.pollFirst();
            slot.holder = borrower;
            borrower.onPlayerAvailable(slot);
        }
    }

    private Slot takeFree(boolean hardware) {
        for (Iterator<Slot> it = mFree.iterator(); it.hasNext(); ) {
            Slot slot = it.next();
            if (slot.hardware == hardware) {
                it.remove();
                return slot;
            }
        }
        return null;
    }

    private Slot create(boolean hardware) {
        Slot slot = new Slot(mContext, mAll.size(), hardware, mDecoders);
        mAll.add(slot);
        if (hardware) {
            mHardwarePlayers++;
        } else {
            mSoftwarePlayers++;
        }
        Log.i(TAG, "created player " + slot.index + (hardware
                ? " reserved " + slot.selector.reservationFor(PRIMARY_MIME_TYPE)
                        + " (" + mHardwarePlayers + "/" + hardwarePlayerCeiling() + " hardware)"
                : " preferring software (" + mSoftwarePlayers + "/" + softwarePlayerCeiling() + ")"));
        if (PooledPlayerEvents.on()) {
            PooledPlayerEvents.event("player_created", 0, "player " + slot.index + (hardware
                    ? " hardware " + slot.selector.reservationFor(PRIMARY_MIME_TYPE)
                            + " " + mHardwarePlayers + "/" + hardwarePlayerCeiling()
                    : " software " + mSoftwarePlayers + "/" + softwarePlayerCeiling()), null);
        }
        return slot;
    }

    /** Decoders are sized once for anything up to 1080p, so another clip size reuses the codec. */
    private static final class ReusableCodecRenderersFactory extends DefaultRenderersFactory {

        ReusableCodecRenderersFactory(Context context, MediaCodecSelector selector) {
            super(context);
            setMediaCodecSelector(selector);
            setEnableDecoderFallback(true);
        }

        @Override
        protected void buildVideoRenderers(
                Context context,
                int extensionRendererMode,
                MediaCodecSelector mediaCodecSelector,
                boolean enableDecoderFallback,
                Handler eventHandler,
                VideoRendererEventListener eventListener,
                long allowedVideoJoiningTimeMs,
                ArrayList<Renderer> out) {
            out.add(new MediaCodecVideoRenderer(
                    context,
                    getCodecAdapterFactory(),
                    mediaCodecSelector,
                    allowedVideoJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY) {
                @Override
                protected CodecMaxValues getCodecMaxValues(
                        MediaCodecInfo codecInfo, Format format, Format[] streamFormats) {
                    CodecMaxValues values = super.getCodecMaxValues(codecInfo, format, streamFormats);
                    return new CodecMaxValues(
                            Math.max(values.width, CODEC_MAX_WIDTH),
                            Math.max(values.height, CODEC_MAX_HEIGHT),
                            Math.max(values.inputSize, CODEC_MAX_INPUT_SIZE));
                }
            });
        }
    }
}
