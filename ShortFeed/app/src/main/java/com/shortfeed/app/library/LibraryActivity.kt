package com.shortfeed.app.library

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shortfeed.app.Prefs
import com.shortfeed.app.data.VideoItem

/** Lists the locally remembered likes / favourites and replays them full-screen. */
class LibraryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    LibraryScreen(
                        prefs = prefs,
                        onPlay = { item ->
                            startActivity(
                                Intent(this, LibraryPlayerActivity::class.java).apply {
                                    putExtra(LibraryPlayerActivity.EXTRA_ID, item.id)
                                    putExtra(LibraryPlayerActivity.EXTRA_URL, item.playUrl)
                                    putExtra(LibraryPlayerActivity.EXTRA_PLATFORM, item.platform.name)
                                    putExtra(LibraryPlayerActivity.EXTRA_TITLE, item.title)
                                },
                            )
                        },
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    prefs: Prefs,
    onPlay: (VideoItem) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    // Bumped by 刷新 so the prefs are re-read if they changed elsewhere.
    var refresh by remember { mutableStateOf(0) }
    val shown = remember(tab, refresh) {
        if (tab == 0) prefs.likedItems() else prefs.favoriteItems()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { tab = 0 }) {
                Text(if (tab == 0) "● 我的赞" else "我的赞")
            }
            TextButton(onClick = { tab = 1 }) {
                Text(if (tab == 1) "● 收藏" else "收藏")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }
        HorizontalDivider()
        if (shown.isEmpty()) {
            Text(
                text = "还没有记录。在沉浸模式里点「♥ 赞」或「★ 收藏」后会出现在这里。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.platform.name + it.id }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(item) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(item.title.ifBlank { "（无标题）" }, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${item.platform.name} · ${item.author.ifBlank { "未知作者" }}" +
                                if (item.playUrl.isBlank()) " · 链接缺失" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        Text(
            text = "提示：记录只存本机，按视频 id 保存。打开时会重新解析地址；" +
                "抖音若重新解析失败则退回保存时的地址，可能已过期。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        Button(
            onClick = { refresh++ },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text("刷新") }
    }
}
