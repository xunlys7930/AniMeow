package com.animeow.app.ui.theme

/** Curated choices, not a ranking inferred from users' private backups. */
enum class AccentPalettePreset(
    val label: String,
    val colors: List<Long>,
    val mode: AccentMode,
    val angleDegrees: Float = 35f,
    val durationMillis: Int = 12_000,
) {
    MINT_SKY("薄荷晴空", listOf(0xFF429C8BL, 0xFF668FE8L), AccentMode.GRADIENT, 28f),
    NIGHT_FLOW("暮色流光", listOf(0xFF96B8FFL, 0xFFC498E8L, 0xFF65C8C3L), AccentMode.FLOW, 145f),
    SUNSET("落日余晖", listOf(0xFFE49A69L, 0xFFC36998L), AccentMode.GRADIENT, 52f),
    BLUE("晴空蓝", listOf(0xFF527AD8L), AccentMode.SOLID),
    GREEN("青竹绿", listOf(0xFF397562L), AccentMode.SOLID),
    WARM("暖纸棕", listOf(0xFF976048L), AccentMode.SOLID);

    fun apply(current: AppearanceSettings): AppearanceSettings = current.copy(
        accentColor = colors.first(),
        accentGradient = AccentGradientSettings(mode, colors.drop(1), angleDegrees, durationMillis),
        useDynamicColor = false,
        randomAccentOnLaunch = false,
    )
}
