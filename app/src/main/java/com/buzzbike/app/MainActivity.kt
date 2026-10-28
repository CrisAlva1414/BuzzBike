package com.buzzbike.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.buzzbike.app.ui.navigation.NavGraph
import com.buzzbike.app.ui.theme.BuzzBikeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BuzzBikeTheme {
                NavGraph()
            }
        }
    }
}
