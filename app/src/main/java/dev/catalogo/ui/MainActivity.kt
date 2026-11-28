package dev.catalogo.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import dev.catalogo.ui.articles.ArticleListScreen
import dev.catalogo.ui.articles.ArticleScreen
import dev.catalogo.ui.articles.EditorScreen
import dev.catalogo.ui.articles.LotsScreen
import dev.catalogo.ui.common.CatalogoTheme
import dev.catalogo.ui.common.ViewerScreen
import dev.catalogo.ui.pc.StatusScreen
import dev.catalogo.ui.photos.CameraSessionScreen
import dev.catalogo.ui.photos.PhotosScreen
import dev.catalogo.ui.photos.SessionPhotosScreen
import dev.catalogo.ui.sales.SaleScreen
import dev.catalogo.ui.sales.SalesScreen
import dev.catalogo.ui.sales.SalesStatsScreen
import dev.catalogo.ui.settings.SettingsScreen
import dev.catalogo.ui.settings.SetupScreen
import dev.catalogo.ui.settings.TemplateScreen
import kotlinx.coroutines.delay
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
        // Sales come in by themselves while the app is on screen (asking to delete the
        // photos of a finished one needs it): at once on opening, then now and again.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.salesOpened()
                var last: Long? = null
                var lastLabels: Long? = null
                while (true) {
                    val every = vm.salesEvery()
                    val now = SystemClock.elapsedRealtime()
                    if (every != null && (last == null || now - last >= every)) {
                        vm.refreshSales()
                        last = SystemClock.elapsedRealtime()
                    }
                    // Labels asked for: the PDF is brought as soon as the PC has it.
                    if (every != null && (lastLabels == null || now - lastLabels >= 4_000)) {
                        vm.refreshLabels()
                        lastLabels = SystemClock.elapsedRealtime()
                    }
                    delay(2_000)
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
    val sessionOpen = state.catalog?.session != null && stack.last() != Screen.SessionPhotos
    val onSecondaryTab = stack.size == 1 && stack.last() in setOf(Screen.Lots, Screen.Status, Screen.Sales)
    BackHandler(enabled = (stack.size > 1 || onSecondaryTab) && !sessionOpen) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        // Forward slides in from the right, back from the left, tabs cross-fade.
        AnimatedContent(
            targetState = stack,
            contentKey = { it.last() },
            transitionSpec = {
                val forward = targetState.size > initialState.size
                val back = targetState.size < initialState.size
                when {
                    forward -> (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(200))) togetherWith fadeOut(tween(160))
                    back -> (slideInHorizontally(tween(260)) { -it / 4 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(220)) { it / 4 } + fadeOut(tween(160)))
                    else -> fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                }
            },
            label = "screens",
        ) { shownStack ->
            val catalog = state.catalog
            val top = shownStack.last()
            when {
                catalog == null -> SetupScreen(vm)
                catalog != null && top == Screen.SessionPhotos && catalog.session != null -> SessionPhotosScreen(vm, catalog)
                catalog != null && catalog.session != null -> CameraSessionScreen(vm, catalog)
                top == Screen.Settings -> SettingsScreen(vm, state)
                catalog != null && top is Screen.Template -> TemplateScreen(vm, catalog, top.accountId)
                catalog != null && top is Screen.Editor -> EditorScreen(vm, catalog, top.number)
                catalog != null && top is Screen.Article -> ArticleScreen(vm, catalog, top.number)
                catalog != null && top is Screen.Photos -> PhotosScreen(vm, catalog, top.number, top.accountId)
                catalog != null && top == Screen.Sales -> SalesScreen(vm, catalog)
                catalog != null && top is Screen.Sale -> SaleScreen(vm, catalog, top.id)
                catalog != null && top == Screen.SalesStats -> SalesStatsScreen(vm, catalog)
                catalog != null && top == Screen.Lots -> LotsScreen(vm, catalog)
                catalog != null && top == Screen.Status -> StatusScreen(vm, catalog)
                else -> ArticleListScreen(vm, catalog)
            }
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
