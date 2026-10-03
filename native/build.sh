#!/bin/sh
#
# Cross-compile LibreSSL, OpenSSH and the pty helper with the Android NDK
# and install them into the app as native libraries.
#
# usage: ANDROID_NDK_HOME=/path/to/ndk native/build.sh [abi ...]

set -eu

LIBRESSL_VERSION=4.3.2
LIBRESSL_SHA256=edf01aee24c65d69e6a9efcb9d44bcda682ff9d4f3bbbd95e794e1dfa90847b5
OPENSSH_VERSION=10.5p1
OPENSSH_SHA256=d44d28a839ea9daf969cc69150fde59910b2b39361dad81a3bd6cbd19218db11
API=33

# scp and sftp find ssh through PATH, see SSH_PROGRAM below.
PROGRAMS="ssh ssh-keygen ssh-agent ssh-add scp sftp"

LIBRESSL_URL=https://cdn.openbsd.org/pub/OpenBSD/LibreSSL
OPENSSH_URL=https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable

top=$(cd "$(dirname "$0")/.." && pwd)
native=$top/native
work=$native/build
jnilibs=$top/app/src/main/jniLibs
assets=$top/app/src/main/assets/licenses

die()
{
	echo "${0##*/}: $*" >&2
	exit 1
}

sha256()
{
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | cut -d ' ' -f 1
	else
		shasum -a 256 "$1" | cut -d ' ' -f 1
	fi
}

# fetch url file sha256, keeping the file only once verified
fetch()
{
	[ -f "$work/$2" ] && [ "$(sha256 "$work/$2")" = "$3" ] && return
	rm -f "$work/$2"
	curl -fsSL -o "$work/$2.part" "$1/$2"
	if [ "$(sha256 "$work/$2.part")" != "$3" ]; then
		rm -f "$work/$2.part"
		die "$2: checksum mismatch"
	fi
	mv "$work/$2.part" "$work/$2"
}

# unpack tarball directory
unpack()
{
	rm -rf "$2"
	mkdir -p "$2"
	tar -xzf "$1" -C "$2" --strip-components 1
}

build_abi()
{
	abi=$1
	case $abi in
	arm64-v8a)	target=aarch64-linux-android ;;
	x86_64)		target=x86_64-linux-android ;;
	*)		die "$abi: unsupported ABI" ;;
	esac

	dir=$work/$abi
	prefix=$dir/prefix
	cc=$toolchain/bin/$target$API-clang
	ldflags="-Wl,-z,max-page-size=16384"
	export CC="$cc" AR="$toolchain/bin/llvm-ar" \
	    RANLIB="$toolchain/bin/llvm-ranlib" \
	    STRIP="$toolchain/bin/llvm-strip"

	unpack "$work/libressl-$LIBRESSL_VERSION.tar.gz" "$dir/libressl"
	(
		cd "$dir/libressl"
		./configure --host="$target" --prefix="$prefix" \
		    --disable-shared --enable-static --disable-tests \
		    --with-pic
		make -j "$jobs" -C crypto install
		make -C include install
	)

	"$cc" -O2 -Wall -Wextra -c -o "$dir/homedir.o" "$native/homedir.c"

	# Without HAVE_ATTRIBUTE__SENTINEL__, OpenSSH defines __sentinel__
	# away and breaks the attribute in the bionic headers. Bionic only
	# provides bzero() as a macro, which the configure link test misses.
	unpack "$work/openssh-$OPENSSH_VERSION.tar.gz" "$dir/openssh"
	patch -d "$dir/openssh" -p1 < "$native/openssh-android.patch"
	(
		cd "$dir/openssh"
		./configure --host="$target" --with-ssl-dir="$prefix" \
		    --disable-security-key --disable-pkcs11 \
		    --sysconfdir=/etc/ssh \
		    CPPFLAGS="-DHAVE_ATTRIBUTE__SENTINEL__" ac_cv_func_bzero=yes \
		    LDFLAGS="$ldflags -Wl,--wrap=getpwuid" \
		    LIBS="$dir/homedir.o"
		make -j "$jobs" SSH_PROGRAM=ssh $PROGRAMS
	)

	mkdir -p "$jnilibs/$abi"
	"$cc" -shared -fPIC -O2 -Wall -Wextra "$ldflags" \
	    -o "$jnilibs/$abi/librassh.so" "$native/pty.c"
	for p in $PROGRAMS; do
		"$STRIP" -o "$jnilibs/$abi/lib$p.so" "$dir/openssh/$p"
	done
	"$STRIP" "$jnilibs/$abi/librassh.so"
}

ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
[ -n "$ndk" ] || die "set ANDROID_NDK_HOME to the NDK directory"
case $(uname -s) in
Linux)	host=linux-x86_64 ;;
Darwin)	host=darwin-x86_64 ;;
*)	die "unsupported build host" ;;
esac
toolchain=$ndk/toolchains/llvm/prebuilt/$host
[ -d "$toolchain" ] || die "$toolchain: not found"
jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)

[ $# -gt 0 ] || set -- arm64-v8a x86_64

mkdir -p "$work"
fetch "$LIBRESSL_URL" "libressl-$LIBRESSL_VERSION.tar.gz" "$LIBRESSL_SHA256"
fetch "$OPENSSH_URL" "openssh-$OPENSSH_VERSION.tar.gz" "$OPENSSH_SHA256"

for abi in "$@"; do
	build_abi "$abi"
done

mkdir -p "$assets"
cp "$work/$1/openssh/LICENCE" "$assets/openssh.txt"
cp "$work/$1/libressl/COPYING" "$assets/libressl.txt"
cp "$top/LICENSE" "$assets/rassh.txt"
