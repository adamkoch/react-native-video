package com.brentvatne.react.pooled;

import android.annotation.SuppressLint;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.ExoPlayer;

import com.brentvatne.react.ReactVideoView.Events;
import com.brentvatne.react.ReactVideoView;
import com.brentvatne.react.ReactVideoViewManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.yqritc.scalablevideoview.ScalableType;
import com.yqritc.scalablevideoview.ScaleManager;
import com.yqritc.scalablevideoview.Size;

/**
 * A video view that plays on a player borrowed from {@link PooledPlayerPool}.
 *
 * The view owns nothing that decodes: it borrows a player when it gets a source, shows the
 * player's persistent SurfaceTexture in a TextureView, and gives the player back when React
 * drops the view. Events have the same names and payload shapes as the MediaPlayer view's.
 *
 * A player that reports an error is prepared again a few times. One that still fails, or that
 * stops putting out frames without reporting anything, is replaced with a new player
 * ({@link StallWatch}); when that does not help either, the error goes to JS. If the players
 * have all stopped because their playback thread has, the pool is rebuilt instead
 * ({@link PooledPlayerPool#rebuild}).
 */
@SuppressLint("ViewConstructor")
public class PooledVideoView extends FrameLayout implements
        Player.Listener,
        LifecycleEventListener,
        PooledPlayerPool.Borrower,
        TextureView.SurfaceTextureListener {

    private static final String TAG = "PooledVideoView";

    /** A failed player is prepared again after this long, never immediately. */
    static final long RETRY_DELAY_MS = 1000;
    /** In-place retries before the error is reported to JS. */
    static final int MAX_RETRIES = 3;

    /** How long a video takes to fade in once its first frame is on the surface. */
    static final long FADE_IN_MS = 180;

    // MediaPlayer's error vocabulary, which JS error handling already understands.
    private static final int MEDIA_ERROR_SYSTEM = -2147479543;

    private final ThemedReactContext mThemedReactContext;
    private final PooledPlayerPool mPool;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private PooledPlayerPool.Slot mSlot;
    private TextureView mTextureView;

    private Uri mUri;
    private String mSrcKey;
    private WritableMap mSrcForEvent;
    private ScalableType mResizeMode = ScalableType.LEFT_TOP;
    private boolean mRepeat = false;
    private boolean mPaused = false;
    private boolean mMuted = false;
    private float mVolume = 1.0f;
    private float mRate = 1.0f;
    private float mProgressUpdateInterval = 250.0f;
    private boolean mPlayInBackground = false;
    private boolean mPreventsDisplaySleepDuringVideoPlayback = true;

    private boolean mHostPaused = false;
    private boolean mDropped = false;
    /** onVideoLoad has been sent for the current source. */
    private boolean mLoaded = false;
    /** Tells the current source's first frame from one of the source before it. */
    private final FirstFrameGate mFirstFrame = new FirstFrameGate();
    /** The current source's first frame is on the surface, so the texture may be shown. */
    private boolean mRevealed = false;
    /** The error was reported to JS; nothing more happens until the source changes. */
    private boolean mFailed = false;
    /**
     * The current source is waiting for its turn to start (see {@link StartStagger}). Until
     * then the player, if the view has one, still holds the source before it, so its events
     * are not for the current source.
     */
    private boolean mStartPending = false;
    /** The source changed in the props being applied; the start is asked for after them. */
    private boolean mStartAfterProps = false;
    /** How this view's starts are spaced from other views'; null starts them at once. */
    private StartStagger.Pacing mPacing;
    private int mRetryCount = 0;
    private long mSeekTimeMs = 0;
    /** The current source played to its end. */
    private boolean mEnded = false;
    /** When the current source was loaded, for {@link PooledPlayerEvents}. */
    private long mLoadedAtMs = 0;
    /** Notices a player that has stopped putting out frames. */
    private final StallWatch mStall = new StallWatch();

    private final Runnable mProgressRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSlot == null || !mLoaded || !mSlot.player.getPlayWhenReady()
                    || mSlot.player.getPlaybackState() == Player.STATE_ENDED) {
                return;
            }
            ExoPlayer player = mSlot.player;
            WritableMap event = Arguments.createMap();
            event.putDouble(ReactVideoView.EVENT_PROP_CURRENT_TIME, player.getCurrentPosition() / 1000.0);
            event.putDouble(ReactVideoView.EVENT_PROP_PLAYABLE_DURATION, player.getBufferedPosition() / 1000.0);
            event.putDouble(ReactVideoView.EVENT_PROP_SEEKABLE_DURATION, durationMs() / 1000.0);
            emit(Events.EVENT_PROGRESS, event);
            mHandler.postDelayed(this, Math.max(1, Math.round(mProgressUpdateInterval)));
        }
    };

    private final Runnable mRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSlot != null && !mFailed && !mDropped && mUri != null && !mStartPending) {
                mSlot.player.prepare();
            }
        }
    };

    /** Runs every {@link StallWatch#CHECK_MS} from the load of a source until it fails or goes. */
    private final Runnable mStallCheck = new Runnable() {
        @Override
        public void run() {
            if (mSlot == null || mUri == null || mFailed || mDropped || mStartPending) {
                // load() starts the checks again.
                return;
            }
            mPool.checkPlaybackThread();
            StallWatch.Verdict verdict =
                    mStall.check(shouldBeDecoding(), framesDecoded(), SystemClock.elapsedRealtime());
            if (verdict == StallWatch.Verdict.OK) {
                mHandler.postDelayed(this, StallWatch.CHECK_MS);
                return;
            }
            long stuckMs = mPool.playbackThreadStuckMs();
            String what = "no frames for " + StallWatch.STALL_MS + "ms player " + mSlot.index
                    + (mSlot.hardware ? "" : " software") + " decoder " + mSlot.decoder
                    + " state " + mSlot.player.getPlaybackState()
                    + (stuckMs > 0 ? " playback thread silent for " + stuckMs + "ms" : "");
            Log.w(TAG, what);
            if (stuckMs > 0) {
                // A new player would start on the same thread, so it would not start.
                if (!mPool.rebuild(what)) {
                    fail(what, PlaybackException.ERROR_CODE_TIMEOUT, MEDIA_ERROR_SYSTEM,
                            "ERROR_CODE_TIMEOUT", "The video players stopped responding");
                }
                // Rebuilt: onPlayerLost has started the source again on a new player.
            } else if (verdict == StallWatch.Verdict.REPLACE) {
                replacePlayer(what);
            } else {
                fail(what, PlaybackException.ERROR_CODE_TIMEOUT, MEDIA_ERROR_SYSTEM,
                        "ERROR_CODE_TIMEOUT", "The video stopped and a new player did not start it");
            }
        }
    };

    private final Runnable mStartRunnable = new Runnable() {
        @Override
        public void run() {
            start();
        }
    };

    public PooledVideoView(ThemedReactContext themedReactContext) {
        super(themedReactContext);
        mThemedReactContext = themedReactContext;
        mPool = PooledPlayerPool.get(themedReactContext);
        themedReactContext.addLifecycleEventListener(this);
    }

    // ---- Props ----

    public void setSrc(String uriString, boolean isNetwork, boolean isAsset, WritableMap srcForEvent) {
        if (mDropped) {
            return;
        }
        Uri uri = resolveUri(uriString, isNetwork, isAsset);
        String key = uri == null ? null : uri.toString();
        if (key != null && key.equals(mSrcKey) && !mFailed) {
            // The same source set again; React can resend an unchanged prop.
            return;
        }
        mUri = uri;
        mSrcKey = key;
        mSrcForEvent = srcForEvent;
        if (uri == null) {
            // Nothing to play. Keep the player if there is one, paused and hidden.
            mPool.starts().cancel(this);
            mStartPending = false;
            mStartAfterProps = false;
            mLoaded = false;
            hideTexture();
            mHandler.removeCallbacks(mStallCheck);
            if (mSlot != null) {
                mHandler.removeCallbacks(mRetryRunnable);
                mSlot.player.setPlayWhenReady(false);
            }
            reportExpectation();
            return;
        }
        // A new source, or a new try at one that failed, may have its player replaced again.
        mStall.reset();
        // The start is asked for once every prop of this update is in: startStagger may be
        // applied after the source.
        prepareForStart();
        mStartAfterProps = true;
        reportExpectation();
    }

    /** See {@link StartStagger.Pacing}; null starts this view's videos at once. */
    public void setStartStagger(StartStagger.Pacing pacing) {
        if (mDropped || (pacing == null ? mPacing == null : pacing.equals(mPacing))) {
            return;
        }
        mPacing = pacing;
        if (mStartPending && !mStartAfterProps && mPool.starts().isWaiting(this)) {
            // Waiting for its turn under the old setting.
            mPool.starts().request(this, mStartRunnable, mPacing);
        }
    }

    /** Called by the view manager after each batch of props has been applied. */
    public void onAfterProps() {
        if (mStartAfterProps && !mDropped) {
            mStartAfterProps = false;
            // Runs inside this call unless the view is staggered and has to wait its turn.
            mPool.starts().request(this, mStartRunnable, mPacing);
        }
    }

    /**
     * The current source is to start. The view shows nothing from now on: what its player
     * holds is the source before.
     */
    private void prepareForStart() {
        mStartPending = true;
        mLoaded = false;
        mEnded = false;
        hideTexture();
        mHandler.removeCallbacks(mRetryRunnable);
        mHandler.removeCallbacks(mProgressRunnable);
        mHandler.removeCallbacks(mStallCheck);
        if (mSlot != null) {
            mSlot.player.setPlayWhenReady(false);
        }
    }

    /** Queues the current source to start on this view's turn. */
    private void requestStart() {
        prepareForStart();
        mStartAfterProps = false;
        mPool.starts().request(this, mStartRunnable, mPacing);
    }

    /** This view's turn: borrow a player if it has none, and load the current source. */
    private void start() {
        mStartPending = false;
        if (mDropped || mUri == null) {
            mPool.starts().cancel(this);
            return;
        }
        if (mSlot == null) {
            PooledPlayerPool.Slot slot = mPool.borrow(this);
            if (slot == null) {
                // Queued; onPlayerAvailable asks for a new turn. Nothing started, so the
                // other views do not wait for this one.
                mPool.starts().cancel(this);
                PooledPlayerEvents.event("waiting_for_player", System.identityHashCode(this), null, mSrcKey);
                return;
            }
            adopt(slot);
        }
        load();
    }

    public void setResizeModeModifier(ScalableType resizeMode) {
        mResizeMode = resizeMode;
        updateTransform();
    }

    public void setRepeatModifier(boolean repeat) {
        mRepeat = repeat;
        if (mSlot != null) {
            mSlot.player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
    }

    public void setPausedModifier(boolean paused) {
        mPaused = paused;
        applyPlayWhenReady();
        reportExpectation();
    }

    public void setMutedModifier(boolean muted) {
        mMuted = muted;
        applyAudio();
    }

    public void setVolumeModifier(float volume) {
        mVolume = volume;
        applyAudio();
    }

    public void setRateModifier(float rate) {
        mRate = rate;
        if (mSlot != null && rate > 0) {
            mSlot.player.setPlaybackParameters(new PlaybackParameters(rate));
        }
        reportExpectation();
    }

    public void setProgressUpdateInterval(float progressUpdateInterval) {
        mProgressUpdateInterval = progressUpdateInterval;
    }

    public void setPlayInBackground(boolean playInBackground) {
        mPlayInBackground = playInBackground;
        reportExpectation();
    }

    public void setPreventsDisplaySleepDuringVideoPlaybackModifier(boolean prevents) {
        mPreventsDisplaySleepDuringVideoPlayback = prevents;
        applyPlayWhenReady();
    }

    public void seekTo(long positionMs) {
        if (mSlot == null || mUri == null || mStartPending) {
            return;
        }
        mSeekTimeMs = positionMs;
        mSlot.player.seekTo(positionMs);
    }

    // ---- Player ownership ----

    @Override
    public void onPlayerAvailable(PooledPlayerPool.Slot slot) {
        if (mDropped || mSlot != null) {
            mPool.giveBack(slot);
            return;
        }
        adopt(slot);
        if (mUri != null) {
            // Several waiting views can be given players in the same pass.
            requestStart();
        }
    }

    @Override
    public void onPlayerLost() {
        if (mSlot == null) {
            return;
        }
        PooledPlayerPool.Slot old = mSlot;
        mSlot = null;
        old.player.removeListener(this);
        detachTexture();
        mHandler.removeCallbacks(mRetryRunnable);
        mHandler.removeCallbacks(mProgressRunnable);
        mHandler.removeCallbacks(mStallCheck);
        if (mDropped || mUri == null || mFailed) {
            // Nothing to play now; the next source borrows a player as a new view does.
            return;
        }
        PooledPlayerEvents.event("player_lost", System.identityHashCode(this), "player " + old.index, mSrcKey);
        // The players that were replaced for this source are gone with the rest.
        mStall.reset();
        requestStart();
    }

    private void adopt(PooledPlayerPool.Slot slot) {
        mSlot = slot;
        slot.player.addListener(this);
        attachTexture();
    }

    /**
     * The view's player is no use as it is: release it, and start the current source on a new
     * one in its place, on this view's turn like any other start.
     */
    private void replacePlayer(String why) {
        PooledPlayerPool.Slot old = mSlot;
        PooledPlayerEvents.event("replacing_player", System.identityHashCode(this),
                why + " replacement " + mStall.replacements() + "/" + StallWatch.MAX_REPLACEMENTS, mSrcKey);
        mSlot = null;
        old.player.removeListener(this);
        detachTexture();
        adopt(mPool.replace(old));
        requestStart();
    }

    /** Called when React drops the view: the player goes back to the pool, still alive. */
    public void drop() {
        if (mDropped) {
            return;
        }
        mDropped = true;
        mThemedReactContext.removeLifecycleEventListener(this);
        mHandler.removeCallbacksAndMessages(null);
        mPool.starts().cancel(this);
        mPool.cancel(this);
        setKeepScreenOn(false);
        reportExpectation();
        PooledPlayerEvents.event("dropped", System.identityHashCode(this), null, null);
        if (mSlot != null) {
            PooledPlayerPool.Slot slot = mSlot;
            mSlot = null;
            slot.player.removeListener(this);
            detachTexture();
            mPool.giveBack(slot);
        }
    }

    /**
     * Starts the current source on the borrowed player. No stop() first: replacing the media
     * item flushes the codec, while stop() would release it.
     */
    private void load() {
        ExoPlayer player = mSlot.player;
        mHandler.removeCallbacks(mRetryRunnable);
        mHandler.removeCallbacks(mProgressRunnable);
        mLoaded = false;
        final int firstFrameLoad = mFirstFrame.onLoad();
        mFailed = false;
        mEnded = false;
        mRetryCount = 0;
        mSeekTimeMs = 0;
        mLoadedAtMs = SystemClock.elapsedRealtime();
        mStall.restart();
        mHandler.removeCallbacks(mStallCheck);
        mHandler.postDelayed(mStallCheck, StallWatch.CHECK_MS);
        if (PooledPlayerEvents.on()) {
            PooledPlayerEvents.event("load", System.identityHashCode(this), "player " + mSlot.index, mSrcKey);
        }
        // The player's surface still holds the last frame of whatever it played before.
        hideTexture();

        player.setRepeatMode(mRepeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        if (mRate > 0) {
            player.setPlaybackParameters(new PlaybackParameters(mRate));
        }
        applyAudio();
        // The playback thread must go from the previous source to the new one and on to the
        // marker with nothing in between, or a frame could land on the wrong side of the
        // marker. It waits until all three are queued; see FirstFrameGate.
        PlaybackHold hold = new PlaybackHold();
        player.createMessage((messageType, payload) -> hold.await()).send();
        try {
            player.setMediaItem(MediaItem.fromUri(mUri));
            player.prepare();
            player.createMessage((messageType, payload) -> mFirstFrame.onMarker(firstFrameLoad))
                    .setLooper(Looper.getMainLooper())
                    .send();
        } finally {
            hold.release();
        }
        applyPlayWhenReady();

        WritableMap event = Arguments.createMap();
        if (mSrcForEvent != null) {
            event.putMap(ReactVideoViewManager.PROP_SRC, mSrcForEvent.copy());
        }
        emit(Events.EVENT_LOAD_START, event);
        reportExpectation();
    }

    /**
     * Tells {@link PooledPlayerEvents} whether this view should be showing a moving picture.
     * A view still waiting for a player counts: it should not wait for long.
     */
    private void reportExpectation() {
        if (!PooledPlayerEvents.on()) {
            return;
        }
        PooledPlayerEvents.expectFrames(System.identityHashCode(this), shouldBePlaying(), mSrcKey, this);
    }

    /** The view should be showing a moving picture, whether or not it has a player yet. */
    private boolean shouldBePlaying() {
        return mUri != null && !mDropped && !mFailed && !mEnded && !mPaused
                && !(mHostPaused && !mPlayInBackground) && mRate > 0 && isAttachedToWindow();
    }

    /**
     * The view's player should be putting out frames. Not while it waits for more of its source
     * to be read, which for a network source can take any time and is not the decoder's doing,
     * and not for a source that turned out to have no video.
     */
    private boolean shouldBeDecoding() {
        if (!shouldBePlaying()) {
            return false;
        }
        ExoPlayer player = mSlot.player;
        if (player.getPlaybackState() == Player.STATE_BUFFERING && player.isLoading()) {
            return false;
        }
        return !(mLoaded && player.getVideoFormat() == null);
    }

    /** How many frames the player's decoder has put out; only a change means anything. */
    private long framesDecoded() {
        DecoderCounters counters = mSlot.player.getVideoDecoderCounters();
        if (counters == null) {
            return -1;
        }
        counters.ensureUpdated();
        // Dropped and skipped frames count: the decoder is working, if late.
        return (long) counters.renderedOutputBufferCount + counters.droppedBufferCount
                + counters.skippedOutputBufferCount;
    }

    private void applyPlayWhenReady() {
        boolean play = !mPaused && !(mHostPaused && !mPlayInBackground);
        setKeepScreenOn(play && mPreventsDisplaySleepDuringVideoPlayback);
        if (mSlot == null || mUri == null || mStartPending) {
            return;
        }
        mSlot.player.setPlayWhenReady(play);
        mHandler.removeCallbacks(mProgressRunnable);
        if (play && mLoaded) {
            mHandler.post(mProgressRunnable);
        }
    }

    /**
     * Muted views play with the audio track switched off, so they create no audio decoder or
     * audio track at all; only an unmuted view decodes sound.
     */
    private void applyAudio() {
        if (mSlot == null) {
            return;
        }
        ExoPlayer player = mSlot.player;
        TrackSelectionParameters parameters = player.getTrackSelectionParameters();
        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO) != mMuted) {
            player.setTrackSelectionParameters(
                    parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, mMuted).build());
        }
        player.setVolume(mMuted ? 0f : Math.max(0f, Math.min(1f, mVolume)));
    }

    private Uri resolveUri(String uriString, boolean isNetwork, boolean isAsset) {
        if (uriString == null || uriString.isEmpty()) {
            return null;
        }
        if (isNetwork || isAsset || uriString.contains("://")) {
            return Uri.parse(uriString);
        }
        if (uriString.startsWith("/")) {
            return Uri.parse("file://" + uriString);
        }
        // A bundled resource, named without extension, as the MediaPlayer view accepts.
        int identifier = getResources().getIdentifier(uriString, "drawable", getContext().getPackageName());
        if (identifier == 0) {
            identifier = getResources().getIdentifier(uriString, "raw", getContext().getPackageName());
        }
        if (identifier == 0) {
            Log.w(TAG, "cannot resolve source " + uriString);
            return null;
        }
        return Uri.parse("android.resource://" + getContext().getPackageName() + "/" + identifier);
    }

    // ---- Texture ----

    private void attachTexture() {
        if (mSlot == null || mTextureView != null || !isAttachedToWindow()) {
            return;
        }
        TextureView textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(this);
        // Show the player's own texture instead of letting the TextureView create one.
        textureView.setSurfaceTexture(mSlot.texture);
        textureView.setAlpha(mRevealed ? 1f : 0f);
        mTextureView = textureView;
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        layoutTexture();
    }

    private void detachTexture() {
        if (mTextureView != null) {
            TextureView textureView = mTextureView;
            mTextureView = null;
            // Removing the view makes it let go of the texture, so that another can adopt it.
            // The listener must still be set while that happens: it is what tells the
            // TextureView not to release the texture, which belongs to the pooled player.
            textureView.animate().cancel();
            removeView(textureView);
            textureView.setSurfaceTextureListener(null);
        }
    }

    private void hideTexture() {
        mRevealed = false;
        if (mTextureView != null) {
            mTextureView.animate().cancel();
            mTextureView.setAlpha(0f);
        }
    }

    private void revealTexture() {
        if (mRevealed) {
            return;
        }
        mRevealed = true;
        mPool.starts().settled(this);
        updateTransform();
        if (mTextureView != null) {
            // Alpha only: nothing is laid out or redrawn by the app while it runs.
            mTextureView.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        }
        emit(Events.EVENT_READY_FOR_DISPLAY, Arguments.createMap());
        if (PooledPlayerEvents.on()) {
            PooledPlayerEvents.event("first_frame", System.identityHashCode(this),
                    "after " + (SystemClock.elapsedRealtime() - mLoadedAtMs) + "ms", mSrcKey);
        }
    }

    /** React does not lay out children it did not create, so the texture is placed by hand. */
    private void layoutTexture() {
        if (mTextureView == null) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        mTextureView.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        mTextureView.layout(0, 0, width, height);
        updateTransform();
    }

    private void updateTransform() {
        if (mTextureView == null || mSlot == null) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        VideoSize videoSize = mSlot.player.getVideoSize();
        int videoWidth = Math.round(videoSize.width * videoSize.pixelWidthHeightRatio);
        int videoHeight = videoSize.height;
        if (width == 0 || height == 0 || videoWidth == 0 || videoHeight == 0) {
            return;
        }
        Matrix matrix = new ScaleManager(new Size(width, height), new Size(videoWidth, videoHeight))
                .getScaleMatrix(mResizeMode);
        if (matrix != null) {
            mTextureView.setTransform(matrix);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutTexture();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachTexture();
        reportExpectation();
    }

    @Override
    protected void onDetachedFromWindow() {
        // A detached TextureView forgets its texture; a new one is made on the next attach.
        detachTexture();
        super.onDetachedFromWindow();
        // Not on screen any more, whatever isAttachedToWindow() says during this call.
        PooledPlayerEvents.expectFrames(System.identityHashCode(this), false, null, this);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // The texture belongs to the pooled player, not to this view.
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        PooledPlayerEvents.frame(System.identityHashCode(this));
    }

    // ---- Player events ----

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mSlot == null || mUri == null || mStartPending) {
            return;
        }
        boolean ended = playbackState == Player.STATE_ENDED;
        if (ended != mEnded) {
            mEnded = ended;
            reportExpectation();
        }
        if (playbackState == Player.STATE_READY) {
            mRetryCount = 0;
            if (!mLoaded) {
                mLoaded = true;
                emitLoad();
                if (PooledPlayerEvents.on()) {
                    PooledPlayerEvents.event("ready", System.identityHashCode(this),
                            "after " + (SystemClock.elapsedRealtime() - mLoadedAtMs) + "ms", mSrcKey);
                }
                if (mSlot.player.getPlayWhenReady()) {
                    mHandler.removeCallbacks(mProgressRunnable);
                    mHandler.post(mProgressRunnable);
                }
            }
            revealIfFrameRendered();
        } else if (playbackState == Player.STATE_ENDED) {
            mHandler.removeCallbacks(mProgressRunnable);
            emit(Events.EVENT_END, null);
            PooledPlayerEvents.event("ended", System.identityHashCode(this), null, mSrcKey);
            setKeepScreenOn(false);
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        if (mSlot == null || mUri == null || mStartPending) {
            return;
        }
        mFirstFrame.onFirstFrame();
        revealIfFrameRendered();
    }

    /**
     * Shows the texture once the current source is ready and a frame of it has been rendered.
     * The player can be ready before the first frame is on the surface, which still holds the
     * previous clip's last frame. A source with no video has nothing to wait for.
     */
    private void revealIfFrameRendered() {
        if (mLoaded && (mFirstFrame.frameRendered() || mSlot.player.getVideoFormat() == null)) {
            revealTexture();
        }
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        updateTransform();
    }

    @Override
    public void onPositionDiscontinuity(
            Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && mLoaded && !mStartPending) {
            WritableMap event = Arguments.createMap();
            event.putDouble(ReactVideoView.EVENT_PROP_CURRENT_TIME, newPosition.positionMs / 1000.0);
            event.putDouble(ReactVideoView.EVENT_PROP_SEEK_TIME, mSeekTimeMs / 1000.0);
            emit(Events.EVENT_SEEK, event);
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        if (mSlot == null || mUri == null || mFailed || mStartPending) {
            // A pending start prepares the player again, which clears the error.
            return;
        }
        Log.w(TAG, "player " + mSlot.index + " error " + error.getErrorCodeName() + " " + error.getMessage()
                + " cause=" + error.getCause() + " retry=" + mRetryCount);
        // No message: it can hold the file's path.
        Throwable cause = error.getCause();
        String what = error.getErrorCodeName()
                + (cause == null ? "" : " " + cause.getClass().getSimpleName())
                + " player " + mSlot.index + (mSlot.hardware ? "" : " software")
                + " decoder " + mSlot.decoder;
        if (isWorthRetrying(error.errorCode) && mRetryCount < MAX_RETRIES) {
            mRetryCount++;
            PooledPlayerEvents.event("error_retrying", System.identityHashCode(this),
                    what + " retry " + mRetryCount + "/" + MAX_RETRIES, mSrcKey);
            if (!mHostPaused) {
                mHandler.removeCallbacks(mRetryRunnable);
                mHandler.postDelayed(mRetryRunnable, RETRY_DELAY_MS);
            }
            // While the app is in the background the retry waits for onHostResume: the decoder
            // may have been taken for the app in front.
            return;
        }
        what += " after " + mRetryCount + " retries";
        if (isWorthRetrying(error.errorCode) && mStall.giveUpOnPlayer() == StallWatch.Verdict.REPLACE) {
            // Preparing this player again did not help; a new one may.
            replacePlayer(what);
            return;
        }
        fail(what, error.errorCode, mediaPlayerExtraFor(error.errorCode),
                error.getErrorCodeName(), String.valueOf(error.getMessage()));
    }

    /** Gives up on the current source and tells JS, in MediaPlayer's error vocabulary. */
    private void fail(String what, int errorCode, int mediaPlayerExtra, String errorCodeName, String message) {
        mFailed = true;
        mPool.starts().settled(this);
        mHandler.removeCallbacks(mProgressRunnable);
        mHandler.removeCallbacks(mRetryRunnable);
        mHandler.removeCallbacks(mStallCheck);
        reportExpectation();
        PooledPlayerEvents.failed(System.identityHashCode(this), what, mSrcKey);
        WritableMap errorMap = Arguments.createMap();
        errorMap.putInt(ReactVideoView.EVENT_PROP_WHAT, MediaPlayer.MEDIA_ERROR_UNKNOWN);
        errorMap.putInt(ReactVideoView.EVENT_PROP_EXTRA, mediaPlayerExtra);
        errorMap.putInt("errorCode", errorCode);
        errorMap.putString("errorCodeName", errorCodeName);
        errorMap.putString("errorString", message);
        WritableMap event = Arguments.createMap();
        event.putMap(ReactVideoView.EVENT_PROP_ERROR, errorMap);
        emit(Events.EVENT_ERROR, event);
    }

    /** A missing or unreadable file will not get better by preparing again. */
    static boolean isWorthRetrying(int errorCode) {
        switch (errorCode) {
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION:
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
            case PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return false;
            default:
                return true;
        }
    }

    /**
     * The MediaPlayer "extra" code closest to a media3 error code, so that JS written for the
     * MediaPlayer view classifies the error the same way. The media3 code is sent as well.
     */
    static int mediaPlayerExtraFor(int errorCode) {
        switch (errorCode) {
            case PlaybackException.ERROR_CODE_TIMEOUT:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return MediaPlayer.MEDIA_ERROR_TIMED_OUT;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return MediaPlayer.MEDIA_ERROR_MALFORMED;
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return MediaPlayer.MEDIA_ERROR_UNSUPPORTED;
            default:
                if (errorCode >= 2000 && errorCode < 3000) {
                    return MediaPlayer.MEDIA_ERROR_IO;
                }
                return MEDIA_ERROR_SYSTEM;
        }
    }

    private void emitLoad() {
        ExoPlayer player = mSlot.player;
        VideoSize videoSize = player.getVideoSize();
        int width = Math.round(videoSize.width * videoSize.pixelWidthHeightRatio);
        int height = videoSize.height;

        WritableMap naturalSize = Arguments.createMap();
        naturalSize.putInt(ReactVideoView.EVENT_PROP_WIDTH, width);
        naturalSize.putInt(ReactVideoView.EVENT_PROP_HEIGHT, height);
        naturalSize.putString(ReactVideoView.EVENT_PROP_ORIENTATION, width > height ? "landscape" : "portrait");

        WritableMap event = Arguments.createMap();
        event.putDouble(ReactVideoView.EVENT_PROP_DURATION, durationMs() / 1000.0);
        event.putDouble(ReactVideoView.EVENT_PROP_CURRENT_TIME, player.getCurrentPosition() / 1000.0);
        event.putMap(ReactVideoView.EVENT_PROP_NATURALSIZE, naturalSize);
        event.putBoolean(ReactVideoView.EVENT_PROP_FAST_FORWARD, true);
        event.putBoolean(ReactVideoView.EVENT_PROP_SLOW_FORWARD, true);
        event.putBoolean(ReactVideoView.EVENT_PROP_SLOW_REVERSE, true);
        event.putBoolean(ReactVideoView.EVENT_PROP_REVERSE, true);
        event.putBoolean(ReactVideoView.EVENT_PROP_STEP_BACKWARD, true);
        event.putBoolean(ReactVideoView.EVENT_PROP_STEP_FORWARD, true);
        emit(Events.EVENT_LOAD, event);
    }

    private long durationMs() {
        long duration = mSlot == null ? C.TIME_UNSET : mSlot.player.getDuration();
        return duration == C.TIME_UNSET ? 0 : duration;
    }

    private void emit(Events event, WritableMap payload) {
        if (mDropped) {
            return;
        }
        // The event dispatcher, not getJSModule(RCTEventEmitter): on the new architecture that
        // call logs an exception with a full stack trace on the main thread for every view.
        EventDispatcher dispatcher =
                UIManagerHelper.getEventDispatcherForReactTag(mThemedReactContext, getId());
        if (dispatcher == null) {
            return;
        }
        dispatcher.dispatchEvent(
                new VideoEvent(
                        UIManagerHelper.getSurfaceId(this), getId(), event.toString(), payload));
    }

    private static final class VideoEvent extends Event<VideoEvent> {
        private final String mName;
        private final WritableMap mPayload;

        VideoEvent(int surfaceId, int viewTag, String name, WritableMap payload) {
            super(surfaceId, viewTag);
            mName = name;
            mPayload = payload;
        }

        @Override
        public String getEventName() {
            return mName;
        }

        @Override
        public boolean canCoalesce() {
            // Every event is delivered, in order, as before.
            return false;
        }

        @Override
        protected WritableMap getEventData() {
            return mPayload;
        }
    }

    // ---- Host lifecycle ----

    @Override
    public void onHostPause() {
        mHostPaused = true;
        // Pause only. The player keeps its decoder while the app is in the background, because
        // giving it up is the operation that can leak it.
        applyPlayWhenReady();
        reportExpectation();
    }

    @Override
    public void onHostResume() {
        mHostPaused = false;
        if (mSlot != null && mUri != null && !mFailed && !mStartPending
                && mSlot.player.getPlaybackState() == Player.STATE_IDLE
                && mSlot.player.getPlayerError() != null) {
            // The player failed while the app was in the background.
            mHandler.removeCallbacks(mRetryRunnable);
            mHandler.postDelayed(mRetryRunnable, RETRY_DELAY_MS);
        }
        applyPlayWhenReady();
        reportExpectation();
    }

    @Override
    public void onHostDestroy() {
        // The pool outlives the activity; the view is dropped by React as usual.
    }
}
