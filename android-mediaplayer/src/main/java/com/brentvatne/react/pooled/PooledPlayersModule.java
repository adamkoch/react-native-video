package com.brentvatne.react.pooled;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.UiThreadUtil;

/** Lets JS ask the pool for players before the views that need them exist. */
public class PooledPlayersModule extends ReactContextBaseJavaModule {

    public static final String NAME = "RCTVideoPooledPlayers";

    public PooledPlayersModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return NAME;
    }

    /** See {@link PooledPlayerPool#prewarm}. Creates nothing until it is called. */
    @ReactMethod
    public void prewarm(final double count) {
        final int players = (int) Math.max(0, Math.min(count, PooledPlayerPool.MAX_HARDWARE_PLAYERS));
        if (players == 0) {
            return;
        }
        UiThreadUtil.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                PooledPlayerPool.get(getReactApplicationContext()).prewarm(players);
            }
        });
    }
}
