package com.majeur.psclient.service.observer

import com.majeur.psclient.service.ServerMessage
import com.majeur.psclient.service.ShowdownService

abstract class AbsMessageObserver<C : AbsMessageObserver.UiCallbacks>(
        val service: ShowdownService
) {

    var uiCallbacks: C? = null
        set(value) {
            field = value
            if (value != null) onUiCallbacksAttached()
        }

    protected abstract fun onUiCallbacksAttached()

    open var observedRoomId: String? = null

    open val interceptCommandBefore = emptySet<String>()

    open val interceptCommandAfter = emptySet<String>()

    // ShowdownService already dispatches all messages on the main thread via
    // uiHandler.post{} in its WebSocketListener.onMessage. A second post here
    // would break the ordering guarantee that GlobalMessageObserver's
    // interceptCommandBefore callbacks (which set observedRoomId on the chat/battle
    // observers) run before the chat/battle observers process the same message.
    fun postMessage(message: ServerMessage, forcePost: Boolean = false) {
        if (forcePost || observedRoomId == message.roomId) {
            onMessage(message)
        }
    }

    protected abstract fun onMessage(message: ServerMessage)

    interface UiCallbacks
}
