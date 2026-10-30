package dev.bwworldloader.pool

import dev.bwworldloader.config.Selection
import java.util.Random

/** A parked arena as seen by the selection logic. */
data class ColdEntry(
    val key: String,
    val group: String,
    /** epoch millis when this arena was last woken up, 0 if never */
    val lastActivated: Long,
    /** stable first-seen sequence number, used for round-robin */
    val order: Long,
)

/** Pure decision functions of the warm pool (no Bukkit, fully unit-tested). */
object WarmPolicy {

    /** How many more arenas of a group must be woken up to reach [target]. */
    fun deficit(target: Int, open: Int, inFlight: Int): Int = (target - open - inFlight).coerceAtLeast(0)

    /**
     * Picks the next arena to wake up.
     * @param cursor the `order` of the previously picked arena for [Selection.ROUND_ROBIN] (-1 at the start).
     */
    fun pick(candidates: List<ColdEntry>, selection: Selection, rnd: Random, cursor: Long): ColdEntry? {
        if (candidates.isEmpty()) return null
        return when (selection) {
            Selection.RANDOM -> candidates[rnd.nextInt(candidates.size)]
            Selection.LEAST_RECENT -> {
                val oldest = candidates.minOf { it.lastActivated }
                val tied = candidates.filter { it.lastActivated == oldest }
                tied[rnd.nextInt(tied.size)]
            }
            Selection.ROUND_ROBIN -> {
                val sorted = candidates.sortedBy { it.order }
                sorted.firstOrNull { it.order > cursor } ?: sorted.first()
            }
        }
    }
}
