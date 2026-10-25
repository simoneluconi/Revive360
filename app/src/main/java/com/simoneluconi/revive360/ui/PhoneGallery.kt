package com.simoneluconi.revive360.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simoneluconi.revive360.R
import com.simoneluconi.revive360.media.MediaSaver
import com.simoneluconi.revive360.media.PhoneItem
import com.simoneluconi.revive360.media.PhoneMedia
import com.simoneluconi.revive360.protocol.FileKind
import com.simoneluconi.revive360.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the app saved on the phone: photos open in the 360 viewer, videos in the phone's player. */
@Composable
fun PhoneGallery(modifier: Modifier = Modifier) {
    val nav = LocalActions.current
    val context = LocalContext.current
    val colors = LocalAppColors.current
    var items by remember { mutableStateOf<List<PhoneItem>?>(null) }
    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { runCatching { PhoneMedia.list(context) }.getOrDefault(emptyList()) }
    }
    val list = items
    when {
        list == null -> Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
        list.isEmpty() -> Card(modifier.fillMaxWidth()) {
            Label(stringResource(R.string.phone_empty, MediaSaver.FOLDER), color = colors.text2)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.uri.toString() }) { item ->
                PhoneTile(item) {
                    if (item.kind == FileKind.VIDEO) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "video/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    } else {
                        nav.navigate(Screen.PhonePanorama(item))
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneTile(item: PhoneItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    var image by remember(item.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.uri) {
        image = withContext(Dispatchers.IO) { PhoneMedia.thumbnail(context, item)?.asImageBitmap() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface2)
            .pressable(label = item.name, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(if (item.kind == FileKind.VIDEO) AppIcons.Video else AppIcons.Photo, colors.text2, size = 28.dp)
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(colors.bg.copy(alpha = 0.72f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Label(item.name, size = 12.sp, weight = FontWeight.Bold, maxLines = 1, mono = true)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            val badge = when {
                item.kind == FileKind.VIDEO -> AppIcons.Video
                item.isStitched -> AppIcons.Sphere
                else -> null
            }
            if (badge != null) {
                Box(
                    Modifier.clip(CircleShape).background(colors.bg.copy(alpha = 0.8f)).padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(badge, colors.text, size = 14.dp) }
            }
        }
    }
}
