package com.pokeagenda.cli

interface ConsoleIO {
    fun printLine(message: String = "")
    fun readLine(prompt: String): String?
}

class SystemConsoleIO : ConsoleIO {
    override fun printLine(message: String) {
        kotlin.io.println(message)
    }

    override fun readLine(prompt: String): String? {
        kotlin.io.print(prompt)
        return kotlin.io.readlnOrNull()
    }
}
