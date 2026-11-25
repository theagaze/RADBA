package com.radba.remoto.adb

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Concrete ADB connection manager with a persistent 2048-bit RSA key pair.
 * The key and its self-signed X.509 certificate are stored in filesDir and
 * reused across app restarts so that the TV keeps authorising this client.
 */
internal class RadbaAdbConnectionManager(context: Context) : AbsAdbConnectionManager() {
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val directory = context.applicationContext.filesDir
        val keyFile = File(directory, "private.key")
        val certFile = File(directory, "cert.pem")

        var key = readPrivateKey(keyFile)
        var cert = readCertificate(certFile)
        if (key == null || cert == null) {
            val generated = generateCredentials()
            key = generated.first
            cert = generated.second
            writePrivateKey(keyFile, key)
            writeCertificate(certFile, cert)
        }
        privateKey = key
        certificate = cert
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = DEVICE_NAME

    private fun generateCredentials(): Pair<PrivateKey, Certificate> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE, SecureRandom.getInstance("SHA1PRNG"))
        val pair = generator.generateKeyPair()
        val private = pair.private
        val public: PublicKey = pair.public

        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + TWENTY_YEARS_MS)

        val extensions = CertificateExtensions()
        extensions.set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(public).identifier),
        )
        val name = X500Name("CN=$DEVICE_NAME")
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))

        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
        info.set("subject", android.sun.security.x509.CertificateSubjectName(name))
        info.set("key", CertificateX509Key(public))
        info.set("validity", CertificateValidity(notBefore, notAfter))
        info.set("issuer", android.sun.security.x509.CertificateIssuerName(name))
        info.set("extensions", extensions)

        val cert = X509CertImpl(info)
        cert.sign(private, algorithm)
        return private to cert
    }

    private companion object {
        const val KEY_SIZE = 2048
        const val DEVICE_NAME = "RADBA_Remoto"
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val TWENTY_YEARS_MS = 20L * 365L * 24L * 60L * 60L * 1000L

        fun readPrivateKey(file: File): PrivateKey? = runCatching {
            if (!file.exists() || file.length() <= 0L) return null
            val bytes = FileInputStream(file).use { it.readBytes() }
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }.getOrNull()

        fun readCertificate(file: File): Certificate? = runCatching {
            if (!file.exists() || file.length() <= 0L) return null
            FileInputStream(file).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        }.getOrNull()

        fun writePrivateKey(file: File, key: PrivateKey) {
            val encoded = key.encoded ?: return
            FileOutputStream(file).use { it.write(encoded) }
        }

        fun writeCertificate(file: File, certificate: Certificate) {
            FileOutputStream(file).use { output ->
                output.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.UTF_8))
                output.write('\n'.code)
                BASE64Encoder().encode(certificate.encoded, output)
                output.write('\n'.code)
                output.write(X509Factory.END_CERT.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }
}
