package com.wearos.ancsbridge.model

/**
 * Fully resolved notification with attributes fetched from ANCS Data Source.
 */
data class AncsNotification(
    val uid: Long,
    val appIdentifier: String,
    val appDisplayName: String?,
    val title: String,
    val subtitle: String?,
    val message: String,
    val date: String?, // ANCS format: yyyyMMdd'T'HHmmss
    val categoryId: Int,
    val eventFlags: Int,
    val positiveActionLabel: String?,
    val negativeActionLabel: String?
) {
    // Only mirror an explicitly offered dismissal. Other negative actions can delete
    // mail, decline invitations or reject calls. Unknown translations stay local.
    fun dismissAction(): Int? {
        val labels = setOf("dismiss", "clear", "clear notification", "xóa thông báo", "bỏ qua thông báo")
        return when {
            positiveActionLabel?.trim()?.lowercase() in labels -> 0
            negativeActionLabel?.trim()?.lowercase() in labels -> 1
            else -> null
        }
    }
}
