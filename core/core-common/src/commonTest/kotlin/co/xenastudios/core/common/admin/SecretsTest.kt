package co.xenastudios.core.common.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretsTest {

    @Test
    fun secretKeysLoseTheirValuesWhereverTheyAre() {
        val config = """
            config-version: 3
            storage:
              type: mysql
              mysql:
                host: db.example.com
                username: minecraft
                password: hunter2
                "api-key": 'abc123'
            discord:
              bot-token: MTA.secret
              webhook-url: https://discord.com/api/webhooks/1/abc
              channel: general
            homes:
              limit: 3
              bypass: false
        """.trimIndent()
        assertEquals(
            """
            config-version: 3
            storage:
              type: mysql
              mysql:
                host: db.example.com
                username: minecraft
                password: <removed>
                "api-key": <removed>
            discord:
              bot-token: <removed>
              webhook-url: <removed>
              channel: general
            homes:
              limit: 3
              bypass: false
            """.trimIndent(),
            Secrets.redact(config),
        )
    }

    @Test
    fun credentialsInUrlsAndQueryStringsGoToo() {
        assertEquals("url: mysql://<removed>@db:3306/mc", Secrets.redact("url: mysql://admin:pa55@db:3306/mc"))
        assertEquals("Couldn't reach https://<removed>@example.com/x", Secrets.redact("Couldn't reach https://token@example.com/x"))
        assertEquals("properties: useSSL=true&password=<removed>&x=1", Secrets.redact("properties: useSSL=true&password=s3cret&x=1"))
        // A section called secret has no value of its own to remove; what's in it is judged by its own keys.
        assertEquals("secret:\n  a: 1", Secrets.redact("secret:\n  a: 1"))
    }

    @Test
    fun onlyKeysThatNameASecret() {
        listOf("password", "mysql.password", "bot_token", "API-KEY", "private-key", "auth", "webhook").forEach { assertTrue(Secrets.isSecretKey(it), it) }
        listOf("key", "bypass", "author", "tokens-per-player", "passive", "keys").forEach { assertFalse(Secrets.isSecretKey(it), it) }
    }
}
