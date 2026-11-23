package com.dot.doctor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dot.doctor.data.LabStatus
import com.dot.doctor.ui.theme.Dot

fun statusColor(s: LabStatus): Color = when (s) {
    LabStatus.GOOD -> Dot.Lime
    LabStatus.WATCH -> Dot.Amber
    LabStatus.BAD -> Dot.Coral
}

fun statusText(s: LabStatus): String = when (s) {
    LabStatus.GOOD -> "정상"
    LabStatus.WATCH -> "주의"
    LabStatus.BAD -> "벗어남"
}

@Composable
fun DotCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Dot.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Dot.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Dot.Lime, contentColor = Dot.OnLime),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = Dot.Text) }
}

@Composable
fun StatusDot(s: LabStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(12.dp).clip(CircleShape).background(statusColor(s)))
        Spacer(Modifier.width(6.dp))
        Text(statusText(s), style = MaterialTheme.typography.labelMedium, color = statusColor(s))
    }
}

/** 작은 추이 그래프: 점마다 상태 색, 정상 경계선은 점선 */
@Composable
fun Sparkline(values: List<Double>, colors: List<Color>, refLines: List<Double>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    Canvas(modifier.fillMaxWidth().height(84.dp)) {
        val all = values + refLines
        val lo = all.min()
        val hi = all.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = 10f
        fun x(i: Int) = pad + (size.width - 2 * pad) * i / (values.size - 1)
        fun y(v: Double) = (pad + (size.height - 2 * pad) * (1 - (v - lo) / span)).toFloat()
        refLines.forEach { r ->
            drawLine(Dot.Faint, Offset(0f, y(r)), Offset(size.width, y(r)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val path = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
        }
        drawPath(path, Dot.Muted, style = Stroke(width = 3f))
        values.forEachIndexed { i, v ->
            drawCircle(colors.getOrElse(i) { Dot.Lime }, radius = if (i == values.lastIndex) 9f else 6f, center = Offset(x(i), y(v)))
        }
    }
}

/** Claude 소견(마크다운 일부: ##, -, **굵게**)을 큰 글씨로 */
@Composable
fun MarkdownText(md: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.startsWith("#") -> {
                    val t = line.trimStart('#').trim()
                    Text(
                        t, style = MaterialTheme.typography.titleLarge,
                        color = if ("🚩" in t) Dot.Coral else Dot.Lime,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    Row {
                        Text("•  ", style = MaterialTheme.typography.bodyLarge, color = Dot.Lime)
                        Text(bold(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyLarge)
                    }
                else -> Text(bold(line), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun bold(s: String) = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, p ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Dot.Text)) { append(p) } else append(p)
    }
}
