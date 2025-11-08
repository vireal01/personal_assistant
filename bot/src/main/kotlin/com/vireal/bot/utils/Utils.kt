package com.vireal.bot.utils

fun String.escapeMarkdownV2(): String {
  return this.replace("_", "\\_")
    .replace("*", "\\*")
    .replace("[", "\\[")
    .replace("]", "\\]")
    .replace("(", "\\(")
    .replace(")", "\\)")
    .replace("~", "\\~")
    .replace("`", "\\`")
    .replace(">", "\\>")
    .replace("#", "\\#")
    .replace("+", "\\+")
    .replace("-", "\\-")
    .replace("=", "\\=")
    .replace("|", "\\|")
    .replace("{", "\\{")
    .replace("}", "\\}")
    .replace(".", "\\.")
    .replace("!", "\\!")
}
