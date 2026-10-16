package it.allard.rassh

import java.io.File
import java.io.IOException

/*
 * The output of ssh -G and its exit status with text as its config, for
 * each of names up to the first it refuses.
 */
@Throws(IOException::class)
fun dumpConfig(paths: Paths, text: String, names: List<String>): List<Pair<String, Int>> {
    /* Its own, a check left running by a recreated screen may still use another. */
    val file = File.createTempFile("config", ".check", paths.tmp)
    try {
        paths.writePrivate(file, text)
        val dumps = mutableListOf<Pair<String, Int>>()
        for (name in names) {
            /* Not looking the name up: offline, a canonicalized name would fail the check. */
            val argv = listOf("ssh", "-G", "-F", file.path, "-o", "CanonicalizeHostname=no",
                "-o", "CanonicalizePermittedCNAMEs=none", name)
            val (out, status) = execute(paths.ssh, argv, paths.env, paths.home.path)
            dumps.add(Pair(out.replace(file.path, "config"), status))
            if (status != 0) break
        }
        return dumps
    } finally {
        file.delete()
    }
}

/*
 * The first of names ssh refuses text as its configuration for, with
 * what it says, null when it takes it for all. One bad line makes ssh
 * refuse the whole file, every host would then fail, and HostName or
 * User expand % tokens. ssh reads the lines of every block, an option
 * it does not know passes only after an IgnoreUnknown line naming it
 * applied to the host: one host may take an option the others fail on.
 */
@Throws(IOException::class)
fun configRefusal(paths: Paths, text: String, names: List<String>): Pair<String, String>? {
    val dumps = dumpConfig(paths, text, names)
    val (out, status) = dumps.lastOrNull() ?: return null
    return if (status == 0) null else Pair(names[dumps.lastIndex], out)
}
