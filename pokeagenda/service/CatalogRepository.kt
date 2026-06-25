package com.pokeagenda.service

import com.pokeagenda.api.PokemonRemoteDataSource
import com.pokeagenda.cache.CatalogCacheStore
import com.pokeagenda.domain.CatalogCache
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Instant

class CatalogRepository(
    private val remoteDataSource: PokemonRemoteDataSource,
    private val cacheStore: CatalogCacheStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun load(forceRefresh: Boolean = false): CatalogLoadResult {
        val cachedCatalog = cacheStore.loadCatalog()
        if (cachedCatalog?.isCurrent() == true && !forceRefresh) {
            return CatalogLoadResult(cachedCatalog, CatalogSource.CACHE)
        }

        return try {
            val freshCatalog = coroutineScope {
                val pokemon = async { remoteDataSource.fetchAllPokemon() }
                val games = async { remoteDataSource.fetchAllGames() }
                val pokemonIdsByGame = async { remoteDataSource.fetchPokemonIdsByGame() }
                CatalogCache(
                    updatedAt = Instant.now(clock).toString(),
                    pokemon = pokemon.await(),
                    games = games.await(),
                    pokemonIdsByGame = pokemonIdsByGame.await(),
                )
            }
            cacheStore.saveCatalog(freshCatalog)
            CatalogLoadResult(freshCatalog, CatalogSource.NETWORK)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (cachedCatalog != null) {
                CatalogLoadResult(
                    catalog = cachedCatalog,
                    source = CatalogSource.FALLBACK_CACHE,
                    warning = error.message ?: error::class.simpleName,
                )
            } else {
                throw CatalogUnavailableException(
                    "Não foi possível acessar a PokéAPI e ainda não existe um catálogo local.",
                    error,
                )
            }
        }
    }

    private fun CatalogCache.isCurrent(): Boolean =
        schemaVersion >= 2 && pokemonIdsByGame.isNotEmpty()
}

data class CatalogLoadResult(
    val catalog: CatalogCache,
    val source: CatalogSource,
    val warning: String? = null,
)

enum class CatalogSource {
    NETWORK,
    CACHE,
    FALLBACK_CACHE,
}

class CatalogUnavailableException(message: String, cause: Throwable) : RuntimeException(message, cause)
