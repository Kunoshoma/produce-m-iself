package dev.kunoshoma.produce.ui.blocked

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kunoshoma.produce.domain.ProduceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class InstalledApp(val packageName: String, val label: String, val blocked: Boolean)

@HiltViewModel
class BlockedAppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ProduceRepository,
) : ViewModel() {

    private val installed = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val apps: StateFlow<List<InstalledApp>> = combine(installed, repo.observeBlockedApps(), _query) { list, blocked, q ->
        val blockedMap = blocked.associateBy { it.packageName }
        // インストール済みに加えて、未インストールでも登録済みのもの（既定候補）は見せる
        val all = (list + blockedMap.values.map { it.packageName to it.label }).distinctBy { it.first }
        all.map { (pkg, label) -> InstalledApp(pkg, label, blockedMap[pkg]?.enabled == true) }
            .filter { q.isBlank() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .sortedWith(compareByDescending<InstalledApp> { it.blocked }.thenBy { it.label.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            installed.value = withContext(Dispatchers.IO) { loadLaunchable() }
        }
    }

    @Suppress("DEPRECATION") // ResolveInfoFlags は API 33+。minSdk 31 なので旧 API を使う。
    private fun loadLaunchable(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
    }

    fun setQuery(q: String) { _query.value = q }
    fun toggle(app: InstalledApp) = viewModelScope.launch { repo.setBlocked(app.packageName, app.label, !app.blocked) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedAppsScreen(onBack: () -> Unit, vm: BlockedAppsViewModel = hiltViewModel()) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("割り込むアプリ", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, label = { Text("検索") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = app.blocked, onCheckedChange = { vm.toggle(app) })
                    }
                }
            }
        }
    }
}
