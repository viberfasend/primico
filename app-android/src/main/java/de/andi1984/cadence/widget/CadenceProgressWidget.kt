package de.andi1984.cadence.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.andi1984.cadence.R
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.DayProgress
import de.andi1984.cadence.ui.Routes
import de.andi1984.cadence.ui.dayProgress
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The day as a ring: how much of what was on today's plate is done, readable from across the
 * room. No rows, no titles — the Today and next-task widgets answer "what"; this one answers
 * "how far", which is the question a home screen is glanced at for most often.
 *
 * The numbers are [dayProgress], the same count the Today widget's progress bar draws, so the two
 * never disagree — and, unlike a count read off the Today *list*, it does not reset to zero when
 * Show completed is off or forget a late task the moment it is ticked off.
 *
 * **The ring is two bitmaps, tinted, rather than one coloured one.** Glance has no determinate
 * circular indicator, and RemoteViews draws no paths, so the arc has to be a picture. Painting it
 * in white and tinting it with a [ColorFilter] lets the colour stay a day/night `ColorProvider` —
 * the launcher swaps it when the system theme flips, where a bitmap painted in the current
 * theme's colours would stay in the wrong one until the next redraw. The track and the arc are
 * separate images for the same reason: one tint per picture.
 *
 * [SizeMode.Responsive] with two layouts — the ring alone when square, the ring beside words when
 * wide. Each is drawn ahead of time for its size bucket, so a resize never needs the process.
 */
class CadenceProgressWidget : CadenceStateWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SQUARE, WIDE))

    @Composable
    override fun Content(context: Context, state: CadenceUiState?, today: LocalDate) {
        val progress = state?.dayProgress(today)
        val size = LocalSize.current
        val wide = size.width >= WIDE.width
        // Nothing planned is the moment to plan something, as on every other widget.
        val tap = if (progress != null && progress.total == 0) {
            WidgetIntents.openQuickAdd(context)
        } else {
            WidgetIntents.openRoute(context, Routes.TODAY)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .clickable(actionStartActivity(tap))
                .semantics { contentDescription = describe(context, progress) }
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (progress == null) return@Box
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(context, progress, diameter = size.height - 24.dp)
                    Spacer(GlanceModifier.width(14.dp))
                    Words(context, progress)
                }
            } else {
                Ring(context, progress, diameter = min(size.width, size.height) - 24.dp)
            }
        }
    }

    private companion object {
        val SQUARE = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(220.dp, 110.dp)
    }
}

/** The ring, with the count — or a tick, or a "+" — in its middle. */
@Composable
private fun Ring(context: Context, progress: DayProgress, diameter: Dp) {
    val ring = diameter.coerceAtLeast(56.dp)
    val px = (ring.value * context.resources.displayMetrics.density).roundToInt().coerceIn(64, MAX_RING_PX)
    Box(modifier = GlanceModifier.size(ring), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(ringBitmap(px, sweep = 1f)),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.surfaceVariant),
            modifier = GlanceModifier.fillMaxSize(),
        )
        if (progress.done > 0) {
            Image(
                provider = ImageProvider(ringBitmap(px, sweep = progress.fraction)),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.fillMaxSize(),
            )
        }
        when {
            progress.total == 0 -> Image(
                provider = ImageProvider(R.drawable.ic_widget_add),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(ring / 3),
            )
            progress.open == 0 -> Image(
                provider = ImageProvider(R.drawable.ic_widget_circle_check),
                contentDescription = null,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(ring / 3),
            )
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${progress.done}/${progress.total}",
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = if (ring >= 72.dp) 20.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                // Overdue work is the one number worth a colour of its own — and it is spelled
                // out, so the colour never stands alone.
                if (progress.overdue > 0 && ring >= 72.dp) {
                    Text(
                        text = context.resources.getQuantityString(
                            R.plurals.widget_progress_overdue,
                            progress.overdue,
                            progress.overdue,
                        ),
                        maxLines = 1,
                        style = TextStyle(color = GlanceTheme.colors.error, fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

/** The wide layout's words: "Today", how many are done, and what is overdue. */
@Composable
private fun Words(context: Context, progress: DayProgress) {
    Column {
        Text(
            text = context.getString(R.string.widget_today_title),
            maxLines = 1,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Text(
            text = headline(context, progress),
            maxLines = 2,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (progress.overdue > 0) {
            Text(
                text = context.resources.getQuantityString(
                    R.plurals.widget_progress_overdue,
                    progress.overdue,
                    progress.overdue,
                ),
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp),
            )
        }
    }
}

private fun headline(context: Context, progress: DayProgress): String = when {
    progress.total == 0 -> context.getString(R.string.widget_progress_nothing)
    progress.open == 0 -> context.getString(R.string.widget_all_done)
    else -> context.resources.getQuantityString(
        R.plurals.widget_progress_done,
        progress.total,
        progress.done,
        progress.total,
    )
}

/** What a screen reader says for the whole tile — the ring itself is a picture. */
private fun describe(context: Context, progress: DayProgress?): String {
    if (progress == null) return context.getString(R.string.widget_today_title)
    val head = headline(context, progress)
    if (progress.overdue == 0) return head
    val overdue = context.resources.getQuantityString(
        R.plurals.widget_progress_overdue,
        progress.overdue,
        progress.overdue,
    )
    return "$head, $overdue"
}

/**
 * A white ring of [sweep] (0 to 1) of a full turn, starting at twelve o'clock, on a transparent
 * square [px] wide — the stroke a tenth of the width, ends rounded like the app's progress bars.
 * White because the colour comes from the [ColorFilter] it is drawn with; see the class comment.
 */
private fun ringBitmap(px: Int, sweep: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val stroke = px / 10f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = android.graphics.Color.WHITE
    }
    val inset = stroke / 2f
    val bounds = RectF(inset, inset, px - inset, px - inset)
    Canvas(bitmap).drawArc(bounds, -90f, 360f * sweep.coerceIn(0f, 1f), false, paint)
    return bitmap
}

/**
 * Bitmaps travel to the launcher inside the RemoteViews, whose size the platform caps. Two rings
 * at this size are a few hundred kilobytes — well inside it, and sharp on any phone screen.
 */
private const val MAX_RING_PX = 320
