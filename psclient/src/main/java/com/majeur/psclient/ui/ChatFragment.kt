package com.majeur.psclient.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.majeur.psclient.R
import com.majeur.psclient.databinding.FragmentChatBinding
import com.majeur.psclient.io.AssetLoader
import com.majeur.psclient.io.GlideHelper
import com.majeur.psclient.model.ChatRoomInfo
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.service.observer.ChatRoomMessageObserver
import com.majeur.psclient.util.Callback
import com.majeur.psclient.util.Utils
import com.majeur.psclient.util.html.Html
import com.majeur.psclient.ui.DebugConsoleActivity
import com.majeur.psclient.util.toId


class ChatFragment : BaseFragment(), ChatRoomMessageObserver.UiCallbacks {

    // Null-safe: service may not be bound yet when the setter is called
    private val observer get() = service?.chatMessageObserver

    private lateinit var inputMethodManager: InputMethodManager
    private lateinit var glideHelper: GlideHelper
    private lateinit var assetLoader: AssetLoader

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    // Backing field kept separately so we can restore it into the observer
    // after a service reconnect, without triggering the setter side-effects
    // while service is still null
    private var _observedRoomId: String? = null
    var observedRoomId: String?
        get() = _observedRoomId
        set(value) {
            _observedRoomId = value
            observer?.observedRoomId = value
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        glideHelper = mainActivity.glideHelper
        assetLoader = mainActivity.assetLoader
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        service?.chatMessageObserver?.uiCallbacks = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.chatLog.apply {
            movementMethod = LinkMovementMethod()
            animate().duration = 200
        }
        binding.joinButton.setOnClickListener {
            when {
                service == null || service?.isConnected != true ->
                    Toast.makeText(requireContext(), "Not connected to Showdown", Toast.LENGTH_SHORT).show()
                observer?.roomJoined == true ->
                    service?.sendRoomCommand(observedRoomId, "leave")
                else -> {
                    DebugConsoleActivity.logEvent("Join button tapped — requesting room list")
                    service?.globalMessageObserver?.requestRoomList()
                }
            }
        }
        binding.usersCount.setOnClickListener {
            val users = observer?.users ?: emptyList()
            val adapter = ArrayAdapter(requireActivity(), android.R.layout.simple_list_item_1, users)
            AlertDialog.Builder(requireActivity())
                    .setTitle("Users")
                    .setAdapter(adapter) { dialog: DialogInterface, pos: Int ->
                        service?.sendGlobalCommand("cmd userdetails", adapter.getItem(pos)!!.toId())
                        dialog.dismiss()
                    }
                    .setNegativeButton("Close", null)
                    .show()
        }
        binding.sendButton.setOnClickListener { sendMessageIfAny() }
        binding.messageInput.setOnEditorActionListener { _: TextView?, actionId: Int, _: KeyEvent? ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessageIfAny()
                return@setOnEditorActionListener true
            }
            false
        }
        setUiState(roomJoined = false)
    }

    private fun setUiState(roomJoined: Boolean) {
        if (roomJoined) {
            binding.apply {
                messageInput.isEnabled = true
                messageInput.requestFocus()
                sendButton.isEnabled = true
                sendButton.drawable.alpha = 255
                joinButton.setImageResource(R.drawable.ic_exit)
                chatLog.gravity = Gravity.START
                chatLog.setText("", TextView.BufferType.EDITABLE)
            }
        } else {
            binding.apply {
                roomTitle.text = "—"
                usersCount.text = "-\nusers"
                messageInput.text.clear()
                messageInput.clearFocus()
                messageInput.isEnabled = false
                sendButton.isEnabled = false
                sendButton.drawable.alpha = 128
                joinButton.setImageResource(R.drawable.ic_enter)
                joinButton.requestFocus()
                // Cancel any in-progress animation before modifying text
                chatLog.animate().cancel()
                chatLog.alpha = 1f
                chatLog.text = "\n\n\n\n\n\n\n\n\n\nTap the join button to join a room"
                chatLog.gravity = Gravity.CENTER_HORIZONTAL
            }
            inputMethodManager.hideSoftInputFromWindow(binding.messageInput.windowToken, 0)
        }
    }

    private fun sendMessageIfAny() {
        val message = binding.messageInput.text.toString()
        if (message.isEmpty()) return
        service?.sendRoomMessage(observedRoomId, message)
        binding.messageInput.text.clear()
    }

    override fun onServiceBound(service: ShowdownService) {
        super.onServiceBound(service)
        service.chatMessageObserver.uiCallbacks = this
        // Restore the observed room into the freshly bound observer so that
        // messages keep routing correctly after a reconnect or config change
        service.chatMessageObserver.observedRoomId = _observedRoomId
    }

    override fun onServiceWillUnbound(service: ShowdownService) {
        super.onServiceWillUnbound(service)
        service.chatMessageObserver.uiCallbacks = null
    }

    fun onAvailableRoomsChanged(officialRooms: List<ChatRoomInfo>, chatRooms: List<ChatRoomInfo>) {
        DebugConsoleActivity.logEvent("ChatFragment.onAvailableRoomsChanged: isAdded=$isAdded official=${officialRooms.size} chat=${chatRooms.size}")
        if (!isAdded) {
            DebugConsoleActivity.logError("ChatFragment not added — dialog NOT shown")
            return
        }
        val existing = parentFragmentManager.findFragmentByTag(JoinChatRoomDialog.FRAGMENT_TAG)
        if (existing != null) {
            DebugConsoleActivity.logInfo("JoinChatRoomDialog already showing — skipping")
            return
        }
        DebugConsoleActivity.logEvent("Showing JoinChatRoomDialog")
        JoinChatRoomDialog.newInstance(officialRooms, chatRooms)
                .show(parentFragmentManager, JoinChatRoomDialog.FRAGMENT_TAG)
    }

    private fun notifyNewMessageReceived() {
        mainActivity.showBadge(id)
    }

    private fun postFullScroll() {
        binding.chatLogContainer.post { binding.chatLogContainer.fullScroll(View.FOCUS_DOWN) }
    }

    // ── ChatRoomMessageObserver.UiCallbacks ──────────────────────────────

    override fun onRoomInit() {
        setUiState(roomJoined = true)
    }

    override fun onRoomDeInit() {
        _observedRoomId = null
        observer?.observedRoomId = null
        setUiState(roomJoined = false)
    }

    override fun onPrintText(text: CharSequence) {
        val b = _binding ?: return  // fragment view may be destroyed
        val fullScrolled = Utils.fullScrolled(b.chatLogContainer)
        if (b.chatLog.length() > 0) b.chatLog.append("\n")
        b.chatLog.append(text)
        notifyNewMessageReceived()
        if (fullScrolled) postFullScroll()
    }

    override fun onPrintHtml(html: String) {
        val b = _binding ?: return  // fragment view destroyed
        val mark = Any()
        val l = b.chatLog.length()
        b.chatLog.append("\u200C")
        b.chatLog.editableText.setSpan(mark, l, l + 1, Spanned.SPAN_MARK_MARK)
        val imgWidth = if (b.chatLog.width > 0) b.chatLog.width
                       else resources.displayMetrics.widthPixels
        Html.fromHtml(
                html,
                Html.FROM_HTML_MODE_COMPACT,
                glideHelper.getHtmlImageGetter(assetLoader, imgWidth),
                Callback { spanned: Spanned? ->
                    val b2 = _binding ?: return@Callback  // view destroyed while image was loading
                    val at = b2.chatLog.editableText.getSpanStart(mark)
                    if (at == -1) return@Callback  // text was cleared while loading
                    val fullScrolled = Utils.fullScrolled(b2.chatLogContainer)
                    b2.chatLog.editableText
                            .insert(at, "\n")
                            .insert(at + 1, spanned)
                    notifyNewMessageReceived()
                    if (fullScrolled) postFullScroll()
                })
    }

    override fun onRoomTitleChanged(title: String) {
        binding.roomTitle.text = title
    }

    override fun onUpdateUsers(users: List<String>) {
        binding.usersCount.text = "${users.size} users"
    }
}
