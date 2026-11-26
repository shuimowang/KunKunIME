package com.windinput.ime.settings

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.windinput.ime.R

/** 应用内品牌图使用原彩色图，不随设置行图标的色调一起染色。 */
@Composable
internal fun KunKunLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.kunkun_logo),
        contentDescription = null,
        modifier = modifier,
    )
}
