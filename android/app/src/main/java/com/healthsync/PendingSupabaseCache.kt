package com.healthsync

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

object PendingSupabaseCache {
    private const val FILE_NAME = "pending_supabase_payload.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun save(context: Context, content: String) {
        val target = file(context)
        val temp = File(context.filesDir, FILE_NAME + ".tmp")
        temp.writeText(content, StandardCharsets.UTF_8)
        if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace pending Supabase cache")
        if (!temp.renameTo(target)) throw IllegalStateException("Could not commit pending Supabase cache")
    }

    fun read(context: Context): String? {
        val target = file(context)
        if (!target.exists()) return null
        return runCatching { target.readText(StandardCharsets.UTF_8) }.getOrNull()
    }

    fun clear(context: Context) { runCatching { file(context).delete() } }
    fun hasPending(context: Context): Boolean = file(context).exists()
}
