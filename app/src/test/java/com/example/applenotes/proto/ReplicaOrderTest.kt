package com.example.applenotes.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A character's replica number is a position in the note's replica list, and
 * Apple's clients read that list in UUID order. Whatever our UUID is, a save
 * must leave the list sorted and every character with the replica it had.
 */
class ReplicaOrderTest {

    private val low = ByteArray(16) { 0x01 }
    private val high = ByteArray(16) { 0xFE.toByte() }

    private fun loadProtoBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fmt-baseline-1.proto")!!.readBytes()

    private fun noteFields(proto: ByteArray): List<ProtobufWire.Field> {
        val doc = ProtobufWire.decode(proto).first { it.fieldNumber == 2 }
        val note = ProtobufWire.decode(doc.payload).first { it.fieldNumber == 3 }
        return ProtobufWire.decode(note.payload)
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Replica UUIDs in list order; replica number n is element n - 1. */
    private fun replicas(proto: ByteArray): List<String> {
        val clock = noteFields(proto).first { it.fieldNumber == 4 }
        return ProtobufWire.decode(clock.payload).filter { it.fieldNumber == 1 }
            .map { hex(ProtobufWire.decode(it.payload).first { f -> f.fieldNumber == 1 }.payload) }
    }

    private class Sub(val replica: Int, val clock: Long, val length: Long, val tsReplica: Int, val tombstone: Boolean)

    private fun subs(proto: ByteArray): List<Sub> =
        noteFields(proto).filter { it.fieldNumber == 3 }.map { f ->
            val sub = ProtobufWire.decode(f.payload)
            fun id(field: Int) = ProtobufWire.decode(sub.first { it.fieldNumber == field }.payload)
            fun num(fields: List<ProtobufWire.Field>, n: Int) =
                fields.firstOrNull { it.fieldNumber == n }?.let { ProtobufWire.decodeVarint(it) } ?: 0L
            Sub(num(id(1), 1).toInt(), num(id(1), 2), num(sub, 2), num(id(3), 1).toInt(), num(sub, 4) != 0L)
        }

    /**
     * How many characters, live or deleted, each replica UUID owns, split by
     * the replica UUID on their timestamp ("owner/stamper").
     */
    private fun charsByReplica(proto: ByteArray): Map<String, Long> {
        val uuids = replicas(proto)
        val out = HashMap<String, Long>()
        for (s in subs(proto)) {
            if (s.replica == 0) continue
            out.merge(uuids[s.replica - 1] + "/" + uuids[s.tsReplica - 1], s.length, Long::plus)
        }
        return out
    }

    /** Clock entries by UUID, as bytes. */
    private fun clockEntries(proto: ByteArray): Map<String, String> {
        val clock = noteFields(proto).first { it.fieldNumber == 4 }
        return ProtobufWire.decode(clock.payload).filter { it.fieldNumber == 1 }.associate {
            hex(ProtobufWire.decode(it.payload).first { f -> f.fieldNumber == 1 }.payload) to hex(it.payload)
        }
    }

    /** The first counter of [uuid]'s clock entry: the next character clock it will hand out. */
    private fun nextClock(proto: ByteArray, uuid: ByteArray): Long {
        val clock = noteFields(proto).first { it.fieldNumber == 4 }
        val entry = ProtobufWire.decode(clock.payload).filter { it.fieldNumber == 1 }
            .map { ProtobufWire.decode(it.payload) }
            .first { hex(it.first { f -> f.fieldNumber == 1 }.payload) == hex(uuid) }
        val counter = ProtobufWire.decode(entry.first { it.fieldNumber == 2 }.payload)
        return ProtobufWire.decodeVarint(counter.first { it.fieldNumber == 1 })
    }

    /** [proto] with replicas 1 and 2 swapped, list and numbers both: the same note, list out of order. */
    private fun swapFirstTwoReplicas(proto: ByteArray): ByteArray {
        fun swap(n: Long) = when (n) { 1L -> 2L; 2L -> 1L; else -> n }
        fun swapId(id: ProtobufWire.Field): ProtobufWire.Field =
            id.copy(payload = ProtobufWire.encode(ProtobufWire.decode(id.payload).map {
                if (it.fieldNumber == 1) ProtobufWire.encodeVarintField(1, swap(ProtobufWire.decodeVarint(it))) else it
            }))
        val top = ProtobufWire.decode(proto)
        val docIdx = top.indexOfFirst { it.fieldNumber == 2 }
        val doc = ProtobufWire.decode(top[docIdx].payload)
        val noteIdx = doc.indexOfFirst { it.fieldNumber == 3 }
        val fields = ProtobufWire.decode(doc[noteIdx].payload).map { f ->
            when (f.fieldNumber) {
                3 -> f.copy(payload = ProtobufWire.encode(ProtobufWire.decode(f.payload).map {
                    if (it.fieldNumber == 1 || it.fieldNumber == 3) swapId(it) else it
                }))
                4 -> {
                    val entries = ProtobufWire.decode(f.payload)
                    f.copy(payload = ProtobufWire.encode(listOf(entries[1], entries[0]) + entries.drop(2)))
                }
                else -> f
            }
        }
        doc[noteIdx] = doc[noteIdx].copy(payload = ProtobufWire.encode(fields))
        top[docIdx] = top[docIdx].copy(payload = ProtobufWire.encode(doc))
        return ProtobufWire.encode(top)
    }

    private fun insertX(proto: ByteArray, uuid: ByteArray): ByteArray {
        val shown = NoteBodyEditor.readVisibleText(proto)!!
        return NoteAppender.setBodySpliceBytes(proto, uuid, shown.substring(0, 5) + "X" + shown.substring(5), 1700000000L)
    }

    private fun assertSortedAndIntact(before: ByteArray, after: ByteArray, uuid: ByteArray, added: Long) {
        val list = replicas(after)
        assertEquals(list.sorted(), list)
        assertTrue(hex(uuid) in list)
        val expected = HashMap(charsByReplica(before)).apply { merge(hex(uuid) + "/" + hex(uuid), added, Long::plus) }
        assertEquals(expected, charsByReplica(after))
        // Nobody else's clock entry changes, and ours is past every character we own.
        assertEquals(clockEntries(before) - hex(uuid), clockEntries(after) - hex(uuid))
        val ours = list.indexOf(hex(uuid)) + 1
        assertEquals(subs(after).filter { it.replica == ours }.maxOf { it.clock + it.length }, nextClock(after, uuid))
    }

    @Test
    fun spliceKeepsReplicaListSortedWhenOurUuidSortsFirst() {
        val orig = loadProtoBytes()
        assertSortedAndIntact(orig, insertX(orig, low), low, 1)
    }

    @Test
    fun spliceKeepsReplicaListSortedWhenOurUuidSortsLast() {
        val orig = loadProtoBytes()
        assertSortedAndIntact(orig, insertX(orig, high), high, 1)
    }

    @Test
    fun secondReplicaSlotsInWithoutDisturbingTheFirst() {
        val orig = loadProtoBytes()
        val once = insertX(orig, high)
        val twice = insertX(once, low)
        assertSortedAndIntact(once, twice, low, 1)
        assertEquals(3, replicas(twice).size)
    }

    @Test
    fun editingAgainKeepsOurSlot() {
        val once = insertX(loadProtoBytes(), high)
        assertSortedAndIntact(once, insertX(once, high), high, 1)
    }

    @Test
    fun listLeftOutOfOrderIsSortedOnTheNextEdit() {
        val unsorted = swapFirstTwoReplicas(insertX(loadProtoBytes(), high))
        assertEquals(replicas(unsorted).sorted().reversed(), replicas(unsorted))

        assertSortedAndIntact(unsorted, insertX(unsorted, low), low, 1)
        assertSortedAndIntact(unsorted, insertX(unsorted, high), high, 1)
        assertSortedAndIntact(unsorted, NoteAppender.appendBytes(unsorted, low, "XY", 1700000000L), low, 2)
    }

    @Test
    fun deletionIsStampedWithOurReplicaWhereverItSorts() {
        val orig = loadProtoBytes()
        val shown = NoteBodyEditor.readVisibleText(orig)!!
        for (uuid in listOf(low, high)) {
            val out = NoteAppender.setBodySpliceBytes(orig, uuid, shown.removeRange(5, 8), 1700000000L)
            val list = replicas(out)
            assertEquals(list.sorted(), list)
            val ours = list.indexOf(hex(uuid)) + 1
            val fresh = subs(out).filter { it.tombstone && it.tsReplica == ours }
            assertEquals(3L, fresh.sumOf { it.length })
            assertTrue(fresh.all { it.replica != ours })
        }
    }

    @Test
    fun appendKeepsReplicaListSorted() {
        val orig = loadProtoBytes()
        for (uuid in listOf(low, high)) {
            assertSortedAndIntact(orig, NoteAppender.appendBytes(orig, uuid, "XY", 1700000000L), uuid, 2)
        }
    }
}
