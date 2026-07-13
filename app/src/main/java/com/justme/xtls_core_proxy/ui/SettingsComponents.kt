package com.justme.xtls_core_proxy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailingValue: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { if (enabled && onClick != null) it.clickable(onClick = onClick) else it }
        .padding(vertical = 12.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = contentColor,
                modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = contentColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailingValue != null) {
            Text(trailingValue, style = MaterialTheme.typography.bodyMedium, color = contentColor)
            Spacer(Modifier.width(4.dp))
        }
        if (enabled && onClick != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = contentColor)
        }
    }
}
