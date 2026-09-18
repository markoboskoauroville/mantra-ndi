package com.mantraproductions.ndi

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Hardware keys, taken before the rest of the system sees them.
 *
 * An accessibility service with key filtering observes key events ahead of
 * dispatch, which is the only route an ordinary app has to a key the platform
 * normally keeps. It is how the volume rocker keeps working when the app is
 * behind something else, and it is the only chance the power button has.
 *
 * **Whether the power key arrives here is not documented and not guaranteed.**
 * On most builds the power key is consumed by the window policy before
 * anything downstream is offered it, and there is no permission or flag that
 * changes that. Rather than claim it works or claim it cannot, this service
 * records every key code it is actually handed, and the settings screen shows
 * that list. The phone answers the question.
 *
 * Nothing is consumed unless it is a key this app has a use for, so the volume
 * rocker still changes the volume everywhere else.
 */
class KeyService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used. This service exists for the key stream alone.
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The measurement: every code seen, kept so the settings screen can
        // report what this particular phone actually delivers.
        seenKeyCodes.add(event.keyCode)

        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!cameraInForeground) return false

        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> ACTION_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> ACTION_DOWN
            KeyEvent.KEYCODE_POWER -> ACTION_SHUTTER
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_HEADSETHOOK -> ACTION_SHUTTER
            else -> return false
        }

        sendBroadcast(
            Intent(BROADCAST).setPackage(packageName)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_REPEAT, event.repeatCount)
        )
        // Consumed, so the volume rocker does not also move the system volume
        // while the camera is in front.
        return true
    }

    companion object {
        const val BROADCAST = "com.mantraproductions.ndi.KEY"
        const val EXTRA_ACTION = "action"
        const val EXTRA_REPEAT = "repeat"

        const val ACTION_UP = "up"
        const val ACTION_DOWN = "down"
        const val ACTION_SHUTTER = "shutter"

        @Volatile private var instance: KeyService? = null

        /** Only take the keys while the camera is actually in front. */
        @Volatile var cameraInForeground: Boolean = false

        val seenKeyCodes: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())

        val isRunning: Boolean get() = instance != null

        /** What this phone has actually delivered, for the settings screen. */
        fun seenKeyReport(): String {
            val codes = seenKeyCodes.toList().sorted()
            if (codes.isEmpty()) return "No keys seen yet. Press a few."
            return codes.joinToString("\n") { code ->
                "  ${KeyEvent.keyCodeToString(code)}" +
                        if (code == KeyEvent.KEYCODE_POWER) "   <- power does reach us" else ""
            }
        }

        fun settingsIntent(): Intent =
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.contains("${context.packageName}/${KeyService::class.java.name}")
        }
    }
}
