package ch.fbc.krakenbridge

// Housing button codes: high nibble = button ID, low nibble = press state.
// Routing compares against these press constants rather than re-deriving
// the nibbles — the low-nibble pattern is not uniform across buttons.
// Each button also sends a release code (same high nibble, different low
// nibble); the router ignores those, so they are not listed here.
internal const val BTN_SHUTTER_PRESS = 0x21
internal const val BTN_FN_PRESS = 0x62
internal const val BTN_BACK_PRESS = 0x11
internal const val BTN_PLUS_PRESS = 0x41
internal const val BTN_OK_PRESS = 0x31
internal const val BTN_MINUS_PRESS = 0x51

/**
 * Parse the Nordic LED Button characteristic payload into a button code:
 * the first byte, unsigned. Null when the housing sent an empty payload.
 */
internal fun buttonCodeFrom(value: ByteArray?): Int? {
    if (value == null || value.isEmpty()) return null
    return value[0].toInt() and 0xFF
}
