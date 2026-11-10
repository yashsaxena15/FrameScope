package com.merlin.fpscore

/**
 * Finds the foreground app and the SurfaceFlinger layer that carries its frames.
 *
 * Android 16 `dumpsys SurfaceFlinger --list` prints
 *   `RequestedLayerState{<name>#<id> parentId=… z=…}`
 * while Android 10–13 print the bare `<name>#<id>`. [extractName] handles both.
 *
 * Verified on HyperOS 3 with BGMI: the game's frames live on
 *   `b1f402 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST)#100207`
 * while the parent `…SurfaceView[…]#100206` returns only zero rows.
 */
object LayerResolver {
    private const val WRAPPER = "RequestedLayerState{"
    private val NAME_WITH_ID = Regex("""^(.*#\d+)(?:\s|$)""")
    private val HEX_TOKEN_PREFIX = Regex("""^[0-9a-f]{4,} """)

    fun extractName(line: String): String? {
        var s = line.trim()
        if (s.isEmpty()) return null
        if (s.startsWith(WRAPPER) && s.endsWith("}")) {
            s = s.substring(WRAPPER.length, s.length - 1)
        }
        val m = NAME_WITH_ID.find(s) ?: return s
        return m.groupValues[1]
    }

    /** Higher is better; negative means "never use". */
    fun score(name: String, pkg: String, component: String?): Int {
        if (!name.contains(pkg)) return -1
        if (name.startsWith("Background for") ||
            name.startsWith("Bounds for") ||
            name.startsWith("Surface(name=") ||
            name.startsWith("ActivityRecord{") ||
            name.startsWith("Task=") ||
            name.contains("ActivityRecordInputSink") ||
            name.contains("rotation-leash") ||
            name.contains("Leash") ||
            name.startsWith("Splash Screen")
        ) return -1
        var s = when {
            name.contains("SurfaceView") && name.contains("(BLAST)") -> 50
            name.contains("SurfaceView") -> 40
            !HEX_TOKEN_PREFIX.containsMatchIn(name) -> 30 // "pkg/Activity#id": the window's buffer layer
            else -> 10 // "<token> pkg/Activity#id": window container
        }
        if (component != null && name.contains(component)) s += 5
        return s
    }

    /** Candidate layer names for [pkg], best first. */
    fun candidates(listOutput: CharSequence, pkg: String, component: String?): List<String> {
        val result = ArrayList<Pair<String, Int>>()
        for (line in listOutput.lineSequence()) {
            if (!line.contains(pkg)) continue
            val name = extractName(line) ?: continue
            val sc = score(name, pkg, component)
            if (sc >= 0) result.add(name to sc)
        }
        result.sortByDescending { it.second }
        return result.map { it.first }
    }

    /**
     * Parses `dumpsys window displays` (or `dumpsys window`) and returns the focused
     * app component "pkg/Activity". Prefers mFocusedApp (always an activity) over
     * mCurrentFocus (may be a popup, the notification shade, etc.).
     */
    fun focusedComponent(windowDump: CharSequence): String? {
        var fromCurrentFocus: String? = null
        for (line in windowDump.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("mFocusedApp=")) {
                componentIn(t)?.let { return it }
            } else if (fromCurrentFocus == null && t.startsWith("mCurrentFocus=")) {
                fromCurrentFocus = componentIn(t)
            }
        }
        return fromCurrentFocus
    }

    /** Finds a "pkg/cls" token in a line like `Window{abc u0 pkg/cls}` or `ActivityRecord{… u0 pkg/cls t17}`. */
    private fun componentIn(line: String): String? {
        val u = line.indexOf(" u0 ").let { if (it >= 0) it else Regex(""" u\d+ """).find(line)?.range?.first ?: -1 }
        if (u < 0) return null
        val start = line.indexOf(' ', u + 1) + 1
        var end = start
        while (end < line.length && line[end] != ' ' && line[end] != '}') end++
        val token = line.substring(start, end)
        return if (token.contains('/')) normalize(token) else null
    }

    /**
     * Expands the short form "pkg/.Activity" (used by ActivityRecord on Android 12,
     * e.g. vivo 1938) to "pkg/pkg.Activity", which is how layer names spell it.
     */
    fun normalize(component: String): String {
        val slash = component.indexOf('/')
        if (slash < 0 || slash + 1 >= component.length || component[slash + 1] != '.') return component
        val pkg = component.substring(0, slash)
        return pkg + "/" + pkg + component.substring(slash + 1)
    }

    fun packageOf(component: String): String = component.substringBefore('/')
}

/**
 * Decides which layer to measure when the focused app changes.
 *
 * A popup or permission dialog takes focus but barely draws, while the game
 * behind it keeps rendering. So the layer currently being measured competes with
 * the newly focused app's candidates on frames presented in the last second.
 * It stays only if it is still listed by SurfaceFlinger and strictly out-draws
 * every new candidate. A real app switch stops the old app's frames, so the new
 * app wins; if nothing draws at all, the new app's best-ranked layer is chosen.
 */
object LayerChooser {
    /**
     * @param current layer being measured now (null if none)
     * @param currentFrames frames it presented in the last second
     * @param currentListed whether it still appears in `--list`
     * @param candidates new app's candidate layers, best-ranked first, each with its recent frame count
     * @return the layer to measure, or null if there is nothing to measure
     */
    fun choose(current: String?, currentFrames: Int, currentListed: Boolean, candidates: List<Pair<String, Int>>): String? {
        var best: String? = null
        var bestFrames = -1
        for ((name, frames) in candidates) {
            if (frames > bestFrames) { best = name; bestFrames = frames }
        }
        if (current != null && currentListed && currentFrames > maxOf(bestFrames, 0)) return current
        return best ?: if (current != null && currentListed) current else null
    }
}
