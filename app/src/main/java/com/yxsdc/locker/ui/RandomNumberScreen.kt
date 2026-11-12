package com.yxsdc.locker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yxsdc.locker.ui.components.ConfirmDialog
import com.yxsdc.locker.ui.components.EmptyStateHint
import com.yxsdc.locker.ui.components.PhotoSourceButton
import com.yxsdc.locker.ui.theme.lockerCardColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RandomNumberScreen(viewModel: LockerViewModel) {
    val currentPerson by viewModel.currentPerson.collectAsStateWithLifecycle()

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000 - (System.currentTimeMillis() % 1_000))
        }
    }
    val clockFormatter = remember {
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss", Locale.CHINA)
    }

    val todayEpochDay = now.toLocalDate().toEpochDay()
    val randomNumber by remember(todayEpochDay) {
        viewModel.observeDailyRandomNumber(todayEpochDay)
    }.collectAsStateWithLifecycle(initialValue = null)
    val todayPhoto by remember(todayEpochDay) {
        viewModel.observeDailyPhoto(todayEpochDay)
    }.collectAsStateWithLifecycle(initialValue = null)
    var pendingReplacement by remember { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(todayEpochDay) {
        viewModel.ensureDailyRandomNumber(todayEpochDay)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "打卡",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "当前时间",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = now.format(clockFormatter),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "当前人物：${currentPerson?.name ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        Card(
            colors = lockerCardColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "5 位随机数",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = randomNumber ?: "—",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "今日照片",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (todayPhoto == null) {
            EmptyStateHint(text = "今天还没有上传照片")
        } else {
            Text(
                text = "今天已上传照片",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(12.dp))
        PhotoSourceButton(
            text = if (todayPhoto == null) "上传今日照片" else "更换今日照片",
            onPhotoReady = { bytes ->
                if (todayPhoto == null) {
                    viewModel.setDailyPhoto(todayEpochDay, bytes)
                } else {
                    pendingReplacement = bytes
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = currentPerson != null,
            primary = true,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "只能上传今天及之前的照片；上传后会显示在月历今天的格子里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingReplacement?.let { bytes ->
        ConfirmDialog(
            title = "更换今日照片？",
            message = "今天已经有一张照片，确定用新照片替换吗？",
            confirmText = "替换",
            icon = Icons.Rounded.Refresh,
            onConfirm = { viewModel.setDailyPhoto(todayEpochDay, bytes) },
            onDismiss = { pendingReplacement = null },
        )
    }
}
