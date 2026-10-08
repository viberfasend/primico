package de.andi1984.cadence.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import de.andi1984.cadence.CadenceApplication
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.ui.components.parseColor
import de.andi1984.cadence.ui.theme.CadenceTheme
import kotlinx.coroutines.launch

/**
 * The picker a [CadenceListWidget] is configured through: every project and every tag, one tap
 * each. The launcher starts it when the widget is placed (`android:configure` in
 * `list_widget_info.xml`), Android 12+ again from the widget's long-press menu
 * (`reconfigurable`), and the widget itself when it has nothing to show — see
 * [WidgetIntents.configureList].
 *
 * The result is `RESULT_CANCELED` until a list is picked, which is the configuration contract:
 * backing out of the picker on placement takes the half-placed widget off the home screen again
 * instead of leaving an unconfigured one behind.
 *
 * Exported, as the contract requires — it is the launcher that starts it. What that exposes is
 * small and checked: another app could only point one of *Primico's own* list widgets at one of
 * the user's own lists, and an id that is not one of this app's widgets is refused before
 * anything is written.
 */
class WidgetListConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val container = (application as? CadenceApplication)?.container
        val ours = AppWidgetManager.getInstance(this)
            .getAppWidgetInfo(appWidgetId)
            ?.provider
            ?.className == CadenceListWidgetReceiver::class.java.name
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || container == null || !ours) {
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            val settings by container.settingsStore.state.collectAsState()
            val projects by container.repository.projects.collectAsState(initial = emptyList())
            val tags by container.repository.tags.collectAsState(initial = emptyList())
            val scope = rememberCoroutineScope()

            CadenceTheme(
                theme = settings.theme,
                density = settings.density,
                locale = LocalConfiguration.current.locales[0],
            ) {
                ListPicker(
                    projects = remember(projects) {
                        projects.map { Choice(ListTarget.Project(it.id), projectPath(it, projects) ?: it.name, it.colorHex) }
                    },
                    tags = remember(tags) {
                        tags.map { Choice(ListTarget.Tag(it.id), "@" + it.name, it.colorHex) }
                    },
                    onPick = { target ->
                        scope.launch {
                            val manager = GlanceAppWidgetManager(this@WidgetListConfigActivity)
                            val glanceId = manager.getGlanceIdBy(appWidgetId)
                            updateAppWidgetState(this@WidgetListConfigActivity, glanceId) { prefs ->
                                ListTarget.write(prefs, target)
                            }
                            CadenceListWidget().update(this@WidgetListConfigActivity, glanceId)
                            // A list widget is a task widget: it keeps the midnight alarm and
                            // the background pull alive like the others.
                            WidgetUpdater.refreshAll(applicationContext)
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                            )
                            finish()
                        }
                    },
                )
            }
        }
    }
}

private class Choice(val target: ListTarget, val label: String, val colorHex: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPicker(projects: List<Choice>, tags: List<Choice>, onPick: (ListTarget) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.widget_list_config_title)) }) },
    ) { padding ->
        if (projects.isEmpty() && tags.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    text = stringResource(R.string.widget_list_config_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (projects.isNotEmpty()) {
                item { GroupLabel(stringResource(R.string.widget_list_config_projects)) }
                items(projects, key = { "p-" + it.target.id }) { ChoiceRow(it, onPick) }
            }
            if (tags.isNotEmpty()) {
                item { GroupLabel(stringResource(R.string.widget_list_config_tags)) }
                items(tags, key = { "t-" + it.target.id }) { ChoiceRow(it, onPick) }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceRow(choice: Choice, onPick: (ListTarget) -> Unit) {
    ListItem(
        headlineContent = { Text(choice.label) },
        leadingContent = {
            Box(
                Modifier
                    .size(12.dp)
                    .background(parseColor(choice.colorHex), CircleShape),
            )
        },
        modifier = Modifier.clickable { onPick(choice.target) },
    )
}
