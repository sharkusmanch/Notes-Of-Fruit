package com.example.applenotes.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Ticking a checklist item must change that item's done flag and nothing
 * else. fmt-baseline-1 holds two checklist items next to each other: the
 * first unchecked, the second checked, each in its own attribute run.
 */
@OptIn(ExperimentalEncodingApi::class)
class ChecklistToggleTest {

    private fun loadProtoBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fmt-baseline-1.proto")!!.readBytes()

    private fun b64(proto: ByteArray): String = Base64.encode(Gzip.compress(proto))

    private fun proto(b64: String): ByteArray = Gzip.decompress(Base64.decode(b64))

    private fun noteFields(proto: ByteArray): List<ProtobufWire.Field> {
        val doc = ProtobufWire.decode(proto).first { it.fieldNumber == 2 }
        val note = ProtobufWire.decode(doc.payload).first { it.fieldNumber == 3 }
        return ProtobufWire.decode(note.payload)
    }

    private fun runs(proto: ByteArray): List<ProtobufWire.Field> =
        noteFields(proto).filter { it.fieldNumber == 5 }

    private fun withNoteFields(proto: ByteArray, fields: List<ProtobufWire.Field>): ByteArray {
        val top = ProtobufWire.decode(proto)
        val docIdx = top.indexOfFirst { it.fieldNumber == 2 }
        val doc = ProtobufWire.decode(top[docIdx].payload)
        val noteIdx = doc.indexOfFirst { it.fieldNumber == 3 }
        doc[noteIdx] = doc[noteIdx].copy(payload = ProtobufWire.encode(fields))
        top[docIdx] = top[docIdx].copy(payload = ProtobufWire.encode(doc))
        return ProtobufWire.encode(top)
    }

    private fun runLength(run: ProtobufWire.Field): Int =
        ProtobufWire.decodeVarint(ProtobufWire.decode(run.payload).first { it.fieldNumber == 1 }).toInt()

    /** The 16-byte id of a checklist run, or null when the run is not a checklist item. */
    private fun todoUuid(run: ProtobufWire.Field): ByteArray? {
        val style = ProtobufWire.decode(run.payload).firstOrNull { it.fieldNumber == 2 } ?: return null
        val todo = ProtobufWire.decode(style.payload).firstOrNull { it.fieldNumber == 5 } ?: return null
        return ProtobufWire.decode(todo.payload).first { it.fieldNumber == 1 }.payload
    }

    /** Start offsets of the two checklist items, in visible-text order. */
    private fun itemStarts(spans: List<NoteBodyEditor.AttrSpan>): List<Int> {
        val out = ArrayList<Int>()
        var pos = 0
        for (s in spans) {
            if (s.style == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST) out.add(pos)
            pos += s.length
        }
        return out
    }

    private fun checkedAt(b64: String, pos: Int): Boolean {
        val text = NoteBodyEditor.readVisibleTextFromBase64(b64)!!
        val spans = NoteBodyEditor.parseAttributeRunsFromBase64(b64)
        return NoteBodyEditor.expandSpansToChars(text, spans)[pos].checked
    }

    @Test
    fun checkingAnItemChangesOnlyItsOwnRun() {
        val orig = loadProtoBytes()
        val origB64 = b64(orig)
        val text = NoteBodyEditor.readVisibleTextFromBase64(origB64)!!
        val (first, second) = itemStarts(NoteBodyEditor.parseAttributeRuns(orig))
        assertFalse(checkedAt(origB64, first))

        val newB64 = ChecklistToggle.applyBase64(origB64, text, mapOf(first to true))!!
        val new = proto(newB64)

        assertTrue(checkedAt(newB64, first))
        assertTrue("the other item keeps its state", checkedAt(newB64, second))
        assertEquals(text, NoteBodyEditor.readVisibleTextFromBase64(newB64))

        // Everything that is not an attribute run is byte-identical.
        val origOther = noteFields(orig).filter { it.fieldNumber != 5 }
        val newOther = noteFields(new).filter { it.fieldNumber != 5 }
        assertEquals(origOther.size, newOther.size)
        for ((a, b) in origOther.zip(newOther)) assertArrayEquals(a.payload, b.payload)

        // Exactly one attribute run differs, and it keeps the item's id.
        val origRuns = runs(orig)
        val newRuns = runs(new)
        assertEquals(origRuns.size, newRuns.size)
        val changed = origRuns.indices.filter { !origRuns[it].payload.contentEquals(newRuns[it].payload) }
        assertEquals(1, changed.size)
        assertArrayEquals(todoUuid(origRuns[changed[0]]), todoUuid(newRuns[changed[0]]))

        // Inside that run only the done flag differs.
        val before = ProtobufWire.decode(origRuns[changed[0]].payload)
        val after = ProtobufWire.decode(newRuns[changed[0]].payload)
        assertSameFieldsExcept(before, after, 2)
        val styleBefore = ProtobufWire.decode(before.first { it.fieldNumber == 2 }.payload)
        val styleAfter = ProtobufWire.decode(after.first { it.fieldNumber == 2 }.payload)
        assertSameFieldsExcept(styleBefore, styleAfter, 5)
        val todoAfter = ProtobufWire.decode(styleAfter.first { it.fieldNumber == 5 }.payload)
        assertEquals(listOf(1, 2), todoAfter.map { it.fieldNumber })
        assertEquals(1L, ProtobufWire.decodeVarint(todoAfter[1]))

        // Nothing outside the note message moved either.
        assertSameFieldsExcept(ProtobufWire.decode(orig), ProtobufWire.decode(new), 2)
        val docBefore = ProtobufWire.decode(ProtobufWire.decode(orig).first { it.fieldNumber == 2 }.payload)
        val docAfter = ProtobufWire.decode(ProtobufWire.decode(new).first { it.fieldNumber == 2 }.payload)
        assertSameFieldsExcept(docBefore, docAfter, 3)
    }

    /** Same fields in the same order, byte for byte, apart from field [except]. */
    private fun assertSameFieldsExcept(a: List<ProtobufWire.Field>, b: List<ProtobufWire.Field>, except: Int) {
        assertEquals(a.map { it.fieldNumber }, b.map { it.fieldNumber })
        for ((x, y) in a.zip(b)) {
            if (x.fieldNumber != except) assertArrayEquals("field ${x.fieldNumber}", x.payload, y.payload)
        }
    }

    /**
     * Apple's own apps stamp runs with field 13. A run we change loses its
     * stamp (iCloud.com writes runs without one); every other run keeps it.
     */
    @Test
    fun onlyTheChangedRunLosesItsStamp() {
        val orig = loadProtoBytes()
        val fields = noteFields(orig).toMutableList()
        for (i in fields.indices) {
            if (fields[i].fieldNumber != 5) continue
            val run = ProtobufWire.decode(fields[i].payload)
            run.add(ProtobufWire.encodeVarintField(13, 700_000_000L + i))
            fields[i] = fields[i].copy(payload = ProtobufWire.encode(run))
        }
        val stamped = withNoteFields(orig, fields)
        val stampedB64 = b64(stamped)
        val text = NoteBodyEditor.readVisibleTextFromBase64(stampedB64)!!
        val (first, _) = itemStarts(NoteBodyEditor.parseAttributeRuns(stamped))

        val new = proto(ChecklistToggle.applyBase64(stampedB64, text, mapOf(first to true))!!)

        val origRuns = runs(stamped)
        val newRuns = runs(new)
        val changed = origRuns.indices.filter { !origRuns[it].payload.contentEquals(newRuns[it].payload) }
        assertEquals(1, changed.size)
        assertTrue(ProtobufWire.decode(newRuns[changed[0]].payload).none { it.fieldNumber == 13 })
        assertEquals(origRuns.size - 1, newRuns.count { r -> ProtobufWire.decode(r.payload).any { it.fieldNumber == 13 } })
    }

    @Test
    fun uncheckingAnItemWorks() {
        val origB64 = b64(loadProtoBytes())
        val text = NoteBodyEditor.readVisibleTextFromBase64(origB64)!!
        val (first, second) = itemStarts(NoteBodyEditor.parseAttributeRunsFromBase64(origB64))
        assertTrue(checkedAt(origB64, second))

        val newB64 = ChecklistToggle.applyBase64(origB64, text, mapOf(second to false))!!

        assertFalse(checkedAt(newB64, second))
        assertFalse(checkedAt(newB64, first))
    }

    @Test
    fun severalItemsInOneCall() {
        val origB64 = b64(loadProtoBytes())
        val text = NoteBodyEditor.readVisibleTextFromBase64(origB64)!!
        val (first, second) = itemStarts(NoteBodyEditor.parseAttributeRunsFromBase64(origB64))

        val newB64 = ChecklistToggle.applyBase64(origB64, text, mapOf(first to true, second to false))!!

        assertTrue(checkedAt(newB64, first))
        assertFalse(checkedAt(newB64, second))
    }

    @Test
    fun anItemAlreadyInTheWantedStateIsLeftAlone() {
        val origB64 = b64(loadProtoBytes())
        val text = NoteBodyEditor.readVisibleTextFromBase64(origB64)!!
        val (_, second) = itemStarts(NoteBodyEditor.parseAttributeRunsFromBase64(origB64))

        assertSame(origB64, ChecklistToggle.applyBase64(origB64, text, mapOf(second to true)))
    }

    @Test
    fun refusesWhenTheNoteTextIsNotWhatTheScreenShowed() {
        val origB64 = b64(loadProtoBytes())
        val text = NoteBodyEditor.readVisibleTextFromBase64(origB64)!!
        val (first, _) = itemStarts(NoteBodyEditor.parseAttributeRunsFromBase64(origB64))

        assertNull(ChecklistToggle.applyBase64(origB64, text + "x", mapOf(first to true)))
    }

    @Test
    fun refusesAParagraphThatIsNotAChecklistItem() {
        val origB64 = b64(loadProtoBytes())
        val text = NoteBodyEditor.readVisibleTextFromBase64(origB64)!!

        assertNull(ChecklistToggle.applyBase64(origB64, text, mapOf(0 to true)))
        assertNull(ChecklistToggle.applyBase64(origB64, text, mapOf(text.length + 5 to true)))
    }

    /**
     * Earlier versions of this app merged neighbouring items in the same
     * state into one run with one id. Ticking one of them has to split the
     * run and give the split-off item an id of its own.
     */
    @Test
    fun splitsARunThatCoversTwoItems() {
        val orig = loadProtoBytes()
        val fields = noteFields(orig).toMutableList()
        val runIdxs = fields.indices.filter { fields[it].fieldNumber == 5 }
        val firstIdx = runIdxs.first { todoUuid(fields[it]) != null }
        val secondIdx = runIdxs[runIdxs.indexOf(firstIdx) + 1]
        val firstLen = runLength(fields[firstIdx])
        val secondLen = runLength(fields[secondIdx])
        // One unchecked run covering both items.
        val merged = ProtobufWire.decode(fields[firstIdx].payload)
        merged[merged.indexOfFirst { it.fieldNumber == 1 }] =
            ProtobufWire.encodeVarintField(1, (firstLen + secondLen).toLong())
        fields[firstIdx] = fields[firstIdx].copy(payload = ProtobufWire.encode(merged))
        fields.removeAt(secondIdx)
        val damaged = withNoteFields(orig, fields)
        val damagedB64 = b64(damaged)
        val text = NoteBodyEditor.readVisibleTextFromBase64(damagedB64)!!
        val first = itemStarts(NoteBodyEditor.parseAttributeRuns(damaged)).single()
        val second = first + firstLen
        assertFalse(checkedAt(damagedB64, second))

        val newB64 = ChecklistToggle.applyBase64(damagedB64, text, mapOf(second to true))!!

        assertFalse(checkedAt(newB64, first))
        assertTrue(checkedAt(newB64, second))
        val items = runs(proto(newB64)).filter { todoUuid(it) != null }
        assertEquals(listOf(firstLen, secondLen), items.map { runLength(it) })
        assertArrayEquals(todoUuid(runs(damaged).first { todoUuid(it) != null }), todoUuid(items[0]))
        assertNotEquals(todoUuid(items[0])!!.toList(), todoUuid(items[1])!!.toList())
        assertEquals(text.length, runs(proto(newB64)).sumOf { runLength(it) })
    }

    /**
     * When the stored text holds something our walk of the substring tree
     * does not see, the screen is not showing the real note and its offsets
     * cannot be trusted.
     */
    @Test
    fun refusesANoteWhoseStoredTextDisagreesWithItsSubstringTree() {
        val orig = loadProtoBytes()
        val fields = noteFields(orig).toMutableList()
        val textIdx = fields.indexOfFirst { it.fieldNumber == 2 }
        fields[textIdx] = fields[textIdx].copy(
            payload = (fields[textIdx].payload.decodeToString() + "unseen").encodeToByteArray(),
        )
        val oddB64 = b64(withNoteFields(orig, fields))
        val text = NoteBodyEditor.readVisibleTextFromBase64(oddB64)!!
        val (first, _) = itemStarts(NoteBodyEditor.parseAttributeRunsFromBase64(oddB64))

        assertNull(ChecklistToggle.applyBase64(oddB64, text, mapOf(first to true)))
    }

    /** A run that already has the wanted state is not split just because it covers two items. */
    @Test
    fun aRunCoveringTwoItemsIsLeftAloneWhenNothingChanges() {
        val orig = loadProtoBytes()
        val fields = noteFields(orig).toMutableList()
        val runIdxs = fields.indices.filter { fields[it].fieldNumber == 5 }
        val firstIdx = runIdxs.first { todoUuid(fields[it]) != null }
        val secondIdx = runIdxs[runIdxs.indexOf(firstIdx) + 1]
        val firstLen = runLength(fields[firstIdx])
        val merged = ProtobufWire.decode(fields[firstIdx].payload)
        merged[merged.indexOfFirst { it.fieldNumber == 1 }] =
            ProtobufWire.encodeVarintField(1, (firstLen + runLength(fields[secondIdx])).toLong())
        fields[firstIdx] = fields[firstIdx].copy(payload = ProtobufWire.encode(merged))
        fields.removeAt(secondIdx)
        val damagedB64 = b64(withNoteFields(orig, fields))
        val text = NoteBodyEditor.readVisibleTextFromBase64(damagedB64)!!
        val first = itemStarts(NoteBodyEditor.parseAttributeRunsFromBase64(damagedB64)).single()

        assertSame(damagedB64, ChecklistToggle.applyBase64(damagedB64, text, mapOf(first + firstLen to false)))
    }

    /**
     * A soft line break (U+2028) sits inside one checklist item, but the
     * screen shows it as a new line with its own box. Ticking either box
     * ticks the whole item and leaves the run in one piece.
     */
    @Test
    fun softLineBreakStaysInsideItsItem() {
        val orig = loadProtoBytes()
        val (first, _) = itemStarts(NoteBodyEditor.parseAttributeRuns(orig))
        val fields = noteFields(orig).toMutableList()
        val textIdx = fields.indexOfFirst { it.fieldNumber == 2 }
        val chars = fields[textIdx].payload.decodeToString().toCharArray()
        chars[first + 3] = ' '
        fields[textIdx] = fields[textIdx].copy(payload = String(chars).encodeToByteArray())
        val soft = withNoteFields(orig, fields)
        val softB64 = b64(soft)
        val text = NoteBodyEditor.readVisibleTextFromBase64(softB64)!!
        assertEquals('\n', text[first + 3])

        val newB64 = ChecklistToggle.applyBase64(softB64, text, mapOf(first + 4 to true))!!

        assertTrue(checkedAt(newB64, first))
        assertTrue(checkedAt(newB64, first + 4))
        assertEquals(runs(soft).size, runs(proto(newB64)).size)
        assertEquals(text, NoteBodyEditor.readVisibleTextFromBase64(newB64))
    }
}
