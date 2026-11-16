package com.yxsdc.locker.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.yxsdc.locker.ui.PhotoPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 选择照片来源（拍照 / 相册），读取并压缩后回调 JPEG 字节。
 * 拍照使用系统相机，不需要在 App 中声明相机权限。
 */
@Composable
fun PhotoSourceButton(
    text: String,
    onPhotoReady: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val buttonCoordinates = remember { Ref<LayoutCoordinates>() }
    var sourceDialogOrigin by remember { mutableStateOf<Offset?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun cleanupCameraFile() {
        pendingCameraFile?.delete()
        pendingCameraFile = null
        pendingCameraUri = null
    }

    fun process(uri: Uri) {
        processing = true
        scope.launch {
            val watermarkAt = if (PhotoPreferences.watermarkEnabled(context)) {
                System.currentTimeMillis()
            } else {
                null
            }
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    loadCompressedJpeg(context, uri, watermarkAt = watermarkAt)
                }.getOrNull()
            }
            processing = false
            cleanupCameraFile()
            if (bytes == null) {
                Toast.makeText(context, "无法读取图片", Toast.LENGTH_SHORT).show()
            } else {
                onPhotoReady(bytes)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            process(uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = TakePictureWithPermission(),
    ) { success ->
        if (success) {
            pendingCameraUri?.let { process(it) } ?: cleanupCameraFile()
        } else {
            cleanupCameraFile()
        }
    }

    fun launchCamera() {
        try {
            val directory = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("photo_", ".jpg", directory)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            pendingCameraFile = file
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (throwable: Throwable) {
            cleanupCameraFile()
            Toast.makeText(
                context,
                throwable.message ?: "无法打开相机",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val label = if (processing) "处理中…" else text
    val buttonModifier = modifier.onPlaced { buttonCoordinates.value = it }
    val openSourceDialog = {
        sourceDialogOrigin = buttonCoordinates.value?.centerOnScreen(view)
        showSourceDialog = true
    }
    if (primary) {
        Button(
            onClick = openSourceDialog,
            modifier = buttonModifier,
            enabled = enabled && !processing,
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = openSourceDialog,
            modifier = buttonModifier,
            enabled = enabled && !processing,
        ) {
            Text(label)
        }
    }

    if (showSourceDialog) {
        PopDialog(
            onDismissRequest = { showSourceDialog = false },
            originOnScreen = sourceDialogOrigin,
        ) { dismiss ->
            Column(Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 12.dp)) {
                Text(
                    text = "上传照片",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.size(16.dp))
                PhotoSourceOption(
                    label = "拍照",
                    glyph = { CameraGlyph() },
                    onClick = {
                        if (dismiss()) launchCamera()
                    },
                )
                Spacer(Modifier.size(10.dp))
                PhotoSourceOption(
                    label = "从相册选择",
                    glyph = { GalleryGlyph() },
                    onClick = {
                        if (dismiss()) {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    },
                )
                Spacer(Modifier.size(4.dp))
                TextButton(
                    onClick = { dismiss() },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
private fun PhotoSourceOption(
    label: String,
    glyph: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                glyph()
            }
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CameraGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val u = size.minDimension / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(8.5f * u, 5f * u),
            size = Size(7f * u, 3.5f * u),
            cornerRadius = CornerRadius(1.75f * u),
            style = Stroke(width = 2f * u),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.5f * u, 8.5f * u),
            size = Size(17f * u, 11.5f * u),
            cornerRadius = CornerRadius(3f * u),
            style = Stroke(width = 2f * u),
        )
        drawCircle(
            color = tint,
            radius = 3.2f * u,
            center = Offset(12f * u, 14.25f * u),
            style = Stroke(width = 2f * u),
        )
    }
}

@Composable
private fun GalleryGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val u = size.minDimension / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.5f * u, 4.5f * u),
            size = Size(17f * u, 15f * u),
            cornerRadius = CornerRadius(3.5f * u),
            style = Stroke(width = 2f * u),
        )
        drawCircle(color = tint, radius = 1.7f * u, center = Offset(9f * u, 9.5f * u))
        val mountain = Path().apply {
            moveTo(6f * u, 17f * u)
            lineTo(10.5f * u, 11f * u)
            lineTo(13.8f * u, 14.6f * u)
            lineTo(16f * u, 12f * u)
            lineTo(18f * u, 14.8f * u)
        }
        drawPath(
            path = mountain,
            color = tint,
            style = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 系统相机需要显式给输出 URI 授予写权限，标准 TakePicture 合约没有加 flag。
 */
private class TakePictureWithPermission : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}
