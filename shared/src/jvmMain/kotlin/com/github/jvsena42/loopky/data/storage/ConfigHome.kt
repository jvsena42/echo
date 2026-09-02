package com.github.jvsena42.loopky.data.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Where the desktop client keeps its state, and why it is a directory of files rather than a
 * keychain.
 *
 * The primary target for the JVM build is a **headless Linux box** — that is where an agent
 * actually runs (#54). libsecret is usually present on a desktop Linux and usually absent there,
 * so making the OS keyring the default and the file the fallback would fail exactly where the
 * tool is meant to work. The file is the default, deliberately, and the trade-off is stated in
 * `loopky --help` rather than only here: **a session secret on this machine is protected by file
 * permissions and nothing else.** It is a capability-scoped, expiring session, never a secret key.
 *
 * macOS gets the same file store today. The Keychain is the right answer on that row — it is the
 * one desktop OS where the KVault-equivalent story is good — and is the macOS row's remaining
 * work, not something the Linux row is waiting on.
 *
 * Resolution order, first hit wins:
 * 1. `LOOPKY_CONFIG_HOME` — an explicit override, so a container or a test can point somewhere
 *    disposable without touching the caller's real state.
 * 2. `$XDG_CONFIG_HOME/loopky`, the freedesktop location.
 * 3. `~/.config/loopky` (Linux) or `~/Library/Application Support/loopky` (macOS).
 */
object ConfigHome {

    /**
     * Public because the client has to be able to *say* where it keeps things — `whoami` reports
     * it, and an agent debugging a container that has lost its session needs the path rather than
     * a description of the rules.
     */
    fun resolve(env: (String) -> String? = System::getenv): Path {
        env("LOOPKY_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it, APP_DIR) }
        val home = Paths.get(System.getProperty("user.home") ?: ".")
        val macOs = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
        return if (macOs) {
            home.resolve("Library/Application Support").resolve(APP_DIR)
        } else {
            home.resolve(".config").resolve(APP_DIR)
        }
    }

    /**
     * Create [dir] if it is missing, owner-only where the filesystem can express that.
     *
     * Best-effort on the permissions, not on the directory: a POSIX mode is meaningless on a
     * filesystem that has none, and refusing to run there would trade a real capability for a
     * guarantee we could not have made anyway. The file writes carry the same mode, so a
     * directory that could not take one is not the only line of defence.
     */
    fun prepare(dir: Path): Path {
        if (!Files.exists(dir)) {
            runCatching {
                Files.createDirectories(
                    dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(DIR_MODE)),
                )
            }.getOrElse { Files.createDirectories(dir) }
        }
        return dir
    }

    private const val APP_DIR = "loopky"
    private const val DIR_MODE = "rwx------"
}
