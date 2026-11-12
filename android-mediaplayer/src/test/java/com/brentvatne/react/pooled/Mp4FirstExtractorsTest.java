package com.brentvatne.react.pooled;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Mp4FirstExtractorsTest {
    @Test
    public void namesThatSayMp4() {
        assertTrue(PooledPlayerPool.Mp4FirstExtractors.namesMp4("squat.mp4"));
        assertTrue(PooledPlayerPool.Mp4FirstExtractors.namesMp4("SQUAT.MP4"));
        assertTrue(PooledPlayerPool.Mp4FirstExtractors.namesMp4("clip.m4v"));
        assertTrue(PooledPlayerPool.Mp4FirstExtractors.namesMp4("clip.mov"));
    }

    @Test
    public void otherNamesGetEveryExtractor() {
        assertFalse(PooledPlayerPool.Mp4FirstExtractors.namesMp4(null));
        assertFalse(PooledPlayerPool.Mp4FirstExtractors.namesMp4("clip"));
        assertFalse(PooledPlayerPool.Mp4FirstExtractors.namesMp4("clip.webm"));
        assertFalse(PooledPlayerPool.Mp4FirstExtractors.namesMp4("clip.mp4.part"));
    }
}
