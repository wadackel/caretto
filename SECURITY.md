# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/wadackel/caretto/security/advisories/new),
not in a public issue or discussion.

A useful report says what an attacker can do, under which conditions (another app on the device,
someone with the phone in hand, a backup or a device transfer), and how to reproduce it.

Caretto is maintained by one person in their spare time, so there is no guaranteed response time.
Reports are read, and a confirmed issue is fixed on `main`.

## Supported versions

Only the latest commit on `main` is supported. There are no release branches and no prebuilt APKs.

## What the app is designed to protect

[`docs/security.md`](docs/security.md) describes what the app reads, the one permission it
declares, where its settings and its log are kept, what is logged, what a backup or a device
transfer carries, and what only debug builds contain. A report showing that the app does not do
what that document says is in scope.
