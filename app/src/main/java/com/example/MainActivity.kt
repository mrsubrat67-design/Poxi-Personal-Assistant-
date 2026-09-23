package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.poxi.ui.PoxiScreen
import com.example.poxi.ui.PoxiViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PoxiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Install safe crash diagnostics without logging sensitive information (PII/audio/keys)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e(
                "POXI_CRASH_DIAGNOSTIC",
                "FATAL CRASH on thread '${thread.name}': ${throwable.javaClass.name} - ${throwable.message}",
                throwable
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090D16)
                ) {
                    PoxiScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            viewModel.stopVoiceSession()
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(text = "Hello $name!", modifier = modifier)
}
