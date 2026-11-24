// A steady load for the budgets: N creative-mode bots that walk, break and place blocks,
// chat, and run the plugins' everyday commands; one bot rejoins every 30 s.
//
//   node load.js <port> <mc-version> <count>
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const [port, version, count] = [process.argv[2], process.argv[3], Number(process.argv[4] || 10)]
const commands = ['/balance', '/baltop', '/stats', '/homes', '/sethome b', '/home b', '/spawn', '/vote',
  '/ping', '/plots', '/warps', '/msg Bot0 hi', '/tpa Bot1', '/tpdeny']
const bots = []
let stats = { joined: 0, chats: 0, commands: 0, broken: 0, placed: 0, errors: 0 }

function start(i) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: +port, username: 'Bot' + i, version, auth: 'offline' })
  bots[i] = bot
  const timers = []
  bot.once('spawn', () => {
    stats.joined++
    let tick = 0
    timers.push(setInterval(() => {
      tick++
      // Walk somewhere new every second, jumping now and then.
      if (tick % 4 === 0) {
        bot.clearControlStates()
        const dirs = ['forward', 'back', 'left', 'right']
        bot.setControlState(dirs[(Math.random() * 4) | 0], true)
        bot.setControlState('jump', Math.random() < 0.2)
        bot.look(Math.random() * Math.PI * 2, 0, true).catch(() => {})
      }
      if (tick % 8 === i % 8) blocks(bot)
      if (tick % 40 === (i * 3) % 40) { bot.chat(`hello from ${bot.username} #${tick}`); stats.chats++ }
      if (tick % 60 === (i * 7) % 60) { bot.chat(commands[(Math.random() * commands.length) | 0]); stats.commands++ }
    }, 250))
  })
  bot.on('error', () => stats.errors++)
  bot.on('kicked', (r) => { stats.errors++; console.log('kicked', bot.username, JSON.stringify(r).slice(0, 200)) })
  bot.on('end', () => timers.forEach(clearInterval))
}

async function blocks(bot) {
  try {
    if (bot.game.gameMode !== 'creative') return
    const below = bot.entity.position.floored().offset(0, -1, 0)
    const target = bot.blockAt(below.offset(1, 0, 0))
    if (target && target.name !== 'air' && target.name !== 'bedrock') { await bot.dig(target, true); stats.broken++ }
    const Item = require('prismarine-item')(bot.registry)
    if (!bot.heldItem) await bot.creative.setInventorySlot(36, new Item(bot.registry.itemsByName.stone.id, 64))
    const ref = bot.blockAt(below.offset(1, -1, 0))
    if (ref && ref.name !== 'air') { await bot.placeBlock(ref, new Vec3(0, 1, 0)); stats.placed++ }
  } catch (e) { /* a block moved under us; try again next time */ }
}

for (let i = 0; i < count; i++) setTimeout(() => start(i), i * 400)
// Join and leave: the last bot rejoins every 30 s.
setInterval(() => { const i = count - 1; try { bots[i].quit() } catch {} setTimeout(() => start(i), 2000) }, 30000)
setInterval(() => console.log(JSON.stringify(stats)), 10000)
process.on('SIGTERM', () => { console.log('final', JSON.stringify(stats)); bots.forEach((b) => { try { b.quit() } catch {} }); setTimeout(() => process.exit(0), 500) })
