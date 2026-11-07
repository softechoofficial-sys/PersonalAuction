# Security and privacy

This build contains only the auction GUI and local auction/payment-notice handling.

## Explicit exclusions

- No automatic balance lookup (for example, `/bal`).
- No automatic payment command (for example, `/pay`).
- No hard-coded payment recipient.
- No remote peer command channel.
- No external presence relay or periodic player-status upload.
- No automatic coordinate disclosure or remote disconnect command.

The client observes matching in-game payment confirmation messages to update the auction display. It does not initiate payments.

## Network behavior

Auction state is exchanged with the Minecraft server through the mod's auction payloads. This build does not contain a third-party HTTP relay client.

## Verification

Before distributing a release, inspect the source and build artifact, and test that no command is sent except the normal auction networking and commands explicitly entered by the player.
