package com.github.jvsena42.loopky.cli

/**
 * The desktop hosts `libpubkycore` is built for, and the refusal for every other one (#210).
 *
 * There is one native row per entry, laid out the way JNA looks it up
 * (`shared/src/jvmMain/resources/README.md`). Two hosts are absent **by decision**: an Intel Mac
 * is not a target — one `darwin-aarch64` row rather than two and a `lipo` — and Windows is
 * deferred rather than omitted.
 *
 * Without this check, an unshipped host is not refused, it is *misdiagnosed*, and precisely
 * because the diagnosis is text. `Native.load` finds no matching directory on the classpath and
 * throws `UnsatisfiedLinkError("Unable to load library 'pubkycore': … **not found** in resource
 * path …")`; `isNotFound()` matches those two words, so the classifier answers
 * [ExitCode.NotFound] — an agent is told the deck it asked for does not exist, on a machine where
 * no deck could ever be read. The failure is a fact about the *binary*, knowable before a byte is
 * sent, and it says so here instead.
 *
 * It is checked in the CLI rather than at the FFI boundary because this is where a process can
 * exit with something an agent can branch on. A native binary can barely reach it — macOS refuses
 * to exec an arm64 executable on Intel at all — but the jar distribution is architecture-blind and
 * runs anywhere a JRE does, which is exactly the shape that gets this wrong.
 */
internal enum class SupportedHost(val jnaPrefix: String, val label: String) {
    LinuxX64("linux-x86-64", "Linux x86_64"),
    MacArm64("darwin-aarch64", "macOS on Apple Silicon"),
}

/**
 * Which row this process is running on, or null for a host no row covers.
 *
 * Reads the same two properties JNA does, so the answer here and the lookup that would follow
 * cannot disagree. `os.arch` is the **JVM's** architecture, not the machine's, which is the point
 * on Apple Silicon: an x86_64 JVM under Rosetta reports `x86_64` on a machine that is not one, and
 * would look for a `darwin-x86-64` row that is never going to exist.
 */
internal fun hostSupport(
    osName: String = System.getProperty("os.name").orEmpty(),
    arch: String = System.getProperty("os.arch").orEmpty(),
): SupportedHost? {
    val os = osName.lowercase()
    val cpu = arch.lowercase()
    return when {
        os.startsWith("linux") && (cpu == "amd64" || cpu == "x86_64") -> SupportedHost.LinuxX64
        os.startsWith("mac") && (cpu == "aarch64" || cpu == "arm64") -> SupportedHost.MacArm64
        else -> null
    }
}

/** The message a refused host gets: what it is, why there is no build for it, and what to do. */
internal fun unsupportedHostMessage(
    osName: String = System.getProperty("os.name").orEmpty(),
    arch: String = System.getProperty("os.arch").orEmpty(),
): String {
    val os = osName.lowercase()
    val cpu = arch.lowercase()
    val advice = when {
        os.startsWith("mac") ->
            "loopky ships one macOS build and it is for Apple Silicon. If this is an Apple " +
                "Silicon Mac, you are on an x86_64 JVM under Rosetta: use the native binary, " +
                "which has no JVM to get wrong — or, for this jar, reinstall an arm64 JDK."
        os.startsWith("windows") ->
            "Windows is not a target yet. Nothing in the design blocks it — it needs a " +
                "`win32-x86-64/pubkycore.dll` row — but there is no build to ship."
        else ->
            "The builds are ${SupportedHost.entries.joinToString(" and ") { it.label }}. " +
                "Building `libpubkycore` for this host is the missing half, not this client."
    }
    return "loopky has no build for $osName ($cpu). $advice"
}

/**
 * Refuse a host before anything tries to load the library on it.
 *
 * Called from the command boundary rather than from `main`, so `--version` and `--help` still
 * answer on a machine that cannot run anything else — an agent working out *why* the binary is
 * refusing needs those two to work.
 */
internal fun requireSupportedHost() {
    if (hostSupport() == null) throw CliError(ExitCode.UnsupportedHost, unsupportedHostMessage())
}
