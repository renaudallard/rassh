package it.allard.rassh.terminal

import it.allard.rassh.terminal.TerminalRow.Companion.WIDE_TAIL
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test

/*
 * The server decides every byte feed() gets: random text, controls and
 * escape sequences, cut anywhere, mixed with resizes, must keep the
 * screen consistent. Each run is replayed by its seed, given on failure.
 * RASSH_FUZZ_RUNS sets the number of runs, with :core:test --rerun.
 */
class TerminalFuzzTest {
    private val client = object : TerminalClient {
        override fun write(data: ByteArray) {}
    }

    @Test
    fun randomInputKeepsTheScreenConsistent() {
        val runs = System.getenv("RASSH_FUZZ_RUNS")?.toLongOrNull() ?: RUNS
        for (seed in 0L until runs)
            run(seed)
    }

    private fun run(seed: Long) {
        val r = Random(seed)
        val t = Terminal(1 + r.nextInt(40), 1 + r.nextInt(20), client, HISTORY)
        for (step in 0 until STEPS) {
            var last = ""
            try {
                when (r.nextInt(50)) {
                    0 -> {
                        val columns = 1 + r.nextInt(60)
                        val rows = 1 + r.nextInt(30)
                        last = "resize $columns x $rows"
                        t.resize(columns, rows)
                    }
                    1 -> {
                        last = "leaveAltScreen"
                        t.leaveAltScreen()
                    }
                    2 -> {
                        last = "resetParser"
                        t.resetParser()
                    }
                    else -> {
                        val data = chunk(r)
                        last = show(data)
                        /* A sequence cut between two reads. */
                        val cut = r.nextInt(data.size + 1)
                        t.feed(data, 0, cut)
                        t.feed(data, cut, data.size - cut)
                    }
                }
                check(t)
            } catch (e: Throwable) {
                throw AssertionError("seed $seed, step $step, after $last: ${e.message}", e)
            }
        }
    }

    private fun check(t: Terminal) {
        require(t.cursorX in 0 until t.columns) { "cursor x ${t.cursorX} out of ${t.columns} columns" }
        require(t.cursorY in 0 until t.rows) { "cursor y ${t.cursorY} out of ${t.rows} rows" }
        require(t.historySize <= HISTORY) { "history of ${t.historySize} lines" }
        require(!t.isAltScreen || t.historySize == 0) { "history on the alternate screen" }
        for (y in 0 until t.rows) {
            val row = t.row(y)
            require(row.columns == t.columns) { "row $y has ${row.columns} columns, not ${t.columns}" }
            cells(row, y)
        }
        for (y in -t.historySize until 0)
            cells(t.row(y), y)
        t.text(-t.historySize, 0, t.rows - 1, t.columns - 1)
        require(t.title.length <= MAX_TITLE) { "title of ${t.title.length} chars" }
        for (i in t.title.indices) {
            val c = t.title[i]
            require(!c.isHighSurrogate() || i + 1 < t.title.length && t.title[i + 1].isLowSurrogate()) {
                "lone surrogate in the title"
            }
        }
    }

    /* Each cell holds a printable code point, and wide ones their tail. */
    private fun cells(row: TerminalRow, y: Int) {
        val text = row.text
        for (x in text.indices) {
            val c = text[x]
            when {
                c == 0 -> {}
                c == WIDE_TAIL -> require(x > 0 && text[x - 1] > 0 && WcWidth.width(text[x - 1]) == 2) {
                    "row $y: tail at $x without its wide character"
                }
                else -> {
                    require(c >= 0x20 && c != 0x7f && c <= 0x10ffff && c !in 0xd800..0xdfff) {
                        "row $y: cell $x holds ${"%x".format(c)}"
                    }
                    require(WcWidth.width(c) != 2 || x + 1 < text.size && text[x + 1] == WIDE_TAIL) {
                        "row $y: wide character at $x without its tail"
                    }
                }
            }
        }
    }

    private fun chunk(r: Random): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(1 + r.nextInt(4)) {
            when (r.nextInt(10)) {
                0 -> out.write(r.nextBytes(1 + r.nextInt(16)))
                1, 2 -> text(r, out)
                3 -> out.write(CONTROLS[r.nextInt(CONTROLS.size)])
                4, 5 -> csi(r, out)
                6 -> modes(r, out)
                7 -> escape(r, out)
                8 -> string(r, out)
                else -> sgr(r, out)
            }
        }
        return out.toByteArray()
    }

    private fun text(r: Random, out: ByteArrayOutputStream) {
        repeat(1 + r.nextInt(12)) {
            val c = when (r.nextInt(8)) {
                0 -> WIDE[r.nextInt(WIDE.size)]
                1 -> ZERO_WIDTH[r.nextInt(ZERO_WIDTH.size)]
                2 -> {
                    /* A character cut short or overlong. */
                    out.write(byteArrayOf(0xe4.toByte(), 0xb8.toByte()))
                    if (r.nextBoolean()) out.write(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
                    return@repeat
                }
                3 -> r.nextInt(0x80, 0x110000).let { if (it in 0xd800..0xdfff) 0xfffd else it }
                else -> r.nextInt(0x20, 0x7f)
            }
            out.write(String(Character.toChars(c)).toByteArray())
        }
    }

    private fun csi(r: Random, out: ByteArrayOutputStream) {
        val sb = StringBuilder("\u001b[")
        if (r.nextInt(4) == 0) sb.append("?<=>"[r.nextInt(4)])
        repeat(r.nextInt(6)) { i ->
            if (i > 0) sb.append(if (r.nextInt(6) == 0) ':' else ';')
            if (r.nextInt(5) != 0) sb.append(number(r))
        }
        if (r.nextInt(8) == 0) sb.append(" !\"$'"[r.nextInt(5)])
        sb.append(if (r.nextInt(6) == 0) (0x40 + r.nextInt(0x3f)).toChar() else FINALS[r.nextInt(FINALS.length)])
        out.write(sb.toString().toByteArray())
    }

    private fun modes(r: Random, out: ByteArrayOutputStream) {
        val list = List(1 + r.nextInt(3)) { MODES[r.nextInt(MODES.size)] }
        out.write("\u001b[?${list.joinToString(";")}${if (r.nextBoolean()) 'h' else 'l'}".toByteArray())
    }

    private fun escape(r: Random, out: ByteArrayOutputStream) {
        out.write(ESCAPES[r.nextInt(ESCAPES.size)].toByteArray())
    }

    /* OSC, DCS, PM or APC, ended by BEL, ST, another escape or not at all. */
    private fun string(r: Random, out: ByteArrayOutputStream) {
        out.write(listOf("\u001b]0;", "\u001b]2;", "\u001b]", "\u001bP", "\u001b^", "\u001b_")[r.nextInt(6)].toByteArray())
        val n = if (r.nextInt(10) == 0) 5000 else r.nextInt(40)
        repeat(n) { out.write(String(Character.toChars(if (r.nextInt(8) == 0) WIDE[r.nextInt(WIDE.size)] else r.nextInt(0x20, 0x7f))).toByteArray()) }
        when (r.nextInt(4)) {
            0 -> out.write(0x07)
            1 -> out.write("\u001b\\".toByteArray())
            2 -> out.write("\u001b[".toByteArray())
        }
    }

    private fun sgr(r: Random, out: ByteArrayOutputStream) {
        val p = when (r.nextInt(4)) {
            0 -> "38;5;${number(r)}"
            1 -> "48;2;${number(r)};${number(r)};${number(r)}"
            2 -> "58:2::${number(r)}:${number(r)}:${number(r)}"
            else -> List(1 + r.nextInt(4)) { r.nextInt(110) }.joinToString(";")
        }
        out.write("\u001b[${p}m".toByteArray())
    }

    private fun number(r: Random): Int = when (r.nextInt(6)) {
        0 -> 0
        1 -> r.nextInt(100_000, 10_000_000)
        2 -> r.nextInt(256)
        else -> r.nextInt(1, 50)
    }

    private fun show(data: ByteArray) = data.joinToString("") {
        val b = it.toInt() and 0xff
        if (b in 0x20..0x7e) b.toChar().toString() else "\\x%02x".format(b)
    }

    private companion object {
        const val RUNS = 200L
        const val STEPS = 200
        const val HISTORY = 50
        const val MAX_TITLE = 256
        const val FINALS = "@ABCDEFGHIJKLMPSTXZ`abcdefghlmnrsu"
        val MODES = listOf(1, 6, 7, 25, 47, 1047, 1048, 1049, 2004, 0, 65535)
        val ESCAPES = listOf("\u001b7", "\u001b8", "\u001bD", "\u001bE", "\u001bM", "\u001bH", "\u001bc",
            "\u001b(0", "\u001b(B", "\u001b)0", "\u001b#8", "\u001b=", "\u001b>", "\u001b", "\u001b(")
        val CONTROLS = intArrayOf(0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x18, 0x1a, 0x7f, 0x00)
        val WIDE = intArrayOf(0x4e2d, 0xac00, 0x1f600, 0xff21, 0x3000)
        val ZERO_WIDTH = intArrayOf(0x301, 0x200b, 0xd7b0, 0xfe0f, 0x1160)
    }
}
