package kz.smartcity.walk.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/** Иконка быстрого маршрута по ключу (тот же набор, что предлагает ИИ). */
fun quickIcon(key: String?): ImageVector = when (key) {
    "home" -> Icons.Filled.Home
    "work" -> Icons.Filled.Work
    "family" -> Icons.Filled.FamilyRestroom
    "friend" -> Icons.Filled.Person
    "school" -> Icons.Filled.School
    "sport" -> Icons.Filled.FitnessCenter
    "food" -> Icons.Filled.Restaurant
    "shop" -> Icons.Filled.ShoppingBag
    "heart" -> Icons.Filled.Favorite
    else -> Icons.Filled.Star
}

val QUICK_ICON_LABELS = mapOf(
    "home" to "Дом", "work" to "Работа", "family" to "Семья", "friend" to "Друг", "school" to "Учёба",
    "sport" to "Спорт", "food" to "Еда", "shop" to "Покупки", "star" to "Избранное", "heart" to "Любимое",
)

/** Иконка подсказки запроса: история, время суток, категория интереса. */
fun hintIcon(key: String): ImageVector = when {
    key == "history" -> Icons.Filled.History
    key == "coffee" -> Icons.Filled.LocalCafe
    key == "food" -> Icons.Filled.Restaurant
    key == "night" -> Icons.Filled.Nightlife
    key == "walk" -> Icons.AutoMirrored.Filled.DirectionsWalk
    key == "bus" -> Icons.Filled.DirectionsBus
    key.startsWith("cat:") -> categoryIcon(key.removePrefix("cat:"))
    else -> Icons.Filled.Star
}
