package com.pokeagenda.http

import com.pokeagenda.domain.Pokemon
import com.pokeagenda.domain.GameVersion
import kotlinx.serialization.Serializable

@Serializable
data class PokemonResponse(
    val id: Int,
    val name: String,
    val displayName: String,
)

@Serializable
data class GameResponse(
    val name: String,
    val displayName: String,
)

@Serializable
data class GameListResponse(
    val count: Int,
    val games: List<GameResponse>,
)

@Serializable
data class PokemonListResponse(
    val game: String? = null,
    val count: Int,
    val pokemon: List<PokemonResponse>,
)

@Serializable
data class PokemonMutationRequest(
    val game: String = "",
    val pokemon: String = "",
)

@Serializable
data class PokemonMutationResponse(
    val status: String,
    val game: String,
    val pokemon: PokemonResponse? = null,
    val message: String,
)

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
)

internal fun Pokemon.toResponse() = PokemonResponse(
    id = id,
    name = name,
    displayName = displayName,
)

internal fun GameVersion.toResponse() = GameResponse(
    name = name,
    displayName = displayName,
)
