# rassh

An SSH client for Android built around the real OpenSSH `ssh(1)`.

The app ships OpenSSH 10.5p1 linked against LibreSSL 4.3.2, runs `ssh`
on a pseudo-terminal and draws it with its own xterm compatible terminal
emulator. There is no SSH reimplementation: what works with `ssh` on a
Unix box works here, including `~/.ssh/config`.

Targets Android 17 (API 37), runs on Android 14 (API 34) and later.

## Features

- OpenSSH 10.5p1 `ssh` and `ssh-keygen` built with LibreSSL 4.3.2
- Extra keys row above the soft keyboard: `ESC` `/` `|` `-` `HOME` `↑`
  `END` `PGUP` on the first row, `TAB` `CTRL` `ALT` `~` `←` `↓` `→`
  `PGDN` on the second. `CTRL` and `ALT` apply to the next key, typed or
  tapped. Arrows and page keys repeat while held.
- Saved hosts, stored as `Host` blocks of `~/.ssh/config`. Host name,
  user, port, identity file, local, remote and dynamic forwards are
  editable, any other `ssh_config(5)` option can be added per host.
  Unrelated parts of the file (comments, `Host *`, `Match`) are kept.
- Quick connect with plain `ssh` arguments, e.g. `-p 2222 me@example.org`
- Key management: generate ed25519, ecdsa, rsa or mldsa44-ed25519 keys
  with `ssh-keygen` (passphrases are asked in a terminal), import
  private keys, show, copy or share public keys
- Several sessions at once, kept alive by a foreground service
- 256 colors and 24 bit color, alternate screen, scroll regions, wide
  characters, DEC line drawing, bracketed paste
- Scrollback with a swipe (in full screen programs a swipe sends the
  cursor keys), pinch to change the font size, long press to select and
  copy
- Hardware keyboards: arrows, Home, End, Page Up/Down, Insert, Delete,
  F1 to F12, Ctrl and Alt

## Layout

```
native/   build script for LibreSSL and OpenSSH, JNI pty helper
core/     terminal emulator and ssh_config editor, plain Kotlin/JVM
app/      Android application
```

`$HOME` is the app files directory, so keys, `known_hosts` and `config`
live in its `.ssh` directory, which is excluded from backups and device
transfers.

Android only lets an app execute files from its native library
directory, so `ssh` and `ssh-keygen` are packaged as `libssh.so` and
`libssh-keygen.so` and extracted at install time.

Bionic reports `/data` as the home directory of application users and
OpenSSH finds `~/.ssh` through `getpwuid(3)`. The binaries are linked
with `-Wl,--wrap=getpwuid` and `native/homedir.c` substitutes `$HOME`, so
OpenSSH itself needs a single patch, `native/openssh-resolver.patch`, as
bionic keeps the resolver state used for SSHFP lookups private.

## Building

Requirements: JDK 17 or later, the Android SDK with platform 37 and
NDK r30. The NDK only runs on x86_64 Linux and macOS hosts.

```sh
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370
native/build.sh
./gradlew :core:test :app:assembleDebug
```

`native/build.sh` downloads the LibreSSL and OpenSSH release tarballs
from cdn.openbsd.org, checks their SHA-256 and builds `arm64-v8a` and
`x86_64`. Pass ABI names to build only some of them. The results go to
`app/src/main/jniLibs` along with the license files shown in the About
dialog.

The GitHub workflow in `.github/workflows/build.yml` does the same and
publishes the debug and unsigned release APKs as artifacts.

## Limitations

- Only `ssh` and `ssh-keygen` are included: no `ssh-agent`, `scp` or
  `sftp` yet
- No FIDO security keys and no PKCS#11
- No mouse reporting
- Combining characters are not rendered
