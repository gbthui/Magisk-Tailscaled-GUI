package top.cenmin.tailcontrol.core.shell

/** Shell syntax belongs here; callers should pass untrusted values as arguments. */
object ShellCommand {
    const val FALLBACK_PATH =
        "/data/adb/tailscale/bin:/data/adb/tailscale/scripts:/data/adb/modules/magisk-tailscaled/system/bin"

    fun quote(value: String): String {
        require('\u0000' !in value) { "Shell arguments cannot contain NUL" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun argv(vararg args: String): String {
        require(args.isNotEmpty() && args.first().isNotBlank()) { "Missing executable" }
        return args.joinToString(" ", transform = ::quote)
    }

    /** Run AFTER su, so its environment filtering cannot discard our PATH. */
    fun script(command: String, fallbackPath: String = FALLBACK_PATH): String =
        "export PATH=${quote(fallbackPath)}:\"\$PATH\"\n$command"

    /** Do not change the shared libsu shell's PATH, cwd, or other shell state. */
    fun isolated(command: String, fallbackPath: String = FALLBACK_PATH): String =
        "(\n${script(command, fallbackPath)}\n)"

    /** exec removes the intermediate command shell and keeps stdin for file data. */
    fun process(vararg args: String): String = script("exec ${argv(*args)}")

    fun fileSend(fileName: String, target: String): Array<String> {
        require(fileName.isNotBlank()) { "Missing file name" }
        require(target.isNotBlank() && !target.startsWith('-')) { "Invalid transfer target" }
        return arrayOf("tailscale", "file", "cp", "--verbose", "--name", fileName, "--", "-", "$target:")
    }

    fun fileReceive(binary: String, directory: String, conflict: String, logPath: String): String {
        require(directory.startsWith('/')) { "Receive directory must be absolute" }
        require(conflict in setOf("rename", "skip", "overwrite")) { "Invalid conflict behavior" }
        val command = argv(binary, "file", "get", "--conflict=$conflict", "--loop", "--", directory)
        return "nohup $command </dev/null >${quote(logPath)} 2>&1 & echo \$!"
    }

    fun isFileReceiver(commandLine: String): Boolean {
        val args = commandLine.split('\u0000')
        return args.size >= 3 && args[0].substringAfterLast('/') == "tailscale" &&
            args[1] == "file" && args[2] == "get"
    }

    fun ping(target: String): String =
        argv("tailscale", "ping", "--timeout=2s", "-c", "30", "--", target)

    /** Do not classify a missing/denied binary as a supported feature. */
    fun supportsFile(result: CommandResult): Boolean = result.ok &&
        !result.text.contains("unknown subcommand", ignoreCase = true) &&
        Regex("(?m)^\\s*cp\\s+\\S").containsMatchIn(result.text) &&
        Regex("(?m)^\\s*get\\s+\\S").containsMatchIn(result.text)
}
