package com.wearos.ancsbridge.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Contact photos from the iPhone's phone book (PBAP PHOTO), stored as small JPEG files and
 * looked up by contact name (ANCS titles are contact names, also for WhatsApp, Messenger…)
 * or by phone number / email (MAP addresses).
 */
object Avatars {
    private const val TAG = "Avatars"
    private const val SIZE = 128

    private val cache = LruCache<String, Bitmap>(48)
    @Volatile private var index: Index? = null

    private class Index(val byName: Map<String, String>, val byAddress: Map<String, String>)

    private fun dir(context: Context) = File(context.filesDir, "avatars").apply { mkdirs() }

    private fun key(name: String) = name.trim().lowercase()

    /** Replaces the stored photos with those of [contacts]. */
    fun store(context: Context, contacts: List<Contact>) {
        val folder = dir(context)
        folder.listFiles()?.forEach { it.delete() }
        val names = JSONObject()
        val addresses = JSONObject()
        contacts.forEach { contact ->
            val photo = contact.photo ?: return@forEach
            val bitmap = decodeScaled(photo) ?: return@forEach
            val file = sha1(contact.name + contact.addresses.joinToString()) + ".jpg"
            runCatching {
                File(folder, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }.onFailure { Log.w(TAG, "Saving photo failed", it); return@forEach }
            names.put(key(contact.name), file)
            contact.addresses.forEach { addresses.put(MapFormats.normalize(it), file) }
        }
        File(folder, "index.json").writeText(JSONObject().put("n", names).put("a", addresses).toString())
        cache.evictAll()
        index = null
        Log.i(TAG, "Stored ${names.length()} contact photos")
    }

    fun forName(context: Context, name: String?): Bitmap? {
        if (name.isNullOrBlank()) return null
        return load(context, index(context).byName[key(name)])
    }

    fun forAddress(context: Context, address: String?): Bitmap? {
        if (address.isNullOrBlank()) return null
        val wanted = MapFormats.normalize(address)
        val idx = index(context)
        val file = idx.byAddress[wanted]
            ?: idx.byAddress.entries.firstOrNull { MapFormats.sameAddress(it.key, wanted) }?.value
        return load(context, file)
    }

    /** Photo for a sender: by address first (exact), then by name. */
    fun find(context: Context, name: String?, address: String? = null): Bitmap? =
        forAddress(context, address) ?: forName(context, name)

    /** A circular copy, for notification icons. */
    fun circle(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply {
                    setTranslate(-(source.width - size) / 2f, -(source.height - size) / 2f)
                })
            }
        }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun index(context: Context): Index {
        index?.let { return it }
        val loaded = runCatching {
            val json = JSONObject(File(dir(context), "index.json").readText())
            fun map(o: JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.getString(it) }.orEmpty()
            Index(map(json.optJSONObject("n")), map(json.optJSONObject("a")))
        }.getOrDefault(Index(emptyMap(), emptyMap()))
        index = loaded
        return loaded
    }

    private fun load(context: Context, file: String?): Bitmap? {
        if (file == null) return null
        cache.get(file)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(File(dir(context), file).path) ?: return null
        cache.put(file, bitmap)
        return bitmap
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
    }.getOrNull()

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
