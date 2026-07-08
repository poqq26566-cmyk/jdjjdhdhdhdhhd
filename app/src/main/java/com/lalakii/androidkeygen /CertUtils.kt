package com.lalakii.androidkeygen

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 支持的密钥算法(已移除强度不足的 DSA) */
enum class AlgorithmType { RSA, EC }

/** 查看证书时展示的信息 */
data class CertInfo(
    val alias: String,
    val version: Int,
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val notBefore: String,
    val notAfter: String,
    val signatureAlgorithm: String,
    val publicKeyAlgorithm: String,
    val publicKeyBits: Int,
    val isSelfSigned: Boolean,
    val isCA: Boolean,
    val sha256Fingerprint: String,
    val sha1Fingerprint: String,
    val md5Fingerprint: String
)

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
            }

            val keyPair = keyPairGenerator.generateKeyPair()

            val notBefore = startDate
            val calendar = Calendar.getInstance()
            calendar.time = startDate
            calendar.add(Calendar.YEAR, years)
            val notAfter = calendar.time

            // 注意:X.509 的 "C=" (Country) 字段规定必须是2位国家代码,采用受限的
            // PrintableString 编码,任意长度的别名(尤其含中文)写入会导致乱码。
            // 改用 "CN=" (Common Name) 字段,支持 UTF8String 编码,可正确保存任意文字。
            val name = X500Name("CN=$alias")
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

    /**
     * 读取 .jks/.p12 证书文件并返回其中每个条目的详细信息
     *
     * @return Pair(证书信息列表, 错误信息)。成功时错误信息为 null。
     */
    fun readCertificateInfo(input: InputStream, password: String): Pair<List<CertInfo>, String?> {
        return try {
            val keyStore = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
            keyStore.load(input, password.toCharArray())

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val results = mutableListOf<CertInfo>()

            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = keyStore.getCertificate(alias) as? X509Certificate ?: continue

                val sha256 = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
                val md5 = MessageDigest.getInstance("MD5").digest(cert.encoded)

                // 公钥位数:RSA 取模数位长,EC 取曲线域位长,其他类型尽力而为
                val publicKeyBits: Int = when (val pub = cert.publicKey) {
                    is java.security.interfaces.RSAPublicKey -> pub.modulus.bitLength()
                    is java.security.interfaces.ECPublicKey -> pub.params.curve.field.fieldSize
                    else -> -1
                }

                val isSelfSigned = try {
                    cert.verify(cert.publicKey)
                    true
                } catch (e: Exception) {
                    false
                }

                results.add(
                    CertInfo(
                        alias = alias,
                        version = cert.version,
                        subject = cert.subjectX500Principal.name,
                        issuer = cert.issuerX500Principal.name,
                        serialNumber = cert.serialNumber.toString(16),
                        notBefore = dateFormat.format(cert.notBefore),
                        notAfter = dateFormat.format(cert.notAfter),
                        signatureAlgorithm = cert.sigAlgName,
                        publicKeyAlgorithm = cert.publicKey.algorithm,
                        publicKeyBits = publicKeyBits,
                        isSelfSigned = isSelfSigned,
                        isCA = cert.basicConstraints != -1,
                        sha256Fingerprint = sha256.joinToString(":") { "%02X".format(it) },
                        sha1Fingerprint = sha1.joinToString(":") { "%02X".format(it) },
                        md5Fingerprint = md5.joinToString(":") { "%02X".format(it) }
                    )
                )
            }

            results to null
        } catch (e: Exception) {
            emptyList<CertInfo>() to (e.message ?: e.toString())
        }
    }
}
