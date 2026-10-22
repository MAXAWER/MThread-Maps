package kz.smartcity.walk.data

/** Копия справочника категорий сервера (categories.yaml) — для подписей, групп и разнообразия колоды. */
data class CategoryInfo(
    val id: String,
    val nameRu: String,
    val group: String,
    val indoor: Boolean,
    val kids: Boolean,
    val night: Boolean,
)

object Categories {
    val groupNames = mapOf(
        "food" to "Еда", "culture" to "Культура", "walks" to "Прогулки", "kids" to "Дети", "shopping" to "Шопинг",
        "fun" to "Развлечения", "sport" to "Спорт", "night" to "Ночь", "services" to "Сервисы",
    )

    private fun c(id: String, name: String, group: String, indoor: Boolean, kids: Boolean, night: Boolean = false) =
        CategoryInfo(id, name, group, indoor, kids, night)

    val all: Map<String, CategoryInfo> = listOf(
        c("coffee", "Кофейня", "food", true, false), c("cafe", "Кафе", "food", true, true),
        c("restaurant", "Ресторан", "food", true, true), c("fastfood", "Фастфуд", "food", true, true),
        c("canteen", "Столовая", "food", true, true), c("bakery", "Пекарня", "food", true, true),
        c("confectionery", "Кондитерская", "food", true, true), c("chaikhana", "Чайхана", "food", true, true),
        c("bar", "Бар", "food", true, false, true), c("pub", "Паб", "food", true, false, true),
        c("museum", "Музей", "culture", true, true), c("gallery", "Галерея", "culture", true, true),
        c("theatre", "Театр", "culture", true, true, true), c("cinema", "Кинотеатр", "culture", true, true, true),
        c("concert_hall", "Концертный зал", "culture", true, true, true), c("library", "Библиотека", "culture", true, true),
        c("monument", "Памятник", "culture", false, true), c("architecture", "Архитектурный объект", "culture", false, true),
        c("park", "Парк", "walks", false, true), c("square", "Сквер", "walks", false, true),
        c("embankment", "Набережная", "walks", false, true), c("botanical_garden", "Ботанический сад", "walks", false, true),
        c("viewpoint", "Смотровая площадка", "walks", false, true), c("pedestrian_street", "Пешеходная улица", "walks", false, true),
        c("playground", "Детская площадка", "kids", false, true), c("kids_center", "Детский развлекательный центр", "kids", true, true),
        c("zoo", "Зоопарк", "kids", false, true), c("oceanarium", "Океанариум", "kids", true, true),
        c("interactive_museum", "Интерактивный музей", "kids", true, true),
        c("mall", "Торговый центр", "shopping", true, true), c("supermarket", "Супермаркет", "shopping", true, true),
        c("bookstore", "Книжный магазин", "shopping", true, true), c("souvenirs", "Сувениры", "shopping", true, true),
        c("market", "Рынок", "shopping", true, true),
        c("bowling", "Боулинг", "fun", true, true, true), c("billiards", "Бильярд", "fun", true, false, true),
        c("quest", "Квест", "fun", true, true), c("karaoke", "Караоке", "fun", true, false, true),
        c("vr_club", "VR-клуб", "fun", true, true), c("attractions", "Аттракционы", "fun", false, true),
        c("skating_rink", "Каток", "fun", false, true),
        c("fitness", "Фитнес-клуб", "sport", true, false), c("pool", "Бассейн", "sport", true, true),
        c("climbing", "Скалодром", "sport", true, true), c("bike_rental", "Прокат велосипедов и самокатов", "sport", false, true),
        c("nightclub", "Ночной клуб", "night", true, false, true), c("lounge", "Лаунж", "night", true, false, true),
        c("cafe_24h", "Круглосуточное кафе", "night", true, true, true),
        c("pharmacy", "Аптека", "services", true, true), c("atm", "Банкомат", "services", true, true, true),
        c("mobile_store", "Салон связи", "services", true, true), c("flowers", "Цветы", "services", true, true),
        c("public_toilet", "Общественный туалет", "services", true, true),
    ).associateBy { it.id }

    /** Категории, которые не предлагаются в колоде «Для вас» как развлечение. */
    val notForDeck = setOf("pharmacy", "atm", "mobile_store", "public_toilet", "supermarket", "flowers")

    fun name(id: String?): String = id?.let { all[it]?.nameRu } ?: "Место"
    fun group(id: String?): String? = id?.let { all[it]?.group }

    /** Склонение для портрета: «кофейни», «парки». */
    fun plural(id: String): String = when (id) {
        "coffee" -> "кофейни"; "cafe" -> "кафе"; "restaurant" -> "рестораны"; "park" -> "парки"
        "museum" -> "музеи"; "mall" -> "торговые центры"; "cinema" -> "кино"; "bar" -> "бары"
        "bakery" -> "пекарни"; "viewpoint" -> "смотровые площадки"; "embankment" -> "набережные"
        "gallery" -> "галереи"; "theatre" -> "театры"; "library" -> "библиотеки"
        else -> name(id).lowercase()
    }
}
