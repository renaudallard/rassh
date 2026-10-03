package it.allard.rassh.sftp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Attributes of a remote file, null for those the server left out. */
class SftpAttrs(
    val size: Long? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val permissions: Int? = null,
    val atime: Long? = null,
    val mtime: Long? = null,
) {
    val isDirectory: Boolean
        get() = permissions != null && permissions and S_IFMT == S_IFDIR

    val isLink: Boolean
        get() = permissions != null && permissions and S_IFMT == S_IFLNK

    companion object {
        const val S_IFMT = 0xf000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xa000
    }
}

class SftpEntry(val name: String, val attrs: SftpAttrs)

/** A request the server refused, status is one of the SSH_FX codes. */
class SftpException(val status: Int, message: String) : IOException(message) {
    companion object {
        const val EOF = 1
        const val NO_SUCH_FILE = 2
        const val PERMISSION_DENIED = 3
        const val FAILURE = 4
    }
}

/** A transfer stopped by its progress callback. */
class SftpCancelledException : IOException("cancelled")

/**
 * A client of SFTP version 3 (draft-ietf-secsh-filexfer-02), the version
 * OpenSSH's sftp-server speaks, over the standard input and output of
 * "ssh -s host sftp". The constructor does the version exchange. Calls
 * block and are serialized, transfers keep several requests in flight.
 */
class SftpClient(input: InputStream, output: OutputStream) : Closeable {
    private val input = DataInputStream(BufferedInputStream(input, BUFFER_SIZE))
    private val output = BufferedOutputStream(output, BUFFER_SIZE)
    private var nextId = 0

    /* Replies read while waiting for another one, by request id. */
    private val early = HashMap<Int, Reply>()

    private class Reply(val type: Int, val body: Reader)

    /* The names of the extensions the server announced. */
    private val extensions = HashSet<String>()

    init {
        output.write(Writer(FXP_INIT).u32(VERSION).packet())
        output.flush()
        val r = readPacket()
        if (r.type != FXP_VERSION) throw IOException("not an SFTP server")
        val version = r.body.u32()
        if (version < VERSION) throw IOException("SFTP version $version is not supported")
        while (r.body.hasMore()) {
            extensions.add(r.body.text())
            r.body.bytes()
        }
    }

    /** The absolute, canonical form of path. */
    @Synchronized
    @Throws(IOException::class)
    fun realpath(path: String): String {
        val r = request(Writer(FXP_REALPATH).string(path))
        expect(r, FXP_NAME)
        if (r.body.u32() < 1) throw IOException("empty reply")
        return r.body.text()
    }

    @Synchronized
    @Throws(IOException::class)
    fun stat(path: String): SftpAttrs = attrsOf(request(Writer(FXP_STAT).string(path)))

    @Synchronized
    @Throws(IOException::class)
    fun lstat(path: String): SftpAttrs = attrsOf(request(Writer(FXP_LSTAT).string(path)))

    /** The entries of the directory path, without "." and "..". */
    @Synchronized
    @Throws(IOException::class)
    fun list(path: String): List<SftpEntry> {
        val handle = handleOf(request(Writer(FXP_OPENDIR).string(path)))
        val entries = mutableListOf<SftpEntry>()
        try {
            while (true) {
                val r = request(Writer(FXP_READDIR).bytes(handle))
                if (r.type == FXP_STATUS) {
                    val e = statusOf(r)
                    if (e.status == SftpException.EOF) break
                    throw e
                }
                expect(r, FXP_NAME)
                repeat(r.body.u32()) {
                    val name = r.body.text()
                    r.body.text()
                    val attrs = r.body.attrs()
                    if (name != "." && name != "..") entries.add(SftpEntry(name, attrs))
                }
            }
        } finally {
            closeHandle(handle)
        }
        return entries
    }

    @Synchronized
    @Throws(IOException::class)
    fun mkdir(path: String) = ok(request(Writer(FXP_MKDIR).string(path).u32(0)))

    @Synchronized
    @Throws(IOException::class)
    fun rmdir(path: String) = ok(request(Writer(FXP_RMDIR).string(path)))

    @Synchronized
    @Throws(IOException::class)
    fun remove(path: String) = ok(request(Writer(FXP_REMOVE).string(path)))

    @Synchronized
    @Throws(IOException::class)
    fun rename(from: String, to: String) = ok(request(Writer(FXP_RENAME).string(from).string(to)))

    /**
     * Copy the file path to out. progress gets the bytes copied so far
     * and returns false to stop, which throws SftpCancelledException.
     */
    @Synchronized
    @Throws(IOException::class)
    fun download(path: String, out: OutputStream, progress: (Long) -> Boolean = { true }) {
        val handle = handleOf(request(Writer(FXP_OPEN).string(path).u32(FXF_READ).u32(0)))
        val pending = ArrayDeque<Pair<Int, Long>>()
        try {
            var offset = 0L
            var done = 0L
            var eof = false
            while (true) {
                while (!eof && pending.size < MAX_REQUESTS) {
                    pending.addLast(Pair(send(Writer(FXP_READ).bytes(handle).u64(offset).u32(CHUNK)), offset))
                    offset += CHUNK
                }
                val (id, at) = pending.removeFirstOrNull() ?: break
                val r = reply(id)
                if (r.type == FXP_STATUS) {
                    val e = statusOf(r)
                    if (e.status != SftpException.EOF) throw e
                    eof = true
                    continue
                }
                expect(r, FXP_DATA)
                val data = r.body.bytes()
                /* The end comes as a status, empty data would ask the same again forever. */
                if (at != done || data.size > CHUNK || data.isEmpty()) throw IOException("unexpected data")
                out.write(data)
                done += data.size
                /* A short read: the requests after it start at the wrong offset. */
                if (data.size < CHUNK && !eof) {
                    drain(pending)
                    offset = done
                }
                if (!progress(done)) throw SftpCancelledException()
            }
        } finally {
            drain(pending)
            closeHandle(handle)
        }
    }

    /**
     * Copy input to the file path. It is written under a temporary name
     * next to it, then renamed over path with the permissions of the file
     * it replaces, so that a stopped transfer leaves path as it was. A
     * link is written through in place, to update the file it leads to.
     * progress works as in download().
     */
    @Synchronized
    @Throws(IOException::class)
    fun upload(input: InputStream, path: String, progress: (Long) -> Boolean = { true }) {
        val old = try {
            lstat(path)
        } catch (e: SftpException) {
            if (e.status != SftpException.NO_SUCH_FILE) throw e
            null
        }
        if (old != null && old.isLink) {
            write(input, path, progress)
            return
        }
        val slash = path.lastIndexOf('/')
        val temp = path.substring(0, slash + 1) + "." + path.substring(slash + 1) + ".part"
        var done = false
        try {
            write(input, temp, progress)
            old?.permissions?.let { ok(request(Writer(FXP_SETSTAT).string(temp).u32(ATTR_PERMISSIONS).u32(it and 0xfff))) }
            replace(temp, path)
            done = true
        } finally {
            if (!done) {
                try {
                    request(Writer(FXP_REMOVE).string(temp))
                } catch (_: IOException) {
                }
            }
        }
    }

    /*
     * Rename from over to. Plain SFTP renames refuse an existing target,
     * OpenSSH's extension replaces it at once.
     */
    private fun replace(from: String, to: String) {
        if (POSIX_RENAME in extensions) {
            ok(request(Writer(FXP_EXTENDED).string(POSIX_RENAME).string(from).string(to)))
            return
        }
        try {
            ok(request(Writer(FXP_REMOVE).string(to)))
        } catch (e: SftpException) {
            if (e.status != SftpException.NO_SUCH_FILE) throw e
        }
        ok(request(Writer(FXP_RENAME).string(from).string(to)))
    }

    /* Copy input to the file path, created or truncated. */
    private fun write(input: InputStream, path: String, progress: (Long) -> Boolean) {
        val flags = FXF_WRITE or FXF_CREAT or FXF_TRUNC
        val handle = handleOf(request(Writer(FXP_OPEN).string(path).u32(flags).u32(0)))
        val pending = ArrayDeque<Int>()
        var failed = true
        try {
            val buf = ByteArray(CHUNK)
            var offset = 0L
            while (true) {
                val n = input.readNBytes(buf, 0, CHUNK)
                if (n <= 0) break
                pending.addLast(send(Writer(FXP_WRITE).bytes(handle).u64(offset).bytes(buf, n)))
                offset += n
                if (pending.size >= MAX_REQUESTS) ok(reply(pending.removeFirst()))
                if (!progress(offset)) throw SftpCancelledException()
            }
            while (pending.isNotEmpty()) ok(reply(pending.removeFirst()))
            failed = false
        } finally {
            drain(pending.map { Pair(it, 0L) })
            /* Closing reports errors of the last writes, they matter then. */
            if (failed) closeHandle(handle) else ok(request(Writer(FXP_CLOSE).bytes(handle)))
        }
    }

    override fun close() {
        try {
            output.close()
        } finally {
            input.close()
        }
    }

    /* Read and drop the replies still due, to keep the stream in step. */
    private fun drain(pending: Collection<Pair<Int, Long>>) {
        for ((id, _) in pending) reply(id)
        if (pending is MutableCollection) pending.clear()
    }

    private fun closeHandle(handle: ByteArray) {
        try {
            request(Writer(FXP_CLOSE).bytes(handle))
        } catch (_: SftpException) {
        }
    }

    private fun request(w: Writer): Reply = reply(send(w))

    private fun send(w: Writer): Int {
        val id = nextId++
        w.id(id)
        output.write(w.packet())
        output.flush()
        return id
    }

    private fun reply(id: Int): Reply {
        early.remove(id)?.let { return it }
        while (true) {
            val r = readPacket()
            val got = r.body.u32()
            if (got == id) return r
            if (early.size >= MAX_EARLY) throw IOException("unexpected reply")
            early[got] = r
        }
    }

    private fun readPacket(): Reply {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            throw IOException("connection closed")
        }
        if (length < 1 || length > MAX_PACKET) throw IOException("bad packet length $length")
        val packet = ByteArray(length)
        input.readFully(packet)
        val body = Reader(packet, 1)
        return Reply(packet[0].toInt() and 0xff, body)
    }

    private fun expect(r: Reply, type: Int) {
        if (r.type == type) return
        if (r.type == FXP_STATUS) throw statusOf(r)
        throw IOException("unexpected reply type ${r.type}")
    }

    private fun ok(r: Reply) {
        expect(r, FXP_STATUS)
        val e = statusOf(r)
        if (e.status != FX_OK) throw e
    }

    private fun statusOf(r: Reply): SftpException {
        expect(r, FXP_STATUS)
        val code = r.body.u32()
        val message = r.body.text()
        return SftpException(code, message.ifEmpty { "error $code" })
    }

    private fun attrsOf(r: Reply): SftpAttrs {
        expect(r, FXP_ATTRS)
        return r.body.attrs()
    }

    private fun handleOf(r: Reply): ByteArray {
        expect(r, FXP_HANDLE)
        return r.body.bytes()
    }

    /* A packet being built, the request id inserted by send(). */
    private class Writer(private val type: Int) {
        private val buf = ByteArrayOutputStream()
        private val out = DataOutputStream(buf)
        private var id: Int? = null

        fun id(id: Int) {
            this.id = id
        }

        fun u32(v: Int) = apply { out.writeInt(v) }

        fun u64(v: Long) = apply { out.writeLong(v) }

        fun string(s: String) = bytes(s.toByteArray())

        fun bytes(b: ByteArray, n: Int = b.size) = apply {
            out.writeInt(n)
            out.write(b, 0, n)
        }

        /* INIT alone has no request id. */
        fun packet(): ByteArray {
            val body = buf.toByteArray()
            val head = if (id == null) 1 else 5
            val p = ByteArrayOutputStream(4 + head + body.size)
            val d = DataOutputStream(p)
            d.writeInt(head + body.size)
            d.writeByte(type)
            id?.let { d.writeInt(it) }
            d.write(body)
            return p.toByteArray()
        }
    }

    /* The fields of a received packet, bounds checked. */
    private class Reader(private val b: ByteArray, private var pos: Int) {
        fun u32(): Int {
            need(4)
            val v = (b[pos].toInt() and 0xff shl 24) or (b[pos + 1].toInt() and 0xff shl 16) or
                (b[pos + 2].toInt() and 0xff shl 8) or (b[pos + 3].toInt() and 0xff)
            pos += 4
            return v
        }

        fun u64(): Long = (u32().toLong() and 0xffffffffL shl 32) or (u32().toLong() and 0xffffffffL)

        fun bytes(): ByteArray {
            val n = u32()
            if (n < 0) throw IOException("bad string length")
            need(n)
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun text(): String = String(bytes(), Charsets.UTF_8)

        fun hasMore(): Boolean = pos < b.size

        fun attrs(): SftpAttrs {
            val flags = u32()
            val size = if (flags and ATTR_SIZE != 0) u64() else null
            var uid: Int? = null
            var gid: Int? = null
            if (flags and ATTR_UIDGID != 0) {
                uid = u32()
                gid = u32()
            }
            val permissions = if (flags and ATTR_PERMISSIONS != 0) u32() else null
            var atime: Long? = null
            var mtime: Long? = null
            if (flags and ATTR_ACMODTIME != 0) {
                atime = u32().toLong() and 0xffffffffL
                mtime = u32().toLong() and 0xffffffffL
            }
            if (flags and ATTR_EXTENDED != 0) {
                repeat(u32()) {
                    bytes()
                    bytes()
                }
            }
            return SftpAttrs(size, uid, gid, permissions, atime, mtime)
        }

        private fun need(n: Int) {
            if (n > b.size - pos) throw IOException("truncated packet")
        }
    }

    companion object {
        private const val VERSION = 3
        private const val BUFFER_SIZE = 65536
        /* Read and write size, OpenSSH's own sftp uses the same. */
        private const val CHUNK = 32768
        private const val MAX_REQUESTS = 16
        /* OpenSSH's limit, a READ reply of CHUNK fits easily. */
        private const val MAX_PACKET = 256 * 1024
        private const val MAX_EARLY = 64

        private const val FXP_INIT = 1
        private const val FXP_VERSION = 2
        private const val FXP_OPEN = 3
        private const val FXP_CLOSE = 4
        private const val FXP_READ = 5
        private const val FXP_WRITE = 6
        private const val FXP_LSTAT = 7
        private const val FXP_OPENDIR = 11
        private const val FXP_READDIR = 12
        private const val FXP_REMOVE = 13
        private const val FXP_MKDIR = 14
        private const val FXP_RMDIR = 15
        private const val FXP_REALPATH = 16
        private const val FXP_STAT = 17
        private const val FXP_RENAME = 18
        private const val FXP_SETSTAT = 9
        private const val FXP_EXTENDED = 200
        private const val FXP_STATUS = 101
        private const val FXP_HANDLE = 102
        private const val FXP_DATA = 103
        private const val FXP_NAME = 104
        private const val FXP_ATTRS = 105

        private const val FX_OK = 0
        private const val POSIX_RENAME = "posix-rename@openssh.com"

        private const val FXF_READ = 1
        private const val FXF_WRITE = 2
        private const val FXF_CREAT = 8
        private const val FXF_TRUNC = 0x10

        private const val ATTR_SIZE = 1
        private const val ATTR_UIDGID = 2
        private const val ATTR_PERMISSIONS = 4
        private const val ATTR_ACMODTIME = 8
        private const val ATTR_EXTENDED = 0x80000000.toInt()
    }
}
