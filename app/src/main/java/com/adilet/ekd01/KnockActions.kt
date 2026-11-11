package com.adilet.ekd01

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/**
 * Something a knock can do. To add a new action: subclass this (or one of the helpers below),
 * add a name string, and list the instance in [KnockActions.all]. Nothing else needs to change:
 * the editor's dropdown, the permission request and the saved settings all work from this class.
 *
 * Actions run on the main thread.
 */
abstract class KnockAction(
    /** Stable key saved with a knock. Never change it once released. */
    val id: String,
    @StringRes val nameRes: Int,
    /** Hint for the extra value the action needs (e.g. a phone number), or null if it needs none. */
    @StringRes val parameterHintRes: Int? = null
) {
    val needsParameter: Boolean get() = parameterHintRes != null

    /** Keyboard type for the extra value (an [InputType] constant). */
    open val parameterInputType: Int = InputType.TYPE_CLASS_TEXT

    /** Runtime permissions the action needs. The editor asks for them when a knock is saved. */
    open val requiredPermissions: List<String> = emptyList()

    /** Cleans up the typed value; null if it is not usable. Only called when [needsParameter]. */
    open fun normalizeParameter(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    /** Does it. Returns false if it could not (no permission, no target, ...). */
    abstract fun run(ctx: Context, parameter: String?): Boolean

    fun missingPermissions(ctx: Context): List<String> =
        requiredPermissions.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
}

/** Sends a media key to whatever player is active. No permission needed. */
class MediaKeyAction(id: String, @StringRes nameRes: Int, private val keyCode: Int) : KnockAction(id, nameRes) {
    override fun run(ctx: Context, parameter: String?): Boolean {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return true
    }
}

/** Raises or lowers the media volume by one step and shows the volume bar. */
class VolumeAction(id: String, @StringRes nameRes: Int, private val direction: Int) : KnockAction(id, nameRes) {
    override fun run(ctx: Context, parameter: String?): Boolean {
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return true
    }
}

/** Places a phone call to the number saved with the knock. */
class CallAction(id: String, @StringRes nameRes: Int, @StringRes hintRes: Int) : KnockAction(id, nameRes, hintRes) {
    override val requiredPermissions = listOf(Manifest.permission.CALL_PHONE)
    override val parameterInputType = InputType.TYPE_CLASS_PHONE

    /** Digits, with an optional leading + ; spaces, dashes and brackets are dropped. At least 3 digits. */
    override fun normalizeParameter(raw: String): String? {
        val cleaned = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        return cleaned.takeIf { c -> c.count { it.isDigit() } >= 3 && !c.drop(1).contains('+') }
    }

    @SuppressLint("MissingPermission")
    override fun run(ctx: Context, parameter: String?): Boolean {
        if (parameter == null || missingPermissions(ctx).isNotEmpty()) return false
        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
        return try {
            telecom.placeCall(Uri.fromParts("tel", parameter, null), Bundle())
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** The list of available actions, in the order the editor's dropdown shows them. */
object KnockActions {
    val all: List<KnockAction> = listOf(
        MediaKeyAction("media_play_pause", R.string.action_media_play_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        MediaKeyAction("media_play", R.string.action_media_play, KeyEvent.KEYCODE_MEDIA_PLAY),
        MediaKeyAction("media_pause", R.string.action_media_pause, KeyEvent.KEYCODE_MEDIA_PAUSE),
        MediaKeyAction("media_next", R.string.action_media_next, KeyEvent.KEYCODE_MEDIA_NEXT),
        MediaKeyAction("media_previous", R.string.action_media_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
        VolumeAction("volume_up", R.string.action_volume_up, AudioManager.ADJUST_RAISE),
        VolumeAction("volume_down", R.string.action_volume_down, AudioManager.ADJUST_LOWER),
        CallAction("call", R.string.action_call, R.string.action_call_hint)
    )

    fun byId(id: String): KnockAction? = all.firstOrNull { it.id == id }
}
