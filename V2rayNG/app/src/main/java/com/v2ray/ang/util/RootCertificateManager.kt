package com.v2ray.ang.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.data.Prefs
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.enums.RootCertificateProvider
import okhttp3.OkHttpClient
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds the trust material used by app-level HTTP clients and the Xray config.
 *
 * The selected roots are passed to the core as verification certificates. DoH/DoT/DoQ local clients
 * additionally receive an app-private PEM path through their DNS URL; the Xray patch consumes
 * that parameter without exposing the path to the remote DNS server.
 */
object RootCertificateManager {

    private const val ANDROID_CA_STORE = "AndroidCAStore"
    private const val MOZILLA_ROOTS_ASSET = "mozilla_roots_pem"
    private const val CHROME_ROOTS_ASSET = "chrome_roots_pem"
    private const val CORE_CERTIFICATE_FILE_PREFIX = "root-certs-"
    private const val DNS_CERTIFICATE_FILE_QUERY = "xrayRootCertFile"
    private val TLS_DNS_SCHEMES = setOf(
        "https",
        "https+local",
        "quic+local",
        "tls",
        "tls+local",
        "dot",
        "dot+local",
    )

    private data class TlsMaterial(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    private val tlsMaterials = ConcurrentHashMap<RootCertificateProvider, TlsMaterial>()
    private val coreBundleCache = ConcurrentHashMap<RootCertificateProvider, String>()
    private val coreFileLock = Any()

    /** Applies the selected app-level root store to an OkHttp builder. */
    fun configure(builder: OkHttpClient.Builder) {
        val provider = currentProvider()
        runCatching {
            val material = tlsMaterials.getOrPut(provider) { buildTlsMaterial(provider) }
            builder.sslSocketFactory(material.socketFactory, material.trustManager)
        }.onFailure {
            // Keep the platform trust manager when a custom store is unavailable or malformed.
            LogUtil.e(AppConfig.TAG, "Failed to configure root certificate provider: ${provider.value}", it)
        }
    }

    fun currentProvider(): RootCertificateProvider = RootCertificateProvider.from(
        runCatching {
            Prefs.string(
                AppConfig.PREF_ROOT_CERTIFICATE_PROVIDER,
                RootCertificateProvider.SYSTEM.value,
            )
        }.getOrNull()
    )

    /** Returns the selected root bundle as PEM, or null when the platform store should be used. */
    fun certificateBundle(provider: RootCertificateProvider = currentProvider()): String? = when (provider) {
        RootCertificateProvider.SYSTEM -> null
        else -> coreBundleCache.getOrPut(provider) { loadCertificateBundle(provider) }
    }

    /** Applies the selected roots to a generated Xray outbound TLS object. */
    fun configureCoreTls(settings: V2rayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean) {
        val provider = currentProvider()
        val bundle = certificateBundle(provider)
        when {
            provider == RootCertificateProvider.SYSTEM -> {
                settings.certificates = null
                settings.disableSystemRoot = false
                settings.useSystemCA = true
            }

            !bundle.isNullOrBlank() -> {
                settings.certificates = listOf(
                    V2rayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean.CertificateBean(
                        usage = "verify",
                        certificate = pemLines(bundle),
                    )
                )
                settings.disableSystemRoot = true
                settings.useSystemCA = false
            }
        }
    }

    /**
     * Writes the selected bundle where the Go core can read it for local DoH/DoT/DoQ clients.
     *
     * @return an absolute path, or null when the core's normal system roots should be used.
     */
    fun prepareCoreCertificateFile(context: Context): String? {
        val provider = currentProvider()
        if (provider == RootCertificateProvider.SYSTEM) return null
        val bundle = loadCertificateBundle(provider).also { coreBundleCache[provider] = it }
        val directory = File(Utils.userAssetPath(context))
        val target = File(directory, "$CORE_CERTIFICATE_FILE_PREFIX${provider.value}.pem")
        synchronized(coreFileLock) {
            directory.mkdirs()
            if (!target.isFile || runCatching { target.readText(Charsets.US_ASCII) }.getOrNull() != bundle) {
                target.writeText(bundle, Charsets.US_ASCII)
            }
        }
        return target.absolutePath
    }

    /** Adds the app-private CA path to a TLS-based Xray DNS server address. */
    fun applyDnsCertificateFile(address: String, context: Context): String {
        val scheme = Uri.parse(address).scheme?.lowercase() ?: return address
        if (scheme !in TLS_DNS_SCHEMES) return address
        val path = prepareCoreCertificateFile(context) ?: return address
        return applyDnsCertificateFile(address, path)
    }

    /** Applies core TLS roots and DNS root-file parameters to a CUSTOM JSON config. */
    fun applyCoreCustomConfig(json: JsonObject, context: Context) {
        val provider = currentProvider()
        val bundle = certificateBundle(provider)
        val rootFile = prepareCoreCertificateFile(context)
        applyCustomConfigElement(json, provider, bundle, rootFile)
    }

    private fun buildTlsMaterial(provider: RootCertificateProvider): TlsMaterial {
        val trustManager = when (provider) {
            RootCertificateProvider.SYSTEM -> buildAndroidTrustManager(includeUserCertificates = false)
            RootCertificateProvider.SYSTEM_AND_USER -> buildAndroidTrustManager(includeUserCertificates = true)
            RootCertificateProvider.MOZILLA -> buildPemTrustManager(MOZILLA_ROOTS_ASSET)
            RootCertificateProvider.CHROME -> buildPemTrustManager(CHROME_ROOTS_ASSET)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return TlsMaterial(sslContext.socketFactory, trustManager)
    }

    private fun assetCertificateBundle(assetName: String): String =
        AngApplication.application.assets.open(assetName).bufferedReader(Charsets.US_ASCII).use { reader ->
            reader.readText().trim() + "\n"
        }

    private fun loadCertificateBundle(provider: RootCertificateProvider): String = when (provider) {
        RootCertificateProvider.SYSTEM -> error("System provider has no certificate bundle")
        RootCertificateProvider.SYSTEM_AND_USER -> androidCertificateBundle()
        RootCertificateProvider.MOZILLA -> assetCertificateBundle(MOZILLA_ROOTS_ASSET)
        RootCertificateProvider.CHROME -> assetCertificateBundle(CHROME_ROOTS_ASSET)
    }

    private fun androidCertificateBundle(): String {
        val store = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null, null) }
        val aliases = store.aliases()
        return buildString {
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                store.getCertificate(alias)?.let { certificate ->
                    append(certificateToPem(certificate))
                    append('\n')
                }
            }
        }.trim() + "\n"
    }

    private fun certificateToPem(certificate: Certificate): String {
        val encoded = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            encoded.chunked(64).forEach { line -> append(line).append('\n') }
            append("-----END CERTIFICATE-----")
        }
    }

    private fun pemLines(bundle: String): List<String> = bundle.trim().lines()

    private fun applyDnsCertificateFile(address: String, path: String): String {
        val uri = Uri.parse(address)
        if (uri.getQueryParameter(DNS_CERTIFICATE_FILE_QUERY) != null) return address
        return uri.buildUpon()
            .appendQueryParameter(DNS_CERTIFICATE_FILE_QUERY, path)
            .build()
            .toString()
    }

    private fun applyCustomConfigElement(
        element: JsonElement,
        provider: RootCertificateProvider,
        bundle: String?,
        rootFile: String?,
    ) {
        when {
            element.isJsonObject -> {
                val objectValue = element.asJsonObject
                objectValue.get("tlsSettings")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    applyCustomTlsSettings(it, provider, bundle)
                }
                objectValue.get("dns")?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    applyCustomDnsSettings(it, rootFile)
                }
                objectValue.entrySet().forEach { (_, child) ->
                    applyCustomConfigElement(child, provider, bundle, rootFile)
                }
            }

            element.isJsonArray -> element.asJsonArray.forEach { child ->
                applyCustomConfigElement(child, provider, bundle, rootFile)
            }
        }
    }

    private fun applyCustomTlsSettings(
        settings: JsonObject,
        provider: RootCertificateProvider,
        bundle: String?,
    ) {
        if (provider == RootCertificateProvider.SYSTEM) {
            settings.addProperty("useSystemCA", true)
            settings.addProperty("disableSystemRoot", false)
            return
        }
        if (bundle.isNullOrBlank()) return

        settings.addProperty("useSystemCA", false)
        settings.addProperty("disableSystemRoot", true)
        val certificates = settings.getAsJsonArray("certificates") ?: JsonArray().also {
            settings.add("certificates", it)
        }
        certificates.add(JsonObject().apply {
            addProperty("usage", "verify")
            add("certificate", JsonArray().also { lines -> pemLines(bundle).forEach(lines::add) })
        })
    }

    private fun applyCustomDnsSettings(settings: JsonObject, rootFile: String?) {
        if (rootFile == null) return
        val servers = settings.get("servers")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (index in 0 until servers.size()) {
            val server = servers[index]
            if (server.isJsonPrimitive && server.asJsonPrimitive.isString) {
                servers.set(index, JsonPrimitive(applyDnsCertificateFile(server.asString, rootFile)))
            } else if (server.isJsonObject) {
                val address = server.asJsonObject.get("address")
                if (address?.isJsonPrimitive == true && address.asJsonPrimitive.isString) {
                    server.asJsonObject.addProperty(
                        "address",
                        applyDnsCertificateFile(address.asString, rootFile)
                    )
                }
            }
        }
    }

    /** Copies Android's read-only combined CA store into a regular trust-store instance. */
    private fun buildAndroidTrustManager(includeUserCertificates: Boolean): X509TrustManager {
        val androidStore = KeyStore.getInstance(ANDROID_CA_STORE).apply {
            load(null, null)
        }
        val combinedStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }

        val aliases = androidStore.aliases()
        var certificateIndex = 0
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (!includeUserCertificates && !alias.startsWith("system:")) continue
            androidStore.getCertificate(alias)?.let { certificate ->
                combinedStore.setCertificateEntry("android-root-$certificateIndex", certificate)
                certificateIndex++
            }
        }
        check(certificateIndex > 0) { "Android CA store is empty" }
        return trustManager(combinedStore)
    }

    private fun buildPemTrustManager(assetName: String): X509TrustManager {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }
        val certificates = AngApplication.application.assets.open(assetName).use { input ->
            certificateFactory.generateCertificates(input)
        }

        certificates.forEachIndexed { index, certificate ->
            keyStore.setCertificateEntry("bundled-root-$index", certificate)
        }
        check(certificates.isNotEmpty()) { "No certificates found in $assetName" }
        return trustManager(keyStore)
    }

    private fun trustManager(keyStore: KeyStore): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .singleOrNull()
            ?: error("No X509TrustManager available")
    }
}
