package co.xenastudios.core.bukkit

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.bukkit.permissions.Permission as BukkitPermission
import org.bukkit.permissions.PermissionDefault as BukkitDefault

class BukkitPermissionsTest {

    private class Perms(payDefault: PermissionDefault) : PermissionSet("xsample") {
        val pay: Permission = permission("pay", "Pay other players.", payDefault)
        val give: Permission = permission("give", "Give coins.")
        val hidden: Permission = permission("hidden", "Nobody, until given.", PermissionDefault.NOBODY)
        val all: Permission = permission("*", "Everything.", children = grants(pay, give))
    }

    private lateinit var registrar: BukkitPermissions

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
        registrar = BukkitPermissions(FakeServer.plugins)
    }

    @Test
    fun eachPermissionIsDeclaredWithItsDefaultAndChildren() {
        registrar.sync(Perms(PermissionDefault.EVERYONE).permissions)
        val plugins = FakeServer.plugins
        assertEquals(BukkitDefault.TRUE, plugins.getPermission("xsample.pay").default)
        assertEquals(BukkitDefault.OP, plugins.getPermission("xsample.give").default)
        assertEquals(BukkitDefault.FALSE, plugins.getPermission("xsample.hidden").default)
        assertEquals("Pay other players.", plugins.getPermission("xsample.pay").description)
        assertEquals(mapOf("xsample.pay" to true, "xsample.give" to true), plugins.getPermission("xsample.*").children)
    }

    @Test
    fun aSyncChangesWhatChangedAndRemovesWhatsGone() {
        registrar.sync(Perms(PermissionDefault.EVERYONE).permissions)
        val give = FakeServer.plugins.getPermission("xsample.give")
        val pay = FakeServer.plugins.getPermission("xsample.pay")

        val changed = Perms(PermissionDefault.NOT_OP)
        registrar.sync(listOf(changed.pay, changed.give))
        assertSame(give, FakeServer.plugins.getPermission("xsample.give"), "an unchanged permission stays as it was")
        assertNotSame(pay, FakeServer.plugins.getPermission("xsample.pay"))
        assertEquals(BukkitDefault.NOT_OP, FakeServer.plugins.getPermission("xsample.pay").default)
        assertNull(FakeServer.plugins.getPermission("xsample.hidden"))
        assertNull(FakeServer.plugins.getPermission("xsample.*"))

        registrar.sync(emptyList())
        assertNull(FakeServer.plugins.getPermission("xsample.pay"))
    }

    @Test
    fun anotherPluginsNodeIsLeftAsThatPluginHasIt() {
        val theirs = BukkitPermission("xsample.give", "Someone else's.", BukkitDefault.TRUE)
        FakeServer.plugins.addPermission(theirs)
        registrar.sync(Perms(PermissionDefault.EVERYONE).permissions)
        registrar.sync(emptyList())
        assertSame(theirs, FakeServer.plugins.getPermission("xsample.give"))
    }
}
