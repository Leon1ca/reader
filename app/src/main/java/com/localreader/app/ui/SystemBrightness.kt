package com.localreader.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * The reader's brightness slider changes the system brightness once the user has allowed "modify system settings"
 * (WRITE_SETTINGS; the user chose this on 2026-10-14). Without that permission the first drag opens the system page for it
 * once per process, and the slider keeps working on the reading window alone, as before.
 */
internal object SystemBrightness {
    private var asked=false
    fun canWrite(context: Context): Boolean=Settings.System.canWrite(context)
    /** The current system brightness as 0..1, or null if it cannot be read. */
    fun read(context: Context): Float?=try { Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS)/255f } catch(_: Settings.SettingNotFoundException) { null }
    /** Writes [value] (0..1) as the system brightness, switching off automatic brightness; false if the system refused. */
    fun write(context: Context,value: Float): Boolean=try {
        val resolver=context.contentResolver
        Settings.System.putInt(resolver,Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver,Settings.System.SCREEN_BRIGHTNESS,(value*255).roundToInt().coerceIn(1,255))
    } catch(_: SecurityException) { false } catch(_: IllegalArgumentException) { false }
    /** Opens the system permission page the first time only; a refusal is respected for the rest of the session. */
    fun requestOnce(context: Context) {
        if(asked)return
        asked=true
        try { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,"package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch(_: ActivityNotFoundException) { }
    }
}
