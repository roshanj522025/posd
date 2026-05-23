package com.majeur.psclient.service.observer

import android.os.Handler
import android.os.Looper
import com.majeur.psclient.service.ServerMessage
import com.majeur.psclient.service.ShowdownService

abstract class AbsMessageObserver<C : AbsMessageObserver.UiCallbacks>(
        val service: ShowdownService
) {

    // All UI callbacks MUST run on the main thread.
    // WebSocket messages arrive on OkHttp's background thread — if we call
    // UI methods directly from that thread we get CalledFromWrongThreadException
    // or silent corruption of the Editable span list, causing the ANR freeze.
    private val uiHandler = Handler(Looper.getMainLooper())

    var uiCallbacks: C? = null
        set(value) {
            field = value
            if (value != null) uiHandler.post { onUiCallbacksAttached() }
        }

    protected abstract fun onUiCallbacksAttached()

    open var observedRoomId: String? = null

    open val interceptCommandBefore = emptySet<String>()

    open val interceptCommandAfter = emptySet<String>()

    fun postMessage(message: ServerMessage, forcePost: Boolean = false) {
        if (forcePost || observedRoomId == message.roomId) {
            // Dispatch to main thread so all observer callbacks are UI-safe
            uiHandler.post { onMessage(message) }
        }
    }

    protected abstract fun onMessage(message: ServerMessage)

    interface UiCallbacks {

    }
}