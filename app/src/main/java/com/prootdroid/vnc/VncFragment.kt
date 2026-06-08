package com.prootdroid.vnc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.prootdroid.databinding.FragmentVncBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * VNC (RFB protocol) fragment that connects to the Xvnc server
 * started inside proot on localhost:5901.
 *
 * Implements a minimal RFB 3.8 client:
 *   1. Version handshake
 *   2. Security negotiation (VNC Auth)
 *   3. ClientInit / ServerInit
 *   4. FramebufferUpdateRequest loop
 *   5. Renders the framebuffer onto a SurfaceView
 *
 * For production use, integrate a full RFB library such as
 * LibVNCAndroid or bVNC for proper encoding support (Tight, ZRLE, etc).
 */
class VncFragment : Fragment() {

    companion object {
        private const val TAG         = "VncFragment"
        private const val VNC_HOST    = "127.0.0.1"
        private const val VNC_PORT    = 5901
        private const val VNC_PASSWORD = "prootdroid"
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private var _binding: FragmentVncBinding? = null
    private val binding get() = _binding!!

    private var vncJob: Job? = null
    private var socket: Socket? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnConnect.setOnClickListener { connectToVnc() }
        binding.btnDisconnect.setOnClickListener { disconnectVnc() }
        binding.tvVncStatus.text = "Tap Connect to open the graphical desktop"
    }

    private fun connectToVnc() {
        binding.btnConnect.isEnabled    = false
        binding.btnDisconnect.isEnabled = true
        binding.tvVncStatus.text        = "Connecting to VNC…"
        binding.progressVnc.visibility  = View.VISIBLE

        vncJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    runVncClient()
                } catch (e: Exception) {
                    Log.w(TAG, "VNC error: ${e.message}; retrying in ${RECONNECT_DELAY_MS}ms")
                    withContext(Dispatchers.Main) {
                        binding.tvVncStatus.text = "Reconnecting… (${e.message})"
                    }
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    private fun disconnectVnc() {
        vncJob?.cancel()
        socket?.close()
        socket = null
        binding.btnConnect.isEnabled    = true
        binding.btnDisconnect.isEnabled = false
        binding.tvVncStatus.text        = "Disconnected"
        binding.progressVnc.visibility  = View.GONE
    }

    /**
     * Minimal RFB 3.8 handshake + framebuffer update loop.
     * Replace this with a full RFB library for production use.
     */
    private suspend fun runVncClient() {
        socket = Socket(VNC_HOST, VNC_PORT)
        val din  = DataInputStream(socket!!.getInputStream())
        val dout = DataOutputStream(socket!!.getOutputStream())

        // ── Step 1: Version handshake ───────────────────────────────────────
        val serverVersion = ByteArray(12)
        din.readFully(serverVersion)
        val serverVerStr = String(serverVersion)
        Log.d(TAG, "Server version: $serverVerStr")

        // Send RFB 3.8
        dout.write("RFB 003.008\n".toByteArray())
        dout.flush()

        // ── Step 2: Security types ──────────────────────────────────────────
        val numSecTypes = din.readUnsignedByte()
        val secTypes = ByteArray(numSecTypes)
        din.readFully(secTypes)
        Log.d(TAG, "Security types: ${secTypes.toList()}")

        // Choose VNC Auth (2) if available, else None (1)
        val useVncAuth = secTypes.contains(2)
        dout.writeByte(if (useVncAuth) 2 else 1)
        dout.flush()

        if (useVncAuth) {
            // Receive 16-byte DES challenge
            val challenge = ByteArray(16)
            din.readFully(challenge)

            // Encrypt with DES using the VNC password
            val response = vncDes(VNC_PASSWORD, challenge)
            dout.write(response)
            dout.flush()
        }

        // Security result
        val secResult = din.readInt()
        if (secResult != 0) {
            val reasonLen = din.readInt()
            val reason = ByteArray(reasonLen)
            din.readFully(reason)
            throw Exception("VNC auth failed: ${String(reason)}")
        }

        withContext(Dispatchers.Main) {
            binding.tvVncStatus.text = "Authenticated — loading desktop…"
        }

        // ── Step 3: ClientInit (shared=1) ───────────────────────────────────
        dout.writeByte(1)
        dout.flush()

        // ── Step 4: ServerInit ──────────────────────────────────────────────
        val fbWidth  = din.readUnsignedShort()
        val fbHeight = din.readUnsignedShort()

        // Skip pixel format (16 bytes)
        din.skipBytes(16)

        // Server name
        val nameLen  = din.readInt()
        val nameBytes = ByteArray(nameLen)
        din.readFully(nameBytes)
        val serverName = String(nameBytes)

        Log.i(TAG, "Connected to '$serverName' ${fbWidth}x${fbHeight}")

        withContext(Dispatchers.Main) {
            binding.tvVncStatus.text    = "$serverName · ${fbWidth}×${fbHeight}"
            binding.progressVnc.visibility = View.GONE
            binding.vncSurface.visibility  = View.VISIBLE
        }

        // ── Step 5: FramebufferUpdate request loop ──────────────────────────
        // Set encodings: Raw(0)
        dout.writeByte(2)       // SetEncodings
        dout.writeByte(0)       // padding
        dout.writeShort(1)      // number of encodings
        dout.writeInt(0)        // Raw
        dout.flush()

        while (true) {
            // Request full framebuffer update
            dout.writeByte(3)   // FramebufferUpdateRequest
            dout.writeByte(0)   // incremental=0 (full)
            dout.writeShort(0)  // x
            dout.writeShort(0)  // y
            dout.writeShort(fbWidth)
            dout.writeShort(fbHeight)
            dout.flush()

            // Read FramebufferUpdate message
            val msgType = din.readUnsignedByte()
            if (msgType != 0) {
                din.skipBytes(1) // unknown message
                continue
            }
            din.skipBytes(1) // padding
            val numRects = din.readUnsignedShort()

            val bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(fbWidth * fbHeight)

            for (r in 0 until numRects) {
                val x      = din.readUnsignedShort()
                val y      = din.readUnsignedShort()
                val w      = din.readUnsignedShort()
                val h      = din.readUnsignedShort()
                val enc    = din.readInt()

                when (enc) {
                    0 -> { // Raw encoding: w*h * 4 bytes (BGRA)
                        val rawBytes = ByteArray(w * h * 4)
                        din.readFully(rawBytes)
                        for (py in 0 until h) {
                            for (px in 0 until w) {
                                val i   = (py * w + px) * 4
                                val red   = rawBytes[i + 2].toInt() and 0xFF
                                val green = rawBytes[i + 1].toInt() and 0xFF
                                val blue  = rawBytes[i].toInt()    and 0xFF
                                pixels[(y + py) * fbWidth + (x + px)] =
                                    (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unsupported encoding $enc — skipping rect")
                    }
                }
            }

            bitmap.setPixels(pixels, 0, fbWidth, 0, 0, fbWidth, fbHeight)
            withContext(Dispatchers.Main) {
                binding.vncSurface.setImageBitmap(bitmap)
            }

            delay(50) // ~20fps cap
        }
    }

    /**
     * VNC DES: Encrypt challenge using VNC-flavoured DES
     * (key bits reversed per byte, as per the RFB spec).
     */
    private fun vncDes(password: String, challenge: ByteArray): ByteArray {
        val key = ByteArray(8)
        val pwBytes = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 8) {
            val b = if (i < pwBytes.size) pwBytes[i].toInt() else 0
            // Reverse bit order (VNC DES quirk)
            var rev = 0
            for (bit in 0..7) {
                if (b and (1 shl bit) != 0) rev = rev or (0x80 ushr bit)
            }
            key[i] = rev.toByte()
        }

        // Use javax.crypto DES/ECB/NoPadding
        val keySpec  = javax.crypto.spec.SecretKeySpec(key, "DES")
        val cipher   = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
        return cipher.doFinal(challenge)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disconnectVnc()
        _binding = null
    }
}
