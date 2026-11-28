package com.wearos.ancsbridge.ancs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.ble.AncsConstants

/**
 * Maps iOS app bundle IDs to bundled drawable icons.
 * Falls back to category-based generic icons for unknown apps.
 */
object AppIconMapper {

    // Map of iOS bundle IDs to drawable resource IDs
    private val appIconMap = mapOf(
        // Apple Apps
        "com.apple.MobileSMS" to R.drawable.ic_messages,
        "com.apple.mobilephone" to R.drawable.ic_phone,
        "com.apple.mobilemail" to R.drawable.ic_email,
        "com.apple.mobilecal" to R.drawable.ic_calendar,
        "com.apple.facetime" to R.drawable.ic_phone,
        "com.apple.reminders" to R.drawable.ic_schedule,
        "com.apple.news" to R.drawable.ic_news,
        "com.apple.mobilenotes" to R.drawable.ic_notes,
        "com.apple.Health" to R.drawable.ic_health,
        "com.apple.Fitness" to R.drawable.ic_fitness,
        "com.apple.weather" to R.drawable.ic_notification_default,
        "com.apple.Maps" to R.drawable.ic_location,
        "com.apple.Music" to R.drawable.ic_music,
        "com.apple.stocks" to R.drawable.ic_stocks,
        "com.apple.camera" to R.drawable.ic_camera,
        "com.apple.Passbook" to R.drawable.ic_wallet,
        "com.apple.Preferences" to R.drawable.ic_settings,
        "com.apple.mobiletimer" to R.drawable.ic_schedule,
        "com.apple.podcasts" to R.drawable.ic_podcast,
        "com.apple.findmy" to R.drawable.ic_location,
        "com.apple.Photos" to R.drawable.ic_camera,
        "com.apple.TestFlight" to R.drawable.ic_notification_default,
        "com.apple.AppStore" to R.drawable.ic_shopping,
        "com.apple.Passwords" to R.drawable.ic_vpn,
        "com.apple.Home" to R.drawable.ic_notification_default,

        // Messaging
        "vn.com.vng.zingalo" to R.drawable.ic_messages,
        "net.whatsapp.WhatsApp" to R.drawable.ic_whatsapp,
        "net.whatsapp.WhatsAppSMB" to R.drawable.ic_whatsapp,
        "org.telegram.Telegram" to R.drawable.ic_telegram,
        "com.facebook.Messenger" to R.drawable.ic_messenger,
        "org.whispersystems.signal" to R.drawable.ic_signal,
        "com.slack.Slack" to R.drawable.ic_slack,
        "com.hammerandchisel.discord" to R.drawable.ic_discord,

        // Social
        "com.atebits.Tweetie2" to R.drawable.ic_social,
        "com.burbn.instagram" to R.drawable.ic_instagram,
        "com.facebook.Facebook" to R.drawable.ic_social,
        "com.facebook.Pages" to R.drawable.ic_social,
        "com.linkedin.LinkedIn" to R.drawable.ic_social,
        "com.zhiliaoapp.musically" to R.drawable.ic_social,
        "com.toyopagroup.picaboo" to R.drawable.ic_social,
        "com.burbn.barcelona" to R.drawable.ic_threads,
        "com.reddit.Reddit" to R.drawable.ic_social,

        // Email & Productivity
        "com.google.Gmail" to R.drawable.ic_gmail,
        "com.microsoft.Office.Outlook" to R.drawable.ic_email,
        "com.google.calendar" to R.drawable.ic_calendar,
        "notion.id" to R.drawable.ic_notes,
        "com.grammarly.keyboard" to R.drawable.ic_notification_default,

        // Google Apps
        "com.google.GoogleMobile" to R.drawable.ic_browser,
        "com.google.Maps" to R.drawable.ic_location,
        "com.google.Drive" to R.drawable.ic_business,
        "com.google.Photos" to R.drawable.ic_camera,
        "com.google.photos" to R.drawable.ic_camera,
        "com.google.Home" to R.drawable.ic_notification_default,
        "com.google.chrome.ios" to R.drawable.ic_browser,
        "com.google.ios.youtube" to R.drawable.ic_youtube,

        // Entertainment & Media
        "com.spotify.client" to R.drawable.ic_music,
        "com.shazam.Shazam" to R.drawable.ic_music,

        // Ride-sharing & Delivery
        "com.ubercab.UberClient" to R.drawable.ic_rideshare,
        "com.ubercab.UberEats" to R.drawable.ic_rideshare,

        // Finance & Banking
        "au.com.westpac.ConsultWPC" to R.drawable.ic_wallet,
        "au.com.westpac.banking" to R.drawable.ic_wallet,
        "com.stake.stake" to R.drawable.ic_stocks,
        "au.com.hellostake" to R.drawable.ic_stocks,
        "com.afterpay.afterpay-consumer" to R.drawable.ic_shopping,

        // Health & Fitness
        "com.anytimefitness.club" to R.drawable.ic_fitness,
        "com.anytimefitness.atfmobile" to R.drawable.ic_fitness,
        "au.com.hotdoc.app" to R.drawable.ic_health,
        "com.hotdoc.patient" to R.drawable.ic_health,

        // Telecom
        "com.jio.myjio" to R.drawable.ic_phone,
        "com.ril.ajio" to R.drawable.ic_phone,

        // Shopping & Services
        "au.com.auspost" to R.drawable.ic_shopping,
        "com.auspost.MyPost" to R.drawable.ic_shopping,

        // Smart Home
        "com.philips.hue.gen4" to R.drawable.ic_notification_default,
        "com.signify.hue.blue" to R.drawable.ic_notification_default,

        // Gaming
        "com.microsoft.smartglass" to R.drawable.ic_gaming,
        "com.microsoft.xboxapp" to R.drawable.ic_gaming,

        // VPN
        "com.surfshark.vpnclient" to R.drawable.ic_vpn,

        // Other
        "com.producthunt.ProductHuntApp" to R.drawable.ic_social,
        "com.github.stormcrow" to R.drawable.ic_notification_default,
    ).mapKeys { it.key.lowercase() }

    // Category-based fallback icons
    private val categoryIconMap = mapOf(
        AncsConstants.CATEGORY_INCOMING_CALL to R.drawable.ic_phone,
        AncsConstants.CATEGORY_MISSED_CALL to R.drawable.ic_phone,
        AncsConstants.CATEGORY_VOICEMAIL to R.drawable.ic_phone,
        AncsConstants.CATEGORY_SOCIAL to R.drawable.ic_social,
        AncsConstants.CATEGORY_SCHEDULE to R.drawable.ic_schedule,
        AncsConstants.CATEGORY_EMAIL to R.drawable.ic_email,
        AncsConstants.CATEGORY_NEWS to R.drawable.ic_news,
        AncsConstants.CATEGORY_HEALTH_FITNESS to R.drawable.ic_health,
        AncsConstants.CATEGORY_BUSINESS_FINANCE to R.drawable.ic_business,
        AncsConstants.CATEGORY_LOCATION to R.drawable.ic_location,
        AncsConstants.CATEGORY_ENTERTAINMENT to R.drawable.ic_entertainment,
    )

    /**
     * Get the icon resource ID for an app.
     */
    fun getIconResId(appIdentifier: String, categoryId: Int): Int {
        return appIconMap[appIdentifier.trim().lowercase()]
            ?: categoryIconMap[categoryId]
            ?: R.drawable.ic_notification_default
    }

    /**
     * Get an Icon object for use in notifications.
     */
    fun getIcon(context: Context, appIdentifier: String, categoryId: Int): Icon {
        return Icon.createWithResource(context, getIconResId(appIdentifier, categoryId))
    }

    // Brand-ish background colors, like the app icon tint Apple Watch shows on alerts.
    private val appColorMap = mapOf(
        "com.apple.MobileSMS" to 0xFF34C759,
        "com.apple.mobilephone" to 0xFF34C759,
        "com.apple.facetime" to 0xFF34C759,
        "com.apple.mobilemail" to 0xFF1E88FF,
        "com.apple.mobilecal" to 0xFFFF3B30,
        "com.apple.reminders" to 0xFFFF9500,
        "com.apple.mobiletimer" to 0xFFFF9500,
        "com.apple.news" to 0xFFFF2D55,
        "com.apple.mobilenotes" to 0xFFFFB800,
        "com.apple.Health" to 0xFFFF2D55,
        "com.apple.Fitness" to 0xFF30D158,
        "com.apple.Maps" to 0xFF30B0C7,
        "com.apple.Music" to 0xFFFA2D48,
        "com.apple.podcasts" to 0xFF9B4DFF,
        "com.apple.Passbook" to 0xFF1C1C1E,
        "com.apple.findmy" to 0xFF34C759,
        "com.apple.AppStore" to 0xFF0A84FF,
        "vn.com.vng.zingalo" to 0xFF0068FF,
        "net.whatsapp.WhatsApp" to 0xFF25D366,
        "net.whatsapp.WhatsAppSMB" to 0xFF25D366,
        "org.telegram.Telegram" to 0xFF229ED9,
        "com.facebook.Messenger" to 0xFF0084FF,
        "com.facebook.Facebook" to 0xFF1877F2,
        "com.facebook.Pages" to 0xFF1877F2,
        "org.whispersystems.signal" to 0xFF3A76F0,
        "com.slack.Slack" to 0xFF4A154B,
        "com.hammerandchisel.discord" to 0xFF5865F2,
        "com.burbn.instagram" to 0xFFE1306C,
        "com.burbn.barcelona" to 0xFF1C1C1E,
        "com.atebits.Tweetie2" to 0xFF1C1C1E,
        "com.zhiliaoapp.musically" to 0xFF1C1C1E,
        "com.linkedin.LinkedIn" to 0xFF0A66C2,
        "com.reddit.Reddit" to 0xFFFF4500,
        "com.google.Gmail" to 0xFFEA4335,
        "com.microsoft.Office.Outlook" to 0xFF0078D4,
        "com.google.calendar" to 0xFF4285F4,
        "com.google.ios.youtube" to 0xFFFF0000,
        "com.spotify.client" to 0xFF1DB954,
        "com.ubercab.UberClient" to 0xFF1C1C1E,
        "com.ubercab.UberEats" to 0xFF06C167,
    ).mapKeys { it.key.lowercase() }

    private val categoryColorMap = mapOf(
        AncsConstants.CATEGORY_INCOMING_CALL to 0xFF34C759,
        AncsConstants.CATEGORY_MISSED_CALL to 0xFF34C759,
        AncsConstants.CATEGORY_VOICEMAIL to 0xFF34C759,
        AncsConstants.CATEGORY_SOCIAL to 0xFF0A84FF,
        AncsConstants.CATEGORY_SCHEDULE to 0xFFFF3B30,
        AncsConstants.CATEGORY_EMAIL to 0xFF1E88FF,
        AncsConstants.CATEGORY_NEWS to 0xFFFF2D55,
        AncsConstants.CATEGORY_HEALTH_FITNESS to 0xFFFF2D55,
        AncsConstants.CATEGORY_BUSINESS_FINANCE to 0xFF5E5CE6,
        AncsConstants.CATEGORY_LOCATION to 0xFF30B0C7,
        AncsConstants.CATEGORY_ENTERTAINMENT to 0xFFBF5AF2,
    )

    fun colorFor(appIdentifier: String, categoryId: Int): Int =
        (appColorMap[appIdentifier.trim().lowercase()] ?: categoryColorMap[categoryId] ?: 0xFF636366).toInt()

    private val badgeCache = HashMap<String, Bitmap>()

    /**
     * Round, colored app badge (white glyph on the brand color) used as the
     * notification's large icon and sender avatar, so every alert is recognisable at a
     * glance the way Apple Watch shows the app icon.
     */
    fun badge(context: Context, appIdentifier: String, categoryId: Int, sizePx: Int = 96): Bitmap {
        val key = "${appIdentifier.lowercase()}|$categoryId|$sizePx"
        badgeCache[key]?.let { return it }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(appIdentifier, categoryId) }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
        ContextCompat.getDrawable(context, getIconResId(appIdentifier, categoryId))?.mutate()?.let { glyph ->
            glyph.setTint(android.graphics.Color.WHITE)
            // The vectors already carry 25% padding; scale them up slightly inside the disc.
            val inset = (sizePx * 0.06f).toInt()
            glyph.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            glyph.draw(canvas)
        }
        if (badgeCache.size > 64) badgeCache.clear()
        badgeCache[key] = bitmap
        return bitmap
    }
}
