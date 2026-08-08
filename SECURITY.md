# Security Policy

AES Camera encrypts photos and video on-device. Because a flaw could expose private media,
security reports are taken seriously and handled privately.

## Reporting a vulnerability

Please do NOT open a public issue for a security vulnerability.

Report it privately through one of these channels:

- GitHub private vulnerability reporting: use the "Report a vulnerability" button on the
  repository's Security tab.
- The Maintainer's contact channel at https://aescamera.dineshkumar.dev

Please include, as far as you can:

- A description of the issue and its impact.
- Steps to reproduce, or a proof of concept.
- The app version (versionName), device model, and Android version.
- Any relevant logs, keeping in mind that they may contain sensitive data.

## What to expect

- Acknowledgement of your report within 5 business days.
- An initial assessment and, if the report is valid, a plan and rough timeline for a fix.
- Coordinated disclosure: please give the Maintainer reasonable time to release a fix
  before any public discussion. Credit is given for responsible disclosure unless you
  prefer to remain anonymous.

## Scope

In scope:

- The AES Camera app in this repository: the encryption pipeline, key derivation and
  handling, vault storage, auto-lock, and screenshot protection (FLAG_SECURE).

Out of scope (examples):

- Vulnerabilities in the underlying Android platform, system libraries, or device firmware.
- Issues that require a rooted or already-compromised device, or physical access to a
  device with the vault already unlocked.
- Loss of a forgotten password. There is no key recovery by design, so forgotten
  passwords cannot be reset.

## Supported versions

Security fixes are released for the latest published version. Please update to the latest
version before reporting.
