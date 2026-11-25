// A real client in a boot test (scripts/boot-test.sh's "client:" step): one mineflayer bot,
// Joiner, an operator through the boot's seeded ops.json (scripts/boot-seeds/client), joins
// with its client's language set to German and checks what unit tests can't, on the server it's
// given: messages sent to a player, a player-run command, tab completion, the reply in the
// client's language and in a new one after the client changes it, and a chat line in xSample's
// format, through the core's chat bridge. The server's side (the join
// and quit in the family's player cache, with the language) is xSample's PlatformCheck. Prints
// each check, then CLIENT PASS and exits 0, or CLIENT FAIL and exits 1.
//
//   node client.js <port> [--name Joiner] [--timeout 90] [--plugin xsample|xutilities|zchat]
//
// --plugin picks whose commands it checks: xSample's (the default), xUtilities' (/fly,
// /heal and /back, a teleport and its way back, and /gamemode's completion), or zChat's (a
// second client, Friend, who isn't an operator: chat in zChat's format, the filter, a mention
// with its sound, /msg and /reply between the two, and /togglechat in German).
//
// The Minecraft version is the server's own (mineflayer asks it first), so one script runs
// from Spigot 1.8.8 to the newest version mineflayer speaks.
const mineflayer = require('mineflayer')

const port = Number(process.argv[2])
const option = (name, fallback) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : fallback }
const username = option('--name', 'Joiner')
const timeout = Number(option('--timeout', 90)) * 1000
const plugin = option('--plugin', 'xsample')
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const failures = []
const heard = []

// German from the start: in the configuration phase's settings (1.20.2 on, clientSettings) and in
// the ones mineflayer sends as it logs in, which it reads from bot.settings once its plugins load.
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, auth: 'offline', version: false, clientSettings: { locale: 'de_de' } })
bot.once('inject_allowed', () => { bot.settings.locale = 'de_de' })
// What a client shows: a signed chat message's unsigned content when the server sent one (a
// chat plugin's format on Paper), which mineflayer's messagestr leaves out.
bot.on('message', (msg) => heard.push((msg.unsigned || msg).toString()))
bot.on('kicked', (reason) => fail(`kicked: ${JSON.stringify(reason).slice(0, 300)}`, true))
bot.on('error', (e) => fail(`client error: ${e.message}`, true))
const deadline = setTimeout(() => fail(`not done within ${timeout / 1000} s`, true), timeout)

function check (name, passed, why) {
  console.log(`${passed ? 'ok  ' : 'FAIL'} ${name}${passed ? '' : `: ${why}`}`)
  if (!passed) failures.push(`${name}: ${why}`)
}

function fail (why, stop) {
  failures.push(why)
  console.log(`FAIL ${why}`)
  if (stop) finish()
}

// Waits for a chat line containing `text` after `from` lines, for up to `ms`.
async function hear (text, from, ms = 8000) {
  for (let waited = 0; waited < ms; waited += 100) {
    const line = heard.slice(from).find((l) => l.includes(text))
    if (line) return line
    await sleep(100)
  }
  return null
}

async function command (line, expect, name) {
  const from = heard.length
  bot.chat(line)
  const got = await hear(expect, from)
  check(name, got != null, `no chat line with "${expect}" after ${line}; heard: ${JSON.stringify(heard.slice(from)).slice(0, 400)}`)
}

// 1.13+ answers with objects ({ match, tooltip }), older servers with strings.
async function completions (text) {
  const matches = await bot.tabComplete(text, false, false, 8000)
  return matches.map((m) => (typeof m === 'string' ? m : m.match).replace(/^\//, ''))
}

// xUtilities as a player uses it, replies drawn from its lang/de.yml, then lang/en.yml.
async function xutilities () {
  await command('/fly', 'Fliegen', '/fly answers the player in German')
  await command('/heal', 'geheilt', '/heal answers the player in German')
  const modes = await completions('/gamemode ')
  check('tab completion of /gamemode', ['survival', 'creative'].every((m) => modes.includes(m)), `/gamemode  completed to ${JSON.stringify(modes)}`)
  // A teleport by a command, which /back remembers, then /back to where the player was.
  const before = bot.entity.position.y
  bot.chat(`/tp ${username} ~ ~10 ~`)
  await sleep(1500)
  await command('/back', 'Zurück zu deinem vorherigen Ort', '/back after a teleport')
  await sleep(1000)
  check('/back took the player back', Math.abs(bot.entity.position.y - before) < 1.5, `y ${bot.entity.position.y}, before the teleport ${before}`)
  // Before 1.12 the server reads a client's language only as it joins (DECISIONS.md, "A real client").
  const [major, minor] = (bot.version || '1.8').split('.').map(Number)
  if (major > 1 || minor >= 12) {
    bot.setSettings({ locale: 'en_us' })
    await sleep(2000)
    await command('/fly', 'Flight', '/fly answers in the language the client changed to (English)')
  }
  finish()
}

// zChat between two players: Joiner (an operator, in German) and Friend (not one, so the filter
// and the cooldown apply to them).
async function zchat () {
  const friend = mineflayer.createBot({ host: '127.0.0.1', port, username: 'Friend', auth: 'offline', version: false })
  const friendHeard = []
  let friendSounds = 0
  let joinerSounds = 0
  friend.on('message', (msg) => friendHeard.push((msg.unsigned || msg).toString()))
  friend.on('soundEffectHeard', () => { friendSounds++ })
  friend.on('hardcodedSoundEffectHeard', () => { friendSounds++ })
  bot.on('soundEffectHeard', () => { joinerSounds++ })
  bot.on('hardcodedSoundEffectHeard', () => { joinerSounds++ })
  friend.on('error', (e) => fail(`Friend's client error: ${e.message}`, true))
  await new Promise((resolve) => friend.once('spawn', resolve))
  await sleep(2000)
  const friendHears = async (text, from, ms = 8000) => {
    for (let waited = 0; waited < ms; waited += 100) {
      if (friendHeard.slice(from).some((l) => l.includes(text))) return true
      await sleep(100)
    }
    return false
  }
  // Chat in zChat's default format, drawn once: both players see the same line.
  let from = friendHeard.length
  await command('hello from the client', `${username}: hello from the client`, "chat in zChat's format, to the sender")
  check('chat in zChat\'s format, to another player', await friendHears(`${username}: hello from the client`, from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  // The filter, on a player without its bypass.
  let mine = heard.length
  friend.chat('you badword1 there')
  check('the filter censors a word', await hear('Friend: you ******** there', mine) != null, `heard ${JSON.stringify(heard.slice(mine))}`)
  // A mention: highlighted, and the mentioned player hears the ping.
  await sleep(3500)
  mine = heard.length
  const before = joinerSounds
  friend.chat(`hey ${username.toLowerCase()} look`)
  check('a mention is highlighted', await hear(`Friend: hey @${username} look`, mine) != null, `heard ${JSON.stringify(heard.slice(mine))}`)
  await sleep(1500)
  check('the mentioned player hears the ping', joinerSounds > before, `${joinerSounds - before} sounds`)
  // /msg and /reply between the two.
  from = friendHeard.length
  await command('/msg Friend psst over here', 'You → Friend: psst over here', '/msg shows the sender their line')
  check('/msg reaches the other player', await friendHears(`${username} → You: psst over here`, from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  mine = heard.length
  friend.chat('/r got it')
  check('/reply answers the last message', await hear('Friend → You: got it', mine) != null, `heard ${JSON.stringify(heard.slice(mine))}`)
  // A reply from zChat's lang/de.yml.
  await command('/togglechat', 'ausgeblendet', "/togglechat answers in the client's language (German)")
  await command('/togglechat', 'angezeigt', '/togglechat again shows chat')
  try { friend.quit() } catch {}
  finish()
}

async function run () {
  // The server reads the client's language as it joins (1.8.8 a moment later) and records it.
  await sleep(3000)
  if (plugin === 'xutilities') return xutilities()
  if (plugin === 'zchat') return zchat()
  await command('/xsample', 'You have', 'a player-run command answers the player')
  // Chat through the core's chat bridge: xSample's format, drawn once and sent back to the sender too.
  await command('hello from the client', `[xSample] ${username}: hello from the client`, "chat in xSample's format")
  const pay = await completions('/pay ')
  check('tab completion of a player argument', pay.includes(username), `/pay  completed to ${JSON.stringify(pay)}`)
  const sub = await completions('/xsample ')
  check('tab completion of subcommands', ['give', 'reload', 'debug'].every((s) => sub.includes(s)), `/xsample  completed to ${JSON.stringify(sub)}`)
  // A core message, drawn in the client's language: German.
  await command('/xsample debug', 'Prüfe', "a reply in the client's language (German)")
  // The client switches to English: the next reply follows.
  bot.setSettings({ locale: 'en_us' })
  await sleep(2000)
  await command('/xsample debug', 'Checking xSample', 'a reply in the language the client changed to (English)')
  finish()
}

function finish () {
  clearTimeout(deadline)
  console.log(failures.length ? `CLIENT FAIL: ${failures.join('; ')}` : `CLIENT PASS: ${username} on ${bot.version || '?'}`)
  try { bot.quit() } catch {}
  // The quit reaches the server before the process ends.
  setTimeout(() => process.exit(failures.length ? 1 : 0), 1000)
}

bot.once('spawn', () => run().catch((e) => fail(`check threw: ${e.stack || e}`, true)))
