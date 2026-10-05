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
`ssh-add` and `ssh-keygen` are the OpenSSH programs, built with LibreSSL
and upstream defaults, without distribution patches or a system-wide
`ssh_config`, so what works with `ssh` on an OpenBSD box works here,
`~/.ssh/config` included. FIDO security keys and PKCS#11 are left out,
see [Limitations](#limitations). Once a fingerprint is enrolled, private
keys are encrypted and unlocked with it.

## Features

- **OpenSSH programs** - `ssh`, `ssh-keygen`, `ssh-agent`, `ssh-add`, `scp`
  and `sftp` from OpenSSH 10.5p1, built with LibreSSL 4.3.2
- **Extra keys** - a row above the soft keyboard with `ESC` `/` `|` `-`
  `HOME` `↑` `END` `PGUP` and `TAB` `CTRL` `ALT` `~` `←` `↓` `→` `PGDN`.
  `CTRL` and `ALT` apply to the next key, typed or tapped, and `ESC`,
  `TAB`, `HOME`, `END`, arrows and page keys repeat while held
- **Saved hosts** - kept as `Host` blocks of `~/.ssh/config`: host name,
  user, port, identity file, local, remote and dynamic forwards, plus any
  other `ssh_config(5)` option. Other blocks, `Host *` and `Match`
  included, and their comments are left untouched, the comments of an
  edited host stay among its other options, but not one ending its host
  name, user, port or identity file line. ssh checks the file before
  it is saved, since one bad line would stop every host. A config or
  `known_hosts` that is not UTF-8 is never rewritten: saving or deleting
  a host, renaming a key, removing a changed host key or an Append import
  then stops with a message. A checkbox
  attaches to the last tmux session or starts one, with
  `RemoteCommand tmux a || tmux` and `RequestTTY yes`
- **Quick connect** - plain `ssh` arguments, e.g. `-p 2222 me@example.org`
  or `-J jump host`
- **Local shell** - from the menu, Android's own `sh` and toybox as the
  app user, in its home directory, with `ssh`, `scp`, `sftp`, `ssh-add`,
  `ssh-agent` and `ssh-keygen` first in `PATH`. Keys in the vault do not
  reach it, only keys in clear are found there. With All files access it
  also reads and writes your files in shared storage, under `/sdcard`
- **Encrypted keys** - private keys live in a vault sealed by the Android
  Keystore and opened with a fingerprint, see [Key storage](#key-storage)
- **Agent** - each connection gets an `ssh-agent` of its own holding the
  unlocked keys while it logs in, so `ssh`, `scp`, `sftp` and `ProxyJump`
  hosts use them without writing them to disk, and one login emptying
  its agent cannot leave another without keys. `ssh` and the file browser
  remove the keys once logged in, `scp` and `sftp` after 60 seconds.
  Agent forwarding is off, as the server could use the keys while they
  are loaded, unless asked with `-A`
- **Key management** - generate ed25519, ecdsa, rsa or mldsa44-ed25519
  keys, import, rename or delete private keys, create a missing public
  key, show, copy or share public keys
- **File browser** - from the menu of a saved host: browse, download,
  upload, rename, delete and create folders over SFTP, through `ssh`, so
  the host's options, jump hosts and keys apply, but not its port
  forwards, as with `sftp`
- **sftp and scp** - from the menu of a saved host, working from shared
  storage with All files access, from the app's private directory without
- **Export and import** - hosts, keys and settings in one file sealed with
  a passphrase, see [Moving to another phone](#moving-to-another-phone)
- **Changed host keys** - when ssh refuses a server whose key changed,
  a warning offers to remove the old key, then connecting again shows the
  fingerprint of the new one to confirm. The host is resolved from the
  local configuration with `ssh -G`, so only its own keys are removed
- **Sessions** - several at once, kept alive by a foreground service and
  reached from its notification or from Sessions in the main menu.
  Opening a second one to the same saved host, or with the same quick
  connect text, asks first, and such sessions show when they were opened
- **Terminal** - 256 and 24 bit colors, alternate screen, scroll regions,
  wide characters, DEC line drawing, bracketed paste, scrollback with a
  swipe, which sends the arrow keys to full screen programs like vim or
  less, pinch to zoom, long press to select and copy. As in xterm, a
  paste, also one from the keyboard's clipboard, turns control
  characters other than tab and newline into spaces, so that it cannot
  send ^C or end a bracketed paste
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
| All files access | Reading and writing your files with sftp, scp and the local shell, asked when sftp or scp is first used |

On Android 17 hosts on the LAN (RFC 1918, CGNAT and link-local addresses)
cannot be reached without the local network permission. Hosts reached
through a VPN or the mobile network, and port forwards on 127.0.0.1, do
not need it.

## Key storage

Private keys are sealed in `~/.ssh/keys.vault`, each with AES-256-GCM
under a random vault key. The vault key is itself sealed by an AES key of
the Android Keystore, kept in StrongBox when the phone has one that takes
it, otherwise in the TEE, that only works after a strong biometric check
and while the phone is unlocked.
There is no PIN fallback.

- Until a fingerprint is enrolled, keys stay in clear in `~/.ssh`. Once
  one is, at first start or later, the app asks whether enrolling a new
  fingerprint should destroy the keys. Destroying them stops someone who learns the PIN from
  adding a finger to use them, at the cost of every stored key.
- Removing or resetting the screen lock always destroys them. Resetting
  the key storage then also removes their public keys and certificates.
- Private keys found in clear in `~/.ssh`, existing, generated or
  imported, are moved into the vault after a fingerprint, once their
  public key sits next to them, if the vault has room and the key is not
  over 64 KiB. One named as a key of the vault never replaces it, it
  stays in clear unless it is the same key.
- Connecting, the file browser, `sftp` and `scp` ask for the fingerprint
  whenever the vault holds keys, then `ssh-add` loads the keys through
  pipes into an agent started for that connection alone, so the
  decrypted keys never touch the disk. `ssh` removes them from it as
  soon as it is logged in, through `LocalCommand`, which then takes the
  place of one set for the host. Otherwise, with `scp`, `sftp` or a
  failed login, they leave it 60 seconds after the fingerprint, or when
  the screen turns off, and the agent ends with the connection. A login
  that takes longer, such as one waiting on a new host key to be
  confirmed, goes on without them. Cancelling connects without them,
  for password logins.

Public keys stay in clear next to the vault. For an `IdentityFile` whose
private key is gone, `ssh` takes the public key next to it and finds the
private one in the agent, so `IdentityFile` lines name vault keys as
usual, and a certificate next to the key, `<key>-cert.pub`, is used too.
Renaming or deleting a key in the app takes both along.
Lines naming the `.pub` of a vault key, as older versions wrote them,
are pointed back at the key.

## Moving to another phone

Export, in the menu, writes `~/.ssh/config` with its hosts, `known_hosts`,
the keys with their public keys and certificates, and the settings to a
file sealed with a passphrase of at least
8 characters, with AES-256-GCM under a key derived by PBKDF2-HMAC-SHA256
with 600,000 iterations. Reading the keys takes a fingerprint. Android
backup stays off, since the vault cannot leave the phone that made it.

Import reads the file on the other phone and asks whether to append or
replace:

- Append adds the `Host` blocks and keys whose names are not used yet,
  the missing `known_hosts` lines and the settings not set yet. Only
  `Host` blocks naming a single host are taken: global options, `Match`
  blocks and `Host` blocks with wildcards or several names are left out,
  as they could change the hosts already there.
- Replace puts the whole `~/.ssh/config`, `known_hosts`, the keys and
  the settings of the file in place of yours. They are written first,
  then what the file does not hold is removed.

Storing keys in the vault takes a fingerprint. Keys exported without
their public key, or over 64 KiB, are written in clear, as they were,
the former moving into the vault once their public key is made. On a
phone without a fingerprint enrolled, all keys come in clear and move
into the vault once one is. Only import files you made: the
hosts of an export can run commands through `ProxyCommand` or
`LocalCommand`, and its `known_hosts` decides which servers are trusted.

## How it works

```
native/   build script for LibreSSL and OpenSSH, JNI pty helper
core/     terminal emulator, ssh_config editor, SFTP client and export format, plain Kotlin/JVM
app/      Android application
```

| | |
| --- | --- |
| **Programs** | Android only lets an app execute files from its native library directory, so each program ships as `lib<name>.so`, e.g. `libssh.so`, extracted at install time |
| **Home** | `$HOME` is the app files directory, so `~/.ssh` is private and excluded from backups and device transfers |
| **PATH** | `~/bin` holds links named after the programs and comes first in `PATH`: `ssh` finds itself there for `ProxyJump`, as do `scp`, `sftp` and `ProxyCommand ssh -W` lines |
| **getpwuid** | Bionic reports `/data` as the home of app users. The programs are linked with `-Wl,--wrap=getpwuid` and `native/homedir.c` answers `$HOME` |
| **Files** | The browser runs `ssh -s host sftp` with pipes as its standard input and output and speaks SFTP version 3 to it. ssh keeps the terminal for its messages and questions, shown while connecting |
| **Patch** | `native/openssh-android.patch` uses the OpenBSD libc `explicit_bzero()`, since bionic has neither it nor the `bzero()` function the portable fallback relies on, initialises the SSHFP resolver without the state bionic keeps private, skips the setgid calls the Android seccomp filter kills, keeps no `known_hosts.old` when ssh adds host keys and moves the `ControlMaster` socket into place with `renameat2()`, since Android refuses `link()` to apps |

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
and keeps the APKs as artifacts, and the release app bundle for Google
Play as a separate one. Local release builds, and those of pull requests
from forks, are unsigned.

## Releases

Bumping `versionName` in `app/build.gradle.kts` and pushing to `main`
publishes a release tagged `v<versionName>` with the signed APK. Pushes
whose version already has a release publish nothing.

Release APKs are signed with the key held in the repository secrets
`RASSH_KEYSTORE` (base64 PKCS12 keystore), `RASSH_KEYSTORE_PASSWORD`,
`RASSH_KEY_ALIAS` and `RASSH_KEY_PASSWORD`. Android only installs an
update signed with the same key, so keep a copy of the keystore.

## Compared with other clients

As of October 2026, from each project's own pages and sources.

| | rassh | Termux + openssh | ConnectBot | Termius | Haven |
| --- | --- | --- | --- | --- | --- |
| SSH code | OpenSSH 10.5p1 | OpenSSH 10.5p1 | sshlib | proprietary | JSch |
| License | ISC | GPLv3 | Apache-2.0 | proprietary | AGPL-3.0 |
| Hosts in `~/.ssh/config` | yes | yes | no | no | no |
| Jump hosts | yes | yes | yes | yes | yes |
| Private keys | vault sealed by the Keystore, fingerprint on every use | files | encrypted, or kept in the Keystore | app vault, or kept in the Keystore | sealed by the Keystore |
| Signing inside the Keystore | none | none | RSA, ECDSA P-256, P-384, P-521 | ECDSA P-256 (reported by users, not documented) | none |
| FIDO security keys | no | no | no | yes | yes |
| mosh | no | yes | not released | yes | yes |
| SFTP | file browser, command line | command line | no | file browser | file browser |

rassh is the only one running OpenSSH behind an SSH interface, so
`~/.ssh/config` works as on a desktop, `ProxyCommand` and `Match`
included, and new algorithms such as `mldsa44-ed25519` keys come with
OpenSSH. Termux has the same programs, as a general shell with keys in
plain files. ConnectBot and Termius can keep keys in the Keystore that
never leave it, but only RSA or ECDSA ones, no Ed25519 and no
post-quantum keys. rassh keeps Ed25519 and `mldsa44-ed25519` keys in a vault
that is useless without the Keystore key and a fingerprint, so a copy of
its files reveals nothing. They are decrypted only in memory, into the
agent of the connection, until the login. JuiceSSH is no longer on Google Play.

## Limitations

- No FIDO security keys and no PKCS#11
- No mosh
- No mouse reporting
- Combining characters are not rendered
- Screen readers such as TalkBack do not read the terminal output
- Saving a host writes its host name, user, port, identity file and
  forwards before its other options, which changes the values that win
  when its block has an `Include`. Names used in included files are not
  checked when a host is added
- The vault holds at most 64 keys, all loaded for each connection, and
  keys over 64 KiB stay out of it
- All files access (`MANAGE_EXTERNAL_STORAGE`) is restricted on Google
  Play to some app categories

## License

ISC, see [LICENSE](LICENSE). OpenSSH, LibreSSL, the Kotlin standard
library and the DejaVu fonts keep their own licenses, which the About
dialog shows.
