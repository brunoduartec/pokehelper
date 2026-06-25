package com.pokeagenda.api

import com.pokeagenda.domain.GameVersion
import com.pokeagenda.domain.Pokemon
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface PokemonRemoteDataSource {
    suspend fun fetchAllPokemon(): List<Pokemon>
    suspend fun fetchAllGames(): List<GameVersion>
    suspend fun fetchPokemonIdsByGame(): Map<String, Set<Int>>
}

class PokeApiClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    baseUrl: String = "https://pokeapi.co/api/v2",
    private val pageSize: Int = 200,
) : PokemonRemoteDataSource, AutoCloseable {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun fetchAllPokemon(): List<Pokemon> =
        fetchAllResources("pokemon").map { resource ->
            val id = resource.url.trimEnd('/').substringAfterLast('/').toIntOrNull()
                ?: throw PokeApiException("A PokéAPI retornou uma URL de Pokémon inválida: ${resource.url}")
            Pokemon(id = id, name = resource.name)
        }.sortedBy(Pokemon::id)

    override suspend fun fetchAllGames(): List<GameVersion> =
        fetchAllResources("version")
            .map { resource -> GameVersion(resource.name) }
            .sortedBy(GameVersion::name)

    override suspend fun fetchPokemonIdsByGame(): Map<String, Set<Int>> {
        val versionGroups = fetchAllResources("version-group")
            .mapInBatches { resource -> fetchResource<VersionGroupResource>(resource.url) }

        val pokedexesByUrl = versionGroups
            .flatMap(VersionGroupResource::pokedexes)
            .distinctBy(NamedApiResource::url)
            .mapInBatches { resource ->
                resource.url to fetchResource<PokedexResource>(resource.url)
            }
            .toMap()

        return buildMap {
            versionGroups.forEach { group ->
                val pokemonIds = group.pokedexes
                    .flatMap { pokedex -> pokedexesByUrl.getValue(pokedex.url).pokemonEntries }
                    .mapNotNull { entry -> resourceId(entry.pokemonSpecies) }
                    .toSet()

                group.versions.forEach { version -> put(version.name, pokemonIds) }
            }
        }
    }

    private suspend fun fetchAllResources(resource: String): List<NamedApiResource> {
        val resources = mutableListOf<NamedApiResource>()
        var nextUrl: String? = "$baseUrl/$resource?limit=$pageSize&offset=0"

        while (nextUrl != null) {
            val currentUrl = nextUrl
            val response = httpClient.get(currentUrl)
            if (!response.status.isSuccess()) {
                throw PokeApiException("A PokéAPI respondeu HTTP ${response.status.value} para $currentUrl")
            }

            val page = response.body<NamedResourcePage>()
            resources += page.results
            nextUrl = page.next
        }

        return resources
    }

    private suspend inline fun <reified T> fetchResource(url: String): T {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) {
            throw PokeApiException("A PokéAPI respondeu HTTP ${response.status.value} para $url")
        }
        return response.body()
    }

    private suspend fun <T, R> List<T>.mapInBatches(
        batchSize: Int = 6,
        transform: suspend (T) -> R,
    ): List<R> = chunked(batchSize).flatMap { batch ->
        coroutineScope {
            batch.map { item -> async { transform(item) } }.awaitAll()
        }
    }

    private fun resourceId(resource: NamedApiResource): Int? =
        resource.url.trimEnd('/').substringAfterLast('/').toIntOrNull()

    override fun close() {
        httpClient.close()
    }

    companion object {
        private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
    }
}

class PokeApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
private data class NamedResourcePage(
    val next: String?,
    val results: List<NamedApiResource>,
)

@Serializable
private data class NamedApiResource(
    val name: String,
    @SerialName("url") val url: String,
)

@Serializable
private data class VersionGroupResource(
    val versions: List<NamedApiResource>,
    val pokedexes: List<NamedApiResource>,
)

@Serializable
private data class PokedexResource(
    @SerialName("pokemon_entries") val pokemonEntries: List<PokedexEntry>,
)

@Serializable
private data class PokedexEntry(
    @SerialName("pokemon_species") val pokemonSpecies: NamedApiResource,
)
