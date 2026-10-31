package com.v2ray.ang.ui.main

/** A viewport's item index and pixel offset, plus identity and the SQL filter that produced it. */
internal data class ScrollAnchor(
    val index: Int,
    val offset: Int,
    val guid: String? = null,
    val query: String = ""
)

private const val AnchorFieldCount = 5

/** SavedState contains only primitives; neither Compose state nor profile payloads are serialised. */
internal fun encodeAnchors(anchors: Map<String, ScrollAnchor>): ArrayList<Any> {
    val encoded = ArrayList<Any>(anchors.size * AnchorFieldCount)
    anchors.forEach { (id, anchor) ->
        encoded.add(id)
        encoded.add(anchor.index)
        encoded.add(anchor.offset)
        encoded.add(anchor.guid.orEmpty())
        encoded.add(anchor.query)
    }
    return encoded
}

/**
 * Discards malformed records and incomplete tails instead of crashing Activity restoration.
 * Offsets are not clamped here; [restoredScrollAnchor] handles them uniformly when applying an anchor.
 */
internal fun decodeAnchors(raw: Any?): Map<String, ScrollAnchor> {
    val fields = raw as? List<*> ?: return emptyMap()
    return buildMap {
        var cursor = 0
        while (cursor + AnchorFieldCount <= fields.size) {
            val id = fields[cursor] as? String
            val index = fields[cursor + 1] as? Int
            val offset = fields[cursor + 2] as? Int
            val guid = fields[cursor + 3] as? String
            val query = fields[cursor + 4] as? String
            if (id != null && index != null && index >= 0 && offset != null && guid != null && query != null) {
                put(id, ScrollAnchor(index, offset, guid.takeIf { it.isNotEmpty() }, query))
            }
            cursor += AnchorFieldCount
        }
    }
}

/** A temporarily unloaded placeholder must not erase a known identity or borrow another row's identity. */
internal fun mergeScrollAnchor(previous: ScrollAnchor?, current: ScrollAnchor): ScrollAnchor =
    if (current.guid == null && previous != null &&
        previous.index == current.index && previous.query == current.query
    ) {
        current.copy(guid = previous.guid)
    } else {
        current
    }

/** Resolves reordering by GUID; deleted/filtered rows fall back to the nearest valid saved index. */
internal fun restoredScrollAnchor(anchor: ScrollAnchor, sqlIndex: Int?, itemCount: Int): ScrollAnchor {
    if (itemCount <= 0) return ScrollAnchor(0, 0, query = anchor.query)
    val validSqlIndex = sqlIndex?.takeIf { it >= 0 }
    val index = (validSqlIndex ?: anchor.index).coerceIn(0, itemCount - 1)
    return anchor.copy(
        index = index,
        offset = anchor.offset.coerceAtLeast(0),
        guid = anchor.guid.takeIf { validSqlIndex == index }
    )
}

/**
 * UI-owned anchor bookkeeping, kept free of Compose so save/restore races can be tested on the JVM.
 * Pending seeds are authoritative until restoration finishes; absent pages are retained on every save.
 */
internal class ScrollAnchorStore(seeds: Map<String, ScrollAnchor> = emptyMap()) {
    private val anchors = seeds.toMutableMap()
    private val pendingIds = seeds.keys.toMutableSet()

    fun pending(groupId: String): ScrollAnchor? = anchors[groupId].takeIf { groupId in pendingIds }

    fun record(groupId: String, anchor: ScrollAnchor) {
        if (groupId !in pendingIds) anchors[groupId] = mergeScrollAnchor(anchors[groupId], anchor)
    }

    fun finishRestore(groupId: String, anchor: ScrollAnchor? = null) {
        pendingIds.remove(groupId)
        if (anchor != null) anchors[groupId] = anchor
    }

    /**
     * Reads live offsets at save time without dropping uncomposed or still-loading restored groups.
     * A group with layout but no recorded anchor is skipped: its SQL scope is unknown, and guessing
     * one would claim another filter's row set for this position.
     */
    fun snapshot(positions: Map<String, Pair<Int, Int>> = emptyMap()): Map<String, ScrollAnchor> =
        anchors.toMutableMap().apply {
            positions.forEach { (id, position) ->
                val previous = anchors[id] ?: return@forEach
                if (id !in pendingIds) {
                    put(
                        id,
                        ScrollAnchor(
                            index = position.first,
                            offset = position.second,
                            // A live index alone proves nothing about identity; only an unmoved
                            // viewport may keep the GUID recorded for the same index.
                            guid = previous.guid.takeIf { previous.index == position.first },
                            query = previous.query
                        )
                    )
                }
            }
        }

    fun retain(validIds: Set<String>) {
        anchors.keys.retainAll(validIds)
        pendingIds.retainAll(validIds)
    }
}
