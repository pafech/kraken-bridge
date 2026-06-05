# Keep BLE service members in release builds. Defensive only: BDD tests
# run against the unminified debug build and read KrakenBleService.state
# directly (no reflection anywhere), so nothing is known to need this —
# but removing it changes release bytecode, which only a housing dive
# would validate. Revisit when a release-build regression test exists.
-keep class ch.fbc.krakenbridge.KrakenBleService { *; }
