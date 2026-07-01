package com.justme.xtls_core_proxy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.justme.xtls_core_proxy.db.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileActionsDialog(
    profile: Profile,
    isConnectedProfile: Boolean,
    canConnect: Boolean,
    shareLink: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPingTest: () -> Unit,
    onEdit: () -> Unit,
    onCopyLink: () -> Unit,
    onCopyJson: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (isConnectedProfile) {
                    ProfileActionRow(
                        icon = painterResource(R.drawable.ic_power_off),
                        label = stringResource(R.string.main_button_disconnect),
                        onClick = onDisconnect
                    )
                } else {
                    ProfileActionRow(
                        icon = rememberVectorPainter(Icons.Filled.PlayArrow),
                        label = stringResource(R.string.main_button_connect),
                        enabled = canConnect,
                        onClick = onConnect
                    )
                }
                ProfileActionRow(
                    icon = painterResource(R.drawable.ic_speedometer),
                    label = stringResource(R.string.ping_action_test),
                    onClick = onPingTest
                )
                ProfileActionRow(
                    icon = rememberVectorPainter(Icons.Filled.Edit),
                    label = stringResource(R.string.main_action_edit),
                    onClick = onEdit
                )
                if (shareLink != null) {
                    ProfileActionRow(
                        icon = painterResource(R.drawable.ic_link),
                        label = stringResource(R.string.main_action_copy_link),
                        onClick = onCopyLink
                    )
                }
                ProfileActionRow(
                    icon = painterResource(R.drawable.ic_content_copy),
                    label = stringResource(R.string.main_action_copy_json),
                    onClick = onCopyJson
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                ProfileActionRow(
                    icon = rememberVectorPainter(Icons.Filled.Delete),
                    label = stringResource(R.string.main_action_delete),
                    color = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun ProfileActionRow(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val effectiveColor = if (enabled) color else color.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = icon, contentDescription = null, tint = effectiveColor)
        Spacer(modifier = Modifier.width(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = effectiveColor
        )
    }
}
