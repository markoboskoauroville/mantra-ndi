package com.mantraproductions.ndi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeping the app alive while it is working, and asking properly.
 *
 * Android stops background work for apps it has decided are idle, and a phone
 * that is streaming from a shelf looks exactly like an idle one to that
 * heuristic: the screen is off, nobody is touching it. The stream stops, and
 * the operator finds out from the mixer rather than from the phone.
 *
 * The exemption is therefore worth asking for, but it is asked once and the
 * answer is respected. Two routes are offered because manufacturers differ:
 * the direct request dialog where it works, and the settings page where the
 * dialog is suppressed, which several Chinese OEM builds do.
 */
object PowerPolicy {

    /** Whether the system has already agreed to leave this app running. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The system's own dialog, which grants the exemption in one tap.
     *
     * Some builds refuse to show it. The caller falls back to the settings
     * page rather than leaving the operator with a button that did nothing.
     */
    fun requestExemption(context: Context): Boolean {
        if (isExempt(context)) return true
        return try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The list of apps and their battery settings, as a fallback.
     *
     * Lands on the list rather than on this app's entry, because there is no
     * public intent that opens a single app's page, and inventing one with a
     * vendor specific component name breaks on the next phone.
     */
    fun openBatterySettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        // Last resort: this app's own system page, which every build has.
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e2: Exception) {
            false
        }
    }

    /**
     * Whether it is worth asking at all.
     *
     * Not if it has been granted, and not twice. A prompt that returns every
     * launch teaches people to dismiss prompts.
     */
    fun shouldAsk(context: Context, settings: AppSettings): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !settings.batteryAsked &&
            !isExempt(context)
}
