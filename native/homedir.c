/*
 * Bionic reports /data as the home directory of application users.
 * OpenSSH looks up ~/.ssh through getpwuid(3), so the binaries are
 * linked with -Wl,--wrap=getpwuid and this wrapper substitutes $HOME
 * for the current user.
 */

#include <pwd.h>
#include <stdlib.h>
#include <unistd.h>

struct passwd	*__real_getpwuid(uid_t);
struct passwd	*__wrap_getpwuid(uid_t);

struct passwd *
__wrap_getpwuid(uid_t uid)
{
	struct passwd	*pw;
	char		*home;

	if ((pw = __real_getpwuid(uid)) == NULL)
		return NULL;
	if (uid == getuid() && (home = getenv("HOME")) != NULL &&
	    *home != '\0')
		pw->pw_dir = home;
	return pw;
}
