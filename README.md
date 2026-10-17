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
  <img src="https://img.shields.io/badge/OpenSSH-10.6p1-1C2833?style=flat-square" alt="OpenSSH 10.6p1"/>
  <img src="https://img.shields.io/badge/LibreSSL-4.3.3-F2C232?style=flat-square" alt="LibreSSL 4.3.3"/>
  <a href="./LICENSE">
    <img src="https://img.shields.io/badge/license-ISC-green.svg?style=flat-square" alt="ISC license"/>
  </a>
  <a href="https://www.paypal.me/RenaudAllard">
    <img src="https://img.shields.io/badge/PayPal-Donate-blue.svg?logo=paypal&style=flat-square" alt="PayPal"/>
  </a>
</p>

<p align="center">
  <b>An SSH client for Android built around the real OpenSSH.</b><br/>
  OpenSSH 10.6p1 and LibreSSL 4.3.3 running on a pseudo-terminal, an xterm
  compatible terminal emulator, and the keys a phone keyboard lacks.
</p>

---

There is no SSH reimplementation: `ssh`, `sftp`, `scp`, `ssh-agent`,
`ssh-add` and `ssh-keygen` are the OpenSSH programs, built with LibreSSL
and upstream defaults, without distribution patches or a system-wide
`ssh_config`, so what works with `ssh` on an OpenBSD box works here,
`~/.ssh/config` included, apart from agent forwarding and
`LocalCommand`, see [Agent](#features) and [Key storage](#key-storage).
FIDO security keys and PKCS#11 are left out, see
[Limitations](#limitations). Once a fingerprint is enrolled, private
keys are encrypted and unlocked with it.

## Features

- **OpenSSH programs** - `ssh`, `ssh-keygen`, `ssh-agent`, `ssh-add`, `scp`
  and `sftp` from OpenSSH 10.6p1, built with LibreSSL 4.3.3
- **Extra keys** - two rows below the terminal, `ESC` `/` `|` `-`
  `HOME` `↑` `END` `PGUP` and `TAB` `CTRL` `ALT` `~` `←` `↓` `→` `PGDN`.
  `CTRL` and `ALT` apply to the next key, typed or tapped, and `ESC`,
  `TAB`, `HOME`, `END`, arrows and page keys repeat while held
- **Saved hosts** - kept as `Host` blocks of `~/.ssh/config`: host name,
  user, port, identity file, local, remote and dynamic forwards, plus
  any other `ssh_config(5)` option. Other blocks, `Host *` and `Match`
  included, and their comments are left untouched, the comments of an
  edited host stay among its other options, but not one ending its host
  name, user, port or identity file line. Further `Host` blocks of the
  same name only change with it: renaming the host renames them,
  deleting it deletes them. ssh checks the file before it is saved, for
  each host its `Host` lines name and for a name they do not, like those
  of quick connect, since one bad line would stop them all: ssh reads
  every block, and an option a host's own `IgnoreUnknown` lets pass
  stops the others. Another host ssh refused already does not stop the
  change. A file changed during that check is not overwritten. A config
  or `known_hosts` that is not UTF-8 is never rewritten: saving,
  deleting or moving a host, renaming a key, removing a changed host key
  or an Append import then stops with a message. One that is a link is
  rewritten in the file it names, but a changed host key is only offered
  for removal from a `known_hosts` in `~/.ssh`. Neither is read when
  over 1 MiB, which could take all the memory of the app, nor made so
  large by the app, an import or a save for one, nor is a vault over 16
  MiB. A checkbox attaches to the last tmux session or starts one, with
  `RemoteCommand tmux a || tmux` and `RequestTTY yes`. Another, under
  the identity file, makes `ssh` use only that key, and those other
  blocks such as `Host *` name, with `IdentitiesOnly yes`, without
  trying the other keys of the agent when the server refuses it. Common
  options, such as `ServerAliveInterval`, `Compression`,
  `StrictHostKeyChecking` or `LogLevel`, have a list of values each.
  Left at Default, an option writes no line, and the value shown next to
  Default is what `ssh -G` gives the saved host without it, from its
  other options, `Host *` blocks or ssh itself. A long press on a host
  opens its menu, dragging it then to another row moves its block in the
  file, but only past the blocks of other saved hosts: ssh takes the
  first value it finds, past a `Host *` or `Match` block the host would
  get other options. Nor does it move when its block or one it would
  pass has an `IgnoreUnknown` or `Include` line, as ssh skips an option
  it does not know only after an `IgnoreUnknown` line naming it, which
  an included file may hold
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
  remove the keys once logged in, `scp`, `sftp` and either of the first
  two reusing a `ControlMaster` connection after 60 seconds. Agent
  forwarding is off, as the server could use the keys while they are
  loaded. `-A` turns it on, but ssh only forwards the agent once logged
  in, when the keys have left it, so no vault key reaches the server.
  The agent also serves nothing to a connection ssh forwards with agent
  forwarding, so a jump host whose block sets `ForwardAgent yes`, read by
  its own ssh without the app's options, gets no key either
- **Key management** - generate ed25519, ecdsa, rsa or mldsa44-ed25519
  keys, import, rename or delete private keys, create a missing public
  key, show, copy or share public keys. OpenSSH 10.6 renamed the
  experimental `ssh-mldsa44-ed25519@openssh.com` keys, mldsa44-ed25519
  keys made with older versions of rassh no longer load and must be
  generated again
- **File browser** - from the menu of a saved host: browse, download,
  upload, rename, delete files and empty folders and create folders over
  SFTP, through `ssh`, so the host's options, jump hosts and keys apply,
  but not its port forwards nor its `LocalCommand`, as with `sftp`. An
  upload first shows the names the files get on the server, as the app
  they come from gives them, those that exist there already and would be
  replaced too, one per line, with `_` for a slash and for control, line
  break and bidi characters, which could hide or disguise another name,
  and a name over 255 bytes becomes `upload`. A transfer cut short by a
  lost connection, or by the browser going away, swiped from the recent
  apps for one, is offered to resume the next time the browser connects
  to that host. A download goes on if the file did not change on the
  server and the one on the phone is a regular file it can append to
  that did not change, its size and time checked. Changed on the phone,
  it is left alone and the download stops, as it may be another file
  now. An upload goes on if the file on the phone is a regular file that
  did not change, its size, time and the SHA-256 of what was sent
  checked, and the server holds no more than was sent, nor less than all
  but its last 2 MiB. It writes again the last 2 MiB the server holds,
  as some of the 64 requests of 32 KiB in flight may not have landed.
  Otherwise the file starts again. A file of its name on the server that
  the upload could not have left, and the files after it that exist
  there by then, are replaced only once asked. A transfer stopped, also
  by closing its session from the terminal screen, or refused by the
  server is not offered: a download removes its partial file, an upload
  the file it created if the connection still works, but not one a
  resumed upload found there. Deleting or editing the host drops what
  was cut short, as does a Replace import. A transfer still running then
  and cut afterwards is offered again if the host kept its name, or
  dropped when the app starts again. A dropped download removes its
  partial file only if its size and time, when known, did not change
  since it was cut
- **sftp and scp** - from the menu of a saved host, working from shared
  storage with All files access, from the app's private directory without
  it
- **Export and import** - hosts, keys and settings in one file sealed with
  a passphrase, see [Moving to another phone](#moving-to-another-phone)
- **Changed host keys** - when `ssh`, `sftp` or the file browser is
  refused by a server whose key changed, a warning offers to remove the
  old key, then connecting again shows the fingerprint of the new one to
  confirm, unless `StrictHostKeyChecking` is `accept-new`, which takes
  it without asking, or `yes`, which refuses it: the warning says so, as
  does the message once it is removed when no pattern line is left.
  The host is resolved from the local configuration with `ssh -G`, so
  only the lines matching it are removed. From a line also naming other
  hosts only its name goes, with the addresses there when ssh checks the
  one it reaches, with `CheckHostIP yes`, no proxy and a `HostName` that
  is neither `localhost` nor a loopback address, which ssh never checks,
  nor another address unless a `HostKeyAlias` names the host off port
  22, as it would refuse a new key differing from that one, or ask each
  time, and a pattern line is left, as other hosts may match it, unlike
  with `ssh-keygen -R`, which removes whole lines: the warning, or a
  message when nothing else can go, then says to edit `known_hosts` by
  hand, as ssh would still refuse the old key there. When ssh checks it
  the warning also says the address may keep the old key on another
  line, of its own, hashed for instance, or with another name of the
  same server, which nothing finds. Another name resolving to a loopback
  address is taken as checked. With a `LogLevel` below `INFO` nothing is
  offered, as the server could then print ssh's lines itself
- **Sessions** - several at once, kept alive by a foreground service and
  reached from its notification or from Sessions in the main menu.
  Opening a second one to the same saved host, with `ssh`, `sftp` or
  `scp`, or with the same quick connect text, asks first. Sessions
  reaching the same server, local shells included, show when they were
  opened, as do file browsers of the same host
- **Terminal** - 256 and 24 bit colors, alternate screen, scroll
  regions, wide characters, DEC line drawing, bracketed paste, reverse
  video, scrollback with a swipe, which sends the arrow keys to full
  screen programs like vim or less, pinch to zoom, long press to select
  and copy. A paste, also one from the keyboard's clipboard, turns
  control characters other than tab and newline into spaces, more than
  xterm does by default, so that it cannot send ^C or end a bracketed
  paste. The keyboard is asked not to learn what is typed, passwords at
  ssh or sudo prompts included, nor what is typed in the app's own
  fields. A session ending inside a full screen program, a connection
  lost in tmux for one, goes back to the main screen and its history,
  followed by what the program's screen showed last, where ssh says why
  it ended
- **Theme** - light or dark like the phone, with its accent color, the
  terminal included. Dark is true black, which turns OLED pixels off
- **Font** - DejaVu Sans Mono bundled, since some vendor themes swap the
  system monospace font for a proportional one
- **Hardware keyboards** - Esc, Tab and Shift-Tab, arrows, Home, End,
  Page Up and Down, Insert, Delete, F1 to F12, Ctrl and Alt, right Alt
  as AltGr and dead keys. With a layout such as Russian or Greek, Ctrl
  and Alt apply to the Latin letter of a key giving a non-Latin letter,
  not of one giving punctuation, as Q does in Greek

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
| Hide overlays | Hiding the windows of apps allowed to display over other apps while rassh shows, where a faint prompt could lead taps onto Delete or a host key removal. Screen dimmers, chat heads or a recorder's controls drawn that way go as well, on the whole display, until rassh leaves the screen |
| All files access | Reading and writing your files with sftp, scp and the local shell, asked each time sftp or scp starts without it, which can go on in the app's private directory instead |

On Android 17 hosts on the LAN (RFC 1918, CGNAT and link-local addresses)
cannot be reached without the local network permission. Hosts reached
through a VPN or the mobile network, and port forwards on 127.0.0.1, do
not need it.

Local and dynamic forwards listen on 127.0.0.1 and ::1 unless a bind
address or `GatewayPorts` says otherwise, and Android shares those
between all apps: while the connection is up, any app on the phone can
use them, and a dynamic forward lets it reach whatever the server can.

rassh collects no data, see the [privacy policy](PRIVACY.md), also
linked from the About dialog.

## Key storage

Private keys are sealed in `~/.ssh/keys.vault`, each with AES-256-GCM
under a random vault key. The vault key is itself sealed by an AES key of
the Android Keystore, kept in StrongBox when the phone has one that takes
it, otherwise in the TEE, that only works after a strong biometric check
and while the phone is unlocked.
There is no PIN fallback.

- Until a fingerprint is enrolled, keys stay in clear in `~/.ssh`. Once
  one is, at first start or later, the app asks whether enrolling a new
  fingerprint should destroy the keys. Destroying them stops someone who
  learns the PIN from adding a finger to use them, at the cost of every
  stored key. Android then destroys them too when every fingerprint is
  removed.
- Removing or resetting the screen lock always destroys them. Resetting
  the key storage then also removes their public keys and certificates.
- Private keys found in clear in `~/.ssh`, existing, generated or
  imported, are moved into the vault after a fingerprint, once their
  public key sits next to them, if the vault has room and neither key is
  over 64 KiB, a larger public key being taken as missing. One named as
  a key of the vault never replaces it, it stays in clear unless it is
  the same key. Names hold letters, digits, `.`, `_` and `-` only, as
  they go into `IdentityFile` lines, and neither end in `.tmp` nor take
  the name of another file there, `config` or `known_hosts` for
  instance: a key named otherwise, made in the local shell for instance,
  is listed with a note, stays out of the vault and stops exports until
  renamed. Nor can a key be given a name starting with a dot, left out
  as hidden, or ending in `.pub` or `-cert`, those of the files that go
  with a key. A key that is a link stays in clear, the file it names
  would be left behind.
- Connecting, the file browser, `sftp` and `scp` ask for the fingerprint
  whenever the vault holds keys, then `ssh-add` loads the keys through
  pipes into an agent started for that connection alone, so the
  decrypted keys never touch the disk. `ssh` removes them from it as
  soon as it is logged in, through `LocalCommand`, which then takes the
  place of one set for the host, with `PermitLocalCommand` turned on:
  with `EnableEscapeCommandline` set, `!` then runs a local command from
  the `~C` command line as well. Otherwise, with `scp`, `sftp`, an
  `ssh` or the file browser reusing a `ControlMaster` connection, which
  does not log in itself, or a failed login, they leave it 60 seconds
  after the fingerprint, or when the screen turns off, and the agent ends
  with the connection. A login that takes longer, such as one waiting on
  a new host key to be confirmed, goes on without them. The Cancel button
  of the prompt, or an error such as too many attempts, connects without
  them, for password logins, while closing it with Back, leaving the app
  or turning the screen off connects nothing.

Public keys stay in clear next to the vault. For an `IdentityFile` whose
private key is gone, `ssh` takes the public key next to it and finds the
private one in the agent, so `IdentityFile` lines name vault keys as
usual, and a certificate next to the key, `<key>-cert.pub`, is used too.
Renaming or deleting a key in the app takes both along, and renaming
also updates the `IdentityFile` and `CertificateFile` lines of
`~/.ssh/config` naming them.
Lines naming the `.pub` of a vault key, as older versions wrote them,
are pointed back at the key.

Root can read every file, but a copy of the vault reveals nothing: the
Keystore key never leaves the TEE or StrongBox, and every use of it
needs a fingerprint checked there. Keys still in clear are read
directly. Root can also run code inside the app or the agent, so it
can take the decrypted keys at the next fingerprint, or sign with them
until they leave the agent. Keys on a phone where untrusted code ran
as root should be treated as exposed and replaced.

A program running as the app can do the same without root. Anyone who
had the phone unlocked in hand can start one from the local shell, and
it keeps running as long as the app does. It can then read the keys
`ssh-add` loads at the next fingerprint, or use them through the agent
of the connection while they are in it. Before the fingerprint,
connecting lists the programs of the app running outside the
foreground of its sessions, such as those put in the background in the
local shell, `ssh -f` or a `ControlPersist` connection, and offers to
end them. One can still hide among the processes of an open session:
when the phone was left unlocked out of your hands, close every
session, or force-stop the app, before connecting.

## Moving to another phone

Export, in the menu, writes `~/.ssh/config` with its hosts, `known_hosts`,
the keys of `~/.ssh` with their public keys and certificates, and the
font size to a file sealed with a passphrase of at least 12
characters, with AES-256-GCM under a key derived by PBKDF2-HMAC-SHA256
with 600,000 iterations. Taking the keys out of the vault for it takes
a fingerprint, but in the file they are only behind the passphrase:
anyone getting the file can try passphrases offline, choose a long
one. Android backup stays off, since the vault cannot leave the phone
that made it. Files read through `Include` and keys kept outside
`~/.ssh` are not exported. A key named as early versions or the local
shell allowed, with an `@` for instance, must be renamed first, and an
export stops at a key holding no private key in PEM form, a lone
certificate for one, naming it, as no import would take either.

Import on the other phone first asks whether to append or replace, then
reads the file, refusing it when one of its keys holds no private key in
PEM form, and writes of a key only its PEM blocks, what ssh reads of it:
any other line could make it a file ssh reads otherwise, a known_hosts
the config names for one, as the key screen does with a key file:

- Append adds the `Host` blocks and keys whose names are not used yet,
  the `known_hosts` lines of hosts not known here yet, in `known_hosts`
  or `known_hosts2`, the files ssh reads by default, also through a
  hashed line, a pattern or a line of the other kind, key or
  `@cert-authority`, but not a `@revoked` line, as ssh would trust again
  an old key of one, removed here once changed, or a certificate a new
  authority signed, hashed and `@revoked` lines as they are, but no key
  or `@cert-authority` line with a wildcard in a name not negated, which
  could match a host known here, and the font size if not set yet. Only
  `Host` blocks naming a single host are taken: global options, `Match`
  blocks and `Host` blocks with wildcards or several names are left out,
  as they could change the hosts already there, and so is a block named
  as the `HostName` or a `ProxyJump` host of a host or a `Host` pattern
  there, as ssh reads the block of a jump host for it, and that of its
  `HostName` after a `Match final` or canonicalization. Every word of a
  `ProxyJump` value counts, a user or a port too, as a shell splits it
  for ssh, and no `Host` block comes in when such a value holds `$`, a
  backquote, `~`, `*`, `?`, `{` or `}`, which the shell would expand, or
  a word starting with `-`, which ssh would take as an option. A jump
  host named with `%` tokens or escapes, or reached through
  `ProxyCommand`, is not seen. ssh checks the result first, as when
  saving one, and nothing is imported if it refuses it: a host relying
  on a global `IgnoreUnknown` line left out would make it refuse them
  all. A host added whose key has the name of one already there uses
  that one, not the key of the file.
- Replace puts the whole `~/.ssh/config`, `known_hosts`, the keys and
  the font size of the file in place of yours. They are written first,
  then what the file does not hold is removed, but for keys named in a
  way the app does not take, see [Key storage](#key-storage), which
  stay.

Storing keys in the vault takes a fingerprint, cancelling it imports
nothing, as does leaving the app while the file is read, since Android
then closes the prompt, and an import that would put more than 64 keys
in it stops before writing anything. Keys exported without their public
key, or over 64 KiB, are written in clear, as they were, the former
moving into the vault once their public key is made. On a phone without
a fingerprint enrolled, all keys come in clear and move into the vault
once one is.
Only import files you made: the hosts of an export can run commands
through `ProxyCommand` or `LocalCommand`, and its `known_hosts` decides
which servers are trusted.

## How it works

```
native/   build script for LibreSSL and OpenSSH, JNI pty helper
core/     terminal emulator, ssh_config editor, SFTP client and export format, plain Kotlin/JVM
app/      Android application
fastlane/ store descriptions, screenshots and changelogs, read by F-Droid
```

| | |
| --- | --- |
| **Programs** | Android only lets an app execute files from its native library directory, so each program ships as `lib<name>.so`, e.g. `libssh.so`, extracted at install time |
| **Home** | `$HOME` is the app files directory, so `~/.ssh` is private and excluded from backups and device transfers |
| **PATH** | `~/bin` holds links named after the programs and comes first in `PATH`: `ssh` finds itself there for `ProxyJump`, as do `scp`, `sftp` and `ProxyCommand ssh -W` lines |
| **getpwuid** | Bionic reports `/data` as the home of app users. The programs are linked with `-Wl,--wrap=getpwuid` and `native/homedir.c` answers `$HOME` |
| **SSHFP** | The resolver behind `res_query()` reaches no DNS server from an app, so `VerifyHostKeyDNS` found nothing. The programs are linked with `-Wl,--wrap=res_query` and `native/dns.c` sends the query to the system resolver with `android_res_nquery()`. It clears the authenticated data bit of the answer, which anyone answering on the network could set, as OpenBSD and glibc do by default: a fingerprint found shows as matching and ssh still asks |
| **Files** | The browser runs `ssh -s host sftp` with pipes as its standard input and output and speaks SFTP version 3 to it. ssh keeps the terminal for its messages and questions, shown while connecting |
| **Patch** | `native/openssh-android.patch` uses the OpenBSD libc `explicit_bzero()`, since bionic has neither it nor the `bzero()` function the portable fallback relies on, lets the SSHFP resolver code build without the state bionic keeps private, skips the setgid calls the Android seccomp filter kills, keeps no `known_hosts.old` when ssh rewrites the file for `UpdateHostKeys`, checking instead that the new file was fully written before it takes the place of the old one, and moves the `ControlMaster` socket into place with `renameat2()`, since Android refuses `link()` to apps. It also gives `ssh-agent` a `-O no-forwarded` option, which the app uses, serving nothing to a connection ssh binds as one it forwards for a server |

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
`app/src/main/jniLibs`, and their license files, shown in the About
dialog, to `app/src/main/assets/licenses`.

The core tests include a fuzz test feeding the terminal random text,
controls and escape sequences, 200 runs by default. A failure names the
seed that replays it. For a longer search:

```sh
RASSH_FUZZ_RUNS=5000 ./gradlew :core:test --tests '*TerminalFuzzTest' --rerun
```

The workflow in `.github/workflows/build.yml` does the same on every push
and keeps the APKs and the release app bundle as artifacts. A local
release build is signed when `RASSH_KEYSTORE_FILE` names the keystore
and the password and alias variables below are set. The workflow builds
without the key. For pushes to `main` only, a job of its own, on another
machine than the build and what it ran, signs the APK with `apksigner`
and the bundle for Google Play with `jarsigner`, keeping them as the
`rassh-release` and `rassh-bundle` artifacts. Those of other branches
and of pull requests stay unsigned.

## Releases

Bumping `versionName` in `app/build.gradle.kts` and pushing to `main`
publishes a release tagged `v<versionName>` with the signed APK. Pushes
whose version already has a release publish nothing. F-Droid takes the
notes of a version from
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, 500
characters at most.

Release APKs are signed with the key held in the repository secrets
`RASSH_KEYSTORE` (base64 PKCS12 keystore), `RASSH_KEYSTORE_PASSWORD`,
`RASSH_KEY_ALIAS` and `RASSH_KEY_PASSWORD`. Android only installs an
update signed with the same key, so keep a copy of the keystore.

## Compared with other clients

As of October 2026, from each project's own pages and sources.

| | rassh | Termux + openssh | ConnectBot | Termius | Haven |
| --- | --- | --- | --- | --- | --- |
| SSH code | OpenSSH 10.6p1 | OpenSSH 10.6p1 | sshlib | proprietary | JSch, sshlib in preview |
| License | ISC | GPLv3 | Apache-2.0 | proprietary | AGPL-3.0 |
| Hosts in `~/.ssh/config` | yes | yes | no | no | no |
| Jump hosts | yes | yes | yes | yes | yes |
| Private keys | vault sealed by the Keystore, fingerprint on every use | files | encrypted, or kept in the Keystore | app vault, or kept in the Keystore | sealed by the Keystore |
| Signing inside the Keystore | none | none | RSA, ECDSA P-256, P-384, P-521 | ECDSA P-256 (reported by users, not documented) | none |
| FIDO security keys | no | no | no | yes | yes |
| mosh | no | yes | yes | yes | yes |
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
  forwards before its other options, and the common options, the tmux
  and the key-only lines after them, which changes the values that win
  when its block has an `Include`. Names used in included files are not
  checked when a host is added
- The vault holds at most 64 keys, all loaded for each connection, and
  keys over 64 KiB stay out of it
- All files access (`MANAGE_EXTERNAL_STORAGE`) is restricted on Google
  Play to some app categories
- On Android 13 and 14, an app with a screen below rassh's in the same
  task, one rassh was opened from or a public key was shared to, can
  later start another one over rassh's, a fake prompt for one. From
  Android 15 the app asks Android to refuse that

## License

ISC, see [LICENSE](LICENSE). OpenSSH, LibreSSL, the Kotlin standard
library and the DejaVu fonts keep their own licenses, which the About
dialog shows.
