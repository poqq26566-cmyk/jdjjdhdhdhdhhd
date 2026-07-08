package com.lalakii.androidkeygen

import com.android.apksig.ApkSigner
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object ApkSignUtils {

    /**
     * 用 .jks/.p12 证书给 APK 签名(V1+V2+V3 签名方案全部启用)
     *
     * @param keyStoreInput 证书文件输入流
     * @param keyStorePassword 证书库密码
     * @param alias 要使用的别名;传 null 时自动使用证书里的第一个别名
     * @param keyPassword 该别名对应的密钥密码;传 null 时与 keyStorePassword 相同
     * @return null 表示成功;否则返回失败原因
     */
    fun signApk(
        inputApk: File,
        outputApk: File,
        keyStoreInput: InputStream,
        keyStorePassword: String,
        alias: String?,
        keyPassword: String?
    ): String? {
        return try {
            val keyStore = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
            keyStore.load(keyStoreInput, keyStorePassword.toCharArray())

            val aliasesEnum = keyStore.aliases()
            val realAlias = alias ?: if (aliasesEnum.hasMoreElements()) {
                aliasesEnum.nextElement()
            } else {
                return "证书文件里没有找到任何密钥条目"
            }

            val realKeyPassword = keyPassword ?: keyStorePassword

            val privateKey = keyStore.getKey(realAlias, realKeyPassword.toCharArray()) as? PrivateKey
                ?: return "找不到别名 \"$realAlias\" 对应的私钥,请检查别名或密码是否正确"

            val certChain = keyStore.getCertificateChain(realAlias)
                ?.map { it as X509Certificate }
                ?: return "找不到别名 \"$realAlias\" 对应的证书"

            val signerConfig = ApkSigner.SignerConfig.Builder(realAlias, privateKey, certChain).build()

            val apkSigner = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()

            apkSigner.sign()
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
}
