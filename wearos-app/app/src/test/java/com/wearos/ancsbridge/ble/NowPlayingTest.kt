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
}
