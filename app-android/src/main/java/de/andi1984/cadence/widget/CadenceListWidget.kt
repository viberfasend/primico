package de.andi1984.cadence.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalGlanceId
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.Routes
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.taskList
import java.time.LocalDate

/**
 * What a [CadenceListWidget] instance shows: one project, or one tag. Stored per widget in its
 * Glance state, written once by [WidgetListConfigActivity].
 *
 * By id, never by name: a renamed project is still the same list, and the title follows the
 * rename on the next emission because it is read from the state, not from here.
 */
internal sealed interface ListTarget {
    val id: String

    data class Project(override val id: String) : ListTarget

    data class Tag(override val id: String) : ListTarget

    companion object {
        private val KIND = stringPreferencesKey("list_kind")
        private val ID = stringPreferencesKey("list_id")
        private const val PROJECT = "project"
        private const val TAG = "tag"

        /** `null` for a widget that has not been configured — or whose state did not parse. */
        fun read(prefs: Preferences): ListTarget? {
            val id = prefs[ID]?.takeIf { it.isNotBlank() } ?: return null
            return when (prefs[KIND]) {
                PROJECT -> Project(id)
                TAG -> Tag(id)
                else -> null
            }
        }

        fun write(prefs: MutablePreferences, target: ListTarget) {
            prefs[KIND] = when (target) {
                is Project -> PROJECT
                is Tag -> TAG
            }
            prefs[ID] = target.id
        }
    }
}

/**
 * A scrolling list of **one project or one tag**, chosen when the widget is placed — the
 * home-screen shortcut to "the shopping list" or "everything `@errand`" that the fixed Today and
 * Inbox widgets cannot be.
 *
 * Everything below the header is [TaskListContent], unchanged: the rows come from [taskList] with
 * a [TaskView.Project] or [TaskView.Tag], so a project's widget bands by section exactly as its
 * screen does, tickable rows and all. Only the chrome differs — the project's or tag's name as the
 * title, the header opening that project's or tag's screen, and a "+" that files into the project
 * (or starts the line with the tag's `@handle`), the way the screen's own add button does.
 *
 * **The target lives in the widget's Glance state**, one per placed widget, so two of these on one
 * home screen show two different lists. [currentState] is read inside the composition, which is
 * also what makes a reconfiguration redraw without anything else having to change.
 *
 * Two states draw a prompt instead of a list, and both open the picker again: a widget placed by
 * a launcher that skipped the configuration step, and one whose project or tag has since been
 * deleted. The second must never fall back to some other list — a widget silently showing the
 * wrong project is worse than one saying its own is gone.
 */
class CadenceListWidget : CadenceStateWidget() {

    override val sizeMode = SizeMode.Single

    override val stateDefinition = PreferencesGlanceStateDefinition

    @Composable
    override fun Content(context: Context, state: CadenceUiState?, today: LocalDate) {
        val target = ListTarget.read(currentState<Preferences>())
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(LocalGlanceId.current)
        val reconfigure = WidgetIntents.configureList(context, appWidgetId)

        if (target == null) {
            Prompt(context.getString(R.string.widget_list_choose), reconfigure)
            return
        }
        if (state == null) {
            // No container to read from: the same blank-but-framed tile the other lists draw.
            TaskListContent(context, placeholderChrome(context, reconfigure), list = null, today = today)
            return
        }

        val chrome = when (target) {
            is ListTarget.Project -> state.project(target.id)?.let { project ->
                ListChrome(
                    title = projectPath(project, state.projects) ?: project.name,
                    emptyText = context.getString(R.string.widget_list_empty),
                    open = WidgetIntents.openRoute(context, Routes.project(project.id)),
                    quickAdd = WidgetIntents.openQuickAdd(context, projectId = project.id),
                    progress = null,
                )
            }
            is ListTarget.Tag -> state.tags.firstOrNull { it.id == target.id }?.let { tag ->
                ListChrome(
                    title = "@" + tag.name,
                    emptyText = context.getString(R.string.widget_list_empty),
                    open = WidgetIntents.openRoute(context, Routes.tag(tag.id)),
                    // The trailing space ends the handle, so the cursor is ready for the title.
                    quickAdd = WidgetIntents.openQuickAdd(context, text = "@" + tag.handle + " "),
                    progress = null,
                )
            }
        }
        if (chrome == null) {
            Prompt(context.getString(R.string.widget_list_missing), reconfigure)
            return
        }

        val view = when (target) {
            is ListTarget.Project -> TaskView.Project(target.id)
            is ListTarget.Tag -> TaskView.Tag(target.id)
        }
        TaskListContent(context, chrome, state.taskList(view, today), today)
    }
}

private fun placeholderChrome(context: Context, reconfigure: Intent) = ListChrome(
    title = "",
    emptyText = "",
    open = reconfigure,
    quickAdd = WidgetIntents.openQuickAdd(context),
    progress = null,
)

/** The whole tile as one tap target that opens the picker, with [text] saying why. */
@Composable
private fun Prompt(text: String, reconfigure: Intent) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity(reconfigure))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
