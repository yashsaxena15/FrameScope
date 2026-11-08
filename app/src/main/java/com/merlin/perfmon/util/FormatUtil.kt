package com.merlin.perfmon.util

/**
 * Human-readable byte sizes (binary, IEC).
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

/**
 * Human-readable network speed (bytes/s → KB/s, MB/s, etc.)
 */
fun formatBytesPerSec(bps: Long): String = when {
    bps >= 1_048_576L -> "%.1f MB/s".format(bps / 1_048_576.0)
    bps >= 1_024L     -> "%.0f KB/s".format(bps / 1_024.0)
    else              -> "$bps B/s"
}

/**
 * Format a float percentage with one decimal place.
 */
fun Float.formatPercent(): String = "%.1f%%".format(this)
