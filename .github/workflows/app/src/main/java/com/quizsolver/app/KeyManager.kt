package com.quizsolver.app

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class KeyManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(
        "quiz_solver_prefs",
        Context.MODE_PRIVATE
    )

    private val cooldown = ConcurrentHashMap<String, Long>()
    private val pairPointer = AtomicInteger(0)

    fun nextKeys(count: Int): List<String> = synchronized(this) {
        val allKeys = loadKeys()

        if (allKeys.isEmpty()) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        cleanExpiredCooldowns(now)

        val pairs = (allKeys.size + 1) / 2

        val pairIndex = if (pairs > 0) {
            Math.floorMod(pairPointer.getAndIncrement(), pairs)
        } else {
            0
        }

        val start = pairIndex * 2
        val result = LinkedHashSet<String>()

        for (i in start until min(start + 2, allKeys.size)) {
            val key = allKeys[i]

            if (isHealthy(key, now)) {
                result.add(key)
            }
        }

        var offset = 0

        while (result.size < count && offset < allKeys.size) {
            val key = allKeys[(start + 2 + offset) % allKeys.size]

            if (isHealthy(key, now)) {
                result.add(key)
            }

            offset++
        }

        if (result.isEmpty()) {
            cooldown.clear()
            return allKeys.take(count)
        }

        result.take(count)
    }

    private fun loadKeys(): List<String> {
        val keys = ArrayList<String>()

        for (i in 1..8) {
            val value = prefs.getString("key_$i", "")?.trim() ?: ""

            if (value.isNotEmpty() && (value.startsWith("AIza") || value.startsWith("AQ."))) {
                keys.add(value)
            }
        }

        return keys
    }

    private fun cleanExpiredCooldowns(now: Long) {
        val iterator = cooldown.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()

            if (entry.value <= now) {
                iterator.remove()
            }
        }
    }

    private fun isHealthy(key: String, now: Long): Boolean {
        val blockedUntil = cooldown[key] ?: 0L
        return blockedUntil <= now
    }

    fun markHttpError(key: String, code: Int) {
        val now = System.currentTimeMillis()

        if (code == 429 || code == 403) {
            cooldown[key] = now + 60_000L
        } else if (code >= 500) {
            cooldown[key] = now + 8_000L
        }
    }

    fun markTimeout(key: String) {
        cooldown[key] = System.currentTimeMillis() + 5_000L
    }
}
