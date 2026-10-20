package com.hashim.notepad.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hashim.notepad.data.Folder

/**
 * App shell modelled on ChatGPT (Taste Engine extraction): a quiet text sidebar on tablets / drawer on phones,
 * a centred composer-style search as the hero, and a bottom composer pill on phones instead of a nav bar.
 */
enum class LibraryView { LIST, GRID }

data class SidebarRecent(val id: String, val title: String, val pageIndex: Int)

@Composable
fun AppSidebar(
    screen: Screen,
    onNavigate: (Screen) -> Unit,
    folders: List<Folder>,
    folderCounts: Map<String?, Int>,
    selectedFolderId: String,
    onSelectFolder: (String) -> Unit,
    onAddFolder: () -> Unit,
    recents: List<SidebarRecent>,
    onOpenRecent: (SidebarRecent) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onAppearance: () -> Unit,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
    expandedWidth: Dp = 264.dp,
) {
    val t = NotePadTheme.tokens
    val side = t.surface2.copy(alpha = 0.5f).compositeOver(t.bg)
    val width by animateDpAsState(if (collapsed) 72.dp else expandedWidth, tween(Motion.medium, easing = Motion.emphasized), label = "sidebar")
    val selectedLibrary = screen == Screen.Notebooks || screen is Screen.Pages || screen is Screen.Read
    Box(modifier.width(width).fillMaxHeight().background(side)) {
        if (collapsed) {
            NavRail(
                items = listOf(
                    NavItem("Library", Icons.Outlined.Book, selectedLibrary) { onNavigate(Screen.Notebooks) },
                    NavItem("Search", Icons.Outlined.Search, screen == Screen.Search) { onNavigate(Screen.Search) },
                    NavItem("Sync", Icons.Outlined.Cloud, screen == Screen.Account) { onNavigate(Screen.Account) },
                ),
                modifier = Modifier.statusBarsPadding(),
                bottom = {
                    ToolCell(Icons.Outlined.Palette, "Appearance", onClick = onAppearance)
                    Spacer(Modifier.height(4.dp))
                    ToolCell(Icons.Outlined.MenuOpen, "Expand sidebar", onClick = onToggleCollapsed)
                },
            )
        } else Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(t.ink, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text("N", color = t.bg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                Text("NotePad", style = MaterialTheme.typography.titleMedium, color = t.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MenuOpen, "Collapse sidebar", tint = t.ink2, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            SidebarRow(Icons.Outlined.EditNote, "New notebook", selected = false, onClick = onNew)
            SidebarRow(Icons.Outlined.FileOpen, "Import PDF", selected = false, onClick = onImport)
            SidebarRow(Icons.Outlined.Search, "Search", selected = screen == Screen.Search, onClick = { onNavigate(Screen.Search) })
            Spacer(Modifier.height(16.dp))
            SidebarRow(Icons.Outlined.Book, "Library", selected = selectedLibrary && selectedFolderId == ALL_FOLDERS, onClick = { onSelectFolder(ALL_FOLDERS); onNavigate(Screen.Notebooks) })
            SidebarRow(Icons.Outlined.Cloud, "Sync", selected = screen == Screen.Account, onClick = { onNavigate(Screen.Account) })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(16.dp))
                SidebarSection("Subjects")
                folders.forEach { folder ->
                    val dot = folder.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: t.ink3
                    SidebarRow(
                        icon = null, label = folder.name, selected = selectedLibrary && selectedFolderId == folder.id,
                        trailing = folderCounts[folder.id]?.toString(), dot = dot,
                        onClick = { onSelectFolder(folder.id); onNavigate(Screen.Notebooks) },
                    )
                }
                SidebarRow(Icons.Default.Add, "New subject", selected = false, muted = true, onClick = onAddFolder)
                if (recents.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SidebarSection("Recent")
                    recents.forEach { r -> SidebarRow(icon = null, label = r.title, selected = false, onClick = { onOpenRecent(r) }) }
                }
            }
            Hairline(Modifier.padding(vertical = 6.dp))
            SidebarRow(Icons.Outlined.Palette, "Appearance", selected = false, onClick = onAppearance)
        }
    }
}

@Composable
private fun SidebarSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = NotePadTheme.tokens.ink3,
        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
    )
}

/** ChatGPT sidebar item: 40 dp row, 10 dp radius, neutral fill when selected, 14/20 label. */
@Composable
fun SidebarRow(
    icon: ImageVector?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
    dot: Color? = null,
    muted: Boolean = false,
) {
    val t = NotePadTheme.tokens
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) t.ink.copy(alpha = 0.07f) else Color.Transparent, tween(Motion.medium, easing = Motion.emphasized), label = "row")
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .clickable(interaction, indication = ripple(color = NotePadTheme.tokens.ink), onClick = onClick)
            .pressable(interaction)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            icon != null -> Icon(icon, null, tint = if (muted) t.ink3 else t.ink, modifier = Modifier.size(20.dp))
            dot != null -> Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)) }
            else -> Spacer(Modifier.width(0.dp))
        }
        if (icon != null || dot != null) Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) t.ink3 else t.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = t.ink3)
    }
}

/** ChatGPT mobile header: menu on the left, centred title, compose action on the right. */
@Composable
fun PhoneTopBar(title: String, subtitle: String?, onMenu: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit) {
    val t = NotePadTheme.tokens
    Row(modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Menu", tint = t.ink) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = t.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = t.ink3)
        }
        actions()
    }
}

/** ChatGPT composer, repurposed: tap to search; "+" opens New notebook / Import PDF; the dark circle is the primary action. */
@Composable
fun ComposerSearch(
    placeholder: String,
    onSearch: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
) {
    val t = NotePadTheme.tokens
    var plusOpen by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.height(height).clip(RoundedCornerShape(height / 2)).clickable(interaction, indication = ripple(color = NotePadTheme.tokens.ink), onClick = onSearch).pressable(interaction),
        shape = RoundedCornerShape(height / 2),
        color = t.surface,
        contentColor = t.ink,
        border = BorderStroke(Spec.hairline, t.border),
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { plusOpen = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Add, "New or import", tint = t.ink) }
                DropdownMenu(expanded = plusOpen, onDismissRequest = { plusOpen = false }, shape = RoundedCornerShape(16.dp), containerColor = t.surface) {
                    DropdownMenuItem(text = { Text("New notebook") }, leadingIcon = { Icon(Icons.Outlined.EditNote, null) }, onClick = { plusOpen = false; onNew() })
                    DropdownMenuItem(text = { Text("Import PDF") }, leadingIcon = { Icon(Icons.Outlined.FileOpen, null) }, onClick = { plusOpen = false; onImport() })
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = t.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(36.dp).background(t.ink, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, "Search", tint = t.bg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** List / grid segmented control, neutral like ChatGPT's view toggles. */
@Composable
fun ViewToggle(view: LibraryView, onChange: (LibraryView) -> Unit, modifier: Modifier = Modifier) {
    val t = NotePadTheme.tokens
    Row(modifier.height(40.dp).background(t.surface2, CircleShape).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(LibraryView.LIST to Icons.AutoMirrored.Outlined.ViewList, LibraryView.GRID to Icons.Outlined.GridView).forEach { (v, icon) ->
            val selected = view == v
            val fill by animateColorAsState(if (selected) t.surface else Color.Transparent, tween(Motion.medium, easing = Motion.emphasized), label = "seg")
            Box(
                Modifier.size(width = 40.dp, height = 34.dp).clip(CircleShape).background(fill).clickable { onChange(v) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, if (v == LibraryView.LIST) "List" else "Grid", tint = if (selected) t.ink else t.ink2, modifier = Modifier.size(20.dp)) }
        }
    }
}
