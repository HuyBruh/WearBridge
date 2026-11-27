package com.wearos.ancsbridge.ancs

import com.wearos.ancsbridge.model.AncsEvent
import org.junit.Assert.*
import org.junit.Test

class PendingAlertsTest {
    private val queue = PendingAlerts()
    private fun event(uid: Long, flags: Int = 0) = AncsEvent(0, flags, 4, 1, uid)

    @Test fun swipeOnlyRunsAnExplicitClearAction() {
        val alert = com.wearos.ancsbridge.model.AncsNotification(1, "app", null, "", null, "", null, 0, 0, "Open", "Delete")
        assertNull(alert.dismissAction())
        assertNull(alert.copy(negativeActionLabel = "Decline").dismissAction())
        assertEquals(1, alert.copy(negativeActionLabel = "Xóa thông báo").dismissAction())
        assertEquals(0, alert.copy(positiveActionLabel = "Dismiss").dismissAction())
    }

    @Test fun liveAlertsJumpAheadOfQuietCatchUp() {
        repeat(35) { queue.offer(event(it.toLong(), 4)) }
        queue.offer(event(85))
        assertEquals(85L, queue.next()?.notificationUid)
        assertEquals(15L, queue.next()?.notificationUid)
    }

    @Test fun updatesCoalesceAndRemoveWins() {
        repeat(200) { queue.offer(event(it.toLong())) }
        queue.offer(event(55, 2))
        queue.remove(70)
        val delivered = generateSequence { queue.next() }.toList()
        assertEquals(199, delivered.size)
        assertEquals(2, delivered.single { it.notificationUid == 55L }.eventFlags)
        assertFalse(delivered.any { it.notificationUid == 70L })
    }

    @Test fun liveEventOverridesBacklogAndSessionResetDropsEveryUid() {
        queue.offer(event(4, 4))
        queue.offer(event(4))
        queue.offer(event(4, 4))
        assertFalse(queue.next()!!.isPreExisting)
        assertNull(queue.next())
        queue.offer(event(4))
        queue.offer(event(5, 4))
        queue.clear()
        assertNull(queue.next())
    }
}
