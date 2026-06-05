package ch.fbc.krakenbridge

/**
 * Parse the Nordic LED Button characteristic payload into a button code:
 * the first byte, unsigned. Null when the housing sent an empty payload.
 *
 * The code's high nibble is the button ID, the low nibble the press state
 * (see the BTN_* constants in [KrakenBleService]); routing compares against
 * those constants rather than re-deriving the nibbles.
 */
internal fun buttonCodeFrom(value: ByteArray?): Int? {
    if (value == null || value.isEmpty()) return null
    return value[0].toInt() and 0xFF
}
