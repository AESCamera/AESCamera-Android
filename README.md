# AES Camera

**A camera app that encrypts every photo and video the moment it's captured.** Nothing is
ever written to disk unencrypted. Media lives on-device and can only be viewed with a key only you know.

---

## What it does

- **Encrypt at the shutter.** Photos are encrypted in memory and saved straight to the
  vault. The unencrypted image never touches storage.
- **Encrypt while recording.** Video is piped through the cipher as it records
  (MPEG-2 TS -> `CipherOutputStream`), so no unencrypted video is ever written.
- **A private vault.** Browse encrypted photos and videos in a grid with encrypted
  thumbnails; open them in an in-app viewer/player that decrypts on the fly.
- **Share, still encrypted.** Send photos and videos out through the app of your choice
  as ciphertext (AES Camera has no network access of its own; the app you share to does the
  sending). Because every file is self-contained, anyone running AES Camera with the
  matching password can open a shared photo and add it to their own vault.
- **Your key, your access.** A password derives the vault key; it's held in memory only
  and never persisted. The app **auto-locks** shortly after it goes to the background.
- **Screenshot-resistant.** `FLAG_SECURE` blocks screenshots and recents-preview
  thumbnails on every screen.
- **No tracking.** No analytics, no ads, no internet permission. Only `CAMERA` and
  `RECORD_AUDIO`.

## Security model

| Asset | Scheme |
|---|---|
| Photos, thumbnails, vault verifier (`.enc` v1) | **AES-256-GCM** (authenticated; 12-byte nonce, 128-bit tag) |
| Video (`.encv` v3) | **AES-256-CTR** streaming with an **HMAC** integrity trailer |
| Key derivation | **PBKDF2-HMAC-SHA256**, random salt per file, cost stored per file |

- Every file is self-contained (its header carries the salt/nonce and KDF parameters),
  so files stay independently decryptable and the KDF cost stays tunable over time.
- The derived key is kept in an in-memory LRU cache for the session and is dropped on
  lock. It is never written to disk.

> ⚠️ **There is no key recovery.** If you forget your password, the encrypted media cannot be decrypted.

## Screenshots

Visit the [website](https://aescamera.dineshkumar.dev) for the full walkthrough.

## Building

**Requirements**

- JDK 17 or newer
- Android SDK with the **API 36** platform and build-tools
- Gradle 9.4.1 - provided by the wrapper (`./gradlew`)

**Debug build**

```bash
git clone --recurse-submodules <repo-url>
cd AESCamera
./gradlew :app:assembleDebug
```

CameraView is pulled in as a git submodule (`libs/CameraView`). If you cloned without
`--recurse-submodules`, run `git submodule update --init` before building.

The debug keystore (`debug.jks`) is committed so debug builds from any machine share a
signature and can update each other's installs.

**Release build**

Release signing values are read from `local.properties`.

```properties
sdk.dir=/path/to/Android/sdk
storeFile=/path/to/your/upload-keystore.jks
storePassword=your-keystore-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Then:

```bash
./gradlew :app:bundleRelease   # AAB for Play
./gradlew :app:assembleRelease # APK
```

## Tech stack

- **Kotlin** 2.2.10, **AGP** 9.2.1, **Gradle** 9.4.1
- **minSdk 26** (Android 8.0) - required for `MediaRecorder.OutputFormat.MPEG_2_TS`,
  which makes encrypt-while-recording possible. **compile/target SDK 36** (Android 16)
- **Material 3**, ViewBinding, ConstraintLayout, RecyclerView
- **Camera:** a vendored fork of
  [otaliastudios/CameraView](https://github.com/natario1/CameraView) 2.7.2 (MIT),
  patched to output MPEG-2 TS so recordings can be encrypted as they stream
- **Video playback:** Media3 / ExoPlayer 1.4.1, with a custom AES-CTR `DataSource`
- **Crypto:** the platform JCA (AES-GCM, AES-CTR, PBKDF2, HMAC)

## Privacy

AES Camera collects nothing and sends nothing. All media stays encrypted on your device.
It requests only the camera and microphone permissions needed to capture photos and
video. There is no internet permission.

## Credits

Camera capture is built on [CameraView](https://github.com/natario1/CameraView) by
[natario1](https://github.com/natario1) (MIT License), forked and patched for encrypted MPEG-2 TS output.

## License

Copyright © 2026 Dineshkumar T.

AES Camera is free software licensed under the **GNU General Public License v3.0** - see
[LICENSE](LICENSE). You may use, study, share, and modify it. If you **distribute** a
modified version, you must release your changes under the GPLv3 as well; the copyleft only
applies to redistributed modifications.

The `cameraview/` module is a fork of CameraView and remains under its original
**MIT License** (compatible with GPLv3).

### Contributing

Contributions are welcome under the GPLv3. Please open an issue before starting significant
work, and see [CONTRIBUTING.md](CONTRIBUTING.md) for details.
