package com.example.applenotes.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The screen shows a soft line break (U+2028) as a newline, so the text the
 * editor hands back has '\n' where the note stores U+2028. A save must treat
 * those as the same character: untouched breaks stay as they are stored and
 * nothing between them is deleted and re-inserted.
 */
class SpliceSoftBreakTest {

    private val replicaUuid = ByteArray(16) { it.toByte() }

    private fun loadProtoBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fmt-baseline-1.proto")!!.readBytes()

    private fun noteFields(proto: ByteArray): List<ProtobufWire.Field> {
        val doc = ProtobufWire.decode(proto).first { it.fieldNumber == 2 }
        val note = ProtobufWire.decode(doc.payload).first { it.fieldNumber == 3 }
        return ProtobufWire.decode(note.payload)
    }

    private fun tombstones(proto: ByteArray): Int =
        noteFields(proto).count { f ->
            f.fieldNumber == 3 && ProtobufWire.decode(f.payload).any {
                it.fieldNumber == 4 && ProtobufWire.decodeVarint(it) != 0L
            }
        }

    /** The sample note with its first and last space stored as soft line breaks. */
    private fun withSoftBreaks(): ByteArray {
        val orig = loadProtoBytes()
        val top = ProtobufWire.decode(orig)
        val docIdx = top.indexOfFirst { it.fieldNumber == 2 }
        val doc = ProtobufWire.decode(top[docIdx].payload)
        val noteIdx = doc.indexOfFirst { it.fieldNumber == 3 }
        val fields = ProtobufWire.decode(doc[noteIdx].payload)
        val textIdx = fields.indexOfFirst { it.fieldNumber == 2 }
        val chars = fields[textIdx].payload.decodeToString().toCharArray()
        chars[chars.indexOf(' ')] = '\u2028'
        chars[chars.lastIndexOf(' ')] = '\u2028'
        fields[textIdx] = fields[textIdx].copy(payload = String(chars).encodeToByteArray())
        doc[noteIdx] = doc[noteIdx].copy(payload = ProtobufWire.encode(fields))
        top[docIdx] = top[docIdx].copy(payload = ProtobufWire.encode(doc))
        return ProtobufWire.encode(top)
    }

    @Test
    fun unchangedTextWithSoftBreaksIsNotRewritten() {
        val soft = withSoftBreaks()
        val shown = NoteBodyEditor.readVisibleText(soft)!!

        assertSame(soft, NoteAppender.setBodySpliceBytes(soft, replicaUuid, shown, 1700000000L))
    }

    @Test
    fun appendLeavesSoftBreaksAndEverythingBetweenThemAlone() {
        val soft = withSoftBreaks()
        val raw = NoteBodyEditor.readVisibleTextRaw(soft)!!
        val shown = NoteBodyEditor.readVisibleText(soft)!!
        assertEquals(2, raw.count { it == '\u2028' })

        val out = NoteAppender.setBodySpliceBytes(soft, replicaUuid, shown + "X", 1700000000L)

        assertEquals(raw + "X", NoteBodyEditor.readVisibleTextRaw(out))
        assertEquals(tombstones(soft), tombstones(out))
    }

    @Test
    fun editBetweenSoftBreaksDeletesOnlyWhatChanged() {
        val soft = withSoftBreaks()
        val raw = NoteBodyEditor.readVisibleTextRaw(soft)!!
        val shown = NoteBodyEditor.readVisibleText(soft)!!
        val at = (raw.indexOf('\u2028') + raw.lastIndexOf('\u2028')) / 2

        val out = NoteAppender.setBodySpliceBytes(
            soft, replicaUuid, shown.substring(0, at) + "X" + shown.substring(at + 1), 1700000000L,
        )

        assertEquals(raw.substring(0, at) + "X" + raw.substring(at + 1), NoteBodyEditor.readVisibleTextRaw(out))
        assertEquals(tombstones(soft) + 1, tombstones(out))
    }
}
