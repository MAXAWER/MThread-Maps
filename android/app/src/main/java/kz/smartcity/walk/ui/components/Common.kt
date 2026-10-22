package kz.smartcity.walk.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ElectricScooter
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.IceSkating
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush
import kz.smartcity.walk.data.Categories
import kz.smartcity.walk.ui.fx.BubbleShapes
import kz.smartcity.walk.ui.fx.Depth
import kz.smartcity.walk.ui.fx.Fills
import kz.smartcity.walk.ui.fx.bubble
import kz.smartcity.walk.ui.fx.darker
import kz.smartcity.walk.ui.fx.lighter

@Suppress("DEPRECATION")
fun categoryIcon(category: String?): ImageVector = when (category) {
    "coffee", "cafe_24h" -> Icons.Filled.LocalCafe
    "cafe", "restaurant", "canteen", "chaikhana" -> Icons.Filled.Restaurant
    "fastfood" -> Icons.Filled.Fastfood
    "bakery", "confectionery" -> Icons.Filled.BakeryDining
    "bar", "pub", "lounge", "nightclub" -> Icons.Filled.LocalBar
    "museum", "gallery", "interactive_museum" -> Icons.Filled.Museum
    "theatre", "concert_hall" -> Icons.Filled.TheaterComedy
    "cinema" -> Icons.Filled.Movie
    "library" -> Icons.Filled.LocalLibrary
    "monument", "architecture" -> Icons.Filled.AccountBalance
    "park", "square", "botanical_garden" -> Icons.Filled.Park
    "viewpoint" -> Icons.Filled.Landscape
    "embankment" -> Icons.Filled.Waves
    "pedestrian_street" -> Icons.Filled.DirectionsWalk
    "playground", "kids_center" -> Icons.Filled.ChildCare
    "zoo", "oceanarium" -> Icons.Filled.Pets
    "mall" -> Icons.Filled.LocalMall
    "supermarket", "market" -> Icons.Filled.ShoppingCart
    "bookstore" -> Icons.Filled.MenuBook
    "souvenirs" -> Icons.Filled.CardGiftcard
    "bowling", "billiards", "quest", "vr_club" -> Icons.Filled.SportsEsports
    "karaoke" -> Icons.Filled.Mic
    "attractions" -> Icons.Filled.Attractions
    "skating_rink" -> Icons.Filled.IceSkating
    "fitness" -> Icons.Filled.FitnessCenter
    "pool" -> Icons.Filled.Pool
    "climbing" -> Icons.Filled.Terrain
    "bike_rental" -> Icons.Filled.PedalBike
    "pharmacy" -> Icons.Filled.LocalPharmacy
    "atm" -> Icons.Filled.LocalAtm
    "mobile_store" -> Icons.Filled.PhoneAndroid
    "flowers" -> Icons.Filled.LocalFlorist
    "public_toilet" -> Icons.Filled.Wc
    else -> Icons.Filled.Place
}

@Suppress("DEPRECATION")
fun transportIcon(t: String?): ImageVector = when (t) {
    "bicycle" -> Icons.Filled.PedalBike
    "scooter" -> Icons.Filled.ElectricScooter
    "driving" -> Icons.Filled.DirectionsCar
    "taxi" -> Icons.Filled.LocalTaxi
    "public_transport" -> Icons.Filled.DirectionsBus
    else -> Icons.Filled.DirectionsWalk
}

val TRANSPORTS = listOf("walking", "bicycle", "scooter", "driving", "taxi", "public_transport")

fun transportLabel(t: String?): String = when (t) {
    "bicycle" -> "Велосипед"; "scooter" -> "Самокат"; "driving" -> "Авто"; "taxi" -> "Такси"
    "public_transport" -> "Транспорт"; else -> "Пешком"
}

/** Подписи признаков из фильтров Jev: «Wi-Fi», «Розетки». */
fun attrLabel(key: String): String = when (key) {
    "indoor" -> "Крытое место"; "wifi" -> "Wi-Fi"; "sockets" -> "Розетки"; "card_payment" -> "Оплата картой"
    "parking" -> "Парковка"; "terrace" -> "Веранда"; "accessible" -> "Доступная среда"; "pets" -> "С питомцами"
    "halal" -> "Халяль"; "vegetarian" -> "Вегетарианское"; "toilet" -> "Туалет"; "exclude_chains" -> "Не сеть"
    else -> key
}

/** Группа категории → цвет иллюстрации. */
fun groupColor(group: String?): Color = when (group) {
    "food" -> Color(0xFFB8621B); "culture" -> Color(0xFF6A4C93); "walks" -> Color(0xFF2E7D4F)
    "kids" -> Color(0xFFC2185B); "shopping" -> Color(0xFF0E5E73); "fun" -> Color(0xFF7B5EA7)
    "sport" -> Color(0xFF1565C0); "night" -> Color(0xFF37474F); else -> Color(0xFF546E7A)
}

/** Значок категории — объёмный пузырь цвета группы с бликом и цветной тенью. */
@Composable
fun CategoryBadge(category: String?, size: Dp = 40.dp) {
    val color = groupColor(Categories.group(category))
    Box(
        modifier = Modifier.size(size).bubble(CircleShape,
            Brush.radialGradient(listOf(color.lighter(0.35f), color, color.darker(0.15f))), Depth.LOW, glow = color, highlight = 0.35f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(category), contentDescription = Categories.name(category), tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun OpenStateText(state: String, until: String?, modifier: Modifier = Modifier) {
    val (text, color) = when (state) {
        "open" -> (if (until == "круглосуточно") "Круглосуточно" else if (until != null) "Открыто до $until" else "Открыто") to Color(0xFF2E7D4F)
        "closed" -> "Закрыто" to MaterialTheme.colorScheme.error
        else -> "Режим работы: нет данных" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

@Composable
fun RatingText(rating: Double?, reviews: Int?) {
    if (rating == null) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics {
        contentDescription = "Рейтинг $rating, отзывов ${reviews ?: 0}"
    }) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFE0A526), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(2.dp))
        Text(String.format(java.util.Locale.US, "%.1f", rating).replace('.', ','), style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold)
        if (reviews != null) {
            Text(" · ${reviews} ${plural(reviews, "отзыв", "отзыва", "отзывов")}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

/** Метка источника: «Данные 2ГИС», «Идея модели» или «Демо-данные». */
@Composable
fun SourceBadge(source: String, dataSource: String) {
    val (text, color) = when {
        source == "model_idea" -> "Идея модели" to MaterialTheme.colorScheme.secondary
        dataSource == "demo" -> "Демо-данные" to MaterialTheme.colorScheme.error
        else -> "Данные 2ГИС" to MaterialTheme.colorScheme.primary
    }
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.bubble(BubbleShapes.Pill, Fills.tint(color), Depth.FLOOR, highlight = 0.15f, rim = 0.3f)
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Заглушка-скелетон карточки вместо спиннера. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Surface(shape = BubbleShapes.L, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Загрузка" }) {
        Column(Modifier.padding(16.dp).alpha(a), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(base))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(160.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(base))
                    Box(Modifier.width(100.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(base))
                }
            }
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(base))
            Box(Modifier.fillMaxWidth(0.7f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(base))
        }
    }
}

fun formatDistance(m: Int?): String = when {
    m == null -> ""
    m >= 1000 -> String.format(java.util.Locale.US, "%.1f км", m / 1000.0).replace('.', ',')
    else -> "$m м"
}
