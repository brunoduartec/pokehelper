package com.pokeagenda

import com.pokeagenda.api.PokeApiClient
import com.pokeagenda.cache.CacheCorruptedException
import com.pokeagenda.cache.LocalJsonStore
import com.pokeagenda.cli.ConsoleApp
import com.pokeagenda.service.CatalogRepository
import com.pokeagenda.service.PokeAgendaService
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    val store = LocalJsonStore()

    try {
        PokeApiClient().use { apiClient ->
            val catalogRepository = CatalogRepository(apiClient, store)
            val service = PokeAgendaService(catalogRepository, store)
            ConsoleApp(service).run()
        }
    } catch (error: CacheCorruptedException) {
        System.err.println(error.message)
        System.err.println("Faça uma cópia do arquivo e corrija ou remova-o antes de iniciar novamente.")
    } catch (error: Exception) {
        System.err.println("Não foi possível iniciar o PokeAgenda: ${error.message}")
    }
}
