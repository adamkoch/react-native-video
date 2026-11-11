package com.brentvatne.react.pooled;

import com.brentvatne.react.ReactVideoView.Events;
import com.brentvatne.react.ReactVideoViewManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.yqritc.scalablevideoview.ScalableType;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * The pooled media3 backend. It takes the same props and sends the same events as the
 * MediaPlayer view manager ({@link ReactVideoViewManager}), under another view name, so that JS
 * can choose between the two at run time.
 */
public class PooledVideoViewManager extends SimpleViewManager<PooledVideoView> {

    public static final String REACT_CLASS = "RCTVideoPooled";

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected PooledVideoView createViewInstance(ThemedReactContext themedReactContext) {
        return new PooledVideoView(themedReactContext);
    }

    @Override
    public void onDropViewInstance(PooledVideoView view) {
        super.onDropViewInstance(view);
        view.drop();
    }

    @Override
    @Nullable
    public Map getExportedCustomDirectEventTypeConstants() {
        MapBuilder.Builder builder = MapBuilder.builder();
        for (Events event : Events.values()) {
            builder.put(event.toString(), MapBuilder.of("registrationName", event.toString()));
        }
        return builder.build();
    }

    @Override
    @Nullable
    public Map getExportedViewConstants() {
        return MapBuilder.of(
                "ScaleNone", Integer.toString(ScalableType.LEFT_TOP.ordinal()),
                "ScaleToFill", Integer.toString(ScalableType.FIT_XY.ordinal()),
                "ScaleAspectFit", Integer.toString(ScalableType.FIT_CENTER.ordinal()),
                "ScaleAspectFill", Integer.toString(ScalableType.CENTER_CROP.ordinal())
        );
    }

    @ReactProp(name = ReactVideoViewManager.PROP_SRC)
    public void setSrc(final PooledVideoView videoView, @Nullable ReadableMap src) {
        if (src == null) {
            videoView.setSrc(null, false, false, null);
            return;
        }
        String uri = src.hasKey(ReactVideoViewManager.PROP_SRC_URI)
                ? src.getString(ReactVideoViewManager.PROP_SRC_URI) : null;
        String type = src.hasKey(ReactVideoViewManager.PROP_SRC_TYPE)
                ? src.getString(ReactVideoViewManager.PROP_SRC_TYPE) : "";
        boolean isNetwork = src.hasKey(ReactVideoViewManager.PROP_SRC_IS_NETWORK)
                && src.getBoolean(ReactVideoViewManager.PROP_SRC_IS_NETWORK);
        boolean isAsset = src.hasKey(ReactVideoViewManager.PROP_SRC_IS_ASSET)
                && src.getBoolean(ReactVideoViewManager.PROP_SRC_IS_ASSET);

        // Echoed in onVideoLoadStart, in the shape the MediaPlayer view uses.
        WritableMap srcForEvent = Arguments.createMap();
        srcForEvent.putString(ReactVideoViewManager.PROP_SRC_URI, uri);
        srcForEvent.putString(ReactVideoViewManager.PROP_SRC_TYPE, type);
        srcForEvent.putMap(ReactVideoViewManager.PROP_SRC_HEADERS, Arguments.createMap());
        srcForEvent.putBoolean(ReactVideoViewManager.PROP_SRC_IS_NETWORK, isNetwork);

        videoView.setSrc(uri, isNetwork, isAsset, srcForEvent);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_PREVENTS_DISPLAY_SLEEP_DURING_VIDEO_PLAYBACK, defaultBoolean = true)
    public void setPropPreventsDisplaySleepDuringVideoPlayback(final PooledVideoView videoView, final boolean doPreventSleep) {
        videoView.setPreventsDisplaySleepDuringVideoPlaybackModifier(doPreventSleep);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_RESIZE_MODE)
    public void setResizeMode(final PooledVideoView videoView, final String resizeModeOrdinalString) {
        videoView.setResizeModeModifier(ScalableType.values()[Integer.parseInt(resizeModeOrdinalString)]);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_REPEAT, defaultBoolean = false)
    public void setRepeat(final PooledVideoView videoView, final boolean repeat) {
        videoView.setRepeatModifier(repeat);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_PAUSED, defaultBoolean = false)
    public void setPaused(final PooledVideoView videoView, final boolean paused) {
        videoView.setPausedModifier(paused);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_MUTED, defaultBoolean = false)
    public void setMuted(final PooledVideoView videoView, final boolean muted) {
        videoView.setMutedModifier(muted);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_VOLUME, defaultFloat = 1.0f)
    public void setVolume(final PooledVideoView videoView, final float volume) {
        videoView.setVolumeModifier(volume);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_PROGRESS_UPDATE_INTERVAL, defaultFloat = 250.0f)
    public void setProgressUpdateInterval(final PooledVideoView videoView, final float progressUpdateInterval) {
        videoView.setProgressUpdateInterval(progressUpdateInterval);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_SEEK)
    public void setSeek(final PooledVideoView videoView, final float seek) {
        videoView.seekTo(Math.round(seek * 1000.0f));
    }

    @ReactProp(name = ReactVideoViewManager.PROP_RATE, defaultFloat = 1.0f)
    public void setRate(final PooledVideoView videoView, final float rate) {
        videoView.setRateModifier(rate);
    }

    @ReactProp(name = ReactVideoViewManager.PROP_PLAY_IN_BACKGROUND, defaultBoolean = false)
    public void setPlayInBackground(final PooledVideoView videoView, final boolean playInBackground) {
        videoView.setPlayInBackground(playInBackground);
    }
}
