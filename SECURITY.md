# Security Policy

## 1. Threat Model & Trust Boundaries

Tidings (`捎话`) is a decentralized, local-area-network (LAN) peer-to-peer messaging and file transfer protocol.

### Trust Assumptions
- **Local Network Perimeter**: The protocol assumes nodes reside on a trusted local network (LAN / Wi-Fi). By design, there is no centralized authentication authority, directory server, or cloud signaling gateway.
- **Identity via UUIDv4**: Peer identities (`nodeId`) are randomly generated UUIDv4 strings. In the current iteration (PPE-1 / core transport), peer identities are verified through dynamic hello handshakes and ephemeral IP/port mappings.
- **Agent Sandbox & Approvals**: External or local agent execution relies on host approvals. Unapproved actions and unauthorized control frames are blocked at the capability layer (`CAP_AGENT_QUEUE`).
- **File Transfers**: Large file transfers require explicit user action ("Accept" or "Decline") prior to disk streaming and are strictly constrained to application sandboxes (`downloads/`).

### Known Protocol Scope & Non-Goals
- **No Global Public Internet Routing**: Tidings does not provide end-to-end encryption across untrusted public relays or WAN NAT-traversal by default. Running Tidings across an untrusted shared network without VPN tunneling is not recommended.
- **No Forward Secrecy / Asymmetric Identity**: PPE-1 focuses on LAN discovery and zero-setup coordination. Cryptographic asymmetric keypairs and message signing are scheduled for future protocol iterations.

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
