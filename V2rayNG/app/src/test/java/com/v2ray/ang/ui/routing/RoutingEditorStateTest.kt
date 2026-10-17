package com.v2ray.ang.ui.routing

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import com.v2ray.ang.data.entities.RulesetItem
import com.v2ray.ang.ui.base.BaseResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class RoutingEditorStateTest {
    private val completeForm = RoutingForm(
        remarks = "rule",
        locked = true,
        domain = "geosite:google,domain:example.com",
        ip = "geoip:private,1.1.1.1",
        process = "com.first,com.second",
        protocol = "http,tls",
        network = "udp",
        port = "53,443",
        outboundTag = "exit"
    )

    @Test
    fun everyFieldMapsFromEntityThroughEditorAndBack() {
        val source = RulesetItem(
            id = "rule",
            sortOrder = 42L,
            remarks = "rule",
            locked = true,
            domain = listOf("geosite:google", "domain:example.com"),
            ip = listOf("geoip:private", "1.1.1.1"),
            process = listOf("com.first", "com.second"),
            protocol = listOf("http", "tls"),
            network = "udp",
            port = "53,443",
            outboundTag = "exit"
        )
        val editor = RoutingEditorState()
        assertTrue(editor.initialize(source.toRoutingForm()))

        assertEquals(RoutingField.entries.map { it.name }.toSet(), completeForm.textValues().keys)
        assertEquals(completeForm, editor.snapshot())
        assertEquals(source, source.applyRoutingForm(editor.snapshot()))
    }

    @Test
    fun snapshotReadsEveryLatestBufferAndIsIndependentOfFurtherEditsAndLockChanges() {
        val editor = RoutingEditorState()
        editor.initialize(RoutingForm())
        completeForm.textValues().forEach { (key, value) ->
            editor.text[key].setTextAndPlaceCursorAtEnd(value)
        }
        editor.locked = true

        val submitted = RoutingEditAction.Save(editor.snapshot())
        RoutingField.entries.forEach { editor[it].setTextAndPlaceCursorAtEnd("later") }
        editor.locked = false

        assertEquals(completeForm, submitted.form)
        assertFalse(editor.snapshot().locked)
    }

    @Test
    fun pickerUsesCurrentTextAndWritesOnlyTheSameProcessState() {
        val editor = RoutingEditorState()
        editor.initialize(completeForm)
        val process = editor[RoutingField.PROCESS]
        process.setTextAndPlaceCursorAtEnd("com.new, com.new,com.last")
        val action = editor.selectProcessAction()
        val before = editor.snapshot()

        editor.onPickerResult(BaseResult.Selected(listOf("com.picked", "com.other")))

        assertEquals("com.new, com.new,com.last", action.process)
        assertSame(process, editor[RoutingField.PROCESS])
        assertEquals(before.copy(process = "com.picked,com.other"), editor.snapshot())
        editor.onPickerResult(BaseResult.Cancelled)
        assertEquals("com.picked,com.other", editor.snapshot().process)
        editor.onPickerResult(BaseResult.Selected())
        assertEquals("", editor.snapshot().process)
    }

    @Test
    fun saverKeepsAllDirectedSelectionsAndLockedFlagAndRejectsLateLoad() {
        val editor = RoutingEditorState()
        editor.initialize(completeForm)
        RoutingField.entries.forEach { field -> editor[field].edit { selection = TextRange(length, 1) } }
        val payload = checkNotNull(with(RoutingEditorState.Saver) { scope.save(editor) })
        editor.locked = false
        editor[RoutingField.PROCESS].setTextAndPlaceCursorAtEnd("later")
        val restored = checkNotNull(RoutingEditorState.Saver.restore(serializedCopy(payload)))

        assertFalse(restored.initialize(RoutingForm(remarks = "late")))
        assertEquals(completeForm, restored.snapshot())
        RoutingField.entries.forEach { field ->
            assertEquals(TextRange(restored[field].text.length, 1), restored[field].selection)
        }
    }

    @Test
    fun unloadedRestoreStillAcceptsFirstLoadAndDisplaysDefaultNetworkAsItsRealText() {
        val payload = checkNotNull(with(RoutingEditorState.Saver) { scope.save(RoutingEditorState()) })
        val restored = checkNotNull(RoutingEditorState.Saver.restore(payload))

        assertFalse(restored.text.isInitialized)
        assertTrue(restored.initialize(RoutingForm()))
        assertEquals(ROUTING_NETWORK_DEFAULT, restored.snapshot().network)
        assertFalse(restored.initialize(completeForm))
    }

    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun serializedCopy(value: Any): Any {
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { it.writeObject(value) }
            output.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    }
}
