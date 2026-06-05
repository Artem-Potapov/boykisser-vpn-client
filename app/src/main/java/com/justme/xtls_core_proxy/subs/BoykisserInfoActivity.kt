package com.justme.xtls_core_proxy.subs

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.LocalizedComponentActivity
import com.justme.xtls_core_proxy.ui.theme.BoykisserMagenta
import com.justme.xtls_core_proxy.ui.theme.XTLS_CORE_PROXYTheme

class BoykisserInfoActivity : LocalizedComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XTLS_CORE_PROXYTheme {
                BoykisserInfoScreen(onBack = { finish() })
            }
        }
    }

    companion object {
        const val SITE_URL = "https://boykiss3r.site"
        const val BOT_URL = "https://t.me/boykisser_vpn_bot"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoykisserInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.isSuccess
        if (!opened) {
            Toast.makeText(context, url, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.boykisser_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.subs_cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.boykisser_info_placeholder),
                style = MaterialTheme.typography.headlineLarge,
                color = BoykisserMagenta,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = { openUrl(BoykisserInfoActivity.SITE_URL) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BoykisserMagenta,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.boykisser_info_open_site))
            }
            Button(
                onClick = { openUrl(BoykisserInfoActivity.BOT_URL) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BoykisserMagenta,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.boykisser_info_open_bot))
            }
        }
    }
}

private enum class HorizontalSide(val xFraction: Float) {
    Start(0.18f),
    Center(0.50f),
    End(0.82f)
}

@Composable
private fun StepCircle(number: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(36.dp),
        shape = CircleShape,
        color = BoykisserMagenta
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number.toString(),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ArrowConnector(
    fromSide: HorizontalSide,
    toSide: HorizontalSide,
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.outline
    val density = LocalDensity.current
    val bodyLargeFontSize = MaterialTheme.typography.bodyLarge.fontSize
    val strokeWidthPx = with(density) { (bodyLargeFontSize.toDp() * 0.18f).toPx() }
    val headLenPx = with(density) { (bodyLargeFontSize.toDp() * 0.8f).toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        val w = size.width
        val h = size.height
        val xFrom = w * fromSide.xFraction
        val xTo = w * toSide.xFraction
        val yFrom = 0f
        val yTo = h - headLenPx * 0.5f

        // S-curve control points: lift each toward the opposite side for a graceful sweep.
        // When both endpoints share the same side the standard formula collapses xFrom==xTo, giving
        // straight-line control points. Add a lateral bulge so same-side connectors remain curved.
        val sameSide = fromSide == toSide
        val bulge = if (sameSide) w * 0.18f else 0f
        val cp1 = Offset(xFrom + (xTo - xFrom) * 0.1f + bulge, h * 0.45f)
        val cp2 = Offset(xTo - (xTo - xFrom) * 0.1f + bulge, h * 0.55f)

        val path = Path().apply {
            moveTo(xFrom, yFrom)
            cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, xTo, yTo)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
        )

        // Arrowhead: chevron whose direction matches the tangent at the end of the curve
        // (approximated by the vector from cp2 to the end point).
        val tx = xTo - cp2.x
        val ty = yTo - cp2.y
        val tLen = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
        val ux = tx / tLen
        val uy = ty / tLen
        val perpX = -uy
        val perpY = ux
        val spread = headLenPx * 0.35f
        val baseX = xTo - ux * headLenPx
        val baseY = yTo - uy * headLenPx
        drawLine(
            color = color,
            start = Offset(baseX + perpX * spread, baseY + perpY * spread),
            end = Offset(xTo, yTo),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(baseX - perpX * spread, baseY - perpY * spread),
            end = Offset(xTo, yTo),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun BotMessageMock(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 280.dp),
        color = Color(0xFF1F2C33),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Конфигурация успешно создана!",
                color = Color(0xFFE1E1E1),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Ваш ключ для подключения:",
                color = Color(0xFFE1E1E1),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "https://somenewsteps.space/subaru/xy0ZoGcCsQngYlWr-L5f",
                color = Color(0xFF6AB7F9),
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "17:58",
                color = Color(0xFF7A8B95),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun RoadmapStep(
    number: Int,
    side: HorizontalSide,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val rowAlignment = when (side) {
        HorizontalSide.Start -> Alignment.TopStart
        HorizontalSide.Center -> Alignment.TopCenter
        HorizontalSide.End -> Alignment.TopEnd
    }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .align(rowAlignment)
                .fillMaxWidth(0.92f)
                .widthIn(max = 360.dp),
            verticalAlignment = Alignment.Top
        ) {
            StepCircle(number = number)
            Spacer(Modifier.width(12.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun PasteAndSubmit(
    onApproved: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    val submit: () -> Unit = {
        val approved = BoykisserCallback.validate(text)
        if (approved == null) {
            showError = true
        } else {
            onApproved(approved)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                showError = false
            },
            singleLine = true,
            isError = showError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            placeholder = { Text(stringResource(R.string.boykisser_step4_field_hint)) },
            supportingText = {
                if (showError) {
                    Text(stringResource(R.string.boykisser_error_invalid_domain))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = submit,
            enabled = text.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = BoykisserMagenta,
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.boykisser_step4_submit))
        }
    }
}
