package com.neoapps.neolauncher.compose.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItemColors
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.neoapps.neolauncher.compose.components.TabItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import kotlin.collections.forEachIndexed

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun NeoNavigationSuiteScaffold(
    pages: ImmutableList<TabItem>,
    selectedPage: Int,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hideNavigation: Boolean = false,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    val adaptiveInfo = currentWindowAdaptiveInfo()
    val customNavSuiteType = with(adaptiveInfo) {
        if (windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND))
            NavigationSuiteType.NavigationRail
        else NavigationSuiteType.NavigationBar
    }
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            unselectedIconColor = MaterialTheme.colorScheme.onBackground,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.onBackground,
            indicatorColor = MaterialTheme.colorScheme.primary,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            unselectedIconColor = MaterialTheme.colorScheme.onBackground,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.onBackground,
            indicatorColor = MaterialTheme.colorScheme.primary,
        ),
        navigationDrawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            unselectedIconColor = MaterialTheme.colorScheme.onBackground,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.onBackground,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
        ),
    )

    NavigationSuiteScaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        layoutType = customNavSuiteType,
        navigationSuiteItems = {
            if (!hideNavigation) pages.forEachIndexed { index, it ->
                navItem(
                    item = it,
                    selected = index == selectedPage,
                    itemColors = itemColors,
                    onClick = {
                        scope.launch {
                            onItemClick(index)
                        }
                    }
                )
            }
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = Color.Transparent,
            navigationRailContainerColor = Color.Transparent,
            navigationDrawerContainerColor = Color.Transparent,
            navigationBarContentColor = MaterialTheme.colorScheme.onBackground,
            navigationRailContentColor = MaterialTheme.colorScheme.onBackground,
            navigationDrawerContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        content = content
    )
}

fun NavigationSuiteScope.navItem(
    item: TabItem,
    selected: Boolean,
    itemColors: NavigationSuiteItemColors,
    onClick: () -> Unit,
) {
    item(
        icon = {
            Icon(
                painter = painterResource(id = item.icon),
                contentDescription = stringResource(id = item.title),
            )
        },
        label = {
            AnimatedVisibility(
                visible = !selected,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Text(
                    text = stringResource(id = item.title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        selected = selected,
        colors = itemColors,
        onClick = onClick,
    )
}