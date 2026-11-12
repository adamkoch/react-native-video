package com.brentvatne.react.pooled;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodecInfo.CodecCapabilities;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide pool of ExoPlayers that are never released.
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
    }

    private static HandlerThread sPlaybackThread;

    /**
     * One playback thread for every pooled player. By default each ExoPlayer starts its own, and a
     * grid of eight then has eight threads waking independently many times a second; on a Chromecast
     * that costs about twice the CPU of a single thread doing the same work, and the UI loses frames
     * to it. The pool's players live for the whole process, so the thread is never stopped.
     */
    static synchronized Looper playbackLooper() {
        if (sPlaybackThread == null) {
            // The priority ExoPlayer gives its own playback thread.
            sPlaybackThread = new HandlerThread("PooledPlayback", Process.THREAD_PRIORITY_AUDIO);
            sPlaybackThread.start();
        }
        return sPlaybackThread.getLooper();
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

    /** The player's own source for a file or URL, asking sooner whether to keep reading. */
    static MediaSource.Factory mediaSourceFactory(Context context) {
        return new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(context))
                .setContinueLoadingCheckIntervalBytes(READ_CHECK_BYTES);
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
    }

    /** The device's hardware decoders and who has reserved them, per MIME type. Thread-safe. */
    static final class Decoders {
        private final Map<String, DecoderAllocator> mAllocators = new HashMap<>();

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

        ReservingSelector(Decoders decoders, boolean preferSoftware) {
            mDecoders = decoders;
            mPreferSoftware = preferSoftware;
        }

        /** Returns the decoder name this player has for the MIME type, reserving one if needed. */
        String reservationFor(String mimeType) {
            synchronized (mPreferred) {
                String name = mPreferred.get(mimeType);
                if (name == null) {
                    name = mPreferSoftware ? null : mDecoders.allocatorFor(mimeType).reserve();
                    mPreferred.put(mimeType, name == null ? NONE : name);
                }
                return name == null || name.equals(NONE) ? null : name;
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
     * Players are never released, so a count below the number that already exist does nothing.
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
        slot.player.setPlayWhenReady(false);
        mFree.addLast(slot);
        if (!mWaiting.isEmpty()) {
            // Hand over after the current view operation has finished.
            mHandler.removeCallbacks(mServeWaiting);
            mHandler.post(mServeWaiting);
        }
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
            mWaiting.pollFirst().onPlayerAvailable(slot);
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
