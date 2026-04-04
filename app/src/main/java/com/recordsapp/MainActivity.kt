package com.recordsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.recordsapp.ui.navigation.RecordsNavGraph
import com.recordsapp.ui.theme.RecordsAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RecordsAppTheme {
                val navController = rememberNavController()
                RecordsNavGraph(navController = navController)
            }
        }
    }
}
