package com.pokeagenda.cache

import com.pokeagenda.domain.AppState
import com.pokeagenda.domain.CatalogCache
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface CatalogCacheStore {
    fun loadCatalog(): CatalogCache?
    fun saveCatalog(catalog: CatalogCache)
}

interface AppStateStore {
    fun loadState(): AppState
    fun saveState(state: AppState)
}

class LocalJsonStore(
    private val directory: Path = defaultDirectory(),
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    },
) : CatalogCacheStore, AppStateStore {
    private val catalogPath = directory.resolve("catalog.json")
    private val statePath = directory.resolve("state.json")

    override fun loadCatalog(): CatalogCache? = read<CatalogCache>(catalogPath)

    override fun saveCatalog(catalog: CatalogCache) {
        write(catalogPath, json.encodeToString(catalog))
    }

    override fun loadState(): AppState = read<AppState>(statePath) ?: AppState()

    override fun saveState(state: AppState) {
        write(statePath, json.encodeToString(state))
    }

    private inline fun <reified T> read(path: Path): T? {
        if (!Files.exists(path)) return null

        try {
            return json.decodeFromString(Files.readString(path))
        } catch (error: SerializationException) {
            throw CacheCorruptedException(path, error)
        } catch (error: IOException) {
            throw CacheAccessException("Não foi possível ler $path", error)
        }
    }

    private fun write(path: Path, content: String) {
        try {
            Files.createDirectories(directory)
            val temporaryPath = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(
                temporaryPath,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )

            try {
                Files.move(
                    temporaryPath,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: IOException) {
            throw CacheAccessException("Não foi possível gravar $path", error)
        }
    }

    companion object {
        fun defaultDirectory(): Path = System.getenv("POKEAGENDA_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let { configuredPath -> Paths.get(configuredPath) }
            ?: Paths.get(System.getProperty("user.home"), ".pokeagenda")
    }
}

open class CacheAccessException(message: String, cause: Throwable) : RuntimeException(message, cause)

class CacheCorruptedException(
    val path: Path,
    cause: Throwable,
) : CacheAccessException("O arquivo de cache $path contém JSON inválido", cause)
