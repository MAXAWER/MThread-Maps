package kz.smartcity.walk.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kz.smartcity.walk.data.api.PartnerLink
import kz.smartcity.walk.data.api.Sponsored
import kz.smartcity.walk.data.api.TaxiPartner
import kz.smartcity.walk.data.api.TaxiPrice
import kz.smartcity.walk.data.api.TaxiResponse
import kz.smartcity.walk.ui.components.formatDistance

private fun money(p: TaxiPrice): String = p.priceText ?: when {
    p.priceMin > 0 && p.priceMax > p.priceMin -> "${p.priceMin}–${p.priceMax} ₸"
    p.priceMax > 0 -> "${p.priceMax} ₸"
    else -> "по счётчику"
}

/**
 * Такси: маршрут на карте уже показан выше, здесь — цены по тарифам у партнёров и кнопка, которая открывает
 * приложение такси сразу с точками «откуда» и «куда».
 */
@Composable
fun TaxiPanel(taxi: TaxiResponse?, loading: Boolean, onOrder: (TaxiPartner, TaxiPrice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalTaxi, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Заказать такси", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            taxi?.let { Text("${it.durationS / 60} мин · ${formatDistance(it.distanceM)}", style = MaterialTheme.typography.labelLarge) }
        }
        if (loading && taxi == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        taxi?.partners?.forEach { partner ->
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(partner.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (partner.prefill) "точки маршрута подставятся" else "адрес скопируем", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    partner.prices.forEachIndexed { i, price ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(price.label ?: "Тариф", style = MaterialTheme.typography.bodyLarge)
                                Text(listOfNotNull(money(price), price.waitMin?.let { "подача ~$it мин" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            if (i == 0) Button(onClick = { onOrder(partner, price) }) { Text("Заказать") }
                            else FilledTonalButton(onClick = { onOrder(partner, price) }) { Text("Заказать") }
                        }
                    }
                }
            }
        }
        taxi?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Кнопки партнёров в карточке места: доставка, билеты, бронь — на страницы, найденные ИИ. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartnerButtons(links: List<PartnerLink>, onOpen: (PartnerLink) -> Unit) {
    if (links.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        links.forEach { link ->
            FilledTonalButton(onClick = { onOpen(link) }) {
                Icon(when (link.kind) {
                    "delivery" -> Icons.Filled.DeliveryDining
                    "tickets" -> Icons.Filled.ConfirmationNumber
                    "booking" -> Icons.Filled.EventSeat
                    else -> Icons.Filled.OpenInNew
                }, null, Modifier.size(18.dp))
                Text(" ${link.label}")
            }
        }
    }
}

/** Предложение партнёра: всегда с пометкой («Реклама» / «Партнёр»), промокод копируется нажатием. */
@Composable
fun SponsoredOffer(s: Sponsored, onUse: () -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalOffer, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                s.offer?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                s.promoCode?.let { Text("Промокод: $it", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
            }
            s.promoCode?.let { code ->
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); onUse() }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Скопировать промокод")
                }
            }
        }
    }
}

/** Маленькая плашка «Реклама» поверх обложки. */
@Composable
fun AdTag(label: String = "Реклама", modifier: Modifier = Modifier) {
    Box(modifier) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}
