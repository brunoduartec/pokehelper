package com.pokeagenda.cli

import com.pokeagenda.domain.GameVersion
import com.pokeagenda.domain.Pokemon
import com.pokeagenda.domain.PokemonFilter
import com.pokeagenda.service.CatalogLoadResult
import com.pokeagenda.service.CatalogSource
import com.pokeagenda.service.PokeAgendaService
import com.pokeagenda.service.PokemonChangeResult
import java.util.Locale
import kotlin.math.ceil

class ConsoleApp(
    private val service: PokeAgendaService,
    private val io: ConsoleIO = SystemConsoleIO(),
    private val pageSize: Int = 25,
) {
    suspend fun run() {
        printBanner()
        printCatalogResult(service.initialize())

        if (service.selectedGame() == null) {
            io.printLine("Nenhum jogo selecionado. Use 'jogos' e depois 'jogo <nome ou número>'.")
        } else {
            io.printLine("Jogo atual: ${service.selectedGame()?.displayName}")
        }

        io.printLine("Digite 'ajuda' para ver os comandos.")

        while (true) {
            val currentGame = service.selectedGame()?.displayName ?: "sem jogo"
            val line = io.readLine("pokeagenda [$currentGame]> ") ?: break
            val shouldContinue = execute(line.trim())
            if (!shouldContinue) break
        }

        io.printLine("Progresso salvo. Até a próxima!")
    }

    internal suspend fun execute(commandLine: String): Boolean {
        if (commandLine.isBlank()) return true
        val parts = commandLine.split(Regex("\\s+"))
        val command = parts.first().lowercase()
        val arguments = parts.drop(1)

        when (command) {
            "ajuda", "help" -> printHelp()
            "jogos" -> printGames()
            "jogo" -> selectGame(arguments)
            "listar" -> listPokemon(arguments)
            "buscar" -> searchPokemon(arguments)
            "capturar" -> changePokemon(arguments, shouldCapture = true)
            "soltar" -> changePokemon(arguments, shouldCapture = false)
            "progresso" -> printProgress()
            "atualizar" -> printCatalogResult(service.refreshCatalog())
            "sair", "exit" -> return false
            else -> io.printLine("Comando desconhecido: '$command'. Digite 'ajuda'.")
        }
        return true
    }

    private fun printBanner() {
        io.printLine("============================")
        io.printLine("       P O K E A G E N D A")
        io.printLine("============================")
    }

    private fun printHelp() {
        io.printLine(
            """
            Comandos:
              jogos                              lista as versões de jogos
              jogo <nome ou número>              seleciona o jogo atual
              listar [todos|capturados|pendentes] [página]
              buscar <nome>                      pesquisa Pokémon pelo nome
              capturar <id ou nome>              marca como capturado no jogo atual
              soltar <id ou nome>                remove uma captura do jogo atual
              progresso                          exibe o progresso do jogo atual
              atualizar                          baixa novamente o catálogo da PokéAPI
              ajuda                              mostra esta ajuda
              sair                               encerra a aplicação
            """.trimIndent(),
        )
    }

    private fun printGames() {
        service.games().forEachIndexed { index, game ->
            val marker = if (game.name == service.selectedGame()?.name) " *" else ""
            io.printLine("${(index + 1).toString().padStart(2)}. ${game.displayName}$marker")
        }
        io.printLine("* jogo selecionado")
    }

    private fun selectGame(arguments: List<String>) {
        if (arguments.isEmpty()) {
            io.printLine("Uso: jogo <nome ou número>")
            return
        }

        val query = arguments.joinToString(" ")
        val byIndex = query.toIntOrNull()?.let { index -> service.games().getOrNull(index - 1) }
        val selected = byIndex ?: service.selectGame(query)
        if (selected == null) {
            io.printLine("Jogo não encontrado. Use 'jogos' para consultar as opções.")
        } else {
            if (byIndex != null) service.selectGame(byIndex.name)
            io.printLine("Jogo selecionado: ${selected.displayName}")
            printProgress()
        }
    }

    private fun listPokemon(arguments: List<String>) {
        val filter = when (arguments.firstOrNull()?.lowercase()) {
            null, "todos" -> PokemonFilter.ALL
            "capturados" -> PokemonFilter.CAPTURED
            "pendentes" -> PokemonFilter.MISSING
            else -> {
                io.printLine("Filtro inválido. Use: todos, capturados ou pendentes.")
                return
            }
        }
        val page = arguments.getOrNull(1)?.toIntOrNull() ?: 1
        printPokemonPage(service.listPokemon(filter), page)
    }

    private fun searchPokemon(arguments: List<String>) {
        if (arguments.isEmpty()) {
            io.printLine("Uso: buscar <nome>")
            return
        }
        printPokemonPage(service.searchPokemon(arguments.joinToString(" ")), page = 1)
    }

    private fun changePokemon(arguments: List<String>, shouldCapture: Boolean) {
        if (arguments.isEmpty()) {
            io.printLine("Uso: ${if (shouldCapture) "capturar" else "soltar"} <id ou nome>")
            return
        }

        val query = arguments.joinToString(" ")
        val result = if (shouldCapture) service.capture(query) else service.release(query)
        when (result) {
            is PokemonChangeResult.Captured -> io.printLine("${result.pokemon.displayName} foi capturado!")
            is PokemonChangeResult.Released -> io.printLine("${result.pokemon.displayName} foi removido das capturas.")
            is PokemonChangeResult.AlreadyCaptured -> io.printLine("${result.pokemon.displayName} já estava capturado.")
            is PokemonChangeResult.NotCaptured -> io.printLine("${result.pokemon.displayName} ainda não estava capturado.")
            is PokemonChangeResult.NotFound -> io.printLine("Pokémon não encontrado: '${result.query}'.")
            is PokemonChangeResult.GameNotFound -> io.printLine("Jogo não encontrado: '${result.query}'.")
            is PokemonChangeResult.UnavailableInGame -> io.printLine(
                "${result.pokemon.displayName} não pertence à Pokédex de ${result.game.displayName}.",
            )
            PokemonChangeResult.NoGameSelected -> io.printLine("Selecione um jogo antes de alterar capturas.")
        }
    }

    private fun printProgress() {
        val game = service.selectedGame()
        if (game == null) {
            io.printLine("Nenhum jogo selecionado.")
            return
        }
        val progress = service.progress()
        io.printLine(
            "${game.displayName}: ${progress.captured}/${progress.total} capturados " +
                "(${String.format(Locale.US, "%.2f", progress.percentage)}%)",
        )
    }

    private fun printPokemonPage(pokemon: List<Pokemon>, page: Int) {
        if (pokemon.isEmpty()) {
            io.printLine("Nenhum Pokémon encontrado.")
            return
        }

        val totalPages = ceil(pokemon.size.toDouble() / pageSize).toInt()
        if (page !in 1..totalPages) {
            io.printLine("Página inválida. Escolha uma página entre 1 e $totalPages.")
            return
        }

        val start = (page - 1) * pageSize
        pokemon.drop(start).take(pageSize).forEach { item ->
            io.printLine("#${item.id.toString().padStart(4, '0')} ${item.displayName}")
        }
        io.printLine("Página $page/$totalPages — ${pokemon.size} resultado(s)")
    }

    private fun printCatalogResult(result: CatalogLoadResult) {
        val source = when (result.source) {
            CatalogSource.NETWORK -> "PokéAPI"
            CatalogSource.CACHE -> "cache local"
            CatalogSource.FALLBACK_CACHE -> "cache local (modo offline)"
        }
        io.printLine(
            "Catálogo carregado de $source: ${result.catalog.pokemon.size} Pokémon e " +
                "${result.catalog.games.size} jogos.",
        )
        result.warning?.let { warning -> io.printLine("Aviso ao atualizar: $warning") }
    }
}
