package com.mootmaker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mootmaker.app.ui.MootmakerApp
import com.mootmaker.app.ui.theme.MootmakerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MootmakerApplication).container
        container.start()
        setContent { MootmakerTheme { MootmakerApp(container) } }
    }
}
