package helium314.keyboard.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.horizontalArrangement
import androidx.compose.foundation.layout.verticalAlignment
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

@Composable
fun KlipyTabBar(
    selectedTab: String,
    onTabSelected: (String) -> Unit,
    colors: Colors,
    isNight: Boolean,
    customFontFamily: androidx.compose.ui.text.font.FontFamily?
) {
    val context = LocalContext.current
    val primaryVal = Color(colors.get(ColorType.SPECIAL_KEY_BACKGROUND))
    val onPrimaryVal = Color(colors.get(ColorType.ACTION_KEY_ICON))
    val surfaceVal = Color(colors.get(ColorType.KEY_BACKGROUND))
    val onSurfaceVal = Color(colors.get(ColorType.KEY_TEXT))

    val themeStyle = colors.themeStyle
    val isSquareStyle = themeStyle == KeyboardTheme.STYLE_MATERIAL || themeStyle == KeyboardTheme.STYLE_HOLO

    val shapes by derivedStateOf {
        val leadingShape = if (isSquareStyle) {
            RoundedCornerShape(8.dp)
        } else {
            RoundedCornerShape(topStart = CornerSize(50), bottomStart = CornerSize(50), topEnd = CornerSize(8.dp), bottomEnd = CornerSize(8.dp))
        }
        val middleShape = RoundedCornerShape(8.dp)
        val trailingShape = if (isSquareStyle) {
            RoundedCornerShape(8.dp)
        } else {
            RoundedCornerShape(topStart = CornerSize(8.dp), bottomStart = CornerSize(8.dp), topEnd = CornerSize(50), bottomEnd = CornerSize(50))
        }
        Triple(leadingShape, middleShape, trailingShape)
    }

    val colorScheme by derivedStateOf {
        if (isNight) {
            darkColorScheme(
                primary = primaryVal,
                onPrimary = onPrimaryVal,
                surface = surfaceVal,
                onSurface = onSurfaceVal,
                secondaryContainer = surfaceVal,
                onSecondaryContainer = onSurfaceVal,
                surfaceVariant = surfaceVal,
                onSurfaceVariant = onSurfaceVal,
                outline = Color.Transparent,
                outlineVariant = Color.Transparent
            )
        } else {
            lightColorScheme(
                primary = primaryVal,
                onPrimary = onPrimaryVal,
                surface = surfaceVal,
                onSurface = onSurfaceVal,
                secondaryContainer = surfaceVal,
                onSecondaryContainer = onSurfaceVal,
                surfaceVariant = surfaceVal,
                onSurfaceVariant = onSurfaceVal,
                outline = Color.Transparent,
                outlineVariant = Color.Transparent
            )
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                fontFamily = customFontFamily,
                fontSize = 14.sp
            )
        ) {
            ButtonGroup(
                overflowIndicator = {},
                expandedRatio = 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val tabs = listOf(
                    Triple(KlipyHistoryDao.TYPE_GIF, stringResource(R.string.tab_gifs), R.drawable.ic_tab_gif),
                    Triple(KlipyHistoryDao.TYPE_STICKER, stringResource(R.string.tab_stickers), R.drawable.ic_tab_stickers)
                )
                tabs.forEachIndexed { index, (tabType, displayText, iconRes) ->
                    val isSelected = selectedTab == tabType
                    customItem(
                        buttonGroupContent = {
                            val buttonShapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(
                                    shape = shapes.first,
                                    pressedShape = RoundedCornerShape(4.dp),
                                    checkedShape = RoundedCornerShape(percent = 50)
                                )
                                tabs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes(
                                    shape = shapes.third,
                                    pressedShape = RoundedCornerShape(4.dp),
                                    checkedShape = RoundedCornerShape(percent = 50)
                                )
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes(
                                    shape = shapes.second,
                                    pressedShape = RoundedCornerShape(4.dp),
                                    checkedShape = RoundedCornerShape(percent = 50)
                                )
                            }
                            ToggleButton(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        onTabSelected(tabType)
                                    }
                                },
                                shapes = buttonShapes,
                                colors = ToggleButtonDefaults.toggleButtonColors(
                                    containerColor = surfaceVal,
                                    contentColor = onSurfaceVal,
                                    checkedContainerColor = primaryVal,
                                    checkedContentColor = onPrimaryVal
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(displayText)
                                }
                            }
                        },
                        menuContent = { _ -> }
                    )
                }
            }
        }
    }
}