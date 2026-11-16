package com.yxsdc.locker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TAB_COUNT = 4

@Composable
fun LockerRoot(viewModel: LockerViewModel) {
    val pagerState = rememberPagerState(pageCount = { TAB_COUNT })
    // 页面滑出视口后仍保留各自的滚动位置
    val pageStateHolder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val persons by viewModel.persons.collectAsStateWithLifecycle()
    val currentPerson by viewModel.currentPerson.collectAsStateWithLifecycle()
    val eventTypes by viewModel.eventTypes.collectAsStateWithLifecycle()
    val activeSegment by viewModel.activeSegment.collectAsStateWithLifecycle()
    val segmentCount by viewModel.segmentCount.collectAsStateWithLifecycle()
    val eventCount by viewModel.eventCount.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    var showGuide by rememberSaveable { mutableStateOf(false) }

    // 指南页有自己的返回处理，显示时让底部导航的返回让位
    BackHandler(enabled = !showGuide && pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(Unit) {
        viewModel.syncNavigation.collect {
            pagerState.animateScrollToPage(1)
        }
    }

    // 桌面快捷方式「发送今日数据」：先切到打卡页，由页面自己打开发送弹窗
    val sendTodayRequested by viewModel.sendTodayRequested.collectAsStateWithLifecycle()
    LaunchedEffect(sendTodayRequested) {
        if (sendTodayRequested) {
            pagerState.animateScrollToPage(2)
        }
    }

    val pendingSharedZip by viewModel.pendingSharedZip.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSharedZip) {
        if (pendingSharedZip != null) {
            pagerState.animateScrollToPage(3)
        }
    }

    if (showGuide) {
        GuideScreen(onBack = { showGuide = false })
        return
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionContentColor = MaterialTheme.colorScheme.primary,
                    dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text("主页") },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = { Icon(Icons.Rounded.DateRange, contentDescription = null) },
                    label = { Text("统计") },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    icon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                    label = { Text("打卡") },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 3,
                    onClick = { scope.launch { pagerState.animateScrollToPage(3) } },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            key = { it },
        ) { page ->
            pageStateHolder.SaveableStateProvider(page) {
                when (page) {
                    0 -> HomeScreen(
                        viewModel,
                        persons,
                        currentPerson,
                        eventTypes,
                        activeSegment,
                        snackbarHostState,
                    )
                    1 -> StatsScreen(
                        viewModel,
                        persons,
                        currentPerson,
                        eventTypes,
                    )
                    2 -> RandomNumberScreen(viewModel)
                    else -> SettingsScreen(
                        viewModel,
                        persons,
                        currentPerson,
                        eventTypes,
                        segmentCount,
                        eventCount,
                        snackbarHostState,
                        onOpenGuide = { showGuide = true },
                    )
                }
            }
        }
    }
}
