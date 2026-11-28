package com.wearos.ancsbridge.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingTest {
    @Test
    fun `only displays commands advertised by the current player`() {
        assertEquals(listOf(MediaCommand.VOLUME_DOWN, MediaCommand.SKIP_FORWARD),
            MediaCommand.available(setOf(6, 9, 247)))
        assertEquals(listOf(MediaCommand.TOGGLE_PLAYBACK), MediaCommand.available(setOf(0, 1, 2)))
        assertEquals(listOf(MediaCommand.PLAY), MediaCommand.available(setOf(0)))
        assertEquals(emptyList<MediaCommand>(), MediaCommand.available(emptySet()))
    }

    @Test
    fun `subscribes to track artist and title`() {
        assertArrayEquals(byteArrayOf(2, 0, 2), NowPlaying.trackSubscription())
    }

    @Test
    fun `updates attributes independently and handles unicode`() {
        val metadata = NowPlaying()
            .withTrackUpdate(byteArrayOf(2, 0, 0) + "Mỹ Tâm".encodeToByteArray())
            .withTrackUpdate(byteArrayOf(2, 2, 0) + "Một bài hát".encodeToByteArray())

        assertEquals(NowPlaying("Một bài hát", "Mỹ Tâm"), metadata)
        assertEquals(NowPlaying("", "Mỹ Tâm"), metadata.withTrackUpdate(byteArrayOf(2, 2, 0)))
    }

    @Test
    fun `ignores short packets and other entity attributes`() {
        val metadata = NowPlaying("Track", "Artist")

        assertEquals(metadata, metadata.withTrackUpdate(byteArrayOf(2, 2)))
        assertEquals(metadata, metadata.withTrackUpdate(byteArrayOf(0, 0, 0)))
        assertEquals(metadata, metadata.withTrackUpdate(byteArrayOf(2, 1, 0)))
    }

    @Test
    fun `tracks player name, playback state and volume`() {
        val state = NowPlaying()
            .withUpdate(byteArrayOf(0, 0, 0) + "Spotify".encodeToByteArray())
            .withUpdate(byteArrayOf(0, 1, 0) + "1,1.0,12.5".encodeToByteArray())
            .withUpdate(byteArrayOf(0, 2, 0) + "0.75".encodeToByteArray())

        assertEquals("Spotify", state.player)
        assertEquals(true, state.isPlaying)
        assertEquals(0.75f, state.volume!!, 0.0001f)
        assertEquals(false, state.withUpdate(byteArrayOf(0, 1, 0) + "0,0.0,12.5".encodeToByteArray()).isPlaying)
        // Garbage volume keeps the previous value.
        assertEquals(0.75f, state.withUpdate(byteArrayOf(0, 2, 0) + "x".encodeToByteArray()).volume!!, 0.0001f)
    }

    @Test
    fun `subscribes to player name, playback info and volume`() {
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), NowPlaying.playerSubscription())
    }

    @Test
    fun `basic commands are assumed until the iPhone lists them`() {
        assertEquals(true, MediaCommand.isSupported(null, MediaCommand.VOLUME_UP))
        assertEquals(false, MediaCommand.isSupported(setOf(2), MediaCommand.NEXT_TRACK))
    }
}
