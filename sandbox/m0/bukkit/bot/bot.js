// A headless player for the benchmarks: joins, counts chat messages, stays until killed.
const mineflayer = require('mineflayer')
const [host, port, version] = process.argv.slice(2)
const bot = mineflayer.createBot({ host, port: Number(port), username: 'M0Bot', version, auth: 'offline' })
let count = 0
let last = ''
bot.on('messagestr', (m) => { count++; last = m })
bot.on('spawn', () => console.log('spawned'))
bot.on('kicked', (r) => console.log('kicked', JSON.stringify(r)))
bot.on('error', (e) => console.log('error', e.message))
setInterval(() => console.log(`messages ${count} last: ${last}`), 2000)
process.on('SIGTERM', () => { console.log(`final messages ${count} last: ${last}`); process.exit(0) })
