package com.wiggletonabbey.wigglebot.accessibility

import android.accessibilityservice.AccessibilityService
import android.media.session.MediaSessionManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.wiggletonabbey.wigglebot.tools.MediaControllerHolder

private const val TAG = "AgentA11y"

/**
 * Two responsibilities:
 *
 * 1. **MediaSession tracking** — Android requires an AccessibilityService
 *    (or NotificationListenerService) to call
 *    MediaSessionManager.getActiveSessions(). We grab the first active session
 *    and put it in MediaControllerHolder so ToolDispatcher can use transport
 *    controls without any per-app integration.
 *
 * 2. **UI fallback** — When an app doesn't expose an Intent or MediaSession API
 *    we can inspect the window hierarchy and simulate taps. Not used by default,
 *    but the service is registered and ready. You can add specific cases in
 *    onAccessibilityEvent if needed.
 *
 * Setup: the user must go to Settings → Accessibility → WiggleBot Control
 * and enable it. The Settings deep link button in the app's settings screen
 *  takes them there directly.
 */
class AgentAccessibilityService : AccessibilityService() {

    private var mediaSessionManager: MediaSessionManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")

        mediaSessionManager =
            getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager

        refreshMediaController()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Refresh media controller on any event — cheap and keeps the reference fresh.
        // In practice you'd filter to specific event types if this becomes a bottleneck.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            refreshMediaController()
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility service interrupted")
        MediaControllerHolder.set(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaControllerHolder.set(null)
    }

    private fun refreshMediaController() {
        try {
            val sessions = mediaSessionManager
                ?.getActiveSessions(
                    android.content.ComponentName(this, AgentAccessibilityService::class.java)
                )
                ?: return

            // Prefer the first session that is actively playing
            val best = sessions.firstOrNull { controller ->
                val state = controller.playbackState?.state
                state == android.media.session.PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull()

            MediaControllerHolder.set(best)

            if (best != null) {
                Log.d(TAG, "Active media session: ${best.packageName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh media controller", e)
        }
    }
}
