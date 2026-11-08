package cn.mdhyy.xtransfer.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.mdhyy.xtransfer.R

// 自适应图标是 XML，painterResource 不支持，直接画成位图。
private fun loadAppIcon(context: Context, sizePx: Int): ImageBitmap? = runCatching {
    val drawable = context.getDrawable(R.mipmap.ic_launcher) ?: return@runCatching null
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(Canvas(bitmap))
    bitmap.asImageBitmap()
}.getOrNull()

private val OPEN_SOURCE = listOf(
    "Kotlin" to "Apache-2.0",
    "Jetpack Compose" to "Apache-2.0",
    "AndroidX" to "Apache-2.0",
    "Room" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "Kotlin Coroutines" to "Apache-2.0",
    "SQLite" to "BSD-3-Clause",
    "gomobile" to "BSD-3-Clause"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(40.dp))
            val iconSize = with(LocalDensity.current) { 88.dp.roundToPx() }
            val appIcon = remember(iconSize) { loadAppIcon(context, iconSize) }
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = versionName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            SettingsGroup {
                SettingRow(
                    icon = Icons.Rounded.Person,
                    title = "开发者",
                    trailingText = "明灯花月夜"
                )
                SettingDivider()
                SettingRow(
                    icon = Icons.Rounded.Public,
                    title = "网址",
                    trailingText = "mdhyy.cn",
                    showChevron = true,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://mdhyy.cn"))
                            )
                        }
                    }
                )
            }
            SectionTitle("开源项目")
            SettingsGroup {
                SettingRow(
                    icon = Icons.Rounded.Code,
                    title = "xlan",
                    trailingText = "MIT",
                    showChevron = true,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/mdhyy008/xlan")
                                )
                            )
                        }
                    }
                )
                OPEN_SOURCE.forEach { (name, license) ->
                    SettingDivider()
                    SettingRow(
                        icon = Icons.Rounded.Code,
                        title = name,
                        trailingText = license
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
