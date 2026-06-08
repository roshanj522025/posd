package com.prootdroid.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class AboutDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("ProotDroid")
            .setMessage(
                "ProotDroid v1.0\n\n" +
                "Runs Alpine Linux inside proot with a built-in VNC viewer.\n\n" +
                "• Terminal: direct shell access\n" +
                "• VNC: graphical desktop via Xvnc + Openbox\n" +
                "• Default VNC password: prootdroid\n\n" +
                "Open-source components:\n" +
                "PRoot • Alpine Linux • Openbox • TigerVNC"
            )
            .setPositiveButton("OK", null)
            .create()
    }
}
