package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.model.AncsEvent

/** Coalesce repeated updates by UID. Restore missed alerts after live traffic. */
internal class PendingAlerts {
    private val live = linkedMapOf<Long, AncsEvent>()
    private val backlog = linkedMapOf<Long, AncsEvent>()

    fun offer(event: AncsEvent) {
        val uid = event.notificationUid
        if (event.isPreExisting && uid in live) return
        val queue = if (event.isPreExisting) backlog else live
        if (!event.isPreExisting) backlog.remove(uid)
        queue[uid] = event
        if (queue === backlog && queue.size > 20) queue.remove(queue.keys.first())
    }

    fun contains(uid: Long) = uid in live || uid in backlog

    fun remove(uid: Long) {
        live.remove(uid)
        backlog.remove(uid)
    }

    fun next(): AncsEvent? {
        val queue = if (live.isNotEmpty()) live else backlog
        val uid = queue.keys.firstOrNull() ?: return null
        return queue.remove(uid)
    }

    fun clear() {
        live.clear()
        backlog.clear()
    }
}
