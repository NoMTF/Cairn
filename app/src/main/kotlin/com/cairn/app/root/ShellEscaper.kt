package com.cairn.app.root

object ShellEscaper {
    fun quote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
