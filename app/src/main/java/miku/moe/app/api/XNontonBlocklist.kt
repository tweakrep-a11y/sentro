package miku.moe.app.api

object XNontonBlocklist {
    private val blockedIds = setOf(2, 308, 246)
    private val blockedNames = setOf(
        "info sangat penting!",
        "live action & asian movie",
        "anime movie sub indo"
    )

    @JvmStatic
    fun isBlocked(id: Int, name: String?): Boolean {
        if (id in blockedIds) return true
        val clean = name?.trim()?.lowercase().orEmpty()
        return clean.isNotEmpty() && clean in blockedNames
    }

    @JvmStatic
    fun isBlocked(item: ApiAnimePost): Boolean {
        return isBlocked(item.cid ?: item.categoryId ?: -1, item.categoryName)
    }
}
