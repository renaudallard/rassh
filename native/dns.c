/*
 * ssh looks up SSHFP records for VerifyHostKeyDNS with res_query(3),
 * which reaches no DNS server from an app: bionic leaves that to the
 * system resolver, which only getaddrinfo(3) and the like reach. The
 * binaries are linked with -Wl,--wrap=res_query and this wrapper asks
 * the system resolver of the default network with android_res_nquery(),
 * from libandroid.so loaded when first needed.
 *
 * The authenticated data bit of an answer is cleared: the resolver
 * passes it on as received, anyone answering for it on the network
 * could set it, and ssh would then take a forged fingerprint as secure
 * without asking. OpenBSD and glibc clear it too unless told to trust
 * the resolver. A fingerprint found is shown as matching, ssh still asks.
 */

#include <sys/types.h>

#include <android/multinetwork.h>
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <resolv.h>

#define TIMEOUT_MS	10000
#define HEADER_SIZE	12
#define AD_BYTE		3
#define AD_BIT		0x20

/* The resolver return codes of RFC 1035. */
#define RCODE_NOERROR	0
#define RCODE_SERVFAIL	2
#define RCODE_NXDOMAIN	3

int	__wrap_res_query(const char *, int, int, u_char *, int);

static int	(*nquery)(net_handle_t, const char *, int, int, uint32_t);
static int	(*nresult)(int, int *, uint8_t *, size_t);
static void	(*cancel)(int);

static int
load(void)
{
	void	*lib;

	if (nquery != NULL)
		return 0;
	if ((lib = dlopen("libandroid.so", RTLD_NOW)) == NULL)
		return -1;
	nresult = dlsym(lib, "android_res_nresult");
	cancel = dlsym(lib, "android_res_cancel");
	nquery = dlsym(lib, "android_res_nquery");
	if (nquery == NULL || nresult == NULL || cancel == NULL) {
		nquery = NULL;
		return -1;
	}
	return 0;
}

/* As res_query(3): the length of the answer, or -1 with h_errno set. */
int
__wrap_res_query(const char *name, int class, int type, u_char *answer,
    int anslen)
{
	struct pollfd	pfd;
	int		fd, n, rcode;

	h_errno = NO_RECOVERY;
	if (anslen < HEADER_SIZE || load() == -1)
		return -1;
	h_errno = TRY_AGAIN;
	if ((fd = nquery(NETWORK_UNSPECIFIED, name, class, type, 0)) < 0)
		return -1;
	pfd.fd = fd;
	pfd.events = POLLIN;
	while ((n = poll(&pfd, 1, TIMEOUT_MS)) == -1 && errno == EINTR)
		;
	if (n != 1) {
		cancel(fd);
		return -1;
	}
	if ((n = nresult(fd, &rcode, answer, (size_t)anslen)) < HEADER_SIZE)
		return -1;
	answer[AD_BYTE] &= ~AD_BIT;
	switch (rcode) {
	case RCODE_NOERROR:
		/* The answer count, bytes 6 and 7 of the header. */
		if (answer[6] != 0 || answer[7] != 0)
			return n;
		h_errno = NO_DATA;
		break;
	case RCODE_NXDOMAIN:
		h_errno = HOST_NOT_FOUND;
		break;
	case RCODE_SERVFAIL:
		h_errno = TRY_AGAIN;
		break;
	default:
		h_errno = NO_RECOVERY;
		break;
	}
	return -1;
}
