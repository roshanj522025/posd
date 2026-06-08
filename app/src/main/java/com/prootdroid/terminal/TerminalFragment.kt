package com.prootdroid.terminal

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.prootdroid.databinding.FragmentTerminalBinding
import com.prootdroid.proot.ProotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Terminal emulator fragment.
 *
 * For a production build you would use a proper terminal emulator library
 * (e.g. the termux TerminalView). This implementation provides a functional
 * scrollable TextView + EditText pair that bridges stdin/stdout of the
 * proot shell process, demonstrating the correct wiring.
 *
 * Replace [TerminalView] with the Termux TerminalView for full VT100 support.
 */
class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private var shellProcess: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var prootService: ProotService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProotService.ProotBinder
            prootService = binder.getService()
            serviceBound = true
            startShell()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            prootService = null
            serviceBound = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTerminalUI()
        bindService()
    }

    private fun setupTerminalUI() {
        // Dark terminal aesthetic
        binding.terminalOutput.apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            setTextColor(Color.parseColor("#C9D1D9"))
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }

        binding.terminalInput.apply {
            setBackgroundColor(Color.parseColor("#161B22"))
            setTextColor(Color.parseColor("#58A6FF"))
            typeface = Typeface.MONOSPACE
            hint = "$ enter command…"
            setHintTextColor(Color.parseColor("#484F58"))
        }

        binding.btnSend.setOnClickListener {
            val cmd = binding.terminalInput.text.toString()
            if (cmd.isNotEmpty()) {
                sendCommand(cmd)
                appendOutput("\$ $cmd\n")
                binding.terminalInput.setText("")
            }
        }

        // Quick-action buttons
        binding.btnTab.setOnClickListener    { sendRaw("\t") }
        binding.btnCtrlC.setOnClickListener  { sendRaw("\u0003") }
        binding.btnCtrlD.setOnClickListener  { sendRaw("\u0004") }
        binding.btnUp.setOnClickListener     { sendRaw("\u001b[A") }
        binding.btnDown.setOnClickListener   { sendRaw("\u001b[B") }
    }

    private fun bindService() {
        val intent = Intent(requireContext(), ProotService::class.java)
        requireContext().bindService(intent, serviceConnection, 0)
    }

    private fun startShell() {
        val service = prootService ?: return
        shellProcess = service.prootSession.openShell()
        writer = OutputStreamWriter(shellProcess!!.outputStream)

        // Read stdout asynchronously
        readerJob = scope.launch(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(shellProcess!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line!! + "\n"
                withContext(Dispatchers.Main) {
                    appendOutput(text)
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        scope.launch(Dispatchers.IO) {
            try {
                writer?.write(cmd + "\n")
                writer?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendOutput("[error sending command: ${e.message}]\n")
                }
            }
        }
    }

    private fun sendRaw(seq: String) {
        scope.launch(Dispatchers.IO) {
            writer?.write(seq)
            writer?.flush()
        }
    }

    private fun appendOutput(text: String) {
        binding.terminalOutput.append(text)
        // Auto-scroll to bottom
        val scrollView = binding.terminalScroll
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        readerJob?.cancel()
        if (serviceBound) {
            requireContext().unbindService(serviceConnection)
            serviceBound = false
        }
        _binding = null
    }
}
