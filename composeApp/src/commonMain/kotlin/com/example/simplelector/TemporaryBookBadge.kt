package com.example.simplelector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TemporaryBookBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val strings = rememberAppStrings()
    val horizontalPadding = if (compact) 6.dp else 8.dp
    val verticalPadding = if (compact) 2.dp else 3.dp
    val fontSize = if (compact) 10.sp else 11.sp
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.temporaryBookBadge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
