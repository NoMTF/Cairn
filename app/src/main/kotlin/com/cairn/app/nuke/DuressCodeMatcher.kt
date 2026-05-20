package com.cairn.app.nuke

import com.cairn.app.storage.SettingsStore
import java.security.MessageDigest

/**
 * 暗码匹配器。
 *
 * 安全设计：
 * - 暗码以 SHA-256 哈希存储（明文不入存储）
 * - 默认出厂码开源公开（强制用户首次启动改）
 * - 比较时常量时间，防侧信道
 */
object DuressCodeMatcher {

    /** 出厂默认码（开源公开，用户必须改）*/
    const val FACTORY_DEFAULT_CODE = "2345678765俄6879729."

    /** 最小长度要求 */
    const val MIN_LENGTH = 8

    /**
     * 计算 SHA-256 hex
     */
    fun hash(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * 校验暗码强度
     */
    fun validateStrength(code: String): ValidationResult {
        if (code.length < MIN_LENGTH) {
            return ValidationResult.TOO_SHORT
        }
        val hasDigit = code.any { it.isDigit() }
        val hasLetter = code.any { it.isLetter() }
        val hasSymbol = code.any { !it.isLetterOrDigit() }

        if (!hasDigit || !hasLetter && !hasSymbol) {
            // 至少含数字 + (字母 或 符号)
            return ValidationResult.TOO_SIMPLE
        }
        if (code == FACTORY_DEFAULT_CODE) {
            return ValidationResult.IS_FACTORY_DEFAULT
        }
        return ValidationResult.OK
    }

    /**
     * 常量时间比较 hash（防侧信道时序攻击）
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    /**
     * 检查输入是否匹配暗码
     */
    suspend fun isMatch(input: String, settings: SettingsStore): Boolean {
        if (input.length < MIN_LENGTH) return false
        val storedHash = settings.getDuressCodeHash() ?: return false
        val inputHash = hash(input)
        return constantTimeEquals(inputHash, storedHash)
    }

    enum class ValidationResult {
        OK,
        TOO_SHORT,
        TOO_SIMPLE,
        IS_FACTORY_DEFAULT
    }
}
