/*
 * JNI helpers to run a program on a pseudo-terminal.
 */

#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pty.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <termios.h>
#include <unistd.h>

#define WINSIZE_MAX	0xffff
#define MAX_FDS		64

static void
throw_io(JNIEnv *env, const char *what, int error)
{
	char	 msg[256];
	jclass	 cls;

	if ((*env)->ExceptionCheck(env))
		return;
	(void)snprintf(msg, sizeof(msg), "%s: %s", what, strerror(error));
	if ((cls = (*env)->FindClass(env, "java/io/IOException")) != NULL)
		(void)(*env)->ThrowNew(env, cls, msg);
}

static void
free_strings(char **v)
{
	char	**p;

	if (v == NULL)
		return;
	for (p = v; *p != NULL; p++)
		free(*p);
	free(v);
}

/*
 * Convert a byte[][] to a NULL terminated array of C strings. The bytes
 * are UTF-8 from the caller: JNI strings are modified UTF-8, which writes
 * characters beyond U+FFFF in a way programs do not read.
 */
static char **
to_strings(JNIEnv *env, jobjectArray array)
{
	char		**v;
	jbyteArray	  b;
	jsize		  i, n, len;

	n = (*env)->GetArrayLength(env, array);
	if ((v = calloc((size_t)n + 1, sizeof(*v))) == NULL)
		return NULL;
	for (i = 0; i < n; i++) {
		if ((b = (*env)->GetObjectArrayElement(env, array, i)) == NULL)
			goto fail;
		len = (*env)->GetArrayLength(env, b);
		if ((v[i] = malloc((size_t)len + 1)) != NULL) {
			(*env)->GetByteArrayRegion(env, b, 0, len, (jbyte *)v[i]);
			v[i][len] = '\0';
		}
		(*env)->DeleteLocalRef(env, b);
		if (v[i] == NULL)
			goto fail;
	}
	return v;
fail:
	free_strings(v);
	return NULL;
}

static void
close_fds(int from, int maxfd)
{
	int	fd;

#ifdef __NR_close_range
	if (syscall(__NR_close_range, from, ~0U, 0) == 0)
		return;
#endif
	for (fd = from; fd < maxfd; fd++)
		(void)close(fd);
}

/*
 * Runs in the forked child, only async-signal-safe calls are allowed.
 * The nfds descriptors in fds become 3, 4, ... in the program, and the
 * nstdio ones in stdio its standard input and output, which otherwise
 * stay on the terminal with the standard error.
 */
static void
child(const char *path, char **argv, char **envp, const char *cwd,
    int *fds, int nfds, int *stdio, int nstdio, int maxfd)
{
	static const char	 nodir[] = "rassh: cannot change directory\r\n";
	static const char	 nofd[] = "rassh: cannot pass descriptor\r\n";
	static const char	 msg[] = "rassh: cannot execute program\r\n";
	struct sigaction	 sa;
	sigset_t		 set;
	int			*fd;
	int			 i, sig;

	memset(&sa, 0, sizeof(sa));
	sa.sa_handler = SIG_DFL;
	for (sig = 1; sig < NSIG; sig++)
		(void)sigaction(sig, &sa, NULL);
	(void)sigemptyset(&set);
	(void)sigprocmask(SIG_SETMASK, &set, NULL);
	/* Move the descriptors above their targets first so none is clobbered. */
	for (i = 0; i < nfds + nstdio; i++) {
		fd = i < nfds ? &fds[i] : &stdio[i - nfds];
		if ((*fd = fcntl(*fd, F_DUPFD, 3 + nfds)) == -1) {
			(void)write(STDERR_FILENO, nofd, sizeof(nofd) - 1);
			_exit(127);
		}
	}
	for (i = 0; i < nfds + nstdio; i++) {
		if (dup2(i < nfds ? fds[i] : stdio[i - nfds],
		    i < nfds ? 3 + i : i - nfds) == -1) {
			(void)write(STDERR_FILENO, nofd, sizeof(nofd) - 1);
			_exit(127);
		}
	}
	close_fds(3 + nfds, maxfd);
	if (chdir(cwd) == -1) {
		(void)write(STDERR_FILENO, nodir, sizeof(nodir) - 1);
		_exit(127);
	}
	(void)execve(path, argv, envp);
	(void)write(STDERR_FILENO, msg, sizeof(msg) - 1);
	_exit(127);
}

JNIEXPORT jintArray JNICALL
Java_it_allard_rassh_Pty_start(JNIEnv *env, jclass cls, jstring jpath,
    jobjectArray jargv, jobjectArray jenvp, jstring jcwd, jintArray jfds,
    jintArray jstdio, jint rows, jint cols)
{
	struct winsize	  ws;
	const char	 *path, *cwd = NULL;
	char		**argv = NULL, **envp = NULL;
	jintArray	  result = NULL;
	jint		  ret[2], jfd[MAX_FDS], jstd[2];
	pid_t		  pid;
	long		  maxfd;
	int		  fds[MAX_FDS], stdio[2];
	int		  i, nfds, nstdio, master, status, saved;

	(void)cls;
	if (rows < 1 || cols < 1 || rows > WINSIZE_MAX || cols > WINSIZE_MAX) {
		throw_io(env, "start", EINVAL);
		return NULL;
	}
	if ((nfds = (*env)->GetArrayLength(env, jfds)) > MAX_FDS) {
		throw_io(env, "start", EINVAL);
		return NULL;
	}
	/* Both standard input and output, or neither. */
	if ((nstdio = (*env)->GetArrayLength(env, jstdio)) != 0 && nstdio != 2) {
		throw_io(env, "start", EINVAL);
		return NULL;
	}
	(*env)->GetIntArrayRegion(env, jfds, 0, nfds, jfd);
	(*env)->GetIntArrayRegion(env, jstdio, 0, nstdio, jstd);
	for (i = 0; i < nfds; i++)
		fds[i] = jfd[i];
	for (i = 0; i < nstdio; i++)
		stdio[i] = jstd[i];
	if ((path = (*env)->GetStringUTFChars(env, jpath, NULL)) == NULL)
		return NULL;
	if ((cwd = (*env)->GetStringUTFChars(env, jcwd, NULL)) == NULL)
		goto out;
	if ((argv = to_strings(env, jargv)) == NULL ||
	    (envp = to_strings(env, jenvp)) == NULL) {
		throw_io(env, "start", ENOMEM);
		goto out;
	}
	if ((maxfd = sysconf(_SC_OPEN_MAX)) < 0 || maxfd > 65536)
		maxfd = 65536;

	memset(&ws, 0, sizeof(ws));
	ws.ws_row = (unsigned short)rows;
	ws.ws_col = (unsigned short)cols;
	if ((pid = forkpty(&master, NULL, NULL, &ws)) == -1) {
		throw_io(env, "forkpty", errno);
		goto out;
	}
	if (pid == 0)
		child(path, argv, envp, cwd, fds, nfds, stdio, nstdio,
		    (int)maxfd);

	if (fcntl(master, F_SETFD, FD_CLOEXEC) == -1) {
		saved = errno;
		goto kill;
	}
	if ((result = (*env)->NewIntArray(env, 2)) == NULL) {
		saved = ENOMEM;
		goto kill;
	}
	ret[0] = master;
	ret[1] = pid;
	(*env)->SetIntArrayRegion(env, result, 0, 2, ret);
	goto out;
kill:
	(void)close(master);
	(void)kill(pid, SIGKILL);
	while (waitpid(pid, &status, 0) == -1 && errno == EINTR)
		;
	throw_io(env, "start", saved);
out:
	free_strings(argv);
	free_strings(envp);
	if (cwd != NULL)
		(*env)->ReleaseStringUTFChars(env, jcwd, cwd);
	(*env)->ReleaseStringUTFChars(env, jpath, path);
	return result;
}

JNIEXPORT void JNICALL
Java_it_allard_rassh_Pty_setWindowSize(JNIEnv *env, jclass cls, jint fd,
    jint rows, jint cols)
{
	struct winsize	ws;

	(void)cls;
	if (rows < 1 || cols < 1 || rows > WINSIZE_MAX || cols > WINSIZE_MAX) {
		throw_io(env, "setWindowSize", EINVAL);
		return;
	}
	memset(&ws, 0, sizeof(ws));
	ws.ws_row = (unsigned short)rows;
	ws.ws_col = (unsigned short)cols;
	if (ioctl(fd, TIOCSWINSZ, &ws) == -1)
		throw_io(env, "setWindowSize", errno);
}

JNIEXPORT jint JNICALL
Java_it_allard_rassh_Pty_waitFor(JNIEnv *env, jclass cls, jint pid)
{
	siginfo_t	info;

	(void)cls;
	/* Leave the zombie: its pid cannot be reused until reap(). */
	memset(&info, 0, sizeof(info));
	while (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOWAIT) == -1) {
		if (errno != EINTR) {
			throw_io(env, "waitid", errno);
			return -1;
		}
	}
	switch (info.si_code) {
	case CLD_EXITED:
		return info.si_status;
	case CLD_KILLED:
	case CLD_DUMPED:
		return 128 + info.si_status;
	default:
		return -1;
	}
}

JNIEXPORT void JNICALL
Java_it_allard_rassh_Pty_reap(JNIEnv *env, jclass cls, jint pid)
{
	int	status;

	(void)cls;
	while (waitpid(pid, &status, 0) == -1) {
		if (errno != EINTR) {
			throw_io(env, "waitpid", errno);
			return;
		}
	}
}

JNIEXPORT void JNICALL
Java_it_allard_rassh_Pty_sendSignal(JNIEnv *env, jclass cls, jint pid,
    jint sig)
{
	(void)env;
	(void)cls;
	if (pid > 0)
		(void)kill(pid, sig);
}

/* The program is the leader of its own session and process group. */
JNIEXPORT void JNICALL
Java_it_allard_rassh_Pty_signalGroup(JNIEnv *env, jclass cls, jint pgid,
    jint sig)
{
	(void)env;
	(void)cls;
	if (pgid > 0)
		(void)kill(-pgid, sig);
}
