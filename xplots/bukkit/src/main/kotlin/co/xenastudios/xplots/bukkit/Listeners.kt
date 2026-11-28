package co.xenastudios.xplots.bukkit

import co.xenastudios.core.bukkit.ref
import co.xenastudios.xplots.Protection
import co.xenastudios.xplots.XPlotsPlugin
import co.xenastudios.xplots.model.Field
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Hanging
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryHolder
import org.bukkit.plugin.Plugin

/**
 * xPlots' listeners on Bukkit: 1.x's protection, asking the shared [Protection] for every
 * decision, and play (sign clicks, the wand, joins, chunks). The events only 1.14 and later have
 * (a lectern's book, a TNT's priming) are in `modern`'s listeners, registered only where the
 * server has them. On Folia each runs on the place's own thread; every answer is from memory.
 */
internal object Listeners {
    fun register(plugin: Plugin, xplots: XPlotsPlugin, server: BukkitPlotServer): AutoCloseable {
        val protect = Protect(xplots, server)
        val play = Play(xplots, server)
        val manager = plugin.server.pluginManager
        manager.registerEvents(protect, plugin)
        manager.registerEvents(play, plugin)
        val modern = ArrayList<Listener>()
        val guard = Guard(xplots)
        if (exists("org.bukkit.event.player.PlayerTakeLecternBookEvent")) modern += co.xenastudios.xplots.bukkit.modern.Lecterns(guard)
        if (exists("org.bukkit.event.block.TNTPrimeEvent") && hasPrimer()) modern += co.xenastudios.xplots.bukkit.modern.Priming(guard)
        modern.forEach { manager.registerEvents(it, plugin) }
        return AutoCloseable {
            HandlerList.unregisterAll(protect)
            HandlerList.unregisterAll(play)
            modern.forEach(HandlerList::unregisterAll)
        }
    }

    private fun exists(type: String): Boolean = try {
        Class.forName(type, false, Player::class.java.classLoader)
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    private fun hasPrimer(): Boolean = try {
        Class.forName("org.bukkit.event.block.TNTPrimeEvent", false, Player::class.java.classLoader).getMethod("getPrimingEntity")
        true
    } catch (e: ReflectiveOperationException) {
        false
    }

    /** What `modern`'s listeners ask, without naming the main source set's classes. */
    private class Guard(private val xplots: XPlotsPlugin) : co.xenastudios.xplots.bukkit.modern.Guard {
        override fun mayBuild(player: Player, block: Block): Boolean = Protect.may(xplots, player, block)
        override fun crosses(from: Block, to: Block): Boolean = Protect.crosses(xplots.protection, from, to)
        override fun tell(player: Player) = xplots.protection.tell(player.ref)
    }

    /**
     * Who may change what in a field (1.x's `Protection`): its owner and members in a rented
     * plot, staff (`xplots.bypass`) anywhere; nothing crosses into a field or from one plot to
     * another. At LOW, so later plugins see what was decided.
     */
    class Protect(private val xplots: XPlotsPlugin, private val server: BukkitPlotServer) : Listener {
        private val protection: Protection get() = xplots.protection

        private fun deny(event: Cancellable, player: Player) {
            event.isCancelled = true
            protection.tell(player.ref)
        }

        private fun check(event: Cancellable, player: Player, block: Block) {
            if (!may(xplots, player, block)) deny(event, player)
        }

        private fun at(block: Block) = protection.at(block.world.name, block.x, block.y, block.z)

        private fun inField(block: Block): Boolean = protection.covers(block.world.name, block.x, block.y, block.z)

        // ---- players ----

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBreak(event: BlockBreakEvent) = check(event, event.player, event.block)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPlace(event: BlockPlaceEvent) {
            if (event is BlockMultiPlaceEvent) {
                for (state in event.replacedBlockStates) {
                    if (!may(xplots, event.player, state.block)) return deny(event, event.player)
                }
            }
            check(event, event.player, event.block)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBucketEmpty(event: PlayerBucketEmptyEvent) = check(event, event.player, event.blockClicked.getRelative(event.blockFace))

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBucketFill(event: PlayerBucketFillEvent) = check(event, event.player, event.blockClicked)

        @EventHandler(priority = EventPriority.LOW)
        fun onInteract(event: PlayerInteractEvent) {
            val block = event.clickedBlock ?: return
            if (event.useInteractedBlock() == Event.Result.DENY && event.useItemInHand() == Event.Result.DENY) return
            val player = event.player
            if (event.action == Action.PHYSICAL) {
                // Trampling farmland, pressure plates and tripwires.
                if (!may(xplots, player, block)) {
                    event.setUseInteractedBlock(Event.Result.DENY)
                    event.isCancelled = true
                }
                return
            }
            if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
            val world = block.world.name
            if (protection.isSign(world, block.x, block.y, block.z)) return // Plot signs are clicked to rent: Play hears that.
            if (!inField(block)) return
            val plot = at(block)
            if (plot != null && protection.mayBuild(player.ref, false, world, block.x, block.y, block.z) || player.hasPermission(XPlotsPlugin.BYPASS)) return
            if (!xplots.isReady) {
                event.isCancelled = true
                return
            }
            val type = block.type
            if (event.action == Action.LEFT_CLICK_BLOCK) {
                // Punching is harmless, except to dragon eggs and note blocks, and to fire, which it puts out.
                if (type == Material.DRAGON_EGG || type == Material.NOTE_BLOCK || block.getRelative(event.blockFace).type == Material.FIRE) deny(event, player)
                return
            }
            var refused = false
            if (plot !== Field.PATH) {
                // Nothing in someone else's plot is theirs to use: chests, doors, beds, pots, all of it.
                event.setUseInteractedBlock(Event.Result.DENY)
                refused = interactable(type)
            } else if (block.state is InventoryHolder) {
                // A path's doors and buttons, which staff put there, are for everyone; its chests aren't.
                event.setUseInteractedBlock(Event.Result.DENY)
                refused = true
            }
            // Tools that change the block they're used on. Food and swords still work.
            val item = event.item
            if (item != null && changesBlocks(item.type)) {
                event.setUseItemInHand(Event.Result.DENY)
                refused = true
            }
            if (refused) protection.tell(player.ref)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onSign(event: SignChangeEvent) = check(event, event.player, event.block)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onFertilize(event: BlockFertilizeEvent) {
            val player = event.player
            for (state in event.blocks) {
                if (if (player != null) !may(xplots, player, state.block) else crosses(protection, event.block, state.block)) {
                    event.isCancelled = true
                    return
                }
            }
        }

        // ---- entities in plots: item frames, paintings, armour stands ----

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onHangingPlace(event: HangingPlaceEvent) {
            val player = event.player ?: return
            check(event, player, event.block)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onHangingBreak(event: HangingBreakByEntityEvent) {
            val player = player(event.remover)
            val block = event.entity.location.block
            if (player != null) check(event, player, block) else if (inField(block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onHangingBreakOther(event: HangingBreakEvent) {
            if (event.cause == HangingBreakEvent.RemoveCause.EXPLOSION && inField(event.entity.location.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onInteractEntity(event: PlayerInteractEntityEvent) {
            val entity = event.rightClicked
            if (entity is Hanging || entity is ArmorStand) check(event, event.player, entity.location.block)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onArmorStand(event: PlayerArmorStandManipulateEvent) = check(event, event.player, event.rightClicked.location.block)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDamageEntity(event: EntityDamageByEntityEvent) {
            val entity = event.entity
            if (entity !is Hanging && entity !is ArmorStand) return // PvP and mobs aren't ours.
            val player = player(event.damager)
            val block = entity.location.block
            if (player != null) check(event, player, block) else if (inField(block)) event.isCancelled = true
        }

        // ---- the world: nothing crosses into a field, or from one plot to another ----

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEntityExplode(event: EntityExplodeEvent) = filter(event.blockList())

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBlockExplode(event: BlockExplodeEvent) = filter(event.blockList())

        // Explosions don't break anything in a field.
        private fun filter(blocks: MutableList<Block>) {
            if (blocks.isEmpty() || !protection.hasFields(blocks[0].world.name)) return
            blocks.removeIf { inField(it) }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPistonExtend(event: BlockPistonExtendEvent) {
            if (pistonCrosses(event.block, event.blocks, event.direction)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPistonRetract(event: BlockPistonRetractEvent) {
            if (pistonCrosses(event.block, event.blocks, event.direction)) event.isCancelled = true
        }

        private fun pistonCrosses(piston: Block, moved: List<Block>, direction: BlockFace): Boolean {
            if (!protection.hasFields(piston.world.name)) return false
            if (crosses(protection, piston, piston.getRelative(direction))) return true
            return moved.any { crosses(protection, piston, it) || crosses(protection, piston, it.getRelative(direction)) }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onFlow(event: BlockFromToEvent) {
            if (crosses(protection, event.block, event.toBlock)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onSpread(event: BlockSpreadEvent) {
            if (event.source.type == Material.FIRE && crosses(protection, event.source, event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBurn(event: BlockBurnEvent) {
            val block = event.block
            if (!inField(block)) return
            val source = event.ignitingBlock
            if (source == null || crosses(protection, source, block) || at(block) === Field.PATH) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onIgnite(event: BlockIgniteEvent) {
            val player = event.player
            if (player != null) return check(event, player, event.block)
            val source = event.ignitingBlock
            // Lightning, fireballs and the like start no fires in a field.
            if (if (source != null) crosses(protection, source, event.block) else inField(event.block)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
            val block = event.block
            val entity = event.entity
            if (entity is Player) return check(event, entity, block)
            if (!inField(block)) return
            val plot = at(block)
            // Falling sand and gravel landing in their own plot are part of building there (Paper knows where they fell from).
            if (entity is FallingBlock && plot != null && plot !== Field.PATH && server.paths.fallingOrigin) {
                val origin = co.xenastudios.xplots.bukkit.modern.Falling.origin(entity)
                if (origin != null && origin.world == block.world && at(origin.block) === plot) return
            }
            event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onGrow(event: StructureGrowEvent) {
            val sapling = event.location.block
            val player = event.player
            val bypass = player != null && player.hasPermission(XPlotsPlugin.BYPASS)
            event.blocks.removeIf { state ->
                when {
                    bypass -> false
                    player != null -> !may(xplots, player, state.block) || crosses(protection, sapling, state.block)
                    else -> crosses(protection, sapling, state.block)
                }
            }
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDispense(event: BlockDispenseEvent) {
            val dispenser = event.block
            val data = dispenser.blockData as? org.bukkit.block.data.Directional ?: return
            if (crosses(protection, dispenser, dispenser.getRelative(data.facing))) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onForm(event: BlockFormEvent) {
            // Snow golems' trails, and concrete powder setting, are fine; only signs are kept clear.
            val b = event.block
            if (protection.isSign(b.world.name, b.x, b.y, b.z)) event.isCancelled = true
        }

        companion object {
            /** Whether [player] may change [block]: outside fields at once, inside as [Protection] says, `xplots.bypass` asked only there. */
            fun may(xplots: XPlotsPlugin, player: Player, block: Block): Boolean {
                val p = xplots.protection
                val world = block.world.name
                if (!p.covers(world, block.x, block.y, block.z)) return true
                return p.mayBuild(player.ref, player.hasPermission(XPlotsPlugin.BYPASS), world, block.x, block.y, block.z)
            }

            fun crosses(protection: Protection, from: Block, to: Block): Boolean =
                protection.crosses(from.world.name, from.x, from.y, from.z, to.world.name, to.x, to.y, to.z)

            private fun player(entity: Entity?): Player? = when {
                entity is Player -> entity
                entity is Projectile -> entity.shooter as? Player
                else -> null
            }

            // Only decides whether to say why: a right click on plain floor while eating says nothing.
            @Suppress("DEPRECATION")
            private fun interactable(type: Material): Boolean = try {
                type.isInteractable
            } catch (e: NoSuchMethodError) {
                false
            }

            private val CHANGERS = setOf(
                "BONE_MEAL", "FLINT_AND_STEEL", "FIRE_CHARGE", "HONEYCOMB", "SHEARS", "BRUSH", "ARMOR_STAND", "END_CRYSTAL", "POTION",
                "SPLASH_POTION", "GLASS_BOTTLE", "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING", "INK_SAC", "GLOW_INK_SAC", "LEAD", "WIND_CHARGE",
            )

            private fun changesBlocks(item: Material): Boolean {
                val name = item.name
                return name.endsWith("_HOE") || (name.endsWith("_AXE") && !name.endsWith("PICKAXE")) || name.endsWith("_SHOVEL") ||
                    name.endsWith("_BOAT") || name.endsWith("_RAFT") || name.endsWith("_SPAWN_EGG") || name.endsWith("MINECART") || name in CHANGERS
            }
        }
    }

    /** Clicking plot signs, the wand, joins and quits, and chunks loading with signs in them. */
    class Play(private val xplots: XPlotsPlugin, private val server: BukkitPlotServer) : Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        fun onInteract(event: PlayerInteractEvent) {
            val block = event.clickedBlock ?: return
            if (event.hand != EquipmentSlot.HAND) return
            val player = event.player
            val left = event.action == Action.LEFT_CLICK_BLOCK
            if ((left || event.action == Action.RIGHT_CLICK_BLOCK) && server.isWand(event.item) && player.hasPermission(ADMIN)) {
                event.isCancelled = true
                val face = event.blockFace
                xplots.wand(player.ref, player.name, left, player.isSneaking, block.world.name, block.x, block.y, block.z, face.modX, face.modY, face.modZ)
                return
            }
            if (event.action != Action.RIGHT_CLICK_BLOCK) return
            val plot = xplots.plots.signAt(block.world.name, block.x, block.y, block.z) ?: return
            event.isCancelled = true
            xplots.signClicked(player.ref, player.name, plot)
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onJoin(event: PlayerJoinEvent) = xplots.joined(event.player.ref, event.player.name)

        @EventHandler(priority = EventPriority.MONITOR)
        fun onQuit(event: PlayerQuitEvent) = xplots.quit(event.player.ref, event.player.name)

        @EventHandler(priority = EventPriority.MONITOR)
        fun onChunkLoad(event: ChunkLoadEvent) {
            val chunk = event.chunk
            xplots.chunkLoaded(chunk.world.name, chunk.x, chunk.z)
        }

        private companion object {
            const val ADMIN = "xplots.admin"
        }
    }
}
