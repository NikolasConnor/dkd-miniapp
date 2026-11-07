package ru.dkd.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DkdPageHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    symbol: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                eyebrow.uppercase(), color = Pink, fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp
            )
            Text(
                title, color = White, fontSize = 29.sp,
                fontWeight = FontWeight.Bold, lineHeight = 34.sp
            )
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(58.dp).background(
                Brush.linearGradient(listOf(Color(0xFF334B91), Color(0xFF654C9C), Color(0xFF3B315F))),
                RoundedCornerShape(19.dp)
            ).border(1.dp, Color(0x665E8BFF), RoundedCornerShape(19.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, color = Color(0xFFF2D5E7), fontSize = 27.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun DkdSectionHeading(title: String, count: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        if (count != null) {
            Surface(color = Color(0x338B7CFF), shape = RoundedCornerShape(30.dp)) {
                Text(count, color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
internal fun DkdHeroPanel(
    eyebrow: String,
    title: String,
    description: String,
    symbol: String,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Surface(
        color = Panel2, shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().border(1.dp, Color(0x665E8BFF), RoundedCornerShape(24.dp))
    ) {
        Box(Modifier.background(
            Brush.linearGradient(listOf(Color(0xFF293B70), Color(0xFF312B61), Color(0xFF202744)))
        )) {
            Column(Modifier.fillMaxWidth().padding(19.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(eyebrow.uppercase(), color = Color(0xFFD6D8FF), fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp,
                        modifier = Modifier.weight(1f))
                    Text(symbol, color = Color(0xFFF0CBE0), fontSize = 25.sp)
                }
                Text(title, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
                Text(description, color = Color(0xFFD1D6F2), fontSize = 12.sp, lineHeight = 17.sp)
                content?.invoke(this)
            }
        }
    }
}

@Composable
internal fun DkdMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Purple
) {
    Surface(
        color = CardColor, shape = RoundedCornerShape(18.dp),
        modifier = modifier.border(1.dp, LineColor, RoundedCornerShape(18.dp))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label.uppercase(), color = Muted, fontSize = 8.sp,
                fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}
