package com.shortfeed.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shortfeed.app.library.LibraryActivity
import com.shortfeed.app.player.PlayerActivity
import com.shortfeed.app.web.BiliLoginActivity
import com.shortfeed.app.web.DouyinWebActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Surface is required: MaterialTheme alone does not set LocalContentColor, so plain
                // Text would render black-on-black. Surface wires content color to the theme.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PickerScreen(
                        initial = prefs.load(),
                        onEnter = { cfg ->
                            prefs.save(cfg)
                            startActivity(Intent(this, PlayerActivity::class.java).apply {
                                putStringArrayListExtra(PlayerActivity.EXTRA_ENABLED, ArrayList(cfg.enabled))
                                putExtra(PlayerActivity.EXTRA_BACKEND_URL, cfg.backendUrl)
                            })
                        },
                        onOpenDouyinWeb = {
                            startActivity(Intent(this, DouyinWebActivity::class.java))
                        },
                        onBiliLogin = {
                            startActivity(Intent(this, BiliLoginActivity::class.java))
                        },
                        onOpenLibrary = {
                            startActivity(Intent(this, LibraryActivity::class.java))
                        },
                        onOpenDiagnostics = {
                            startActivity(Intent(this, DiagnosticsActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}

data class FeedConfig(val enabled: List<String>, val backendUrl: String)

@Composable
private fun PickerScreen(
    initial: FeedConfig,
    onEnter: (FeedConfig) -> Unit,
    onOpenDouyinWeb: () -> Unit,
    onBiliLogin: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var bili by remember { mutableStateOf(PlayerActivity.KEY_BILIBILI in initial.enabled) }
    var douyin by remember { mutableStateOf(PlayerActivity.KEY_DOUYIN in initial.enabled) }
    var kuaishou by remember { mutableStateOf(PlayerActivity.KEY_KUAISHOU in initial.enabled) }
    var url by remember { mutableStateOf(initial.backendUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ShortFeed", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Text("选择内容源（至少一个）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        SourceRow("Bilibili", "WBI 签名在本地完成，开箱即用，无需后端", bili) { bili = it }
        SourceRow("抖音", "不填后端=用你登录的抖音网页会话取推荐流；填了=走自部署后端", douyin) { douyin = it }
        SourceRow("快手", "需自部署后端（签名维护在服务端）", kuaishou) { kuaishou = it }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("后端地址（抖音/快手需要）") },
            placeholder = { Text("http://192.168.1.10:8000") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val enabled = buildList {
                    if (bili) add(PlayerActivity.KEY_BILIBILI)
                    if (douyin) add(PlayerActivity.KEY_DOUYIN)
                    if (kuaishou) add(PlayerActivity.KEY_KUAISHOU)
                }
                onEnter(FeedConfig(enabled.ifEmpty { listOf(PlayerActivity.KEY_BILIBILI) }, url))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("进入沉浸模式") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenDouyinWeb,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("抖音网页版（登录 / 点赞 / 收藏）") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBiliLogin,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Bilibili 登录（个性化推荐流）") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenLibrary,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("我的赞 / 收藏") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("诊断（加载失败记录）") }
        Spacer(Modifier.height(8.dp))
        Text(
            "提示：播放中用音量键切换内容源；屏幕上没有任何控件。\n" +
                "抖音沉浸流用你登录的网页会话：先去「抖音网页版」登录，再进沉浸模式。\n" +
                "B站登录后自动切换为你的账号推荐流（Cookie 只存在本机）。\n" +
                "网页版登录/点赞/收藏原样可用；若弹出「下载 App」忽略即可。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SourceRow(label: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}
