# Contributing to AES Camera

Thanks for your interest in improving AES Camera. This document explains how to contribute.

## Before you start

- For anything beyond a small fix, please open an issue first to discuss the approach. This
  avoids duplicated or unwanted work.
- Keep changes focused. One logical change per pull request is easier to review.

## Building and running

See the Building section of the [README](README.md) for JDK, SDK, and Gradle requirements.
CameraView is a git submodule, so clone with `--recurse-submodules` (or run
`git submodule update --init` in an existing clone). In short:

    git submodule update --init
    ./gradlew :app:assembleDebug

## Code style

- Kotlin, matching the style of the surrounding code.
- Keep functions small and names descriptive; follow the existing naming and comment
  density.
- Do not introduce new dependencies without discussing it in an issue first.

## Before opening a pull request

Please make sure the project builds and passes lint:

    ./gradlew :app:assembleDebug
    ./gradlew :app:lintDebug

If you changed behavior, describe how you tested it in the pull request.

## Security issues

Please do not report security vulnerabilities in public issues. Because AES Camera is an
encryption app, responsible disclosure matters: contact the Maintainer privately through
https://aescamera.dineshkumar.dev and allow time for a fix before any public discussion.

## License of contributions

By contributing, you agree that your contributions are licensed under the GPLv3, the same
license as the project.
