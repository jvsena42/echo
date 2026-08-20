package com.github.jvsena42.loopky.data.homegate

/**
 * A Homegate instance paired with the homeserver it issues signup tokens for.
 *
 * **These two values must never be configured separately.** A token minted by one Homegate is only
 * valid on *its* homeserver; spending it anywhere else is rejected, and because a signup token is
 * single-use, that rejection is permanent — the user's payment or SMS attempt is simply gone. Two
 * independent config strings make that a one-typo mistake, so they travel as one value instead.
 *
 * The homeserver here is only a **fallback for the invite-code path**, which makes no Homegate call
 * and so has nothing to learn a homeserver from. Whenever Homegate does answer, the
 * `homeserverPubky` it returns in the [SignupGrant] wins — the token and the server it belongs to
 * arrive together and stay together.
 *
 * Both environments are live and distinguishable: production charges 1000 sat for the Lightning
 * route, staging 10.
 *
 * **These are not secrets, and hardcoding them is correct.** A homeserver pubky is a public key —
 * it is that server's identity on the mainline DHT, republished inside the world-readable `_pubky`
 * record of every user hosted there, and it doubles as the TLS identity. No client can reach a
 * homeserver without resolving it. Both values are already committed in public repos: pubky-ring's
 * `src/utils/constants.ts` and pubky-app's `src/libs/env/env.ts` (as a `NEXT_PUBLIC_` default,
 * i.e. deliberately shipped to the browser) and its Docker build workflow. Moving them to a secret
 * env var would protect nothing and would only make a misconfigured build harder to diagnose.
 */
enum class PubkyEnvironment(
    val homegateBaseUrl: String,
    val defaultHomeserver: String,
) {
    Staging(
        homegateBaseUrl = "https://homegate.staging.pubky.app",
        defaultHomeserver = "ufibwbmed6jeq9k4p583go95wofakh9fwpp4k734trq79pd9u1uy",
    ),
    Production(
        homegateBaseUrl = "https://homegate.pubky.app",
        defaultHomeserver = "8um71us3fyw6h8wbcxb5ar3rwusy1a6u49956ikzojg3gcwd1dty",
    ),
    ;

    companion object {
        /**
         * Parse a persisted or build-time name, falling back to [Production].
         *
         * Production is the safe default for an unrecognised value: pointing a confused build at
         * staging would have it mint tokens the production homeserver rejects.
         */
        fun fromNameOrProduction(name: String?): PubkyEnvironment =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Production
    }
}
