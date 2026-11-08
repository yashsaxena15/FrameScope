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
        return if (token.contains('/')) token else null
    }

    fun packageOf(component: String): String = component.substringBefore('/')
}
