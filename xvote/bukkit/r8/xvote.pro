# Pinned, so R8 doesn't inline them back into what loads at startup: the Bukkit events load with
# the first event (EventBridge$Fire), what the commands do with the first command (Actions), the
# rewards with the first vote paid (Payout), the imports with the first import, the placeholders
# only while PlaceholderAPI is on.
-keep,allowobfuscation class co.xenastudios.xvote.bukkit.EventBridge$Fire { *; }
-keep,allowobfuscation class co.xenastudios.xvote.Actions { *; }
-keep,allowobfuscation class co.xenastudios.xvote.AdminParts { *; }
-keep,allowobfuscation class co.xenastudios.xvote.Payout { *; }
-keep,allowobfuscation class co.xenastudios.xvote.FileImports { *; }
-keep,allowobfuscation class co.xenastudios.xvote.XMoneyEconomy { *; }
-keep,allowobfuscation class co.xenastudios.xvote.bukkit.VaultEconomy { *; }
-keep class co.xenastudios.xvote.bukkit.Placeholders { *; }
-keep,allowobfuscation class co.xenastudios.xvote.bukkit.PlaceholderHook { *; }
-keep,allowobfuscation class co.xenastudios.xvote.Calls { *; }
-keep,allowobfuscation class co.xenastudios.xvote.Votes { *; }
-keep,allowobfuscation class co.xenastudios.xvote.DeferredImports { *; }
-keep,allowobfuscation class co.xenastudios.xvote.receiver.Conversation { *; }
-keep,allowobfuscation class co.xenastudios.xvote.store.Ledger { *; }
# The receiver's v1 keys load with the first v1 vote (Receiver$Credentials), its limiter with the
# first connection (Conversation), writing tokens.yml with the first token made, NuVotifier's
# takeover on the first start, /vote test's client with the first test; reward items with the
# first reward given; Vault's economy only where Vault has one.
-keep,allowobfuscation class co.xenastudios.xvote.receiver.Keys { *; }
-keep,allowobfuscation class co.xenastudios.xvote.receiver.RateLimiter { *; }
-keep,allowobfuscation class co.xenastudios.xvote.receiver.TokensFile { *; }
-keep,allowobfuscation class co.xenastudios.xvote.bukkit.ItemsOut { *; }
-keep,allowobfuscation class co.xenastudios.xvote.bukkit.VaultEconomy$Companion { *; }
-keep,allowobfuscation class co.xenastudios.xvote.receiver.NuVotifier { *; }
-keep,allowobfuscation class co.xenastudios.xvote.receiver.VoteClient { *; }
