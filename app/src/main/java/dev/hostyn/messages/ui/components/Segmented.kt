@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package dev.hostyn.messages.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Colors for the app's segmented lists: the items sit on the screen's surface as their own
 * containers, as in the M3 segmented list examples.
 */
@Composable
fun segmentedColors(): ListItemColors =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** Collects the items of a [SegmentedGroup]; each gets its shape from its place in the group. */
class SegmentedGroupScope {
    @PublishedApi internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun item(content: @Composable (shapes: ListItemShapes) -> Unit) {
        items += content
    }
}

/**
 * An M3 Expressive segmented list built item by item (the builder may read resources and state):
 * large corners at the ends of the group, small ones between items, and the standard gap. Items are
 * usually [androidx.compose.material3.SegmentedListItem]s using the shapes they are given.
 *
 * Inline so the builder and the drawing of its items recompose together: otherwise a state read in
 * the builder (a switch's value) would rebuild the items without drawing them again. For the same
 * reason, pass the builder as a lambda literal, or only through other inline functions.
 */
@Composable
inline fun SegmentedGroup(modifier: Modifier = Modifier, content: @Composable SegmentedGroupScope.() -> Unit) {
    val scope = SegmentedGroupScope()
    scope.content()
    SegmentedItems(scope.items, modifier)
}

@PublishedApi
@Composable
internal fun SegmentedItems(items: List<@Composable (ListItemShapes) -> Unit>, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        items.forEachIndexed { i, item -> item(ListItemDefaults.segmentedShapes(i, items.size)) }
    }
}

/** Title above a group of list items. */
@Composable
fun ListHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp),
    )
}
