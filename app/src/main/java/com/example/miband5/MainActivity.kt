package com.example.miband5

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.miband5.data.GadgetbridgeWatchSettings
import com.example.miband5.sync.GadgetbridgeWatcherWorker
import com.example.miband5.ui.dashboard.DashboardScreen
import com.example.miband5.ui.theme.MiBand5Theme

class MainActivity : ComponentActivity() {

    // Storage Access Framework folder picker. Android requires this explicit,
    // one-time user grant before any app can read a folder in the
    // background on API 29+ -- there's no way around it that doesn't
    // involve a third-party automation app instead, which is the whole
    // point of doing it this way.
    private val pickWatchFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            GadgetbridgeWatchSettings(this).watchFolderUri = uri.toString()
            GadgetbridgeWatcherWorker.runOnce(this) // pick up whatever's already there immediately
        }
    }

    fun launchGadgetbridgeFolderPicker() {
        pickWatchFolder.launch(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GadgetbridgeWatcherWorker.schedule(this)
        setContent {
            MiBand5Theme {
                DashboardScreen()
            }
        }
    }
}
