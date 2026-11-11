package com.adilet.ekd01

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A "knock": changing the assist level on the stock controls in a set order, each step within
 * [gapMs] of the one before, runs [actionId] (see [KnockActions]) with [parameter].
 * [sequence] is the list of levels as the display reports them; its first level is where you start.
 */
data class Knock(
    val id: String,
    val sequence: List<Int>,
    val gapMs: Int,
    val actionId: String,
    val parameter: String?,
    val enabled: Boolean
) {
    val sequenceText: String get() = sequence.joinToString("-")
}

/** The saved knocks (JSON in the app preferences). Read from any thread, changed from the UI. */
object KnockStore {
    private const val PREFS = "ekd01_prefs"
    private const val KEY = "knocks_json"

    const val MIN_STEPS = 2
    const val MAX_STEPS = 12
    const val DEFAULT_GAP_MS = 2000
    const val MIN_GAP_MS = 200
    const val MAX_GAP_MS = 10_000

    @Volatile private var items: List<Knock> = emptyList()
    private val lock = Any()

    val all: List<Knock> get() = items

    fun load(ctx: Context) = synchronized(lock) {
        val text = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        items = if (text == null) emptyList() else try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val seq = parseSequence(o.getString("sequence")) ?: return@mapNotNull null
                Knock(
                    id = o.getString("id"),
                    sequence = seq,
                    gapMs = o.optInt("gapMs", DEFAULT_GAP_MS).coerceIn(MIN_GAP_MS, MAX_GAP_MS),
                    actionId = o.getString("action"),
                    parameter = if (o.isNull("parameter")) null else o.getString("parameter"),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Knock>) {
        val arr = JSONArray()
        for (k in list) {
            arr.put(
                JSONObject().put("id", k.id).put("sequence", k.sequenceText).put("gapMs", k.gapMs)
                    .put("action", k.actionId).put("parameter", k.parameter ?: JSONObject.NULL).put("enabled", k.enabled)
            )
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        items = list
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Adds the knock, or replaces the one with the same id. */
    fun upsert(ctx: Context, knock: Knock) = synchronized(lock) {
        val list = items.toMutableList()
        val i = list.indexOfFirst { it.id == knock.id }
        if (i >= 0) list[i] = knock else list.add(knock)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) { save(ctx, items.filterNot { it.id == id }) }

    fun setEnabled(ctx: Context, id: String, enabled: Boolean) = synchronized(lock) {
        save(ctx, items.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** Levels 0-5 separated by "-", "," or spaces; [MIN_STEPS] to [MAX_STEPS] of them. Null if it does not parse. */
    fun parseSequence(text: String): List<Int>? {
        val parts = text.split('-', ',', ' ', ';').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() ?: return null }
        return parts.takeIf { it.size in MIN_STEPS..MAX_STEPS && it.all { v -> v in 0..5 } }
    }

    /**
     * A saved knock whose sequence contains [sequence] or is contained in it (this includes being equal).
     * Such pairs cannot work: the shorter one would always fire first. [ignoreId] = the knock being edited.
     */
    fun conflictWith(sequence: List<Int>, ignoreId: String?): Knock? =
        items.firstOrNull { it.id != ignoreId && (contains(it.sequence, sequence) || contains(sequence, it.sequence)) }

    private fun contains(big: List<Int>, small: List<Int>): Boolean {
        if (small.size > big.size) return false
        for (start in 0..big.size - small.size) {
            if (small.indices.all { big[start + it] == small[it] }) return true
        }
        return false
    }
}

/**
 * Watches the assist level reported by the display and runs the matching knock's action.
 * Called from [BikeLink] for every T1 packet, so it works in background mode too.
 */
object KnockDetector {
    private class State(val level: Int, val elapsedMs: Long)
    private val states = ArrayList<State>()
    private val main = Handler(Looper.getMainLooper())

    fun reset() = synchronized(states) { states.clear() }

    fun onLevel(ctx: Context, level: Int, elapsedMs: Long) {
        val knocks = KnockStore.all.filter { it.enabled }
        if (knocks.isEmpty()) return
        val longest = knocks.maxOf { it.sequence.size }
        val hit: Knock? = synchronized(states) {
            if (states.isNotEmpty() && states.last().level == level) return
            states.add(State(level, elapsedMs))
            while (states.size > longest) states.removeAt(0)
            val found = knocks.firstOrNull { matches(it) }
            if (found != null) {
                states.clear()
                states.add(State(level, elapsedMs))
            }
            found
        }
        if (hit != null) main.post { fire(ctx.applicationContext, hit) }
    }

    /** The last [Knock.sequence].size levels must equal the sequence, each change after the first within the time limit. */
    private fun matches(k: Knock): Boolean {
        val n = k.sequence.size
        if (states.size < n) return false
        val first = states.size - n
        for (i in 0 until n) {
            val s = states[first + i]
            if (s.level != k.sequence[i]) return false
            if (i >= 1 && s.elapsedMs - states[first + i - 1].elapsedMs > k.gapMs) return false
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun fire(ctx: Context, knock: Knock) {
        val action = KnockActions.byId(knock.actionId)
        val actionName = action?.let { ctx.getString(it.nameRes) } ?: knock.actionId
        BikeLink.log(ctx.getString(R.string.log_knock, knock.sequenceText, actionName))
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        BikeLink.notifyGesture("${knock.sequenceText} → $actionName")
        val ok = try { action?.run(ctx, knock.parameter) ?: false } catch (e: Exception) { false }
        if (!ok) BikeLink.log(ctx.getString(R.string.log_knock_failed, actionName))
    }
}
