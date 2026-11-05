# Presence encryption implementation notes

The client-side cryptographic primitive uses X25519 for shared-secret agreement,
HKDF-SHA-256 for key derivation, and AES-256-GCM for authenticated encryption.
The relay must only transport opaque envelopes; player names and timestamps must
never be sent in a plaintext payload or written to server-side storage.

## Security limitation

Automatic first-contact key exchange without an independent identity proof is
not authenticated. A malicious relay/server operator can replace exchanged
public keys and mount a man-in-the-middle attack. This implementation therefore
provides cryptographic confidentiality against passive inspection only when
the exchanged public keys are not substituted. It must not be advertised as
fully authenticated end-to-end encryption until an independent trust mechanism
exists.

The cryptographic helper is a foundation, not a complete presence feature.
Persistent identity-key storage, protocol payload integration, encrypted
history synchronization, local-only `/online-last` registration, and tests
must be completed before release.
