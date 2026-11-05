# Presence protocol security notes

## Implemented protocol

- Clients create and persist an X25519 identity key pair in their local config directory.
- Clients exchange public keys through the server relay.
- For each peer, clients derive a shared AES-256 key using X25519 and HKDF-SHA-256.
- Presence records (player name, timestamp, and client build marker) are encrypted with AES-256-GCM before leaving the client.
- The relay stores and forwards only opaque ciphertext for each recipient/sender pair. It does not decode player names or timestamps.
- Encrypted latest-seen records are persisted in the server world file and delivered when the recipient reconnects.
- Build 121 registers the local client command `/online-last` and filters its output to records marked as Build 146. Build 146 does not register that command.
- Names and last-seen times are decrypted and displayed on the receiving client only.

## Important limitations

Automatic first-contact key exchange without an independent identity proof is not authenticated. A malicious relay/server operator can replace exchanged public keys and mount a man-in-the-middle attack. This design protects against passive inspection when keys are relayed honestly; it is **not** fully authenticated end-to-end encryption.

The relay sees routing UUIDs, public keys, connection activity, and message timing/size. The normal Minecraft server also knows the names of players who are currently connected to it. The presence history contents (names and timestamps) remain encrypted in relay storage.

A sender can only encrypt records for recipients whose public keys it has received. Therefore, a Build 121 client must have connected at least once and exchanged its key before a Build 146 sender can create history addressed to it. Records from before that first key exchange cannot be reconstructed later.

The local identity private key is stored unencrypted in the user's local config file. Protect the local game account and config directory. Do not share `personalauction-presence.properties`.

The legacy plaintext presence payload is registered only for wire compatibility and is ignored by the new relay. New clients do not send it.
