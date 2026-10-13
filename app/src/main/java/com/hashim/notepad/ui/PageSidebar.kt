package com.hashim.notepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hashim.notepad.data.NotePadRepository
import com.hashim.notepad.data.Page

@Composable
internal fun PageSidebar(
    repo: NotePadRepository,
    pages: List<Page>,
    currentPageId: String,
    onPageSelected: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Pages", "Bookmarks").forEachIndexed { index, title ->
                Column(
                    Modifier.weight(1f).clickable { selectedTab = index }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(title, color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selectedTab == index) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
            Text("×", Modifier.clickable(onClick = onClose).padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val visible = pages.withIndex().filter { selectedTab == 0 || it.value.bookmarked }
        if (selectedTab == 1 && visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No bookmarks yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(visible, key = { _, item -> item.value.id }) { _, indexedPage ->
                    val page = indexedPage.value
                    Column(
                        Modifier.fillMaxWidth().clickable { onPageSelected(page.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PagePreview(
                            repo = repo,
                            page = page,
                            paperColor = MaterialTheme.colorScheme.background,
                            modifier = Modifier.fillMaxWidth().aspectRatio(page.widthPt / page.heightPt)
                                .clip(RoundedCornerShape(4.dp))
                                .border(
                                    if (page.id == currentPageId) 2.dp else 1.dp,
                                    if (page.id == currentPageId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(4.dp),
                                ),
                        )
                        Text("${indexedPage.index + 1}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
