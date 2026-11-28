package com.wearos.ancsbridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.wearos.ancsbridge.ui.theme.AncsBridgeTheme
import com.wearos.ancsbridge.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the workout notification / ongoing-activity chip. */
        const val EXTRA_OPEN_WORKOUT = "open_workout"
        /** Deep link from tiles and complications. */
        const val EXTRA_OPEN = "open"
        const val OPEN_ACTIVITY = "activity"
        const val OPEN_WORKOUT_PREFIX = "workout:"
        const val OPEN_LIVE_WORKOUT = "live_workout"
    }

    private val viewModel: MainViewModel by viewModels()
    private val deepLink = mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        deepLink.value = linkFrom(intent)
        setContent {
            AncsBridgeTheme {
                MainScreen(viewModel = viewModel, deepLink = deepLink.value) { deepLink.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkFrom(intent)?.let { deepLink.value = it }
    }

    private fun linkFrom(intent: Intent?): String? = when {
        intent == null -> null
        intent.getBooleanExtra(EXTRA_OPEN_WORKOUT, false) -> OPEN_LIVE_WORKOUT
        else -> intent.getStringExtra(EXTRA_OPEN)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            viewModel.startService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
