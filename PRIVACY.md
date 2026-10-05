# Privacy policy

rassh is an SSH client. It does not collect, store or share any personal
data with its developer or with third parties. It contains no analytics,
advertising, crash reporting or third-party libraries.

## Network

rassh connects only to the servers you choose, with ssh, sftp and scp,
and opens only the port forwards you configure. Nothing else is sent
anywhere.

## Data on your phone

Your hosts (`~/.ssh/config`), known host keys, keys and settings are kept
in the app's private storage. Once a fingerprint is enrolled, private keys
are encrypted with a key held in the Android Keystore. This data is
excluded from Android backups and device-to-device transfers.

Fingerprints are checked by Android. rassh never sees them, it only learns
whether the check succeeded.

## Your files

With All files access, sftp, scp and the local shell read and write files
in shared storage only when you run a command. Without it, sftp and scp
only reach the app's private directory. The file browser uses the system
file picker.

An export holds your hosts, keys and settings, encrypted with a passphrase
you choose, and is saved only where you put it.

## Permissions

- Internet and local network: connecting to your servers
- Notifications and foreground service: keeping sessions open
- Biometric: unlocking your keys
- All files access: file transfers and the local shell, as above

## Removing your data

Uninstalling rassh, or clearing its storage in Android's settings,
deletes everything it keeps.

## Children

rassh is not directed at children.

## Changes

Changes to this policy are published in this file, with their history in
the repository.

## Contact

Questions can be asked in the project's issues:
https://github.com/renaudallard/rassh/issues
