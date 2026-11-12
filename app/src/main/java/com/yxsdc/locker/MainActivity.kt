package com.yxsdc.locker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yxsdc.locker.ui.LockerRoot
import com.yxsdc.locker.ui.LockerViewModel
import com.yxsdc.locker.ui.theme.LockerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LockerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            LockerTheme(themeMode) {
                LockerRoot(viewModel)
            }
        }
    }
}
