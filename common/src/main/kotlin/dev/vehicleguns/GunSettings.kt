package dev.vehicleguns

/**
 * Server and client switches. Plain fields with sensible defaults: set them from your own mod's config, or leave them.
 */
object GunSettings {
    enum class BlastGriefing { MOB_GRIEFING, ALWAYS, NEVER }

    /** Whether a gunner's round breaks blocks and starts fires. MOB_GRIEFING follows the game rule. A guard's never does. */
    @JvmField var blastGriefing = BlastGriefing.MOB_GRIEFING

    /** Whether a gunner's round hurts other players. */
    @JvmField var gunnerHurtsPlayers = true

    /** Client: the view kicks as the gun fires and shakes near a burst. */
    @JvmField var screenShake = true

    /** Client: the screen flashes near a burst. */
    @JvmField var burstFlash = true

    /** Client: share of a burst's particles drawn, 0 to 1. */
    @JvmField var burstParticles = 1.0
}
