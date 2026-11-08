package com.v2ray.ang.ui.server

import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.data.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XdriveFormTest {

    @Test
    fun templateKeepsOptionalFolderAndSecretsWhenSaved() {
        val form = validTemplateForm(remoteFolder = "folder", secrets = "user\npass")
        val saved = form.toProfileItem(ProfileItem.create(EConfigType.VLESS), EConfigType.VLESS)

        assertEquals(NetworkType.XDRIVE.type, saved.network)
        assertEquals(AppConfig.XDRIVE_SERVICE_TEMPLATE, saved.service)
        assertEquals("folder", saved.remoteFolder)
        assertEquals("user\npass", saved.secrets)
        assertEquals(form.template, saved.template)
        assertEquals(null, saved.flow)
    }

    @Test
    fun templateFolderAndSecretsAreOptional() {
        val errors = ServerValidator.validateForm(
            EConfigType.VLESS,
            validTemplateForm(remoteFolder = "", secrets = "")
        )
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun googleDriveNeedsExactlyThreeSecrets() {
        val valid = ServerForm(
            remarks = "Google Drive",
            address = "drive.example.com",
            password = "uuid",
            encryption = "native-encryption",
            network = NetworkType.XDRIVE.type,
            service = AppConfig.XDRIVE_SERVICE_GOOGLE_DRIVE,
            remoteFolder = "folder-id",
            secrets = "client-id\nclient-secret\nrefresh-token",
        )

        assertTrue(ServerValidator.validateForm(EConfigType.VLESS, valid).isEmpty())
        assertEquals(
            R.string.server_lab_xdrive_secrets_error,
            ServerValidator.validateForm(EConfigType.VLESS, valid.copy(secrets = "one\ntwo"))[ServerField.SECRETS]
        )
    }

    @Test
    fun xdriveRequiresVlessEncryptionAndNoFlow() {
        val errors = ServerValidator.validateForm(
            EConfigType.VLESS,
            validTemplateForm().copy(encryption = "none", flow = "xtls-rprx-vision")
        )

        assertEquals(R.string.server_lab_xdrive_encryption_error, errors[ServerField.ENCRYPTION])
        assertEquals(R.string.server_lab_xdrive_flow_error, errors[ServerField.FLOW])
    }

    @Test
    fun nonVlessXdriveAndOldServiceNameAreRejected() {
        val nonVlessErrors = ServerValidator.validateForm(EConfigType.VMESS, validTemplateForm())
        val oldServiceErrors = ServerValidator.validateForm(
            EConfigType.VLESS,
            validTemplateForm(service = "Google Drive", remoteFolder = "folder", secrets = "a\nb\nc")
        )

        assertEquals(R.string.server_lab_xdrive_vless_error, nonVlessErrors[ServerField.NETWORK])
        assertEquals(R.string.server_lab_xdrive_service_error, oldServiceErrors[ServerField.SERVICE])
    }

    @Test
    fun templateNamesRegexMustContainARealCaptureGroup() {
        val errors = ServerValidator.validateForm(
            EConfigType.VLESS,
            validTemplateForm(template = templateJson(namesRegex = "[()]"))
        )
        assertEquals(R.string.server_lab_xdrive_template, errors[ServerField.TEMPLATE])
    }

    @Test
    fun s3SettingsAndCredentialsSurviveEditing() {
        val form = validS3Form()
        val saved = form.toProfileItem(ProfileItem.create(EConfigType.VLESS), EConfigType.VLESS)
        val restored = ServerForm.from(saved)

        assertEquals(AppConfig.XDRIVE_SERVICE_S3, saved.service)
        assertEquals(form.s3, saved.s3)
        assertEquals(form.secrets, saved.secrets)
        assertEquals(form.remoteFolder, restored.remoteFolder)
        assertEquals(form.s3, restored.s3)
        assertEquals(form.secrets, restored.secrets)
        assertEquals(null, saved.template)
        assertTrue(ServerValidator.validateForm(EConfigType.VLESS, form).isEmpty())
        assertEquals(null, ServerValidator.validateProfile(EConfigType.VLESS, saved))
    }

    @Test
    fun s3ValidationIsSharedByFormAndProfile() {
        val cases = listOf(
            Triple(validS3Form().copy(s3 = "{}"), ServerField.S3, R.string.server_lab_xdrive_s3_error),
            Triple(
                validS3Form().copy(secrets = "access\n\ntoken"),
                ServerField.SECRETS,
                R.string.server_lab_xdrive_s3_secrets_error
            ),
            Triple(
                validS3Form().copy(remoteFolder = "../streams"),
                ServerField.REMOTE_FOLDER,
                R.string.server_lab_xdrive_s3_remote_folder_error
            )
        )
        cases.forEach { (form, field, message) ->
            assertEquals(message, ServerValidator.validateForm(EConfigType.VLESS, form)[field])
            val profile = form.toProfileItem(ProfileItem.create(EConfigType.VLESS), EConfigType.VLESS)
            assertEquals(
                com.v2ray.ang.ui.base.BaseText.of(message),
                ServerValidator.validateProfile(EConfigType.VLESS, profile)
            )
        }
    }

    @Test
    fun switchingAwayFromS3ClearsItsSettings() {
        val form = validS3Form()
        val initial = form.toProfileItem(ProfileItem.create(EConfigType.VLESS), EConfigType.VLESS)
        val local = form.copy(service = AppConfig.XDRIVE_SERVICE_LOCAL).toProfileItem(initial, EConfigType.VLESS)
        val tcp = form.copy(network = NetworkType.TCP.type).toProfileItem(initial, EConfigType.VLESS)

        assertEquals(null, local.s3)
        assertEquals(null, local.secrets)
        assertEquals(null, tcp.s3)
        assertEquals(null, tcp.secrets)
    }

    private fun validS3Form() = validTemplateForm(
        service = AppConfig.XDRIVE_SERVICE_S3,
        remoteFolder = "streams/client/",
        secrets = "access\nsecret\ntoken"
    ).copy(s3 = """{"endpoint":"https://s3.example.com","bucket":"bucket","pathStyle":true}""")

    private fun validTemplateForm(
        remoteFolder: String = "",
        secrets: String = "",
        service: String = AppConfig.XDRIVE_SERVICE_TEMPLATE,
        template: String = templateJson(),
    ) = ServerForm(
        remarks = "XDRIVE",
        address = "xdrive.example.com",
        password = "uuid",
        encryption = "native-encryption",
        network = NetworkType.XDRIVE.type,
        service = service,
        remoteFolder = remoteFolder,
        secrets = secrets,
        template = template,
    )

    private fun templateJson(namesRegex: String = "item:([^\\n]+)"): String =
        """
        {
          "put": {"url": "https://storage.example/put/{name}"},
          "get": {"url": "https://storage.example/get/{name}"},
          "delete": {"url": "https://storage.example/delete/{name}"},
          "list": {"url": "https://storage.example/list/{prefix}", "namesRegex": "$namesRegex"}
        }
        """.trimIndent()
}
