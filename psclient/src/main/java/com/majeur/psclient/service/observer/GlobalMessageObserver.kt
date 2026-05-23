package com.majeur.psclient.service.observer

import com.majeur.psclient.model.BattleRoomInfo
import com.majeur.psclient.model.ChatRoomInfo
import com.majeur.psclient.model.common.BattleFormat
import com.majeur.psclient.service.ServerMessage
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.util.Utils
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import com.majeur.psclient.ui.DebugConsoleActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.*

class GlobalMessageObserver(service: ShowdownService)
    : AbsMessageObserver<GlobalMessageObserver.UiCallbacks>(service) {

    override var observedRoomId: String? = "lobby"
    override val interceptCommandBefore = setOf("init", "noinit")
    override val interceptCommandAfter = setOf("deinit")

    val myUsername get() = service.getSharedData<String>("myusername")?.drop(1)
    var isUserGuest: Boolean = true
        private set

    // Whether the next queryresponse|rooms should show the full room picker.
    // Written from UI thread (requestRoomList), read on WS thread — use AtomicBoolean.
    private val pendingFullRoomList = AtomicBoolean(false)

    private val privateMessages = mutableMapOf<String, MutableList<String>>()

    override fun onUiCallbacksAttached() {
        // If we did not store at least the username we will not have anything else
        val username = service.getSharedData<String>("myusername") ?: return

        onUserChanged(username, isUserGuest, /* TODO */ "000")
        onUpdateCounts(
            service.getSharedData("users") ?: -1,
            service.getSharedData("battles") ?: -1
        )
        onBattleFormatsChanged(service.getSharedData("formats") ?: emptyList())
        onSearchBattlesChanged(
            service.getSharedData("searching") ?: emptyList(),
            service.getSharedData("games") ?: emptyMap()
        )
        onChallengesChange(
            service.getSharedData("challenge_to"),
            service.getSharedData("challenge_to_format"),
            service.getSharedData("challenge_from") ?: emptyMap()
        )
    }

    public override fun onMessage(message: ServerMessage) {
        message.newArgsIteration()
        when (message.command) {
            "connected"        -> onConnectedToServer()
            "challstr"         -> processChallengeString(message)
            "updateuser"       -> processUpdateUser(message)
            "queryresponse"    -> processQueryResponse(message)
            "formats"          -> processAvailableFormats(message)
            "popup"            -> handlePopup(message)
            "updatesearch"     -> handleUpdateSearch(message)
            "pm"               -> handlePm(message)
            "updatechallenges" -> handleChallenges(message)
            "networkerror"     -> onNetworkError()
            "init"             -> onRoomInit(message.roomId, message.nextArg)
            "deinit"           -> onRoomDeinit(message.roomId)
            "noinit" -> {
                if (message.hasNextArg && "nonexistent" == message.nextArg && message.hasNextArg)
                    onShowPopup(message.nextArg)
            }
            "nametaken" -> {
                message.nextArg // Skipping name
                onShowPopup(message.nextArg)
            }
            "usercount" -> { /* unused */ }
        }
    }

    private fun processChallengeString(msg: ServerMessage) {
        service.putSharedData("challenge", msg.remainingArgsRaw)
        service.tryCookieSignIn()
    }

    private fun processUpdateUser(msg: ServerMessage) {
        var username = msg.nextArg
        service.putSharedData("myusername", username)
        username = username.substring(1)
        val isGuest = "0" == msg.nextArg
        var avatar = msg.nextArg
        avatar = "000$avatar".substring(avatar.length)
        isUserGuest = isGuest
        onUserChanged(username, isGuest, avatar)

        // Fetch server counts only (don't reset pendingFullRoomList — user may have
        // tapped Join just before login completed)
        service.sendGlobalCommand("cmd", "rooms")
        DebugConsoleActivity.logEvent("updateuser: user=$username guest=$isGuest")
    }

    private fun processQueryResponse(msg: ServerMessage) {
        val query = msg.nextArg
        val queryResponse = msg.remainingArgsRaw
        when (query) {
            "rooms"       -> processRoomsQueryResponse(queryResponse)
            "roomlist"    -> processRoomListQueryResponse(queryResponse)
            "savereplay"  -> processSaveReplayQueryResponse(queryResponse)
            "userdetails" -> processUserDetailsQueryResponse(queryResponse)
            else          -> Timber.w("Command |queryresponse| not handled, type=$query")
        }
    }

    /**
     * Called by the UI when the user taps the join-room button. Sends a rooms
     * query and marks the pending response as needing a full room list so that
     * [processRoomsQueryResponse] does not short-circuit after reading counts.
     *
     * Must be called from the UI thread; the flag is consumed on the WebSocket
     * message thread. Because WebSocket messages are delivered sequentially and
     * the send is enqueued before the response can arrive, there is no race.
     */
    fun requestRoomList() {
        pendingFullRoomList.set(true)
        DebugConsoleActivity.logEvent("requestRoomList: sending cmd rooms")
        service.sendGlobalCommand("cmd", "rooms")
    }

    private fun processRoomsQueryResponse(response: String) {
        if (response == "null") return
        try {
            val jsonObject = JSONObject(response)
            val userCount = jsonObject.getInt("userCount")
            val battleCount = jsonObject.getInt("battleCount")
            service.putSharedData("users", userCount)
            service.putSharedData("battles", battleCount)
            onUpdateCounts(userCount, battleCount)

            val showFullList = pendingFullRoomList.getAndSet(false)
            DebugConsoleActivity.logEvent("queryresponse|rooms: showFullList=$showFullList userCount=$userCount battleCount=$battleCount")
            if (!showFullList) return

            // PS API changed: no longer sends a separate "official" array.
            // All rooms are now in "chat" with a per-room "section" field.
            val allRooms = jsonObject.getJSONArray("chat")
            val officialRooms = mutableListOf<ChatRoomInfo>()
            val chatRooms = mutableListOf<ChatRoomInfo>()
            for (i in 0 until allRooms.length()) {
                val room = allRooms.getJSONObject(i)
                val info = ChatRoomInfo(
                    room.getString("title"),
                    room.optString("desc", ""),
                    room.optInt("userCount", 0))
                if (room.optString("section", "") == "Official")
                    officialRooms.add(info)
                else
                    chatRooms.add(info)
            }
            DebugConsoleActivity.logEvent("rooms parsed: official=${officialRooms.size} chat=${chatRooms.size} → calling onAvailableRoomsChanged")
            onAvailableRoomsChanged(officialRooms, chatRooms)
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse rooms query response")
            DebugConsoleActivity.logError("rooms parse FAILED: ${e.message}")
        }
    }

    private fun processRoomListQueryResponse(response: String) {
        try {
            val battleRooms = mutableListOf<BattleRoomInfo>()
            val jsonObject = JSONObject(response).getJSONObject("rooms")
            val iterator = jsonObject.keys()
            while (iterator.hasNext()) {
                val roomId = iterator.next()
                val roomJson = jsonObject.getJSONObject(roomId)
                battleRooms.add(
                    BattleRoomInfo(roomId, roomJson.getString("p1"),
                        roomJson.getString("p2"), roomJson.optInt("minElo", 0))
                )
            }
            onAvailableBattleRoomsChanged(battleRooms)
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse room list query response")
            onAvailableBattleRoomsChanged(emptyList())
        }
    }

    private fun processSaveReplayQueryResponse(response: String) {
        try {
            val replayId = JSONObject(response).optString("id")
            onReplaySaved(replayId, "https://replay.pokemonshowdown.com/$replayId")
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse save replay response")
        }
    }

    private fun processUserDetailsQueryResponse(response: String) {
        try {
            val jsonObject = JSONObject(response)
            val userId = jsonObject.optString("userid")
            if (userId.isBlank()) return
            val name = jsonObject.optString("name")
            val online = jsonObject.has("status")
            val group = jsonObject.optString("group")
            val chatRooms = mutableListOf<String>()
            val battles = mutableListOf<String>()
            (jsonObject.opt("rooms") as? JSONObject)?.keys()?.forEach {
                if (it.startsWith("battle-") || it.drop(1).startsWith("battle-"))
                    battles.add(it)
                else
                    chatRooms.add(it)
            }
            onUserDetails(userId, name, online, group, chatRooms, battles)
        } catch (e: JSONException) {
            Timber.e(e, "Failed to parse user details response")
        }
    }

    private fun processAvailableFormats(msg: ServerMessage) {
        val rawText: String = msg.remainingArgsRaw
        val categories: MutableList<BattleFormat.Category> = LinkedList() // /!\ needs to impl Serializable

        rawText.split("|,").forEach { rawCategory ->
            val catName = rawCategory.substringAfter("|").substringBefore("|")
            val formats = rawCategory.substringAfter(catName).split("|")
                    .filter { it.isNotBlank() }
                    .mapNotNull { s ->
                        try {
                            BattleFormat(s.substringBefore(","), s.substringAfter(",", "").trim().toInt(16))
                        } catch (e: NumberFormatException) {
                            Timber.w("Skipping malformed format entry: $s")
                            null
                        }
                    }
            BattleFormat.Category().apply {
                this.formats.addAll(formats)
                label = catName
            }.also { categories.add(it) }
        }
        service.putSharedData("formats", categories)
        onBattleFormatsChanged(categories)
    }

    private fun handlePopup(msg: ServerMessage) = onShowPopup(msg.args.joinToString("\n"))

    private fun handleUpdateSearch(msg: ServerMessage) {
        val jsonObject = Utils.jsonObject(msg.remainingArgsRaw) ?: return
        val searching = mutableListOf<String>()
        jsonObject.optJSONArray("searching")?.let { arr ->
            for (i in 0 until arr.length()) searching.add(arr.getString(i))
        }
        val games = mutableMapOf<String, String>()
        jsonObject.optJSONObject("games")?.let { obj ->
            obj.keys().forEach { key -> games[key] = obj.getString(key) }
        }
        service.putSharedData("searching", searching)
        service.putSharedData("games", games)
        onSearchBattlesChanged(searching, games)
    }

    private fun handlePm(msg: ServerMessage) {
        val from = msg.nextArg.substring(1)
        val to = msg.nextArg.substring(1)
        val myUsername = service.getSharedData<String>("myusername")?.drop(1)
        val with = if (myUsername == from) to else from
        var content = msg.nextArgSafe
        if (content != null && (content.startsWith("/raw") || content.startsWith("/html") || content.startsWith("/uhtml")))
            content = "Html messages not supported in pm."
        val message = "$from: $content"
        privateMessages.getOrPut(with) { mutableListOf() }.add(message)
        onNewPrivateMessage(with, message)
    }

    private fun handleChallenges(message: ServerMessage) {
        val jsonObject = Utils.jsonObject(message.remainingArgsRaw)
        var to: String? = null
        var format: String? = null
        val from = mutableMapOf<String, String>()
        jsonObject.optJSONObject("challengeTo")?.let {
            to = it.getString("to")
            format = it.getString("format")
        }
        jsonObject.optJSONObject("challengesFrom")?.let { obj ->
            obj.keys().forEach { key -> from[key] = obj.getString(key) }
        }
        service.putSharedData("challenge_to", to)
        service.putSharedData("challenge_to_format", format)
        service.putSharedData("challenge_from", from)
        onChallengesChange(to, format, from)
    }

    fun getPrivateMessages(with: String): List<String>? = privateMessages[with]

    fun onConnectedToServer() = uiCallbacks?.onConnectedToServer()
    fun onUserChanged(userName: String, isGuest: Boolean, avatarId: String) = uiCallbacks?.onUserChanged(userName, isGuest, avatarId)
    fun onUpdateCounts(userCount: Int, battleCount: Int) = uiCallbacks?.onUpdateCounts(userCount, battleCount)
    fun onBattleFormatsChanged(battleFormats: List<BattleFormat.Category>) = uiCallbacks?.onBattleFormatsChanged(battleFormats)
    fun onSearchBattlesChanged(searching: List<String>, games: Map<String, String>) = uiCallbacks?.onSearchBattlesChanged(searching, games)
    fun onReplaySaved(replayId: String, url: String) = uiCallbacks?.onReplaySaved(replayId, url)
    fun onUserDetails(id: String, name: String, online: Boolean, group: String, rooms: List<String>, battles: List<String>) = uiCallbacks?.onUserDetails(id, name, online, group, rooms, battles)
    fun onShowPopup(message: String) = uiCallbacks?.onShowPopup(message)
    fun onAvailableRoomsChanged(officialRooms: List<ChatRoomInfo>, chatRooms: List<ChatRoomInfo>) {
        DebugConsoleActivity.logEvent("onAvailableRoomsChanged: uiCallbacks=${uiCallbacks != null} official=${officialRooms.size} chat=${chatRooms.size}")
        uiCallbacks?.onAvailableRoomsChanged(officialRooms, chatRooms)
    }
    fun onAvailableBattleRoomsChanged(battleRooms: List<BattleRoomInfo>) = uiCallbacks?.onAvailableBattleRoomsChanged(battleRooms)
    fun onNewPrivateMessage(with: String, message: String) = uiCallbacks?.onNewPrivateMessage(with, message)
    fun onChallengesChange(to: String?, format: String?, from: Map<String, String>) = uiCallbacks?.onChallengesChange(to, format, from)
    fun onRoomInit(roomId: String, type: String) = uiCallbacks?.onRoomInit(roomId, type)
    fun onRoomDeinit(roomId: String) = uiCallbacks?.onRoomDeinit(roomId)
    fun onNetworkError() = uiCallbacks?.onNetworkError()

    interface UiCallbacks : AbsMessageObserver.UiCallbacks {
        fun onConnectedToServer()
        fun onUserChanged(userName: String, isGuest: Boolean, avatarId: String)
        fun onUpdateCounts(userCount: Int, battleCount: Int)
        fun onBattleFormatsChanged(battleFormats: List<@JvmSuppressWildcards BattleFormat.Category>)
        fun onSearchBattlesChanged(searching: List<String>, games: Map<String, String>)
        fun onReplaySaved(replayId: String, url: String)
        fun onUserDetails(id: String, name: String, online: Boolean, group: String, rooms: List<String>, battles: List<String>)
        fun onShowPopup(message: String)
        fun onAvailableRoomsChanged(officialRooms: List<ChatRoomInfo>, chatRooms: List<ChatRoomInfo>)
        fun onAvailableBattleRoomsChanged(battleRooms: List<BattleRoomInfo>)
        fun onNewPrivateMessage(with: String, message: String)
        fun onChallengesChange(to: String?, format: String?, from: Map<String, String>)
        fun onRoomInit(roomId: String, type: String)
        fun onRoomDeinit(roomId: String)
        fun onNetworkError()
    }
}
