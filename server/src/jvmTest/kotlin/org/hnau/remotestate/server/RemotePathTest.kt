package org.hnau.remotestate.server

import org.hnau.remotestate.RemoteJson
import org.hnau.remotestate.RemotePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemotePathTest {

    @Test
    fun rootIsEmptyString() {
        assertEquals("\"\"", RemoteJson.encodeToString(RemotePath.serializer(), RemotePath.root))
        assertEquals(RemotePath.root, RemoteJson.decodeFromString(RemotePath.serializer(), "\"\""))
    }

    @Test
    fun segmentsAreEscaped() {
        val path = RemotePath.root / "tasks" / "a/b" / "~" / "remove"
        val encoded = RemoteJson.encodeToString(RemotePath.serializer(), path)
        assertEquals("\"tasks/a~/b/~~/remove\"", encoded)
        assertEquals(path, RemoteJson.decodeFromString(RemotePath.serializer(), encoded))
    }

    @Test
    fun emptySegmentsAreRejected() {
        assertFailsWith<IllegalArgumentException> { RemotePath.root / "tasks" / "" }
    }
}
