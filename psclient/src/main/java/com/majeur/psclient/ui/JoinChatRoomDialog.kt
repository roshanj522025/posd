package com.majeur.psclient.ui

import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListAdapter
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.majeur.psclient.databinding.DialogJoinRoomBinding
import com.majeur.psclient.databinding.ListFooterOtherRoomBinding
import com.majeur.psclient.databinding.ListItemRoomBinding
import com.majeur.psclient.model.ChatRoomInfo
import com.majeur.psclient.util.*
import java.util.*

class JoinChatRoomDialog : BottomSheetDialogFragment() {

    private lateinit var officialRooms: List<ChatRoomInfo>
    private lateinit var chatRooms: List<ChatRoomInfo>

    private var _binding: DialogJoinRoomBinding? = null
    private val binding get() = _binding!!

    // Follow the same pattern as SearchBattleDialog: access service via the
    // parent fragment rather than casting the activity directly
    private val chatFragment get() = parentFragment as ChatFragment
    private val service get() = chatFragment.mainActivity.service

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        officialRooms = requireArguments().getParcelableArrayList<ChatRoomInfo>(ARG_OFFICIAL_ROOMS)!!
        chatRooms = requireArguments().getParcelableArrayList<ChatRoomInfo>(ARG_CHAT_ROOMS)!!
                .sortedByDescending { it.userCount }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = DialogJoinRoomBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.list.apply {
            adapter = listAdapter
            setOnTouchListener(NestedScrollLikeTouchListener())
            onItemClickListener = AdapterView.OnItemClickListener { _, _, index, _ ->
                val roomInfo = listAdapter.getItem(index) as? ChatRoomInfo ?: return@OnItemClickListener
                joinRoom(roomInfo.name)
            }
        }

        val footerBinding = ListFooterOtherRoomBinding.inflate(layoutInflater, binding.list, false)
        footerBinding.button.isEnabled = false
        footerBinding.button.setOnClickListener {
            val input = footerBinding.roomNameInput.text.toString().trim()
            if (input.startsWith("battle-", ignoreCase = true)) {
                Toast.makeText(context, "You cannot join a battle from here", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            joinRoom(input)
        }
        footerBinding.roomNameInput.addTextChangedListener(object : SimpleTextWatcher() {
            override fun afterTextChanged(editable: Editable) {
                footerBinding.button.isEnabled = editable.isNotEmpty()
            }
        })
        binding.list.addFooterView(footerBinding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun joinRoom(roomName: String) {
        val roomId = roomName.lowercase(Locale.ROOT).replace("[^a-z0-9-]".toRegex(), "")
        if (roomId.isEmpty()) return
        service?.sendGlobalCommand("join", roomId)
        dismiss()
    }

    private val listAdapter: ListAdapter = object : BaseAdapter() {

        private val VIEW_TYPE_HEADER  = 0
        private val VIEW_TYPE_REGULAR = 1

        override fun getCount() = 2 + officialRooms.size + chatRooms.size
        override fun isEnabled(position: Int) = getItemViewType(position) == VIEW_TYPE_REGULAR
        override fun areAllItemsEnabled() = false
        override fun getViewTypeCount() = 2

        override fun getItemViewType(position: Int) =
                if (position == 0 || position == officialRooms.size + 1) VIEW_TYPE_HEADER else VIEW_TYPE_REGULAR

        override fun getView(i: Int, view: View?, viewGroup: ViewGroup): View {
            val itemBinding: ListItemRoomBinding
            val convertView: View
            if (view == null) {
                itemBinding = ListItemRoomBinding.inflate(layoutInflater, viewGroup, false)
                convertView = itemBinding.root
                convertView.tag = itemBinding
            } else {
                convertView = view
                itemBinding = view.tag as ListItemRoomBinding
            }
            itemBinding.apply {
                when (val item = getItem(i)) {
                    is String -> {
                        title.text = item.relSize(1.35f)
                        description.visibility = View.GONE
                        chatImage.visibility = View.GONE
                    }
                    is ChatRoomInfo -> {
                        title.text = item.name.bold()
                        title.append(" (${item.userCount})".small().italic())
                        description.text = item.description
                        description.visibility = View.VISIBLE
                        chatImage.visibility = View.VISIBLE
                    }
                }
            }
            return convertView
        }

        override fun getItem(i: Int): Any {
            val off = officialRooms.size
            return when {
                i == 0       -> "Official Rooms"
                i < off + 1  -> officialRooms[i - 1]
                i == off + 1 -> "Chat Rooms"
                else         -> chatRooms[i - off - 2]
            }
        }

        override fun getItemId(i: Int) = 0L
    }

    companion object {
        const val FRAGMENT_TAG = "join-chat-room-dialog"

        private const val ARG_OFFICIAL_ROOMS = "official-rooms"
        private const val ARG_CHAT_ROOMS     = "chat-rooms"

        fun newInstance(officialRooms: List<ChatRoomInfo>, chatRooms: List<ChatRoomInfo>) =
                JoinChatRoomDialog().apply {
                    arguments = Bundle().apply {
                        putParcelableArrayList(ARG_OFFICIAL_ROOMS, officialRooms as? ArrayList ?: ArrayList(officialRooms))
                        putParcelableArrayList(ARG_CHAT_ROOMS,     chatRooms     as? ArrayList ?: ArrayList(chatRooms))
                    }
                }
    }
}
