package com.quotto.fridgemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.quotto.fridgemanager.di.DefaultAppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = DefaultAppContainer(context = applicationContext)
        setContent {
            FridgeManagerApp(container = container)
        }
    }
}
