# Security policy

## Reporting a vulnerability

Please do not open a public GitHub issue for security reports. Use one of:

- A **private security advisory** on GitHub (Security tab → "Report a vulnerability"), or
- A direct message to the maintainer.

Include:

- The version (`dist/<version>/` or `versionName` if you built from source).
- A clear description of the issue and the impact you observed.
- A minimal reproduction (steps, sample input, or a small patch demonstrating the flaw).
- Any logs, screenshots, or stack traces that help.

We try to acknowledge reports within 5 business days, agree on a coordinated-disclosure timeline within 10, and ship a fix within 30. Critical issues are prioritized.

## Scope

This project ships an Android client that talks to `api.hypem.com` and `hypem.com`. In-scope concerns include:

- Token / credential handling (storage, transmission, rotation).
- Cleartext traffic (the codebase explicitly disables it; report any path that re-enables it).
- Insecure deserialization or untrusted-data parsing.
- Permission misuse (declared but unnecessary permissions, exposed components).
- Dependency vulnerabilities — please cross-check with the latest `gradle/libs.versions.toml` before reporting.

Out of scope:

- Issues against the upstream Hype Machine API (`api.hypem.com`) — please report those to Hype Machine directly.
- Bugs that require a rooted device or sideloaded malicious app to exploit, unless they expose data without those preconditions.

## What we will and won't do

- We will credit you publicly in the release notes if you'd like.
- We will not pursue legal action against good-faith research that follows this policy.
- We will not pay a bounty (this is an unpaid open-source project).

## Defense in depth currently in place

- Auth token encrypted at rest with Android Keystore (AES-GCM); legacy plaintext tokens auto-migrate on read.
- `cleartextTrafficPermitted="false"` in the production network security config; debug variant whitelists `10.0.2.2`/`localhost` only.
- `HypeApiInterceptor` host-scopes the `hm_token` query parameter; release builds reject all loopback hosts.
- `UnauthorizedSessionInterceptor` clears the session when `api.hypem.com` returns HTTP 401.
- `data_extraction_rules.xml` + `allowBackup="false"` exclude the session DataStore, Room DB, and shared prefs from cloud backup and device transfer.

See the codebase comments and `CHANGELOG.md` (`[0.2.0]` notes) for details.
