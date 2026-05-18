package com.fishcam.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object AppPreferences {

    private const val PREF_NAME = "fishcam_prefs"

    private const val KEY_SETUP_DONE        = "setup_done"
    private const val KEY_START_COMMAND     = "start_command"
    private const val KEY_STOP_COMMAND      = "stop_command"
    private const val KEY_CAMERA_FACING     = "camera_facing"   // "back" | "front"
    private const val KEY_BUFFER_SECONDS    = "buffer_seconds"  // default 15
    private const val KEY_SAVE_FOLDER       = "save_folder"     // display name

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Setup ────────────────────────────────────────────────────────────────

    fun isSetupDone(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SETUP_DONE, false)

    fun markSetupDone(context: Context) =
        prefs(context).edit { putBoolean(KEY_SETUP_DONE, true) }

    // ── Voice Commands ───────────────────────────────────────────────────────

    fun getStartCommand(context: Context): String =
        prefs(context).getString(KEY_START_COMMAND, "peixe") ?: "peixe"

    fun setStartCommand(context: Context, cmd: String) =
        prefs(context).edit { putString(KEY_START_COMMAND, cmd.lowercase().trim()) }

    fun getStopCommand(context: Context): String =
        prefs(context).getString(KEY_STOP_COMMAND, "parar") ?: "parar"

    fun setStopCommand(context: Context, cmd: String) =
        prefs(context).edit { putString(KEY_STOP_COMMAND, cmd.lowercase().trim()) }

    // ── Camera ───────────────────────────────────────────────────────────────

    fun getCameraFacing(context: Context): String =
        prefs(context).getString(KEY_CAMERA_FACING, "back") ?: "back"

    fun setCameraFacing(context: Context, facing: String) =
        prefs(context).edit { putString(KEY_CAMERA_FACING, facing) }

    // ── Buffer ───────────────────────────────────────────────────────────────

    fun getBufferSeconds(context: Context): Int =
        prefs(context).getInt(KEY_BUFFER_SECONDS, 15)

    fun setBufferSeconds(context: Context, seconds: Int) =
        prefs(context).edit { putInt(KEY_BUFFER_SECONDS, seconds) }

    // ── Save Folder ──────────────────────────────────────────────────────────

    fun getSaveFolder(context: Context): String =
        prefs(context).getString(KEY_SAVE_FOLDER, "FishCam") ?: "FishCam"

    fun setSaveFolder(context: Context, folder: String) =
        prefs(context).edit { putString(KEY_SAVE_FOLDER, folder) }
}
