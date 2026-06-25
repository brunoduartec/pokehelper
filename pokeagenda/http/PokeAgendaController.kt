package com.pokeagenda.http

import com.pokeagenda.domain.PokemonFilter
import com.pokeagenda.service.PokeAgendaService
import com.pokeagenda.service.PokemonChangeResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
class PokeAgendaController(
    private val service: PokeAgendaService,
) {
    @GetMapping("/getGames")
    fun getGames(): GameListResponse {
        val games = service.games()
        return GameListResponse(
            count = games.size,
            games = games.map { game -> game.toResponse() },
        )
    }

    @GetMapping("/getAllPokemons")
    fun getAllPokemons(
        @RequestParam(name = "game", required = false) gameQuery: String?,
    ): ResponseEntity<Any> {
        val normalizedGameQuery = gameQuery?.trim()
        val game = normalizedGameQuery
            ?.takeIf(String::isNotEmpty)
            ?.let(service::game)

        if (!normalizedGameQuery.isNullOrEmpty() && game == null) {
            return errorResponse(
                status = HttpStatus.NOT_FOUND,
                code = "game_not_found",
                message = "Jogo não encontrado: '$normalizedGameQuery'.",
            )
        }

        val pokemon = if (game == null) {
            service.allPokemon()
        } else {
            service.listPokemonForGame(game.name, PokemonFilter.ALL).orEmpty()
        }

        return ResponseEntity.ok(
            PokemonListResponse(
                game = game?.name,
                count = pokemon.size,
                pokemon = pokemon.map { item -> item.toResponse() },
            ),
        )
    }

    @GetMapping("/getMyPokemons")
    fun getMyPokemons(
        @RequestParam(name = "game", required = false) gameQuery: String?,
    ): ResponseEntity<Any> {
        val normalizedGameQuery = gameQuery?.trim()
        if (normalizedGameQuery.isNullOrEmpty()) {
            return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                code = "missing_game",
                message = "Informe o jogo com ?game=<nome>.",
            )
        }

        val game = service.game(normalizedGameQuery)
            ?: return errorResponse(
                status = HttpStatus.NOT_FOUND,
                code = "game_not_found",
                message = "Jogo não encontrado: '$normalizedGameQuery'.",
            )

        val pokemon = service.listPokemonForGame(game.name, PokemonFilter.CAPTURED).orEmpty()
        return ResponseEntity.ok(
            PokemonListResponse(
                game = game.name,
                count = pokemon.size,
                pokemon = pokemon.map { item -> item.toResponse() },
            ),
        )
    }

    @PostMapping("/catchPokemon")
    fun catchPokemon(@RequestBody request: PokemonMutationRequest): ResponseEntity<Any> {
        val invalidResponse = validateMutationRequest(request)
        if (invalidResponse != null) return invalidResponse

        val game = service.game(request.game)
            ?: return errorResponse(
                status = HttpStatus.NOT_FOUND,
                code = "game_not_found",
                message = "Jogo não encontrado: '${request.game}'.",
            )

        return mutationResponse(service.captureInGame(request.pokemon, game.name), game.name)
    }

    @DeleteMapping("/releasePokemon")
    fun releasePokemon(@RequestBody request: PokemonMutationRequest): ResponseEntity<Any> {
        val invalidResponse = validateMutationRequest(request)
        if (invalidResponse != null) return invalidResponse

        val game = service.game(request.game)
            ?: return errorResponse(
                status = HttpStatus.NOT_FOUND,
                code = "game_not_found",
                message = "Jogo não encontrado: '${request.game}'.",
            )

        return mutationResponse(service.releaseInGame(request.pokemon, game.name), game.name)
    }

    private fun validateMutationRequest(request: PokemonMutationRequest): ResponseEntity<Any>? {
        if (request.game.isBlank() || request.pokemon.isBlank()) {
            return errorResponse(
                status = HttpStatus.BAD_REQUEST,
                code = "invalid_body",
                message = "Os campos 'game' e 'pokemon' não podem ser vazios.",
            )
        }
        return null
    }

    private fun mutationResponse(
        result: PokemonChangeResult,
        game: String,
    ): ResponseEntity<Any> =
        when (result) {
            is PokemonChangeResult.Captured -> ResponseEntity.status(HttpStatus.CREATED).body(
                PokemonMutationResponse(
                    status = "captured",
                    game = game,
                    pokemon = result.pokemon.toResponse(),
                    message = "${result.pokemon.displayName} foi capturado.",
                ),
            )

            is PokemonChangeResult.Released -> ResponseEntity.ok(
                PokemonMutationResponse(
                    status = "released",
                    game = game,
                    pokemon = result.pokemon.toResponse(),
                    message = "${result.pokemon.displayName} foi solto.",
                ),
            )

            is PokemonChangeResult.AlreadyCaptured -> ResponseEntity.ok(
                PokemonMutationResponse(
                    status = "already_captured",
                    game = game,
                    pokemon = result.pokemon.toResponse(),
                    message = "${result.pokemon.displayName} já estava capturado.",
                ),
            )

            is PokemonChangeResult.NotCaptured -> errorResponse(
                status = HttpStatus.NOT_FOUND,
                code = "pokemon_not_captured",
                message = "${result.pokemon.displayName} não está nas capturas de $game.",
            )

            is PokemonChangeResult.NotFound -> errorResponse(
                status = HttpStatus.NOT_FOUND,
                code = "pokemon_not_found",
                message = "Pokémon não encontrado: '${result.query}'.",
            )

            is PokemonChangeResult.GameNotFound -> errorResponse(
                status = HttpStatus.NOT_FOUND,
                code = "game_not_found",
                message = "Jogo não encontrado: '${result.query}'.",
            )

            is PokemonChangeResult.UnavailableInGame -> errorResponse(
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                code = "pokemon_unavailable_in_game",
                message = "${result.pokemon.displayName} não pertence à Pokédex de ${result.game.displayName}.",
            )

            PokemonChangeResult.NoGameSelected -> errorResponse(
                status = HttpStatus.BAD_REQUEST,
                code = "missing_game",
                message = "Informe o jogo da captura.",
            )
        }
}

@RestControllerAdvice
class PokeAgendaExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidJsonBody(): ResponseEntity<Any> =
        errorResponse(
            status = HttpStatus.BAD_REQUEST,
            code = "invalid_body",
            message = "Envie um JSON com os campos 'game' e 'pokemon'.",
        )
}

private fun errorResponse(
    status: HttpStatus,
    code: String,
    message: String,
): ResponseEntity<Any> = ResponseEntity.status(status).body(ApiErrorResponse(code, message))
