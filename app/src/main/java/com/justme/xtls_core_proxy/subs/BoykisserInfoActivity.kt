package com.justme.xtls_core_proxy.subs

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
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
                BoykisserInfoScreen(
                    onBack = { finish() },
                    onSubmitted = { finish() }
                )
            }
        }
    }

    companion object {
        const val BOT_URL = "https://t.me/boykisser_vpn_bot?start=FromApp"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoykisserInfoScreen(
    onBack: () -> Unit,
    onSubmitted: () -> Unit
) {
    val context = LocalContext.current
    // Default to showing arrows when the window hasn't measured yet (heightPx == 0):
    // on a tall screen this avoids a one-frame flicker; on a short screen the
    // measurement lands before the user can perceive anything.
    val showArrows = with(LocalDensity.current) {
        val heightPx = LocalWindowInfo.current.containerSize.height
        heightPx == 0 || heightPx.toDp() >= 800.dp
    }

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
        // Overflow-only scroll. BoxWithConstraints yields the usable viewport height
        // (after the scaffold insets and imePadding for the keyboard). The Column is
        // verticalScroll-wrapped but pinned to a *minimum* of one viewport tall
        // (heightIn min = maxHeight): when the roadmap fits, Arrangement.SpaceBetween
        // distributes the slack exactly as before (evenly spread, bottom group anchored
        // 15.dp up) and there is nothing to scroll. When it does not fit — small screen,
        // max font scale, or the keyboard raised — the content grows past the viewport,
        // SpaceBetween runs out of slack, and the scroll lets the user reach the paste
        // field / "Let's go!" button. OutlinedTextField brings itself into view on focus,
        // so tapping the field auto-scrolls it above the keyboard.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                RoadmapStep(number = 1, side = HorizontalSide.Start) {
                    Text(
                        text = stringResource(R.string.boykisser_step1_intro),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (showArrows) {
                    ArrowConnector(fromSide = HorizontalSide.Start, toSide = HorizontalSide.Start)
                }

                RoadmapStep(number = 2, side = HorizontalSide.Start) {
                    Text(
                        text = stringResource(R.string.boykisser_step2_label),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = { openUrl(BoykisserInfoActivity.BOT_URL) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BoykisserMagenta,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.boykisser_step2_button))
                    }
                }
                if (showArrows) {
                    ArrowConnector(fromSide = HorizontalSide.Start, toSide = HorizontalSide.End)
                }

                RoadmapStep(number = 3, side = HorizontalSide.End) {
                    Text(
                        text = stringResource(R.string.boykisser_step3_intro),
                        style = MaterialTheme.typography.titleMedium
                    )
                    BotMessageMock()
                    Text(
                        text = stringResource(R.string.boykisser_step3_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showArrows) {
                    ArrowConnector(fromSide = HorizontalSide.End, toSide = HorizontalSide.Center)
                }

                // Group Step 4 (label) and the PasteAndSubmit block with a fixed 15.dp gap, so
                // Arrangement.SpaceBetween treats them as a single bottom-anchored unit instead
                // of inserting a stretchy gap (which was reading like a missing-arrow slot).
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    RoadmapStep(number = 4, side = HorizontalSide.Center) {
                        Text(
                            text = stringResource(R.string.boykisser_step4_label),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    PasteAndSubmit(
                        onApproved = { approved ->
                            context.startActivity(
                                Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    putExtra(MainActivity.EXTRA_ADD_BOYKISSER_SUB, approved)
                                }
                            )
                            onSubmitted()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
            // Trim the half-leading above/below the glyph so Alignment.Center centers
            // the actual digit rather than a line-box padded by ~4 sp on each side.
            // Without this, glyphs whose visual bounds aren't symmetric within the
            // line box (notably "4") drift visibly off-center inside the 36 dp circle.
            Text(
                text = number.toString(),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                ),
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
    val strokeWidthPx = with(density) { (bodyLargeFontSize.toDp() * 0.14f).toPx() }
    val headLenPx = with(density) { (bodyLargeFontSize.toDp() * 0.55f).toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        val w = size.width
        val h = size.height
        val xFrom = w * fromSide.xFraction
        val xTo = w * toSide.xFraction
        val yFrom = 0f
        val yTo = h - headLenPx * 0.3f
        val midY = h / 2f
        // Asymmetric cubic bezier:
        //   cp1 at (xFrom, midY)         -> tangent at start is purely vertical (arrows
        //                                   tail out of the previous step going straight
        //                                   down, matching the hand-drawn sketch).
        //   cp2 at end - 0.4 * chord     -> tangent at end is proportional to the chord
        //                                   (xTo - xFrom, yTo - yFrom). This means the
        //                                   bezier's natural end-tangent and the chord
        //                                   point in the same direction, so the chevron
        //                                   visually merges with the curve's final
        //                                   approach instead of looking detached.
        // For same-side connectors (xFrom == xTo) cp1 and cp2 share x with start/end,
        // so the curve collapses to a clean straight vertical line.
        val tailAlpha = 0.4f
        val cp2x = xTo - tailAlpha * (xTo - xFrom)
        val cp2y = yTo - tailAlpha * (yTo - yFrom)
        val path = Path().apply {
            moveTo(xFrom, yFrom)
            cubicTo(xFrom, midY, cp2x, cp2y, xTo, yTo)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
        )

        // Arrowhead chevron uses the bezier's actual tangent at t=1, which is
        // (end - cp2) = tailAlpha * chord. Same direction as chord, but explicitly
        // derived from the curve so future cp2 tweaks keep the chevron in sync.
        val tx = xTo - cp2x
        val ty = yTo - cp2y
        val tLen = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
        val ux = tx / tLen
        val uy = ty / tLen
        // Perpendicular to (ux, uy) is (-uy, ux).
        val spread = headLenPx * 0.4f
        val baseX = xTo - ux * headLenPx
        val baseY = yTo - uy * headLenPx
        drawLine(
            color = color,
            start = Offset(baseX - uy * spread, baseY + ux * spread),
            end = Offset(xTo, yTo),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(baseX + uy * spread, baseY - ux * spread),
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

    // Conditionally null so the supportingText slot reserves NO height when there's
    // no error. Combined with spacedBy(15.dp) below this makes the gap between the
    // text field and "Let's go!" exactly 15 dp at rest. When an error is shown the
    // gap grows by the supporting-text row, which is acceptable — the user is
    // actively reading the error in that case.
    val errorSupportingText: (@Composable () -> Unit)? = if (showError) {
        { Text(stringResource(R.string.boykisser_error_invalid_domain)) }
    } else {
        null
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(15.dp)
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
            supportingText = errorSupportingText,
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
