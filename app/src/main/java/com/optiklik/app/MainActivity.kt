package com.optiklik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.optiklik.app.ui.navigation.OptiklikNavGraph
import com.optiklik.app.ui.theme.OptiklikTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OptiklikTheme {
                OptiklikNavGraph()
            }
        }
    }
}
