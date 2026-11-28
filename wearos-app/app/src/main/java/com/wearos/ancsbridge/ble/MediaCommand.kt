package com.wearos.ancsbridge.ble

/** Remote Command IDs from the Apple Media Service specification. */
enum class MediaCommand(val code: Int, val label: String) {
    TOGGLE_PLAYBACK(2, "Play / Pause"),
    NEXT_TRACK(3, "Next track"),
    PREVIOUS_TRACK(4, "Previous track"),
    VOLUME_UP(5, "Volume up"),
    VOLUME_DOWN(6, "Volume down"),
    NEXT_REPEAT(7, "Change repeat mode"),
    NEXT_SHUFFLE(8, "Change shuffle mode"),
    SKIP_FORWARD(9, "Skip forward"),
    SKIP_BACKWARD(10, "Skip back"),
    LIKE(11, "Like track"),
    DISLIKE(12, "Dislike track"),
    BOOKMARK(13, "Bookmark"),
    PLAY(0, "Play"),
    PAUSE(1, "Pause");

    companion object {
        fun available(ids: Set<Int>?): List<MediaCommand> {
            if (ids == null) return listOf(TOGGLE_PLAYBACK, NEXT_TRACK, PREVIOUS_TRACK)
            return entries.filter { it.code in ids && !(TOGGLE_PLAYBACK.code in ids && it in listOf(PLAY, PAUSE)) }
        }

        /** Whether the iPhone accepts [command] right now (unknown list = assume the basics). */
        fun isSupported(ids: Set<Int>?, command: MediaCommand): Boolean =
            if (ids == null) command in listOf(TOGGLE_PLAYBACK, NEXT_TRACK, PREVIOUS_TRACK, VOLUME_UP, VOLUME_DOWN)
            else command.code in ids
    }
}

/**
 * Now Playing state from AMS Entity Update notifications:
 * EntityID(1) · AttributeID(1) · EntityUpdateFlags(1) · UTF-8 value.
 */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val player: String = "",
    val isPlaying: Boolean = false,
    /** 0..1, or null before the iPhone reports it. */
    val volume: Float? = null,
) {
    val hasTrack: Boolean get() = title.isNotBlank() || artist.isNotBlank()

    fun withUpdate(packet: ByteArray): NowPlaying {
        if (packet.size < 3) return this
        val value = packet.decodeToString(startIndex = 3)
        return when (packet[0]) {
            PLAYER_ENTITY -> when (packet[1]) {
                PLAYER_NAME -> copy(player = value)
                // "PlaybackState,PlaybackRate,ElapsedTime"; state 1 = playing.
                PLAYER_PLAYBACK_INFO -> copy(isPlaying = value.substringBefore(',').trim() == "1")
                PLAYER_VOLUME -> copy(volume = value.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: volume)
                else -> this
            }
            TRACK_ENTITY -> when (packet[1]) {
                TRACK_ARTIST -> copy(artist = value)
                TRACK_TITLE -> copy(title = value)
                else -> this
            }
            else -> this
        }
    }

    /** Kept for existing callers/tests: only track updates are applied. */
    fun withTrackUpdate(packet: ByteArray): NowPlaying =
        if (packet.isNotEmpty() && packet[0] == TRACK_ENTITY) withUpdate(packet) else this

    companion object {
        private const val PLAYER_ENTITY: Byte = 0
        private const val TRACK_ENTITY: Byte = 2
        private const val PLAYER_NAME: Byte = 0
        private const val PLAYER_PLAYBACK_INFO: Byte = 1
        private const val PLAYER_VOLUME: Byte = 2
        private const val TRACK_ARTIST: Byte = 0
        private const val TRACK_TITLE: Byte = 2

        fun playerSubscription(): ByteArray =
            byteArrayOf(PLAYER_ENTITY, PLAYER_NAME, PLAYER_PLAYBACK_INFO, PLAYER_VOLUME)

        fun trackSubscription(): ByteArray =
            byteArrayOf(TRACK_ENTITY, TRACK_ARTIST, TRACK_TITLE)
    }
}
