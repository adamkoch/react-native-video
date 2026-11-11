package com.brentvatne.react.pooled;

import android.annotation.SuppressLint;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.exoplayer.ExoPlayer;

import com.brentvatne.react.ReactVideoView.Events;
import com.brentvatne.react.ReactVideoView;
import com.brentvatne.react.ReactVideoViewManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.yqritc.scalablevideoview.ScalableType;
import com.yqritc.scalablevideoview.ScaleManager;
import com.yqritc.scalablevideoview.Size;

/**
 * A video view that plays on a player borrowed from {@link PooledPlayerPool}.
 *
 * The view owns nothing that decodes: it borrows a player when it gets a source, shows the
 * player's persistent SurfaceTexture in a TextureView, and gives the player back when React
 * drops the view. Events have the same names and payload shapes as the MediaPlayer view's.
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

    // MediaPlayer's error vocabulary, which JS error handling already understands.
    private static final int MEDIA_ERROR_SYSTEM = -2147479543;

    private final ThemedReactContext mThemedReactContext;
    private final RCTEventEmitter mEventEmitter;
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
    /** The player has rendered a frame since the current source was loaded. */
    private boolean mFrameRendered = false;
    /** The current source's first frame is on the surface, so the texture may be shown. */
    private boolean mRevealed = false;
    /** The error was reported to JS; nothing more happens until the source changes. */
    private boolean mFailed = false;
    private int mRetryCount = 0;
    private long mSeekTimeMs = 0;

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
            if (mSlot != null && !mFailed && !mDropped && mUri != null) {
                mSlot.player.prepare();
            }
        }
    };

    public PooledVideoView(ThemedReactContext themedReactContext) {
        super(themedReactContext);
        mThemedReactContext = themedReactContext;
        mEventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);
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
            mLoaded = false;
            hideTexture();
            if (mSlot != null) {
                mHandler.removeCallbacks(mRetryRunnable);
                mSlot.player.setPlayWhenReady(false);
            }
            return;
        }
        if (mSlot != null) {
            load();
        } else {
            PooledPlayerPool.Slot slot = mPool.borrow(this);
            if (slot != null) {
                adopt(slot);
            }
        }
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
    }

    public void setProgressUpdateInterval(float progressUpdateInterval) {
        mProgressUpdateInterval = progressUpdateInterval;
    }

    public void setPlayInBackground(boolean playInBackground) {
        mPlayInBackground = playInBackground;
    }

    public void setPreventsDisplaySleepDuringVideoPlaybackModifier(boolean prevents) {
        mPreventsDisplaySleepDuringVideoPlayback = prevents;
        applyPlayWhenReady();
    }

    public void seekTo(long positionMs) {
        if (mSlot == null || mUri == null) {
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
    }

    private void adopt(PooledPlayerPool.Slot slot) {
        mSlot = slot;
        slot.player.addListener(this);
        attachTexture();
        if (mUri != null) {
            load();
        }
    }

    /** Called when React drops the view: the player goes back to the pool, still alive. */
    public void drop() {
        if (mDropped) {
            return;
        }
        mDropped = true;
        mThemedReactContext.removeLifecycleEventListener(this);
        mHandler.removeCallbacksAndMessages(null);
        mPool.cancel(this);
        setKeepScreenOn(false);
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
        mFrameRendered = false;
        mFailed = false;
        mRetryCount = 0;
        mSeekTimeMs = 0;
        // The player's surface still holds the last frame of whatever it played before.
        hideTexture();

        player.setRepeatMode(mRepeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        if (mRate > 0) {
            player.setPlaybackParameters(new PlaybackParameters(mRate));
        }
        applyAudio();
        player.setMediaItem(MediaItem.fromUri(mUri));
        player.prepare();
        applyPlayWhenReady();

        WritableMap event = Arguments.createMap();
        if (mSrcForEvent != null) {
            event.putMap(ReactVideoViewManager.PROP_SRC, mSrcForEvent.copy());
        }
        emit(Events.EVENT_LOAD_START, event);
    }

    private void applyPlayWhenReady() {
        boolean play = !mPaused && !(mHostPaused && !mPlayInBackground);
        setKeepScreenOn(play && mPreventsDisplaySleepDuringVideoPlayback);
        if (mSlot == null || mUri == null) {
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
            removeView(textureView);
            textureView.setSurfaceTextureListener(null);
        }
    }

    private void hideTexture() {
        mRevealed = false;
        if (mTextureView != null) {
            mTextureView.setAlpha(0f);
        }
    }

    private void revealTexture() {
        if (mRevealed) {
            return;
        }
        mRevealed = true;
        updateTransform();
        if (mTextureView != null) {
            mTextureView.setAlpha(1f);
        }
        emit(Events.EVENT_READY_FOR_DISPLAY, Arguments.createMap());
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
    }

    @Override
    protected void onDetachedFromWindow() {
        // A detached TextureView forgets its texture; a new one is made on the next attach.
        detachTexture();
        super.onDetachedFromWindow();
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
    }

    // ---- Player events ----

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (mSlot == null || mUri == null) {
            return;
        }
        if (playbackState == Player.STATE_READY) {
            mRetryCount = 0;
            if (!mLoaded) {
                mLoaded = true;
                emitLoad();
                if (mSlot.player.getPlayWhenReady()) {
                    mHandler.removeCallbacks(mProgressRunnable);
                    mHandler.post(mProgressRunnable);
                }
            }
            revealIfFrameRendered();
        } else if (playbackState == Player.STATE_ENDED) {
            mHandler.removeCallbacks(mProgressRunnable);
            emit(Events.EVENT_END, null);
            setKeepScreenOn(false);
        }
    }

    @Override
    public void onRenderedFirstFrame() {
        if (mSlot == null || mUri == null) {
            return;
        }
        mFrameRendered = true;
        revealIfFrameRendered();
    }

    /**
     * Shows the texture once the current source is ready and a frame of it has been rendered.
     * Neither is enough alone. The player can be ready before the first frame is on the surface,
     * which still holds the previous clip's last frame. And a first-frame event of the previous
     * source can arrive after this one was loaded, while the ready state always belongs to the
     * current source. A source with no video has nothing to wait for.
     */
    private void revealIfFrameRendered() {
        if (mLoaded && (mFrameRendered || mSlot.player.getVideoFormat() == null)) {
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
        if (reason == Player.DISCONTINUITY_REASON_SEEK && mLoaded) {
            WritableMap event = Arguments.createMap();
            event.putDouble(ReactVideoView.EVENT_PROP_CURRENT_TIME, newPosition.positionMs / 1000.0);
            event.putDouble(ReactVideoView.EVENT_PROP_SEEK_TIME, mSeekTimeMs / 1000.0);
            emit(Events.EVENT_SEEK, event);
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        if (mSlot == null || mUri == null || mFailed) {
            return;
        }
        Log.w(TAG, "player " + mSlot.index + " error " + error.getErrorCodeName() + " " + error.getMessage()
                + " cause=" + error.getCause() + " retry=" + mRetryCount);
        if (isWorthRetrying(error.errorCode) && mRetryCount < MAX_RETRIES) {
            mRetryCount++;
            if (!mHostPaused) {
                mHandler.removeCallbacks(mRetryRunnable);
                mHandler.postDelayed(mRetryRunnable, RETRY_DELAY_MS);
            }
            // While the app is in the background the retry waits for onHostResume: the decoder
            // may have been taken for the app in front.
            return;
        }
        mFailed = true;
        mHandler.removeCallbacks(mProgressRunnable);
        WritableMap errorMap = Arguments.createMap();
        errorMap.putInt(ReactVideoView.EVENT_PROP_WHAT, MediaPlayer.MEDIA_ERROR_UNKNOWN);
        errorMap.putInt(ReactVideoView.EVENT_PROP_EXTRA, mediaPlayerExtraFor(error.errorCode));
        errorMap.putInt("errorCode", error.errorCode);
        errorMap.putString("errorCodeName", error.getErrorCodeName());
        errorMap.putString("errorString", String.valueOf(error.getMessage()));
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
        mEventEmitter.receiveEvent(getId(), event.toString(), payload);
    }

    // ---- Host lifecycle ----

    @Override
    public void onHostPause() {
        mHostPaused = true;
        // Pause only. The player keeps its decoder while the app is in the background, because
        // giving it up is the operation that can leak it.
        applyPlayWhenReady();
    }

    @Override
    public void onHostResume() {
        mHostPaused = false;
        if (mSlot != null && mUri != null && !mFailed
                && mSlot.player.getPlaybackState() == Player.STATE_IDLE
                && mSlot.player.getPlayerError() != null) {
            // The player failed while the app was in the background.
            mHandler.removeCallbacks(mRetryRunnable);
            mHandler.postDelayed(mRetryRunnable, RETRY_DELAY_MS);
        }
        applyPlayWhenReady();
    }

    @Override
    public void onHostDestroy() {
        // The pool outlives the activity; the view is dropped by React as usual.
    }
}
