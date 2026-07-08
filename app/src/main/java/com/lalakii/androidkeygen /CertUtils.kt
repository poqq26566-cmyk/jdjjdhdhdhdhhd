package com.lalakii.androidkeygen

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/** 支持的密钥算法,与原 Windows 版 AndroidKeyGen 保持一致 */
enum class AlgorithmType { RSA, EC, DSA }

object CertUtils {

    init {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    /**
     * 生成 Android APK 签名证书(PKCS12/.jks)
     *
     * @return null 表示成功;否则返回失败原因
     */
    fun create(
        output: OutputStream,
        type: AlgorithmType,
        years: Int,
        alias: String,
        password: String,
        startDate: Date
    ): String? {
        return try {
            val random = SecureRandom()

            val keyPairGenerator: KeyPairGenerator
            val signatureAlgorithm: String

            when (type) {
                AlgorithmType.RSA -> {
                    keyPairGenerator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
                    keyPairGenerator.initialize(2048, random)
                    signatureAlgorithm = "SHA256WithRSAEncryption"
                }

                AlgorithmType.EC -> {
                    keyPairGenerator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
                    keyPairGenerator.initialize(256, random)
                    signatureAlgorithm = "SHA256withECDSA"
                }

                AlgorithmType.DSA -> {
                    keyPairGenerator = KeyPairGenerator.getInstance("DSA", BouncyCastleProvider.PROVIDER_NAME)
                    keyPairGenerator.initialize(1024, random)
                    signatureAlgorithm = "SHA256withDSA"
                }
            }

            val keyPair = keyPairGenerator.generateKeyPair()

            val notBefore = startDate
            val calendar = Calendar.getInstance()
            calendar.time = startDate
            calendar.add(Calendar.YEAR, years)
            val notAfter = calendar.time

            // 与原版一致:自签名证书,Issuer = Subject = "C=别名"
            val name = X500Name("C=$alias")
            val serial = BigInteger(63, random)

            val certBuilder = JcaX509v3CertificateBuilder(
                name,
                serial,
                notBefore,
                notAfter,
                name,
                keyPair.public
            )

            val signer = JcaContentSignerBuilder(signatureAlgorithm)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.private)

            val certHolder = certBuilder.build(signer)
            val cert: X509Certificate = JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(certHolder)

            val keyStore = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
            keyStore.load(null, null)
            keyStore.setKeyEntry(alias, keyPair.private, password.toCharArray(), arrayOf<java.security.cert.Certificate>(cert))
            keyStore.store(output, password.toCharArray())

            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
}
