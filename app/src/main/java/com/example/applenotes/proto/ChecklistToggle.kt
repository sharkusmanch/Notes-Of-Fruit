package com.example.applenotes.proto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Ticks and unticks checklist items by editing the item's own attribute run
 * and nothing else.
 *
 * The general formatting path ([NoteAppender.setBodyBase64] with spans)
 * re-encodes every attribute run from [NoteBodyEditor.AttrSpan], which only
 * models part of a run: it gives every checklist item a new id, merges
 * neighbouring items in the same state into one run, and drops the fields it
 * does not know. This path leaves every byte it does not have to change.
 *
 *   AttributeRun  { length = 1; ParagraphStyle style = 2; ... }
 *   ParagraphStyle { styleType = 1 (103 = checklist); Todo todo = 5; ... }
 *   Todo          { bytes uuid = 1; int32 done = 2; }
 */
@OptIn(ExperimentalEncodingApi::class)
object ChecklistToggle {
    private const val FIELD_NOTESTOREPROTO_DOCUMENT = 2
    private const val FIELD_DOCUMENT_NOTE = 3
    private const val FIELD_NOTE_ATTRIBUTE_RUN = 5
    private const val FIELD_AR_LENGTH = 1
    private const val FIELD_AR_STYLE = 2
    private const val FIELD_AR_TIMESTAMP = 13
    private const val FIELD_PS_STYLETYPE = 1
    private const val FIELD_PS_TODO = 5
    private const val FIELD_TODO_UUID = 1
    private const val FIELD_TODO_DONE = 2

    /**
     * Set the done flag of the checklist items in [checks]. Each key is a
     * character offset into [expectedText] — the note as
     * [NoteBodyEditor.readVisibleText] returned it when the user tapped —
     * anywhere inside the item; the value is the state the item should have.
     *
     * Returns the new `TextDataEncrypted`, or [textDataEncryptedB64] itself
     * when every item is already in the wanted state. Returns null when the
     * note's text is no longer [expectedText] or an offset is not inside a
     * checklist item: the offsets then say nothing about this note.
     */
    fun applyBase64(
        textDataEncryptedB64: String,
        expectedText: String,
        checks: Map<Int, Boolean>,
    ): String? {
        val compressed = Base64.decode(textDataEncryptedB64)
        val proto = Gzip.decompress(compressed)
        val newProto = applyBytes(proto, expectedText, checks) ?: return null
        if (newProto === proto) return textDataEncryptedB64
        return Base64.encode(Gzip.compress(newProto, Gzip.detect(compressed)))
    }

    /** [applyBase64] on the decompressed proto. Returns [protoBytes] itself when nothing changes. */
    fun applyBytes(protoBytes: ByteArray, expectedText: String, checks: Map<Int, Boolean>): ByteArray? {
        val raw = NoteBodyEditor.readVisibleTextRaw(protoBytes) ?: return null
        if (raw.replace(' ', '\n').replace(' ', '\n') != expectedText) return null

        val top = ProtobufWire.decode(protoBytes)
        val docIdx = top.indexOfFirst {
            it.fieldNumber == FIELD_NOTESTOREPROTO_DOCUMENT && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        if (docIdx < 0) return null
        val docFields = ProtobufWire.decode(top[docIdx].payload)
        val noteIdx = docFields.indexOfFirst {
            it.fieldNumber == FIELD_DOCUMENT_NOTE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
        }
        if (noteIdx < 0) return null
        val noteFields = ProtobufWire.decode(docFields[noteIdx].payload)

        // One piece per attribute run to begin with; a run that covers more
        // than the item being changed is cut into pieces below.
        val pieces = ArrayList<Piece>()
        var pos = 0
        for ((i, f) in noteFields.withIndex()) {
            if (f.fieldNumber != FIELD_NOTE_ATTRIBUTE_RUN || f.wireType != ProtobufWire.WIRE_LENGTH_DELIM) continue
            val len = runLength(f)
            pieces.add(Piece(sourceIdx = i, source = f, start = pos, end = pos + len))
            pos += len
        }

        for ((offset, checked) in checks.toSortedMap()) {
            if (offset !in raw.indices) return null
            // The item is the whole stored paragraph. A soft line break
            // (U+2028) is inside it even though the screen starts a new line.
            val itemStart = raw.lastIndexOf('\n', offset - 1) + 1
            val itemEnd = raw.indexOf('\n', offset).let { if (it < 0) raw.length else it + 1 }
            if (pieces.none { it.start <= offset && offset < it.end && it.isChecklist }) return null

            var newUuid: ByteArray? = null
            var i = 0
            while (i < pieces.size) {
                val p = pieces[i]
                if (p.end <= itemStart || p.start >= itemEnd || !p.isChecklist) { i++; continue }
                if (p.start >= itemStart && p.end <= itemEnd) {
                    if (p.done != checked) p.done = checked
                    i++
                    continue
                }
                // The run also covers a neighbouring item: cut this item out
                // of it and give the cut-out part an id of its own.
                val cutStart = maxOf(p.start, itemStart)
                val cutEnd = minOf(p.end, itemEnd)
                val uuid = newUuid ?: randomUuidBytes().also { newUuid = it }
                val parts = ArrayList<Piece>(3)
                if (p.start < cutStart) parts.add(p.slice(p.start, cutStart))
                parts.add(p.slice(cutStart, cutEnd).also { it.done = checked; it.uuid = uuid })
                if (cutEnd < p.end) parts.add(p.slice(cutEnd, p.end))
                pieces.removeAt(i)
                pieces.addAll(i, parts)
                i += parts.size
            }
        }

        if (pieces.none { it.changed }) return protoBytes

        val rebuilt = ArrayList<ProtobufWire.Field>(noteFields.size + 4)
        var next = 0
        for ((i, f) in noteFields.withIndex()) {
            if (next < pieces.size && pieces[next].sourceIdx == i) {
                while (next < pieces.size && pieces[next].sourceIdx == i) rebuilt.add(pieces[next++].encode())
            } else {
                rebuilt.add(f)
            }
        }
        docFields[noteIdx] = docFields[noteIdx].copy(payload = ProtobufWire.encode(rebuilt))
        top[docIdx] = top[docIdx].copy(payload = ProtobufWire.encode(docFields))
        return ProtobufWire.encode(top)
    }

    /** A stretch of text carrying one attribute run's formatting. */
    private class Piece(
        val sourceIdx: Int,
        val source: ProtobufWire.Field,
        val start: Int,
        val end: Int,
    ) {
        private val style: List<ProtobufWire.Field>? = ProtobufWire.decode(source.payload)
            .firstOrNull { it.fieldNumber == FIELD_AR_STYLE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?.let { ProtobufWire.decode(it.payload) }
        private val todo: List<ProtobufWire.Field>? = style
            ?.firstOrNull { it.fieldNumber == FIELD_PS_TODO && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM }
            ?.let { ProtobufWire.decode(it.payload) }
        private val sourceDone: Boolean = todo
            ?.firstOrNull { it.fieldNumber == FIELD_TODO_DONE && it.wireType == ProtobufWire.WIRE_VARINT }
            ?.let { ProtobufWire.decodeVarint(it) != 0L } ?: false

        val isChecklist: Boolean = style
            ?.firstOrNull { it.fieldNumber == FIELD_PS_STYLETYPE && it.wireType == ProtobufWire.WIRE_VARINT }
            ?.let { ProtobufWire.decodeVarint(it) } == NoteBodyEditor.ParagraphStyle.CHECKBOX_LIST.code.toLong()

        var done: Boolean = sourceDone
        /** Set only when the piece needs a new id; null keeps the run's own. */
        var uuid: ByteArray? = null

        private val todoChanged: Boolean get() = done != sourceDone || uuid != null
        val changed: Boolean get() = todoChanged || end - start != runLength(source)

        fun slice(from: Int, to: Int): Piece = Piece(sourceIdx, source, from, to).also {
            it.done = done
            it.uuid = uuid
        }

        fun encode(): ProtobufWire.Field {
            if (!changed) return source
            val ar = ProtobufWire.decode(source.payload)
            val lenIdx = ar.indexOfFirst { it.fieldNumber == FIELD_AR_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT }
            val lenField = ProtobufWire.encodeVarintField(FIELD_AR_LENGTH, (end - start).toLong())
            if (lenIdx >= 0) ar[lenIdx] = lenField else ar.add(0, lenField)
            if (todoChanged) {
                val styleIdx = ar.indexOfFirst {
                    it.fieldNumber == FIELD_AR_STYLE && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }
                val ps = ProtobufWire.decode(ar[styleIdx].payload)
                val todoIdx = ps.indexOfFirst {
                    it.fieldNumber == FIELD_PS_TODO && it.wireType == ProtobufWire.WIRE_LENGTH_DELIM
                }
                val td = if (todoIdx >= 0) ProtobufWire.decode(ps[todoIdx].payload) else mutableListOf()
                set(td, ProtobufWire.encodeVarintField(FIELD_TODO_DONE, if (done) 1L else 0L))
                val id = uuid ?: if (td.none { it.fieldNumber == FIELD_TODO_UUID }) randomUuidBytes() else null
                if (id != null) set(td, ProtobufWire.Field(FIELD_TODO_UUID, ProtobufWire.WIRE_LENGTH_DELIM, id))
                td.sortBy { it.fieldNumber }
                val todoField = ProtobufWire.Field(
                    FIELD_PS_TODO, ProtobufWire.WIRE_LENGTH_DELIM, ProtobufWire.encode(td),
                )
                if (todoIdx >= 0) ps[todoIdx] = todoField else ps.add(todoField)
                ar[styleIdx] = ar[styleIdx].copy(payload = ProtobufWire.encode(ps))
                // The run's own timestamp describes the state we just replaced.
                // iCloud.com writes runs without one, so do the same here.
                ar.removeAll { it.fieldNumber == FIELD_AR_TIMESTAMP }
            }
            return source.copy(payload = ProtobufWire.encode(ar))
        }

        private fun set(fields: MutableList<ProtobufWire.Field>, field: ProtobufWire.Field) {
            val i = fields.indexOfFirst { it.fieldNumber == field.fieldNumber }
            if (i >= 0) fields[i] = field else fields.add(field)
        }
    }

    private fun runLength(run: ProtobufWire.Field): Int =
        ProtobufWire.decode(run.payload)
            .firstOrNull { it.fieldNumber == FIELD_AR_LENGTH && it.wireType == ProtobufWire.WIRE_VARINT }
            ?.let { ProtobufWire.decodeVarint(it).toInt() } ?: 0

    private fun randomUuidBytes(): ByteArray {
        val u = java.util.UUID.randomUUID()
        return java.nio.ByteBuffer.allocate(16)
            .putLong(u.mostSignificantBits)
            .putLong(u.leastSignificantBits)
            .array()
    }
}
