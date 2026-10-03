<p align="center">
  <img src="docs/logo.svg" width="128" alt="rassh">
</p>

<h1 align="center">rassh</h1>

<p align="center">
  <a href="https://github.com/renaudallard/rassh/releases/latest">
    <img src="https://img.shields.io/github/v/release/renaudallard/rassh?label=version&style=flat-square&sort=semver" alt="Latest release"/>
  </a>
  <a href="https://github.com/renaudallard/rassh/releases">
    <img src="https://img.shields.io/github/downloads/renaudallard/rassh/total?style=flat-square&label=downloads" alt="Downloads"/>
  </a>
  <a href="https://github.com/renaudallard/rassh/actions/workflows/build.yml">
    <img src="https://img.shields.io/github/actions/workflow/status/renaudallard/rassh/build.yml?style=flat-square&label=build" alt="Build"/>
  </a>
  <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white&style=flat-square" alt="Android 13 or newer"/>
  <img src="https://img.shields.io/badge/OpenSSH-10.5p1-1C2833?style=flat-square" alt="OpenSSH 10.5p1"/>
  <img src="https://img.shields.io/badge/LibreSSL-4.3.2-F2C232?style=flat-square" alt="LibreSSL 4.3.2"/>
  <a href="./LICENSE">
    <img src="https://img.shields.io/badge/license-ISC-green.svg?style=flat-square" alt="ISC license"/>
  </a>
  <a href="https://www.paypal.me/RenaudAllard">
    <img src="https://img.shields.io/badge/PayPal-Donate-blue.svg?logo=paypal&style=flat-square" alt="PayPal"/>
  </a>
</p>

<p align="center">
  <b>An SSH client for Android built around the real OpenSSH.</b><br/>
  OpenSSH 10.5p1 and LibreSSL 4.3.2 running on a pseudo-terminal, an xterm
  compatible terminal emulator, and the keys a phone keyboard lacks.
</p>

---

There is no SSH reimplementation: `ssh`, `sftp`, `scp`, `ssh-agent`,
`ssh-add` and `ssh-keygen` are the OpenSSH programs, so what works with
`ssh` on a Unix box works here, `~/.ssh/config` included. Private keys are
encrypted and unlocked with your fingerprint.

## Features

- **OpenSSH programs** - `ssh`, `ssh-keygen`, `ssh-agent`, `ssh-add`, `scp`
  and `sftp` from OpenSSH 10.5p1, built with LibreSSL 4.3.2
- **Extra keys** - a row above the soft keyboard with `ESC` `/` `|` `-`
  `HOME` `↑` `END` `PGUP` and `TAB` `CTRL` `ALT` `~` `←` `↓` `→` `PGDN`.
  `CTRL` and `ALT` apply to the next key, typed or tapped, and arrows and
  page keys repeat while held
- **Saved hosts** - kept as `Host` blocks of `~/.ssh/config`: host name,
  user, port, identity file, local, remote and dynamic forwards, plus any
  other `ssh_config(5)` option. Comments, `Host *` and `Match` blocks are
  left untouched
- **Quick connect** - plain `ssh` arguments, e.g. `-p 2222 me@example.org`
  or `-J jump host`
- **Encrypted keys** - private keys live in a vault sealed by the Android
  Keystore and opened with a fingerprint, see [Key storage](#key-storage)
- **Agent** - one `ssh-agent` holds the unlocked keys while logging in,
  so `ssh`, `scp`, `sftp` and `ProxyJump` hosts use them without writing
  them to disk. The keys leave it once logged in, so a forwarded agent is
  empty
- **Key management** - generate ed25519, ecdsa, rsa or mldsa44-ed25519
  keys, import private keys, rename keys, show, copy or share public keys
- **sftp and scp** - from the menu of a saved host, working from shared
  storage with All files access, from the app's private directory without
- **Export and import** - hosts, keys and settings in one file sealed with
  a passphrase, see [Moving to another phone](#moving-to-another-phone)
- **Sessions** - several at once, kept alive by a foreground service.
  Opening a second one to the same server asks first, and sessions to the
  same server show when they were opened
- **Terminal** - 256 and 24 bit colors, alternate screen, scroll regions,
  wide characters, DEC line drawing, bracketed paste, scrollback with a
  swipe, pinch to zoom, long press to select and copy
- **Theme** - light or dark like the phone, with its accent color, the
  terminal included. Dark is true black, which turns OLED pixels off
- **Font** - DejaVu Sans Mono bundled, since some vendor themes swap the
  system monospace font for a proportional one
- **Hardware keyboards** - arrows, Home, End, Page Up and Down, Insert,
  Delete, F1 to F12, Ctrl and Alt

---

## Install

Download `rassh-v<version>.apk` from the
[latest release](https://github.com/renaudallard/rassh/releases/latest)
and install it. It needs Android 13 or newer on an `arm64-v8a` or
`x86_64` device.

| Permission | Why |
| --- | --- |
| Internet | Connecting to servers |
| Local network | Reaching hosts on the LAN, asked on Android 17 and later |
| Notifications | The notification keeping sessions alive |
| Foreground service | Keeping sessions open in the background |
| Biometric | Unlocking the encrypted keys |
| All files access | Reading and writing your files with sftp and scp, asked when first used |

On Android 17 hosts on the LAN (RFC 1918, CGNAT and link-local addresses)
cannot be reached without the local network permission. Hosts reached
through a VPN or the mobile network, and port forwards on 127.0.0.1, do
not need it.

## Key storage

Private keys are sealed in `~/.ssh/keys.vault`, each with AES-256-GCM
under a random vault key. The vault key is itself sealed by an AES key of
the Android Keystore, kept in StrongBox when the phone has one, that only
works after a strong biometric check and while the phone is unlocked.
There is no PIN fallback.

- At first start the app asks whether enrolling a new fingerprint should
  destroy the keys. Destroying them stops someone who learns the PIN from
  adding a finger to use them, at the cost of every stored key.
- Removing or resetting the screen lock always destroys them.
- Private keys found in clear in `~/.ssh`, existing, generated or
  imported, are moved into the vault after a fingerprint, once their
  public key sits next to them.
- Connecting, `sftp` and `scp` ask for the fingerprint, then `ssh-add`
  loads the keys into the agent through pipes, so the decrypted keys
  never touch the disk. `ssh` removes them from the agent as soon as it
  is logged in, through `LocalCommand`. Otherwise, with `scp`, `sftp` or
  a failed login, they leave the agent after 60 seconds, or when the
  screen turns off. Cancelling connects without them, for password
  logins.
- The keys screen can unlock them on demand, for 60 seconds at most,
  list the agent keys and remove them from the agent.

Public keys stay in clear next to the vault. Since `ssh` skips an
`IdentityFile` whose private key is gone, but takes a public key and finds
the private one in the agent, `IdentityFile` lines naming a vault key are
pointed at its `.pub`, and the host editor offers them that way.

## Moving to another phone

Export, in the menu, writes the hosts of `~/.ssh/config`, `known_hosts`,
the keys and the settings to a file sealed with a passphrase of at least
8 characters, with AES-256-GCM under a key derived by PBKDF2-HMAC-SHA256
with 600,000 iterations. Reading the keys takes a fingerprint. Android
backup stays off, since the vault cannot leave the phone that made it.

Import reads the file on the other phone and asks whether to append or
replace:

- Append adds the `Host` blocks and keys whose names are not used yet,
  the missing `known_hosts` lines and the settings not set yet. Global
  options, wildcard `Host` and `Match` blocks of the file are left out,
  as they could change the hosts already there.
- Replace removes the hosts, keys and settings first, and empties the
  agent.

Storing the keys takes a fingerprint.

## How it works

```
native/   build script for LibreSSL and OpenSSH, JNI pty helper
core/     terminal emulator, ssh_config editor and export format, plain Kotlin/JVM
app/      Android application
```

| | |
| --- | --- |
| **Programs** | Android only lets an app execute files from its native library directory, so each program ships as `lib<name>.so`, e.g. `libssh.so`, extracted at install time |
| **Home** | `$HOME` is the app files directory, so `~/.ssh` is private and excluded from backups and device transfers |
| **PATH** | `~/bin` holds links named after the programs and comes first in `PATH`: `ssh` finds itself there for `ProxyJump`, as do `scp`, `sftp` and `ProxyCommand ssh -W` lines |
| **getpwuid** | Bionic reports `/data` as the home of app users. The programs are linked with `-Wl,--wrap=getpwuid` and `native/homedir.c` answers `$HOME` |
| **Patch** | `native/openssh-android.patch` uses the OpenBSD libc `explicit_bzero()`, since bionic has neither it nor the `bzero()` function the portable fallback relies on, initialises the SSHFP resolver without the state bionic keeps private, and skips the setgid calls the Android seccomp filter kills |

## Building

You need JDK 17 or later, the Android SDK with platform 37, and NDK r30.
`native/build.sh` runs on x86_64 Linux and on macOS, where the NDK
toolchain is.

```sh
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370
native/build.sh
./gradlew :core:test :app:assembleDebug
```

`native/build.sh` downloads the LibreSSL and OpenSSH release tarballs from
cdn.openbsd.org, checks their SHA-256 and builds `arm64-v8a` and `x86_64`,
or only the ABIs given as arguments. The programs go to
`app/src/main/jniLibs`, with the license files shown in the About dialog.

The workflow in `.github/workflows/build.yml` does the same on every push
and keeps the APKs as artifacts. Local and pull request release builds
are unsigned.

## Releases

Bumping `versionName` in `app/build.gradle.kts` and pushing to `main`
publishes a release tagged `v<versionName>` with the signed APK. Pushes
whose version already has a release publish nothing.

Release APKs are signed with the key held in the repository secrets
`RASSH_KEYSTORE` (base64 PKCS12 keystore), `RASSH_KEYSTORE_PASSWORD`,
`RASSH_KEY_ALIAS` and `RASSH_KEY_PASSWORD`. Android only installs an
update signed with the same key, so keep a copy of the keystore.

## Limitations

- No FIDO security keys and no PKCS#11
- No mouse reporting
- Combining characters are not rendered
- At most 64 keys can be unlocked at once
- All files access (`MANAGE_EXTERNAL_STORAGE`) is restricted on Google
  Play to some app categories

## License

ISC, see [LICENSE](LICENSE). OpenSSH, LibreSSL and the DejaVu fonts keep
their own licenses, which the About dialog shows.
