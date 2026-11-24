package co.xenastudios.core.common.i18n

/** A plugin's messages, as the tests' plugin declares them. */
object HomesMessages : MessageBundle() {
    val set: MessageKey = message(
        "homes.set", "<prefix>Home <highlight><name></highlight> set.",
        "When a player sets a home.",
        placeholders = listOf("name"),
        formerEnglish = listOf("<prefix>Set home <name>."),
    )
    val list: MessageKey = message("homes.list", "<prefix>You have <highlight><count></highlight> homes.", placeholders = listOf("count"))
    val none: MessageKey = message("homes.none", "<error>You have no homes.")
    val teleport: MessageKey = message(
        "teleport.go", "<click:run_command:'/home <name>'>Go to <name></click>",
        "A line in /homes.",
        placeholders = listOf("name"),
    )
    val hidden: MessageKey = message("teleport.hidden", "", "Blank: sends nothing.")
}

val CATALOG: MessageCatalog = MessageCatalog.of(HomesMessages)

val DE: LanguageTag = LanguageTag.of("de")
val PT_BR: LanguageTag = LanguageTag.of("pt-BR")
val PT: LanguageTag = LanguageTag.of("pt")

/** The jar's German, complete, with a former translation. */
const val SHIPPED_DE: String = """# German
homes.set: "<prefix>Zuhause <highlight><name></highlight> gesetzt."
homes.list: "<prefix>Du hast <highlight><count></highlight> Zuhause."
homes.none: "<error>Du hast kein Zuhause."
teleport.go: "<click:run_command:'/home <name>'>Gehe zu <name></click>"
teleport.hidden: ""
translation:
  name: "Deutsch"
  machine-drafted: true
  former:
    homes.set: ["<prefix>Home <highlight><name></highlight> gesetzt."]
"""

/** The jar's Brazilian Portuguese, partial: English fills in the rest. */
const val SHIPPED_PT_BR: String = """homes.set: "<prefix>Casa <highlight><name></highlight> definida."
homes.none: "<error>Você não tem casas."
"""
