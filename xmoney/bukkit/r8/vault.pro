# Vault's and VaultUnlocked's API ship whole, in their own names: plugins written for Vault find
# these classes in xMoney, which stands in for Vault (DECISIONS.md), including the parts xMoney
# doesn't use itself (permissions, chat). LGPL-3.0, unmodified: META-INF/licenses.
-keep class net.milkbowl.** { *; }
-keepnames class net.milkbowl.**
# PlaceholderAPI calls the expansion's methods by overriding; Bukkit's ServicesManager registers
# the API under its interface, which the common rule keeps.
-keep class co.xenastudios.xmoney.bukkit.Papi { public *; }
# Pinned whole: inlined into the entry point, it would load PlaceholderAPI's classes without it.
-keep class co.xenastudios.xmoney.bukkit.PapiHook { *; }
# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first transaction (EventBridge), the jar reading only when a jar's name says Vault.
-keep,allowobfuscation class co.xenastudios.xmoney.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xmoney.bukkit.vault.VaultJars { *; }
-keep,allowobfuscation class co.xenastudios.xmoney.bukkit.vault.ForeignVault { *; }
# What the commands do, made with the first command (Commands), and opening the change feed, on
# a network only: pinned for the same reason.
-keep,allowobfuscation class co.xenastudios.xmoney.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xmoney.ledger.Feed$Companion { *; }
-keep,allowobfuscation class co.xenastudios.xmoney.ledger.LedgerWork { *; }
-keep,allowobfuscation class co.xenastudios.xmoney.bukkit.vault.VaultBridge { *; }
-keep,allowobfuscation class co.xenastudios.xmoney.Money { *; }
-keep,allowobfuscation class co.xenastudios.xmoney.AdminParts { *; }
