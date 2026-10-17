package com.v2ray.ang.ui.backup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.entities.WebDavConfig
import com.v2ray.ang.ui.components.InputFieldStatesSaver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebDavInputSnapshotTest {

    @Test
    fun saveActionCapturesAllFinalEditsWithoutFieldActionsOrACollector() {
        val states = WebDavFieldOrder.map { TextFieldState("committed-$it") }
        val edited = mapOf(
            WebDavField.URL to " https://example.com/dav ",
            WebDavField.USERNAME to " alice ",
            WebDavField.PASSWORD to " secret with spaces ",
            WebDavField.PATH to " /latest/backup "
        )
        WebDavFieldOrder.zip(states).forEach { (field, state) ->
            state.setTextAndPlaceCursorAtEnd(edited.getValue(field))
        }

        val action = BackupAction.SaveWebDav(snapshotWebDavFields(states))
        states.forEach { it.setTextAndPlaceCursorAtEnd("later edit") }

        assertEquals(
            WebDavConfig(
                baseUrl = "https://example.com/dav",
                username = "alice",
                password = " secret with spaces ",
                remoteBasePath = "/latest/backup"
            ),
            action.snapshot
        )
    }

    @Test
    fun blankOptionalFieldsUseTheExistingNormalization() {
        val states = WebDavFieldOrder.map { field ->
            TextFieldState(if (field == WebDavField.URL) "https://example.com" else " ")
        }

        assertEquals(
            WebDavConfig(
                baseUrl = "https://example.com",
                password = " ",
                remoteBasePath = AppConfig.WEBDAV_BACKUP_DIR
            ),
            snapshotWebDavFields(states)
        )
    }

    @Test
    fun savedDialogVisibilityAndFourDirectedSelectionsRestoreTogether() {
        val host = BackupDialogHost().apply { openWebDav() }
        val states = WebDavFieldOrder.map { TextFieldState("draft-$it", TextRange(5, 1)) }
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val expected = snapshotWebDavFields(states)
        val expectedDialog = host.current
        val savedHost = checkNotNull(with(BackupDialogHost.Saver) { scope.save(host) })
        val savedStates = checkNotNull(with(InputFieldStatesSaver) { scope.save(states) })
        host.dismiss()
        states.forEach { it.setTextAndPlaceCursorAtEnd("later edit") }

        val restoredHost = checkNotNull(BackupDialogHost.Saver.restore(savedHost))
        val restoredStates = checkNotNull(InputFieldStatesSaver.restore(savedStates))

        assertEquals(expectedDialog, restoredHost.current)
        assertEquals(expected, snapshotWebDavFields(restoredStates))
        assertEquals(List(WebDavFieldOrder.size) { TextRange(5, 1) }, restoredStates.map { it.selection })
    }

    @Test
    fun passwordIsNotRestoredFromInstanceStateAndMissingFieldsUseEmptyBuffers() {
        val states = WebDavFieldOrder.map { field ->
            TextFieldState(if (field == WebDavField.PASSWORD) "secret" else "value-$field")
        }
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = checkNotNull(with(WebDavFieldStatesSaver) { scope.save(states) })
        val restored = checkNotNull(WebDavFieldStatesSaver.restore(saved))

        assertEquals("", restored[WebDavFieldOrder.indexOf(WebDavField.PASSWORD)].text.toString())
        assertEquals("value-URL", restored.first().text.toString())
        assertEquals(AppConfig.WEBDAV_BACKUP_DIR, snapshotWebDavFields(restored.dropLast(1)).remoteBasePath)
    }

    @Test
    fun cancellingAndReopeningStartsANewSessionFromThePersistedForm() {
        val persisted = WebDavForm(
            baseUrl = "https://example.test/persisted",
            username = "alice",
            password = "secret",
            remotePath = "/backups"
        )
        val host = BackupDialogHost()

        host.openWebDav()
        val firstSession = checkNotNull(host.current as BackupDialog.WebDav)
        host.dismiss()
        assertNull(host.current)
        host.openWebDav()
        val reopenedSession = checkNotNull(host.current as BackupDialog.WebDav)

        assertNotEquals(firstSession.sessionKey, reopenedSession.sessionKey)
        assertEquals(persisted, webDavDialogSeed(reopenedSession.sessionKey, persisted).form)
    }

    @Test
    fun initialLoadDoesNotReplaceAWebDavStateAlreadyAcceptedFromRestore() {
        val restored = WebDavForm(baseUrl = "https://example.test/restored")
        val state = BackupUiState(webDav = restored, isWebDavLoaded = true, isWebDavLoadFinished = true)

        assertEquals(state, state.acceptInitialWebDav(WebDavForm(baseUrl = "https://example.test/initial")))
    }
}
