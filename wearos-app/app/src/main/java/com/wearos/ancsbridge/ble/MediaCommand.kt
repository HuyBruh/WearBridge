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
    }
}

data class NowPlaying(val title: String = "", val artist: String = "") {
    fun withTrackUpdate(packet: ByteArray): NowPlaying {
        if (packet.size < 3 || packet[0] != TRACK_ENTITY) return this
        val value = packet.decodeToString(startIndex = 3)
        return when (packet[1]) {
            ARTIST_ATTRIBUTE -> copy(artist = value)
            TITLE_ATTRIBUTE -> copy(title = value)
            else -> this
        }
    }

    companion object {
        private const val TRACK_ENTITY: Byte = 2
        private const val ARTIST_ATTRIBUTE: Byte = 0
        private const val TITLE_ATTRIBUTE: Byte = 2

        fun trackSubscription(): ByteArray =
            byteArrayOf(TRACK_ENTITY, ARTIST_ATTRIBUTE, TITLE_ATTRIBUTE)
    }
}
