package com.pokeagenda.http

import com.pokeagenda.api.PokeApiClient
import com.pokeagenda.api.PokemonRemoteDataSource
import com.pokeagenda.cache.AppStateStore
import com.pokeagenda.cache.CacheCorruptedException
import com.pokeagenda.cache.CatalogCacheStore
import com.pokeagenda.cache.LocalJsonStore
import com.pokeagenda.service.CatalogRepository
import com.pokeagenda.service.PokeAgendaService
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Configuration
class PokeAgendaSpringConfiguration {
    @Bean
    fun localJsonStore(): LocalJsonStore = LocalJsonStore()

    @Bean(destroyMethod = "close")
    fun pokeApiClient(): PokeApiClient = PokeApiClient()

    @Bean
    fun catalogRepository(
        remoteDataSource: PokemonRemoteDataSource,
        cacheStore: CatalogCacheStore,
    ): CatalogRepository = CatalogRepository(remoteDataSource, cacheStore)

    @Bean
    fun pokeAgendaService(
        catalogRepository: CatalogRepository,
        stateStore: AppStateStore,
    ): PokeAgendaService {
        val service = PokeAgendaService(catalogRepository, stateStore)

        try {
            val catalogResult = runBlocking { service.initialize() }
            println(
                "Catálogo pronto: ${catalogResult.catalog.pokemon.size} Pokémon, " +
                    "fonte ${catalogResult.source}.",
            )
            if (catalogResult.warning != null) {
                System.err.println("Aviso ao atualizar catálogo: ${catalogResult.warning}")
            }
            return service
        } catch (error: CacheCorruptedException) {
            System.err.println(error.message)
            System.err.println("Faça uma cópia do arquivo e corrija ou remova-o antes de iniciar novamente.")
            throw error
        }
    }
}

@Component
class ApiStartupLogger(
    private val environment: Environment,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun logReady() {
        val host = environment.getProperty("server.address", "0.0.0.0")
        val port = environment.getProperty("local.server.port")
            ?: environment.getProperty("server.port", "8080")

        println("API disponível em http://$host:$port")
    }
}
