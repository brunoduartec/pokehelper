package com.pokeagenda.service

import com.pokeagenda.cache.AppStateStore
import com.pokeagenda.domain.AppState
import com.pokeagenda.domain.CatalogCache
import com.pokeagenda.domain.GameVersion
import com.pokeagenda.domain.Pokemon
import com.pokeagenda.domain.PokemonFilter
import com.pokeagenda.domain.Progress

class PokeAgendaService(
    private val catalogRepository: CatalogRepository,
    private val stateStore: AppStateStore,
) {
    @Volatile
    private lateinit var catalog: CatalogCache

    @Volatile
    private var state: AppState = AppState()

    suspend fun initialize(): CatalogLoadResult {
        state = stateStore.loadState()
        return loadCatalog(forceRefresh = false)
    }

    suspend fun refreshCatalog(): CatalogLoadResult = loadCatalog(forceRefresh = true)

    fun games(): List<GameVersion> = catalog.games

    fun allPokemon(): List<Pokemon> = catalog.pokemon

    fun game(query: String): GameVersion? = findGame(query)

    fun selectedGame(): GameVersion? = catalog.games.find { game -> game.name == state.selectedGame }

    @Synchronized
    fun selectGame(query: String): GameVersion? {
        val game = findGame(query) ?: return null

        state = state.copy(selectedGame = game.name)
        stateStore.saveState(state)
        return game
    }

    fun listPokemon(filter: PokemonFilter = PokemonFilter.ALL): List<Pokemon> {
        val capturedIds = capturedIdsForGame(state.selectedGame)
        return filterPokemon(pokemonForGame(state.selectedGame), filter, capturedIds)
    }

    fun listPokemonForGame(
        gameQuery: String,
        filter: PokemonFilter = PokemonFilter.CAPTURED,
    ): List<Pokemon>? {
        val game = findGame(gameQuery) ?: return null
        return filterPokemon(
            pokemonForGame(game.name),
            filter,
            capturedIdsForGame(game.name),
        )
    }

    private fun filterPokemon(
        pokemon: List<Pokemon>,
        filter: PokemonFilter,
        capturedIds: Set<Int>,
    ): List<Pokemon> {
        return when (filter) {
            PokemonFilter.ALL -> pokemon
            PokemonFilter.CAPTURED -> pokemon.filter { item -> item.id in capturedIds }
            PokemonFilter.MISSING -> pokemon.filterNot { item -> item.id in capturedIds }
        }
    }

    fun searchPokemon(text: String): List<Pokemon> {
        val query = text.trim().lowercase()
        if (query.isBlank()) return emptyList()
        return pokemonForGame(state.selectedGame).filter { pokemon ->
            pokemon.name.contains(query) || pokemon.displayName.lowercase().contains(query)
        }
    }

    fun capture(query: String): PokemonChangeResult = changeCapture(query, shouldCapture = true)

    fun release(query: String): PokemonChangeResult = changeCapture(query, shouldCapture = false)

    fun captureInGame(query: String, gameQuery: String): PokemonChangeResult =
        changeCapture(query, shouldCapture = true, gameQuery = gameQuery)

    fun releaseInGame(query: String, gameQuery: String): PokemonChangeResult =
        changeCapture(query, shouldCapture = false, gameQuery = gameQuery)

    fun progress(): Progress {
        val availablePokemon = listPokemon(PokemonFilter.ALL)
        return Progress(
            captured = listPokemon(PokemonFilter.CAPTURED).size,
            total = availablePokemon.size,
        )
    }

    fun catalogUpdatedAt(): String = catalog.updatedAt

    private suspend fun loadCatalog(forceRefresh: Boolean): CatalogLoadResult {
        val result = catalogRepository.load(forceRefresh)
        catalog = result.catalog
        return result
    }

    @Synchronized
    private fun changeCapture(
        query: String,
        shouldCapture: Boolean,
        gameQuery: String? = null,
    ): PokemonChangeResult {
        val game = if (gameQuery == null) {
            selectedGame() ?: return PokemonChangeResult.NoGameSelected
        } else {
            findGame(gameQuery) ?: return PokemonChangeResult.GameNotFound(gameQuery)
        }
        val pokemon = findPokemon(query) ?: return PokemonChangeResult.NotFound(query)
        val availableIds = catalog.pokemonIdsByGame[game.name]
        if (availableIds != null && pokemon.id !in availableIds) {
            return PokemonChangeResult.UnavailableInGame(pokemon, game)
        }
        val currentIds = state.capturedPokemonByGame[game.name].orEmpty()

        if (shouldCapture && pokemon.id in currentIds) {
            return PokemonChangeResult.AlreadyCaptured(pokemon)
        }
        if (!shouldCapture && pokemon.id !in currentIds) {
            return PokemonChangeResult.NotCaptured(pokemon)
        }

        val updatedIds = if (shouldCapture) currentIds + pokemon.id else currentIds - pokemon.id
        state = state.copy(
            capturedPokemonByGame = state.capturedPokemonByGame + (game.name to updatedIds),
        )
        stateStore.saveState(state)

        return if (shouldCapture) {
            PokemonChangeResult.Captured(pokemon)
        } else {
            PokemonChangeResult.Released(pokemon)
        }
    }

    private fun findPokemon(query: String): Pokemon? {
        val trimmedQuery = query.trim()
        val id = trimmedQuery.toIntOrNull()
        return if (id != null) {
            catalog.pokemon.find { pokemon -> pokemon.id == id }
        } else {
            catalog.pokemon.find { pokemon ->
                pokemon.name.equals(trimmedQuery, ignoreCase = true) ||
                    pokemon.displayName.equals(trimmedQuery, ignoreCase = true)
            }
        }
    }

    private fun capturedIdsForGame(gameName: String?): Set<Int> =
        gameName?.let(state.capturedPokemonByGame::get).orEmpty()

    private fun pokemonForGame(gameName: String?): List<Pokemon> {
        val availableIds = gameName?.let(catalog.pokemonIdsByGame::get) ?: return catalog.pokemon
        return catalog.pokemon.filter { pokemon -> pokemon.id in availableIds }
    }

    private fun findGame(query: String): GameVersion? {
        val normalizedQuery = normalizeGameName(query)
        return catalog.games.find { candidate ->
            candidate.name.equals(normalizedQuery, ignoreCase = true) ||
                candidate.displayName.equals(query.trim(), ignoreCase = true)
        }
    }

    private fun normalizeGameName(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), "-")
}

sealed interface PokemonChangeResult {
    data class Captured(val pokemon: Pokemon) : PokemonChangeResult
    data class Released(val pokemon: Pokemon) : PokemonChangeResult
    data class AlreadyCaptured(val pokemon: Pokemon) : PokemonChangeResult
    data class NotCaptured(val pokemon: Pokemon) : PokemonChangeResult
    data class NotFound(val query: String) : PokemonChangeResult
    data class GameNotFound(val query: String) : PokemonChangeResult
    data class UnavailableInGame(
        val pokemon: Pokemon,
        val game: GameVersion,
    ) : PokemonChangeResult
    data object NoGameSelected : PokemonChangeResult
}
