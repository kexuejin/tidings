# Security Policy

## 1. Threat Model & Trust Boundaries

Tidings (`捎话`) is a decentralized peer-to-peer messaging, file-transfer, screen-sharing and
agent protocol. It runs on a **local network** *and* **directly across the public internet**
(public IPv6), with two distinct transport-security tiers. There is still no centralized
authentication authority, directory server, or cloud signaling gateway.

### 1.1 Two transport tiers, one judgement

| Tier | When | Protection |
| :--- | :--- | :--- |
| **Plaintext** | peer is on the **LAN** | local network only — plaintext never leaves the LAN |
| **TLS + mutual pin** | peer is on the **public internet** | TLS with a pinned peer key (SPKI), **fail-closed** |

The LAN/public judgement has exactly **one** implementation:
`IpAddressUtils.isLanAddress(peer, localAddresses)` — private/loopback (RFC1918, `127/8`)
∨ link-local (`fe80::/10`) ∨ ULA (`fc00::/7`) ∨ **the peer IPv6 shares a /64 with one of our own
addresses**. It is shared by dialing, dial diagnostics, link acceptance and the post-change
re-check; see `docs/public-transport-security.md` §11 for the four call sites and why they must
not drift apart.

**Key fact: an IPv6 GUA is not the same thing as "reachable from the public internet".**
A typical Chinese home broadband LAN hands out global unicast addresses (measured on a real
device: `2001:db8:2020:cb44::/64`) — classified by address alone they "are public", yet they
are the same local segment as us. Judging by `isPrivateOrLocal` alone silently switched K1 off
in exactly those networks. The other half is **not** relaxed: a peer that is neither private nor
on our own /64 still **must** use TLS + mutual pin.

What enforces it (`core/.../transport/Link.kt`):
- the dialer only ever produces a **plaintext** target for an `isLanAddress` peer, otherwise only TLS;
- acceptance (`linkDenyDiagnostic`) answers `not-lan` to any plaintext link from a non-LAN peer (fail-closed);
- after a peer address change, the re-check (`enforcePlaintextPolicy`) only drops a connection that
  declares itself plaintext **and** is provably no longer on the LAN (fail-safe — never drops a TLS one).

**iOS has no TLS link transport yet**, so public long connections are refused by policy rather
than downgraded to plaintext (`LinkTransportFactory.ios.kt`; `docs/spike-kmp-tls.md`).

### 1.1.1 Frame budgets are part of the attack surface

The largest thing a peer can make us accept in one frame is a budget, and budgets are chosen,
not inherited. Three of them are worth stating explicitly:

| Frame | Budget | Why |
| :--- | :--- | :--- |
| `msg` / `group-text` text | UDP 800 B, **TCP 32 KiB** | 4096 B was a UDP-era number; agent replies routinely exceed it, and a rejected frame is silently lost. 32 KiB leaves room for the 24 KiB `reasoning` field inside one 64 KiB frame. |
| `agent-event` body | TCP 64 KiB | code blocks, diffs, deep reasoning |
| one-to-one `kind:"text"` | 4096 B (unchanged) | hand-typed text; no reason to widen the surface |

A sender that does not check the receiver's budget does not get an error — the receiver drops the
whole frame (`bad-payload`). So `NodeOutbound.clampGroupText` / `clampGroupReasoning` exist to make
"we never emit a frame that is guaranteed to be rejected" an invariant rather than a hope.

### 1.2 Trust roots: how a peer key becomes trusted

- A **pin** is `sha256:` + SHA-256 of the peer's SubjectPublicKeyInfo (DER), 64 hex chars.
  `TlsPin.parse` is strict; a malformed pin is *dropped* rather than failing the pairing.
- The pairing QR is **one-way**: it carries the host pin to the phone. When the host has never
  seen the phone's key it falls back to **TOFU** (trust-on-first-use), persists it to
  `stores/peer-pins.json`, and records its **source**. Once the phone also carries its pin in
  the pairing payload, new×new pairings are `pairing`-sourced and no longer TOFU.
- **TOFU is a documented downgrade path** (it has a first-use race: whoever answers first gets
  pinned). It must therefore stay diagnosable: `pinSource` (`pairing` / `tofu` / `unknown`) is
  reported by the host (`/v1/peers`) and by the phone (`files/relay/diag/link.json`). Pin files
  written before this key existed read back as `unknown` — never reported as `pairing`.
- **Verification is fail-closed** (`peerPinAccepted`): no peer certificate, no expected pin, or a
  mismatching pin all mean *reject*. A TLS failure never falls back to plaintext.
- **No forward secrecy, no rotation UI yet**: the pin is the SPKI of a long-lived self-signed
  identity. The private key stays stable and the certificate is re-issued automatically when it
  expires; key rotation / user-facing expiry prompts are still planned (P4).

### 1.3 Pairing from outside the app requires explicit confirmation

`relay://` and `relay-pair://` deep links (`exported=true` + BROWSABLE) and the debug
`app.relay.TEST_INJECT_QR` broadcast are **outside the app's own UI**. They no longer pair on
arrival: the payload is only *registered*, the user sees a confirmation dialog stating which
node it is and — only when the payload really carries one — that its key will be pinned.
**Declining has zero side effects** (no node, no candidate pool, no pin). Storing an externally
supplied key as a trust root is an irreversible trust decision, so it is treated as a
destructive action: conservative, never implicit.

### 1.4 Agent sandbox & grants

- External or local agent execution relies on host approvals; unapproved actions and unauthorized
  control frames are blocked at the capability layer (`CAP_AGENT_QUEUE`).
- Authorization is fine-grained per workspace and per access level (`agr1`). The host filters
  before delivery, so **silence means unauthorized** (no data, no request flow, no guessing).
- A button's wording caps what it can grant: "collaborate" is at most read/write, only
  "my devices" reaches owner.
- Workspace→group binding matches on three keys; an ambiguous match is dropped (fail-closed)
  rather than delivered to a guessed group.

### Known Protocol Scope & Non-Goals

- **No centralized routing / cloud signaling.** Public reachability uses direct IPv6; we do no
  NAT traversal or hole punching. The peer's router must allow inbound IPv6 — that is the user's
  network to configure, not ours.
- **Cloud push is designed, not implemented** (`docs/cloud-push-design.md`).
- **Cross-network screen sharing is not implemented** — screen assistance still requires the same
  LAN. Cross-network *file* transfer is implemented (second TCP connection, reusing `file-ctl` /
  raw stream / resume); the end-to-end send on a real device is still pending acceptance.
- **Opening an attachment by tapping it is not implemented** (we carry `attachmentId` but no
  `fileId`/path yet).
- **No forward secrecy / asymmetric message signing**; key rotation is planned (P4).

---

## 2. Supported Versions

| Version | Supported |
| :--- | :--- |
| `main` branch (latest commit) | :white_check_mark: |
| Historical releases | :x: |

---

## 3. Reporting a Vulnerability

We take the security of Tidings seriously. If you discover a security vulnerability, please do **NOT** open a public issue.

Instead, please report the vulnerability privately:
1. **GitHub Private Vulnerability Reporting**: Use the "Report a vulnerability" button in the **Security** tab of the GitHub repository.
2. **Email Disclosure**: If GitHub Private Vulnerability Reporting is unavailable, send an encrypted email with detailed reproduction steps to the repository maintainers.

### What to Include
- Detailed description of the vulnerability and attack vector.
- Minimum reproducible example (PoC script, Wireshark packet capture, or test case).
- Potential impact on local network nodes (e.g. DoS, path traversal, sandbox breakout, unauthorized approvals).

We will acknowledge receipt within 48 hours and work with you on validation and coordinated disclosure.
