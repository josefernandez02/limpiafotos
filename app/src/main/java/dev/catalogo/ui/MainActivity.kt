package dev.catalogo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.catalogo.core.Role
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val deleteConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.consentHandled(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.consent.collect { sender ->
                    if (sender != null) deleteConsent.launch(IntentSenderRequest.Builder(sender).build())
                }
            }
        }
        setContent { CatalogoTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stack by vm.stack.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.clearMessage()
    }
    val sessionOpen = state.parent?.session != null && stack.last() != Screen.Review
    val onSecondaryTab = stack.size == 1 && (stack.last() == Screen.Lots || stack.last() == Screen.Connect)
    BackHandler(enabled = (stack.size > 1 || onSecondaryTab) && !sessionOpen) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        val parent = state.parent
        val top = stack.last()
        val child = state.child
        when {
            state.role == null -> SetupScreen(vm)
            parent != null && top == Screen.Review && parent.session != null -> ReviewScreen(vm, parent)
            parent != null && parent.session != null -> SessionScreen(vm, parent)
            top == Screen.Connect -> ConnectScreen(vm, state)
            top == Screen.Settings -> SettingsScreen(vm, state)
            parent != null && top is Screen.Template -> TemplateScreen(vm, parent, top.deviceId)
            parent != null && top is Screen.Editor -> EditorScreen(vm, parent, top.number)
            parent != null && top is Screen.Article -> ArticleScreen(vm, parent, top.number)
            parent != null && top == Screen.Lots -> LotsScreen(vm, parent)
            parent != null -> ParentHome(vm, parent)
            child != null && top is Screen.Album -> AlbumScreen(vm, child, top.number)
            state.role == Role.CHILD -> ChildHome(vm, state)
        }
        val viewer by vm.viewer.collectAsStateWithLifecycle()
        viewer?.let { v -> ViewerScreen(v.photos, v.start, onClose = { vm.closeViewer() }) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
