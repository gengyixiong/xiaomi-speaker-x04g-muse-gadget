Muse X04G uses Meta's muse-gadget-sdk, commit
3229892e93c18a768ace42cbe1fe7133f91ca203, under Apache-2.0.
The Android pairing and session code are adaptations of that SDK.
The original headers and LICENSE remain in upstream/muse-gadget-sdk.

The native crypto dependency is Mbed TLS v3.6.7, commit
068ff080b369adfac81509f9b57b2afabaf82dc5, under Apache-2.0 or GPL-2.0-or-later;
this project uses it under Apache-2.0. Its license remains in upstream/mbedtls.

The default Jollybot artwork/renderer is a separate Meta copyrighted asset,
excluded from the SDK's Apache-2.0 grant. Do not relicense it.
