package com.radba.remoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.radba.remoto.ui.MainScreen
import com.radba.remoto.ui.theme.RadbaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tela cheia com barras do sistema translúcidas (visual "liquid glass").
        enableEdgeToEdge()
        setContent {
            RadbaTheme {
                val viewModel: MainViewModel = viewModel()
                MainScreen(viewModel)
            }
        }
    }
}
