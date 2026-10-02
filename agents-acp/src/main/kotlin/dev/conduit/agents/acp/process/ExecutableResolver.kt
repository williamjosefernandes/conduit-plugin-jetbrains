package dev.conduit.agents.acp.process

import java.io.File

/**
 * Finds executables the way a shell would, using an explicit environment. The IDE passes the
 * user's shell environment here because a GUI process often has a shorter `PATH` than the terminal.
 *
 * @property env environment to search; `PATH` and, on Windows, `PATHEXT` are used
 * @property isFile test seam for file existence
 */
class ExecutableResolver(
    private val env: Map<String, String>,
    private val windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    private val isFile: (String) -> Boolean = { File(it).isFile },
) {
    private val separator = if (windows) ';' else ':'
    private val slash = if (windows) '\\' else '/'

    /** Returns the absolute path of [name], or null when it is not on the `PATH`. */
    fun resolve(name: String): String? {
        if (name.contains('/') || name.contains('\\')) {
            return candidates(name).firstOrNull(isFile)
        }
        val path =
            env.entries
                .firstOrNull { it.key.equals("PATH", ignoreCase = true) }
                ?.value
                .orEmpty()
        return path
            .split(separator)
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> candidates(dir.trimEnd(slash) + slash + name) }
            .firstOrNull(isFile)
    }

    /**
     * Returns the full command line that runs [executable] (an absolute path from [resolve]) with
     * [args]. Windows script shims (`.cmd`, `.bat`), which is how npm installs CLIs, cannot be
     * started directly and are run through `cmd.exe`.
     */
    fun commandLine(
        executable: String,
        args: List<String>,
    ): List<String> {
        val script = windows && (executable.endsWith(".cmd", ignoreCase = true) || executable.endsWith(".bat", ignoreCase = true))
        return if (script) listOf("cmd.exe", "/c", executable) + args else listOf(executable) + args
    }

    private fun candidates(base: String): Sequence<String> {
        if (!windows) return sequenceOf(base)
        val extensions =
            (env.entries.firstOrNull { it.key.equals("PATHEXT", ignoreCase = true) }?.value ?: DEFAULT_PATHEXT)
                .split(';')
                .filter { it.isNotBlank() }
        // Prefer real launchers over the extension-less POSIX shim npm puts next to them.
        val bare = sequenceOf(base).filter { it.substringAfterLast(slash).contains('.') }
        return extensions.asSequence().map { base + it.lowercase() } + bare
    }

    private companion object {
        const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"
    }
}
