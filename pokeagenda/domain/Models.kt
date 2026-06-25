package com.pokeagenda.domain

import kotlinx.serialization.Serializable

@Serializable
data class Pokemon(
    val id: Int,
    val name: String,
) {
    val displayName: String
        get() = name
            .split('-')
            .joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }
}

@Serializable
data class GameVersion(
    val name: String,
) {
    val displayName: String
        get() = name
            .split('-')
            .joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }
}

@Serializable
data class CatalogCache(
    val schemaVersion: Int = 2,
    val updatedAt: String,
    val pokemon: List<Pokemon>,
    val games: List<GameVersion>,
    val pokemonIdsByGame: Map<String, Set<Int>> = emptyMap(),
)

@Serializable
data class AppState(
    val schemaVersion: Int = 1,
    val selectedGame: String? = null,
    val capturedPokemonByGame: Map<String, Set<Int>> = emptyMap(),
)

enum class PokemonFilter {
    ALL,
    CAPTURED,
    MISSING,
}

data class Progress(
    val captured: Int,
    val total: Int,
) {
    val percentage: Double
        get() = if (total == 0) 0.0 else captured.toDouble() / total * 100.0
}
