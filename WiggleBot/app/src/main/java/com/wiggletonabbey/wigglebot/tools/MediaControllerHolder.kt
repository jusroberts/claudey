package com.wiggletonabbey.wigglebot.tools

import android.media.session.MediaController
import android.media.session.PlaybackState

/**
 * Singleton that the AgentAccessibilityService updates whenever it finds
 * an active MediaSession. ToolDispatcher reads from here.
 *
 * The pattern: AccessibilityService has BIND_ACCESSIBILITY_SERVICE permission
 * and can see all active media sessions. It hands the most recent active one
 * here. ToolDispatcher uses it for transport controls (play/pause/skip).
 *
 * If no controller is set (service not enabled yet), ToolDispatcher falls
 * back to broadcasting AudioManager key events, which works for most players.
 */
object MediaControllerHolder {

    @Volatile
    private var controller: MediaController? = null

    fun set(c: MediaController?) {
        controller = c
    }

    fun get(): MediaController? = controller

    /** Convenience: returns TransportControls if there's an active playing session. */
    fun transport(): MediaController.TransportControls? {
        val c = controller ?: return null
        val state = c.playbackState?.state
        // Only return transport if session is in a controllable state
        return if (state != null && state != PlaybackState.STATE_NONE) {
            c.transportControls
        } else null
    }
}
