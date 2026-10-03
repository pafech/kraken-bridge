package ch.fbc.krakenbridge

/**
 * Parse the standard Battery Level characteristic (0x2A19) payload: one
 * unsigned byte, 0–100 %. Null for an empty payload or an out-of-range
 * value, so a misbehaving firmware never shows a nonsense percentage.
 */
internal fun batteryPercentFrom(value: ByteArray?): Int? {
    if (value == null || value.isEmpty()) return null
    val percent = value[0].toInt() and 0xFF
    return percent.takeIf { it in 0..100 }
}
