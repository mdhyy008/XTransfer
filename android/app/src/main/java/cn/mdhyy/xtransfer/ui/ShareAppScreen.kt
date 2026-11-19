package cn.mdhyy.xtransfer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.mdhyy.xtransfer.data.BucketItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SITE_URL = "https://mdhyy.cn/tools/page/x-transfer/"
private const val SHARE_TEXT =
    "X互传 · 点对点文件互传，手机和电脑之间传文件不压缩、不限速。同一局域网内直连高速传输。\n官网：" + SITE_URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareAppScreen(onBack: () -> Unit, onSharePackage: (BucketItem) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }
    val qr = remember { qrBitmap(SITE_URL, 720) }

    fun copySite() {
        val copied = runCatching {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("text", SITE_URL)) != null
        }.getOrDefault(false)
        if (copied) context.toast("已复制官网链接") else context.showError("复制失败")
    }

    fun shareText() {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, SHARE_TEXT)
            }
            context.startActivity(Intent.createChooser(send, "分享网址"))
        }.onFailure { context.showError("没有可用的分享应用") }
    }

    fun sharePackage() {
        if (busy) return
        busy = true
        scope.launch {
            val item = withContext(Dispatchers.IO) {
                runCatching {
                    val source = File(context.applicationInfo.sourceDir)
                    val dir = File(context.cacheDir, "app-share").apply { mkdirs() }
                    val target = File(dir, "X互传-$versionName.apk")
                    source.copyTo(target, overwrite = true)
                    BucketItem(target.absolutePath, target.name, "file", target.length())
                }.getOrNull()
            }
            busy = false
            if (item == null) {
                context.showError("打包失败")
            } else {
                onSharePackage(item)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("分享本软件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "软件官网",
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text(
                            text = "二维码生成失败",
                            color = Color.Black,
                            modifier = Modifier.size(220.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "mdhyy.cn/tools/page/x-transfer",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { copySite() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("复制软件官网") }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { shareText() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("分享网址") }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { sharePackage() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "正在打包…" else "通过文件分发") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
