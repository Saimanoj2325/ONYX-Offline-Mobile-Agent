package com.offlineagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineagent.app.ui.ChatScreen
import com.offlineagent.app.ui.MainViewModel
import com.offlineagent.app.ui.ModelScreen
import com.offlineagent.core.inference.EngineState

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot(vm) } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    // Start on Chat when a model is ready, otherwise on Model.
    var tab by rememberSaveable { mutableIntStateOf(if (ui.storedModel == null) 0 else 1) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            snackbar.showSnackbar(it)
            vm.notice(null)
        }
    }
    LaunchedEffect(engine) {
        if (engine is EngineState.Ready && tab == 0 && ui.messages.isEmpty()) tab = 1
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Model") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Email, contentDescription = null) }, label = { Text("Chat") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> ModelScreen(ui, engine, vm, Modifier.padding(padding))
            else -> ChatScreen(ui, engine, vm, onOpenModel = { tab = 0 }, modifier = Modifier.padding(padding))
        }
    }
}
