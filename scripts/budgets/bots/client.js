// A real client in a boot test (scripts/boot-test.sh's "client:" step): one mineflayer bot,
// Joiner, an operator through the boot's seeded ops.json (scripts/boot-seeds/client), joins
// with its client's language set to German and checks what unit tests can't, on the server it's
// given: messages sent to a player, a player-run command, tab completion, the reply in the
// client's language and in a new one after the client changes it, and a chat line in xSample's
// format, through the core's chat bridge. The server's side (the join
// and quit in the family's player cache, with the language) is xSample's PlatformCheck. Prints
// each check, then CLIENT PASS and exits 0, or CLIENT FAIL and exits 1.
//
//   node client.js <port> [--name Joiner] [--timeout 90] [--plugin xsample|xutilities|zchat|zchat-network|xmoney|xteleport|xbans|xbans-network|xvanish|xvanish-network|xvote|xvote-network|xmarket|xbackup] [--zchat] [--xmoney]
//     [--version 1.21.11] [--forge xena:xsample] [--vote-port 8192] [--vote-token <token>] [--fabric] [--fixed-language]
//
// --plugin picks whose commands it checks: xSample's (the default), xUtilities' (/fly,
// /heal and /back, a teleport and its way back, and /gamemode's completion), or zChat's (a
// second client, Friend, who isn't an operator: chat in zChat's format, the filter, a mention
// with its sound, /msg and /reply between the two, and /togglechat in German), or zChat across a
// network (zchat-network: through a proxy with two servers, Friend moves to "game" with /server,
// and chat, /msg and /reply cross between the two), or xMoney's (Friend again: /eco and /balance in
// German, Joiner paying Friend with /pay and Friend hearing it, an overdraft refused, /baltop; with
// --zchat, zChat beside it with a format showing <balance>, and Joiner's chat line carrying it), or
// xTeleport's (Friend again: a home and /home, /tpa accepted and Friend arriving after the warmup,
// /rtp; with --xmoney, Friend paying for /spawn through xMoney), or xBans' (Friend again: a tempban
// kicks them and keeps them out until it ends, then lets them back in; a mute stops their chat, and
// with --zchat zChat's /msg too, until it's lifted; a kick; Joiner's replies in German), or xBans
// across a network (xbans-network: through a proxy with two servers, Friend on "game": a mute made on
// lobby stops Friend's chat on game, and a ban made on lobby takes Friend off the network and turns
// them away at the proxy), or xVanish's with zChat and xTeleport beside it (xvanish: Friend again, not
// an operator: Joiner vanishes, and Friend no longer has them in the tab list or the world, hears a
// fake quit, can't /tpa or /msg them nor complete their name, and mentioning them pings no one; a
// relog of Friend's keeps them hidden; showing again brings them back with a fake join), or xVanish
// across a network (xvanish-network: through a proxy with two servers, Friend and Watcher, an operator
// too, on "game": Joiner vanishes on lobby, Watcher's /vanish list on game names them as not on this
// server, Joiner switches to game and stays hidden from Friend, and shows again there), or
// xVote's (real Votifier v2 votes sent to --vote-port, signed with --vote-token: one for Friend
// before Friend has ever joined, paid as Friend joins; one for Joiner, paid at once; /vote in
// German; with --xmoney, the money reward in Joiner's /balance), or xVote across a network
// (xvote-network: the votes sent to the proxy's receiver, Friend paid on lobby as Friend first
// joins, then on game, where Friend moved, and Joiner on lobby), or xMarket's (with --xmoney: /shop's
// menus, a category and the amount screen clicked through to a purchase that xMoney debits, /sell
// hand that it credits, /shop buy and /worth, all in German), or xBackup's (Joiner builds, backs
// up and plans, stages and calls off a restore of the world, all in German, with /backup's
// completion; Friend, no operator, is refused).
//
// The same scenarios run on Fabric (scripts/boot-fabric.sh's "client:" step, which adds --fabric):
// a check that differs there by design takes Fabric's side, as each says (xUtilities' /back,
// xSample's English-only sandbox). --fixed-language is for a server that keeps no client
// language (Fabric on 1.20.1, which boot-fabric.sh passes it for): Joiner reads English there, so
// each reply expected in German is expected in English, and changes of language aren't checked.
//
// The Minecraft version is the server's own (mineflayer asks it first), so one script runs
// from Spigot 1.8.8 to the newest version mineflayer speaks; --version sets it instead, for a
// proxy, which speaks every version and answers a ping with the newest. --forge sends a plugin
// message on that channel as soon as the client spawns, as a hacked client could, for the proxy
// boot tests: the proxy must drop it (scripts/boot-proxy.sh).
const mineflayer = require('mineflayer')

const port = Number(process.argv[2])
const option = (name, fallback) => { const i = process.argv.indexOf(name); return i > 0 ? process.argv[i + 1] : fallback }
const username = option('--name', 'Joiner')
const timeout = Number(option('--timeout', 90)) * 1000
const plugin = option('--plugin', 'xsample')
const version = option('--version', false)
const forge = option('--forge', null)
const withZChat = process.argv.includes('--zchat')
const withXMoney = process.argv.includes('--xmoney')
const votePort = Number(option('--vote-port', 8192))
const voteToken = option('--vote-token', '')
// The server is Fabric (scripts/boot-fabric.sh passes it): a few checks differ there by design.
const onFabric = process.argv.includes('--fabric')
// The server reads no client language (Fabric on 1.20.1): Joiner reads the default, English.
const fixedLanguage = process.argv.includes('--fixed-language')
// What Joiner, whose client is in German, should read: the German, or with --fixed-language the
// English every player reads there.
const de = (german, english) => (fixedLanguage ? english : german)
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const failures = []
const heard = []

// German from the start: in the configuration phase's settings (1.20.2 on, clientSettings) and in
// the ones mineflayer sends as it logs in, which it reads from bot.settings once its plugins load.
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username, auth: 'offline', version, clientSettings: { locale: 'de_de' } })
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

// 1.13+ answers with objects ({ match, tooltip }), older servers with strings. A moment's pause
// first, as a player's typing has: Velocity drops a request that comes within 10 ms of the last
// (its tab-complete-rate-limit), which a bot asking right after an answer would.
async function completions (text) {
  await sleep(250)
  const matches = await bot.tabComplete(text, false, false, 8000)
  return matches.map((m) => (typeof m === 'string' ? m : m.match).replace(/^\//, ''))
}

// xUtilities as a player uses it, replies drawn from its lang/de.yml, then lang/en.yml.
async function xutilities () {
  await command('/fly', de('Fliegen', 'Flight'), '/fly answers the player in German')
  await command('/heal', de('geheilt', 'healed'), '/heal answers the player in German')
  const modes = await completions('/gamemode ')
  check('tab completion of /gamemode', ['survival', 'creative'].every((m) => modes.includes(m)), `/gamemode  completed to ${JSON.stringify(modes)}`)
  if (onFabric) {
    // Fabric has no teleport event, so /back remembers deaths there, not vanilla's /tp (the
    // docs' platform table): away with /tp, die there, respawn, and /back returns to the spot.
    await command(`/tp ${username} ~ ~ ~40`, 'Teleported', 'vanilla /tp moves the player away')
    await sleep(1500)
    const died = bot.entity.position.clone()
    await command('/back', de('Du hast keinen Ort, zu dem du zurückkehren kannst.', 'You have nowhere to go back to.'), "/back doesn't hear vanilla /tp on Fabric")
    bot.chat(`/kill ${username}`)
    await new Promise((resolve) => bot.once('spawn', resolve))
    await sleep(1500)
    const flat = (a, b) => Math.sqrt((a.x - b.x) ** 2 + (a.z - b.z) ** 2)
    check('the player respawned away from where they died', flat(bot.entity.position, died) > 30, `at ${bot.entity.position}, died at ${died}`)
    await command('/back', de('Zurück zu deinem vorherigen Ort', 'Teleported back to your previous location'), '/back after a death')
    await sleep(1000)
    check('/back took the player to where they died', flat(bot.entity.position, died) < 1.5, `at ${bot.entity.position}, died at ${died}`)
  } else {
    // A teleport by a command, which /back remembers, then /back to where the player was.
    const before = bot.entity.position.y
    bot.chat(`/tp ${username} ~ ~10 ~`)
    await sleep(1500)
    await command('/back', 'Zurück zu deinem vorherigen Ort', '/back after a teleport')
    await sleep(1000)
    check('/back took the player back', Math.abs(bot.entity.position.y - before) < 1.5, `y ${bot.entity.position.y}, before the teleport ${before}`)
  }
  // Before 1.12 the server reads a client's language only as it joins (DECISIONS.md, "A real client").
  const [major, minor] = (bot.version || '1.8').split('.').map(Number)
  if ((major > 1 || minor >= 12) && !fixedLanguage) {
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
  await command('/togglechat', de('ausgeblendet', 'hidden'), "/togglechat answers in the client's language (German)")
  await command('/togglechat', de('angezeigt', 'shown'), '/togglechat again shows chat')
  try { friend.quit() } catch {}
  finish()
}

// xMoney between two players: Joiner (an operator, in German) pays Friend (not one, in English).
async function xmoney () {
  const friend = mineflayer.createBot({ host: '127.0.0.1', port, username: 'Friend', auth: 'offline', version: false })
  const friendHeard = []
  friend.on('message', (msg) => friendHeard.push((msg.unsigned || msg).toString()))
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
  // Replies from xMoney's lang/de.yml, amounts in the currency's own format.
  await command(`/eco give ${username} 100`, `${username} $100.00`, "/eco give answers the operator in German")
  await command('/balance', de('Kontostand: $100.00', 'Balance: $100.00'), "/balance answers in the client's language (German)")
  // Joiner pays Friend: both sides told, the money moved.
  let from = friendHeard.length
  await command('/pay Friend 12.5', de('Gesendet: $12.50 an Friend. Dir bleiben $87.50.', 'Sent $12.50 to Friend. You have $87.50 left.'), '/pay tells the payer what they sent and have left')
  check('/pay tells whoever is paid', await friendHears(`Received $12.50 from ${username}.`, from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  from = friendHeard.length
  friend.chat('/balance')
  check("the money reached the other player's balance", await friendHears('Balance: $12.50', from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  from = friendHeard.length
  friend.chat(`/pay ${username} 1000`)
  check('a payment over the balance is refused', await friendHears("You don't have $1,000.00. You have $12.50.", from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  await command('/baltop', ` ${username} $87.50`, '/baltop lists the payer with what they have left')
  if (withZChat) {
    // zChat's format with xMoney's <balance>, through zChat's placeholder seam.
    from = friendHeard.length
    await command('rich now', `[$87.50] ${username}: rich now`, "the sender's balance in zChat's format, to the sender")
    check("the sender's balance in zChat's format, to another player", await friendHears(`[$87.50] ${username}: rich now`, from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  }
  try { friend.quit() } catch {}
  finish()
}

// xTeleport between two players: Joiner (an operator, in German, so no warmup or cooldown) sets a
// home and goes back to it, accepts Friend's /tpa (Friend isn't one: a real warmup, then Friend
// arrives beside Joiner), and /rtp's search sends Joiner somewhere new. With --xmoney, xMoney beside
// it and /spawn costing 5: Friend is given 10, pays for /spawn, and has 5 left.
async function xteleport () {
  const friend = mineflayer.createBot({ host: '127.0.0.1', port, username: 'Friend', auth: 'offline', version: false })
  const friendHeard = []
  friend.on('message', (msg) => friendHeard.push((msg.unsigned || msg).toString()))
  friend.on('error', (e) => fail(`Friend's client error: ${e.message}`, true))
  await new Promise((resolve) => friend.once('spawn', resolve))
  await sleep(2000)
  const friendHears = async (text, from, ms = 10000) => {
    for (let waited = 0; waited < ms; waited += 100) {
      if (friendHeard.slice(from).some((l) => l.includes(text))) return true
      await sleep(100)
    }
    return false
  }
  const distance = (a, b) => Math.sqrt((a.x - b.x) ** 2 + (a.z - b.z) ** 2)
  // A home, then away with vanilla /tp, then /home brings Joiner back: replies from lang/de.yml.
  await command('/sethome base', de('Zuhause gesetzt: base.', 'Set home base.'), '/sethome answers the player in German')
  const home = bot.entity.position.clone()
  bot.chat(`/tp ${username} ~ ~ ~40`)
  await sleep(1500)
  check('vanilla /tp moved the player away', distance(bot.entity.position, home) > 30, `at ${bot.entity.position}, home ${home}`)
  await command('/home base', de('Willkommen in base.', 'Welcome to base.'), '/home teleports and says so in German')
  await sleep(1000)
  check('/home brought the player back', distance(bot.entity.position, home) < 1.5, `at ${bot.entity.position}, home ${home}`)
  await command('/homes', de('Deine Zuhause (1/', 'Your homes (1/'), '/homes lists the home')
  // Friend asks to come to Joiner; Joiner accepts; Friend waits out the warmup and arrives.
  let from = friendHeard.length
  let mine = heard.length
  friend.chat(`/tpa ${username}`)
  check('/tpa tells whoever asked', await friendHears(`Asked ${username} if you can teleport to them.`, from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  check('/tpa reaches whoever is asked, in their language', await hear(de('Friend möchte sich zu dir teleportieren.', 'Friend wants to teleport to you.'), mine) != null, `heard ${JSON.stringify(heard.slice(mine))}`)
  from = friendHeard.length
  await command('/tpaccept', de('Anfrage von Friend angenommen.', "Accepted Friend's request."), '/tpaccept accepts in German')
  check('Friend waits out the warmup', await friendHears('Teleporting in 3s', from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  check('Friend arrives beside Joiner', await friendHears(`Teleported to ${username}.`, from, 15000), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  await sleep(1000)
  check('Friend is where Joiner is', distance(friend.entity.position, bot.entity.position) < 2, `Friend at ${friend.entity.position}, Joiner at ${bot.entity.position}`)
  // /rtp: a search off the server thread, then the teleport.
  const before = bot.entity.position.clone()
  await command('/rtp', de('Suche einen sicheren Ort...', 'Looking for a safe spot...'), '/rtp starts a search')
  const landed = await hear(de('teleportiert', 'Teleported to'), heard.length - 1, 30000)
  check('/rtp finds a spot and teleports there', landed != null, `heard ${JSON.stringify(heard.slice(-5))}`)
  await sleep(1500)
  check('/rtp moved the player far', distance(bot.entity.position, before) > 250, `at ${bot.entity.position}, before ${before}`)
  if (withXMoney) {
    await command('/eco give Friend 10', 'Friend', '/eco gives Friend money')
    from = friendHeard.length
    friend.chat('/spawn')
    check('/spawn costs Friend 5, through xMoney', await friendHears('Paid $5.00.', from, 15000), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
    check('Friend arrives at spawn', await friendHears('Welcome to spawn.', from, 15000), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
    from = friendHeard.length
    friend.chat('/balance')
    check("the cost left Friend's balance", await friendHears('Balance: $5.00', from), `Friend heard ${JSON.stringify(friendHeard.slice(from))}`)
  }
  try { friend.quit() } catch {}
  finish()
}

// zChat across a network: Joiner stays on lobby, Friend moves to game, and what each says reaches
// the other through zChat on the proxy.
async function zchatNetwork () {
  const friend = mineflayer.createBot({ host: '127.0.0.1', port, username: 'Friend', auth: 'offline', version })
  const friendHeard = []
  friend.on('message', (msg) => friendHeard.push((msg.unsigned || msg).toString()))
  friend.on('error', (e) => fail(`Friend's client error: ${e.message}`, true))
  friend.on('kicked', (reason) => fail(`Friend was kicked: ${JSON.stringify(reason).slice(0, 300)}`, true))
  await new Promise((resolve) => friend.once('spawn', resolve))
  await sleep(2000)
  friend.chat('/server game')
  await sleep(5000)
  const friendHears = async (text, from, ms = 10000) => {
    for (let waited = 0; waited < ms; waited += 100) {
      if (friendHeard.slice(from).some((l) => l.includes(text))) return true
      await sleep(100)
    }
    return false
  }
  let from = friendHeard.length
  bot.chat('hello from lobby')
  check('chat from lobby reaches game', await friendHears(`[lobby] ${username}: hello from lobby`, from), `Friend heard ${JSON.stringify(friendHeard.slice(from)).slice(0, 400)}`)
  let mine = heard.length
  friend.chat('hi from game')
  check('chat from game reaches lobby', await hear('[game] Friend: hi from game', mine, 10000) != null, `heard ${JSON.stringify(heard.slice(mine)).slice(0, 400)}`)
  from = friendHeard.length
  await command('/msg Friend psst across', 'You → Friend: psst across', '/msg to a player on another server')
  check('the private message reaches the other server', await friendHears(`${username} → You: psst across`, from), `Friend heard ${JSON.stringify(friendHeard.slice(from)).slice(0, 400)}`)
  mine = heard.length
  friend.chat('/r got it')
  check('/reply answers across servers', await hear('Friend → You: got it', mine, 10000) != null, `heard ${JSON.stringify(heard.slice(mine)).slice(0, 400)}`)
  try { friend.quit() } catch {}
  finish()
}

// A second player for xBans' checks, who can be kicked and come back: their lines, whether and why
// they were kicked, whether the server let them in at all (loggedIn: past the login, into the
// game), and a way to wait for either.
function friendBot (opts = {}) {
  const friend = mineflayer.createBot({ host: '127.0.0.1', port, username: opts.name ?? 'Friend', auth: 'offline', version: opts.version ?? version })
  const state = { bot: friend, heard: [], kicked: null, loggedIn: false, spawned: false, ended: false }
  friend.on('message', (msg) => state.heard.push((msg.unsigned || msg).toString()))
  friend.on('kicked', (reason) => { state.kicked = typeof reason === 'string' ? reason : JSON.stringify(reason) })
  friend.on('error', () => {})
  friend.once('login', () => { state.loggedIn = true })
  friend.once('spawn', () => { state.spawned = true })
  friend.on('end', () => { state.ended = true })
  state.until = async (test, ms = 15000) => {
    for (let waited = 0; waited < ms; waited += 100) {
      if (test()) return true
      await sleep(100)
    }
    return false
  }
  state.hears = (text, from, ms = 10000) => state.until(() => state.heard.slice(from).some((l) => l.includes(text)), ms)
  return state
}

// xBans as staff use it on one server: Joiner (an operator, in German) punishes Friend.
async function xbans () {
  let friend = friendBot({ version: false })
  check('Friend joins', await friend.until(() => friend.spawned), 'Friend never spawned')
  await sleep(1500)
  // A tempban kicks them, with the ban screen, and keeps them out until it ends.
  bot.chat('/tempban Friend 15s testing expiry')
  check('a tempban kicks Friend with the ban screen', await friend.until(() => friend.kicked != null) && /banned/i.test(friend.kicked), `kicked: ${friend.kicked}`)
  await sleep(1000)
  friend = friendBot({ version: false })
  // Kept out at the door, during the login: a ban that only kicks once they're in the game races
  // the client's first packets, and the ban screen can be lost with the connection.
  check('the tempban keeps Friend out', await friend.until(() => friend.kicked != null || friend.ended) && !friend.loggedIn && /banned/i.test(friend.kicked || ''), `logged in ${friend.loggedIn}, spawned ${friend.spawned}, kicked: ${friend.kicked}`)
  await sleep(15000)
  friend = friendBot({ version: false })
  check('once it ends, Friend joins again', await friend.until(() => friend.spawned), `kicked: ${friend.kicked}`)
  await sleep(1500)
  // A mute stops their chat (and zChat's /msg) until it's lifted.
  await command('/mute Friend 1h spam test', 'Friend', 'Joiner mutes Friend (the broadcast, in German)')
  await sleep(500)
  let mine = heard.length
  let from = friend.heard.length
  friend.bot.chat('can anyone hear me')
  check('a muted player is told so', await friend.hears("You're muted", from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  check('nobody hears a muted player', (await hear('can anyone hear me', mine, 2000)) == null, `heard ${JSON.stringify(heard.slice(mine))}`)
  if (withZChat) {
    from = friend.heard.length
    friend.bot.chat(`/msg ${username} psst`)
    check("zChat's /msg is refused to a muted player", await friend.hears('muted', from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
    check("zChat delivers nothing", (await hear('psst', mine, 2000)) == null, `heard ${JSON.stringify(heard.slice(mine))}`)
  }
  bot.chat('/unmute Friend')
  await sleep(1500)
  mine = heard.length
  friend.bot.chat('free at last')
  check('an unmuted player is heard again', (await hear('free at last', mine, 8000)) != null, `heard ${JSON.stringify(heard.slice(mine))}`)
  // A reply in Joiner's language.
  await command('/history Friend', de('Verlauf', 'history'), "a lookup answers in the client's language (German)")
  bot.chat('/kick Friend bye for now')
  check('a kick takes Friend off with the kick screen', await friend.until(() => friend.kicked != null) && /kicked/i.test(friend.kicked), `kicked: ${friend.kicked}`)
  finish()
}

// xBans across a network: Joiner on lobby, Friend on game, through the proxy. The proxy's own
// punishment commands are off in this test, so Joiner's reach lobby.
async function xbansNetwork () {
  let friend = friendBot()
  check('Friend joins through the proxy', await friend.until(() => friend.spawned), `kicked: ${friend.kicked}`)
  await sleep(2000)
  friend.bot.chat('/server game')
  await sleep(5000)
  bot.chat('/mute Friend 1h muted on lobby')
  // Network mode: game hears of it from the database within its check, a second here.
  await sleep(4000)
  const from = friend.heard.length
  friend.bot.chat('hello from game')
  check('a mute made on lobby stops chat on game', await friend.hears("You're muted", from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  bot.chat('/ban Friend banned on lobby')
  check('a ban made on lobby takes Friend off the network', await friend.until(() => friend.kicked != null || friend.ended, 20000), `Friend is still on: ${JSON.stringify(friend.heard.slice(-3))}`)
  check('with the ban screen', /banned/i.test(friend.kicked || ''), `kicked: ${friend.kicked}`)
  await sleep(1000)
  friend = friendBot()
  check('the proxy turns Friend away at the door', await friend.until(() => friend.kicked != null || friend.ended) && !friend.spawned && /banned/i.test(friend.kicked || ''), `spawned ${friend.spawned}, kicked: ${friend.kicked}`)
  bot.chat('/unban Friend')
  await sleep(4000)
  friend = friendBot()
  check('once unbanned, Friend gets back on', await friend.until(() => friend.spawned), `kicked: ${friend.kicked}`)
  try { friend.bot.quit() } catch {}
  finish()
}

// xVanish with zChat and xTeleport: Joiner (an operator, so xvanish.vanish and xvanish.see, in
// German) vanishes; Friend (not one) mustn't find them anywhere.
async function xvanish () {
  let friend = friendBot({ version: false })
  check('Friend joins', await friend.until(() => friend.spawned), 'Friend never spawned')
  await sleep(2000)
  // No mobs to make noise (or kill Friend) while it listens for zChat's ping.
  bot.chat('/difficulty peaceful')
  let joinerSounds = 0
  // zChat's ping is the experience orb's sound; others (a hurt mob) don't count.
  const ping = (name) => /orb/.test(String(name?.name ?? name))
  bot.on('soundEffectHeard', (name) => { if (ping(name)) joinerSounds++ })
  bot.on('hardcodedSoundEffectHeard', (id) => { if (ping(bot.registry?.sounds?.[id]?.name ?? id)) joinerSounds++ })
  const listed = (f) => Object.keys(f.bot.players).includes(username)
  const inWorld = (f) => Object.values(f.bot.entities).some((e) => e.type === 'player' && e.username === username)
  check('Friend sees Joiner before', listed(friend) && inWorld(friend), `listed ${listed(friend)}, in the world ${inWorld(friend)}`)
  let from = friend.heard.length
  await command('/vanish', de('Du bist unsichtbar.', "You're vanished."), '/vanish answers in German')
  check('Friend hears a fake quit', await friend.hears(`${username} left the game`, from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  check('Joiner is gone from Friend\'s tab list', await friend.until(() => !listed(friend), 5000), 'still listed')
  check('and from Friend\'s world', await friend.until(() => !inWorld(friend), 5000), 'still in the world')
  // xTeleport: /tpa as if Joiner weren't there.
  from = friend.heard.length
  friend.bot.chat(`/tpa ${username}`)
  check("xTeleport's /tpa can't find a vanished player", await friend.hears(`No player called ${username} is online.`, from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  // zChat: a mention isn't highlighted and pings no one; /msg finds no one.
  await sleep(3500)
  let mine = heard.length
  const sounds = joinerSounds
  friend.bot.chat(`hey ${username.toLowerCase()} look`)
  check('a mention of a vanished player isn\'t highlighted', await hear(`Friend: hey ${username.toLowerCase()} look`, mine) != null, `heard ${JSON.stringify(heard.slice(mine))}`)
  await sleep(1500)
  check('and pings no one', joinerSounds === sounds, `${joinerSounds - sounds} sounds`)
  from = friend.heard.length
  mine = heard.length
  friend.bot.chat(`/msg ${username} psst`)
  check("zChat's /msg can't reach a vanished player", (await hear('psst', mine, 3000)) == null, `heard ${JSON.stringify(heard.slice(mine))}`)
  const tpa = await (async () => { await sleep(250); return (await friend.bot.tabComplete('/tpa ', false, false, 8000)).map((m) => (typeof m === 'string' ? m : m.match)) })()
  check('nor is their name completed', !tpa.includes(username), `/tpa  completed to ${JSON.stringify(tpa)}`)
  // Friend relogs: still nothing to see.
  try { friend.bot.quit() } catch {}
  await sleep(2000)
  friend = friendBot({ version: false })
  check('Friend joins again', await friend.until(() => friend.spawned), 'Friend never spawned')
  await sleep(2000)
  check('a vanished player stays hidden from a player joining', !listed(friend) && !inWorld(friend), `listed ${listed(friend)}, in the world ${inWorld(friend)}`)
  // Visible again: a fake join, and back in the list.
  from = friend.heard.length
  await command('/vanish', de('wieder sichtbar', 'visible'), '/vanish again shows Joiner')
  check('Friend hears a fake join', await friend.hears(`${username} joined the game`, from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  check('Joiner is back in Friend\'s tab list and world', await friend.until(() => listed(friend) && inWorld(friend), 5000), `listed ${listed(friend)}, in the world ${inWorld(friend)}`)
  try { friend.bot.quit() } catch {}
  finish()
}

// A Votifier v2 vote, as a server list sends one (NuVotifier's protocol): read the greeting's
// challenge, send the signed JSON, read the reply. Resolves to null once accepted, or the error.
function sendVote (username, service) {
  const net = require('net')
  const crypto = require('crypto')
  return new Promise((resolve) => {
    const socket = net.connect(votePort, '127.0.0.1')
    let buffer = ''
    let sent = false
    const done = (result) => { socket.destroy(); resolve(result) }
    socket.setTimeout(10000, () => done('the receiver timed out'))
    socket.on('error', (e) => done(`couldn't reach the receiver: ${e.message}`))
    socket.on('data', (data) => {
      buffer += data.toString('utf8')
      if (!sent && buffer.includes('\n')) {
        const greeting = buffer.slice(0, buffer.indexOf('\n')).trim().split(' ')
        buffer = buffer.slice(buffer.indexOf('\n') + 1)
        if (greeting.length !== 3 || greeting[0] !== 'VOTIFIER') return done(`not Votifier's greeting: ${greeting.join(' ')}`)
        const payload = JSON.stringify({ serviceName: service, username, address: '127.0.0.1', timestamp: String(Date.now()), challenge: greeting[2] })
        const signature = crypto.createHmac('sha256', voteToken).update(payload).digest('base64')
        const message = Buffer.from(JSON.stringify({ payload, signature }), 'utf8')
        const header = Buffer.alloc(4)
        header.writeUInt16BE(0x733a, 0)
        header.writeUInt16BE(message.length, 2)
        socket.write(Buffer.concat([header, message]))
        sent = true
      }
      if (sent && buffer.includes('\n')) {
        const reply = JSON.parse(buffer.slice(0, buffer.indexOf('\n')))
        done(reply.status === 'ok' ? null : reply.error || 'refused')
      }
    })
  })
}

async function vote (username, service, name) {
  const refused = await sendVote(username, service)
  check(name, refused == null, refused)
}

// xVote on one server: real votes through its receiver, rewards where the player is.
async function xvote () {
  // Friend has never joined: the vote waits for the name, and is paid as Friend first joins.
  await vote('Friend', 'PlanetMinecraft.com', 'a vote for a name that never joined is taken')
  await sleep(1000)
  const friend = friendBot({ version: false })
  check('Friend joins', await friend.until(() => friend.spawned), 'Friend never spawned')
  check("Friend's waiting vote is paid as Friend joins", await friend.hears('Thanks for voting', 0, 15000), `Friend heard ${JSON.stringify(friend.heard).slice(0, 400)}`)
  try { friend.bot.quit() } catch {}
  const from = heard.length
  await vote(username, 'PlanetMinecraft.com', 'a vote for a player online is taken')
  check("Joiner's vote is paid at once", await hear('Thanks for voting', from, 10000) != null, `heard ${JSON.stringify(heard.slice(from)).slice(0, 400)}`)
  await command('/vote', de('Stimme für den Server ab', 'Vote for the server'), '/vote answers in German')
  if (withXMoney) await command('/balance', '25', "the vote's money reward, paid through xMoney")
  finish()
}

// xVote across a network: the proxy receives, the server the player is on pays.
async function xvoteNetwork () {
  await vote('Friend', 'PlanetMinecraft.com', 'the proxy takes a vote for a name that never joined')
  await sleep(3000)
  const friend = friendBot()
  check('Friend joins through the proxy', await friend.until(() => friend.spawned), `kicked: ${friend.kicked}`)
  check('the waiting vote is paid on lobby, where Friend first joins', await friend.hears('Thanks for voting', 0, 20000), `Friend heard ${JSON.stringify(friend.heard).slice(0, 400)}`)
  await sleep(1000)
  friend.bot.chat('/server game')
  await sleep(5000)
  const at = friend.heard.length
  await vote('Friend', 'Minecraft-MP.com', 'the proxy takes a vote for Friend, now on game')
  check('the vote is paid on game, where Friend is', await friend.hears('Thanks for voting', at, 20000), `Friend heard ${JSON.stringify(friend.heard.slice(at)).slice(0, 400)}`)
  const from = heard.length
  await vote(username, 'PlanetMinecraft.com', 'the proxy takes a vote for Joiner, on lobby')
  check('it is paid on lobby', await hear('Thanks for voting', from, 20000) != null, `heard ${JSON.stringify(heard.slice(from)).slice(0, 400)}`)
  try { friend.bot.quit() } catch {}
  finish()
}

// xVanish across a network: Joiner on lobby, Friend (not an operator) and Watcher (one) on game,
// through the proxy. Network mode shares who's vanished through the database.
async function xvanishNetwork () {
  // One login per address every 3 s gets through Velocity (its login-ratelimit).
  const friend = friendBot()
  // A real client empties its world on a respawn, as BungeeCord's switch sends; mineflayer only on
  // a login, so Friend would keep the players it saw on lobby.
  friend.bot._client.on('respawn', () => {
    for (const [id, e] of Object.entries(friend.bot.entities)) if (e !== friend.bot.entity) delete friend.bot.entities[id]
  })
  check('Friend joins through the proxy', await friend.until(() => friend.spawned), `kicked: ${friend.kicked}`)
  await sleep(3500)
  const watcher = friendBot({ name: 'Watcher' })
  check('Watcher joins through the proxy', await watcher.until(() => watcher.spawned), `kicked: ${watcher.kicked}`)
  await sleep(2000)
  friend.bot.chat('/server game')
  watcher.bot.chat('/server game')
  await sleep(5000)
  await command('/vanish', 'Du bist unsichtbar.', '/vanish on lobby answers in German')
  // Network mode: game hears of it from the database within its check, a second here.
  await sleep(4000)
  let from = watcher.heard.length
  watcher.bot.chat('/vanish list')
  check("/vanish list on game names Joiner, vanished on lobby", await watcher.hears(`${username} not on this server`, from), `Watcher heard ${JSON.stringify(watcher.heard.slice(from))}`)
  const listed = (f) => Object.keys(f.bot.players).includes(username)
  const inWorld = (f) => Object.values(f.bot.entities).some((e) => e.type === 'player' && e.username === username)
  from = friend.heard.length
  const mine = heard.length
  bot.chat('/server game')
  // Before 1.12 the server reads a client's language only as it joins (DECISIONS.md, "A real client"),
  // and through BungeeCord the client joins game in English.
  const [major, minor] = (bot.version || '1.8').split('.').map(Number)
  const arrived = major > 1 || minor >= 12 ? 'unsichtbar beigetreten' : 'joined vanished'
  check('Joiner arrives on game vanished', await hear(arrived, mine, 15000) != null, `heard ${JSON.stringify(heard.slice(mine)).slice(0, 400)}`)
  await sleep(3000)
  check('Friend on game has no Joiner in the tab list or the world', !listed(friend) && !inWorld(friend), `listed ${listed(friend)}, in the world ${inWorld(friend)}`)
  check('nor heard them join', !friend.heard.slice(from).some((l) => l.includes(`${username} joined`)), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  check('Watcher, an operator, sees them', listed(watcher), 'not in Watcher\'s tab list')
  from = watcher.heard.length
  watcher.bot.chat('/vanish list')
  check('/vanish list on game now has Joiner here', await watcher.hears(`${username} level`, from), `Watcher heard ${JSON.stringify(watcher.heard.slice(from))}`)
  from = friend.heard.length
  await command('/vanish', 'wieder sichtbar', '/vanish on game shows Joiner')
  check('Friend hears a fake join', await friend.hears(`${username} joined the game`, from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  check('and has Joiner back', await friend.until(() => listed(friend) && inWorld(friend), 5000), `listed ${listed(friend)}, in the world ${inWorld(friend)}`)
  for (const f of [friend, watcher]) try { f.bot.quit() } catch {}
  finish()
}

async function run () {
  if (forge) {
    // Looks like a frame of ours ('X', version 1), signed with nothing the network knows.
    bot._client.write('custom_payload', { channel: forge, data: Buffer.from([0x58, 1, 1, 4, 0x6b, 0x69, 0x63, 0x6b, ...Array(34).fill(0)]) })
    console.log(`sent a forged message on ${forge}`)
  }
  // The server reads the client's language as it joins (1.8.8 a moment later) and records it.
  await sleep(3000)
  if (plugin === 'xutilities') return xutilities()
  if (plugin === 'zchat') return zchat()
  if (plugin === 'zchat-network') return zchatNetwork()
  if (plugin === 'xmoney') return xmoney()
  if (plugin === 'xteleport') return xteleport()
  if (plugin === 'xbans') return xbans()
  if (plugin === 'xbackup') return xbackup()
  if (plugin === 'xbans-network') return xbansNetwork()
  if (plugin === 'xvanish') return xvanish()
  if (plugin === 'xvote') return xvote()
  if (plugin === 'xvote-network') return xvoteNetwork()
  if (plugin === 'xmarket') return xmarket()
  if (plugin === 'xvanish-network') return xvanishNetwork()
  await command('/xsample', 'You have', 'a player-run command answers the player')
  // Chat through the core's chat bridge: xSample's format, drawn once and sent back to the sender too.
  await command('hello from the client', `[xSample] ${username}: hello from the client`, "chat in xSample's format")
  const pay = await completions('/pay ')
  check('tab completion of a player argument', pay.includes(username), `/pay  completed to ${JSON.stringify(pay)}`)
  const sub = await completions('/xsample ')
  check('tab completion of subcommands', ['give', 'reload', 'debug'].every((s) => sub.includes(s)), `/xsample  completed to ${JSON.stringify(sub)}`)
  // A core message, drawn in the client's language: German. xSample's Fabric sandbox ships no
  // lang/ files (core/platform-fabric/sandbox's XSampleMod), so there it's English.
  await command('/xsample debug', onFabric || fixedLanguage ? 'Checking xSample' : 'Prüfe', "a reply in the client's language (German)")
  // The client switches to English: the next reply follows.
  if (!fixedLanguage) {
    bot.setSettings({ locale: 'en_us' })
    await sleep(2000)
    await command('/xsample debug', 'Checking xSample', 'a reply in the language the client changed to (English)')
  }
  finish()
}

// xMarket as a player uses it, through its menus: Joiner (an operator, in German) is given money
// through xMoney, opens /shop, clicks into the ores, picks the diamond, and buys one on the amount
// screen; then sells it with /sell hand, buys stone with /shop buy, and asks /worth. Every click
// in a menu is cancelled by the server, which sends the client the truth back, so what Joiner ends
// up holding is only what xMarket gave after payment.
async function xmarket () {
  const title = (w) => JSON.stringify(w && w.title)
  const opened = (ms = 8000) => new Promise((resolve) => {
    const timer = setTimeout(() => { bot.removeListener('windowOpen', on); resolve(null) }, ms)
    const on = (w) => { clearTimeout(timer); resolve(w) }
    bot.once('windowOpen', on)
  })
  const click = async (slot) => {
    try { await bot.clickWindow(slot, 0, 0) } catch (e) { /* a refused click is the point */ }
  }
  const count = (name) => bot.inventory.items().filter((i) => i.name === name).reduce((n, i) => n + i.count, 0)
  if (withXMoney) await command(`/eco give ${username} 1000`, username, '/eco gives the client money through xMoney')
  let w = opened()
  bot.chat('/shop')
  w = await w
  check('/shop opens its menu of categories', w != null && title(w).includes('Shop'), `window ${title(w)}`)
  if (!w) return finish()
  // The ores sit at slot 14 (shops/ores.yml).
  let next = opened()
  await click(14)
  w = await next
  check('a category opens from its icon', w != null && title(w).includes('Ores'), `window ${title(w)}`)
  if (!w) return finish()
  const slot = w.slots.findIndex((s, i) => i < 45 && s && s.name === 'diamond')
  check('the category shows the diamond', slot >= 0, `slots ${JSON.stringify(w.slots.slice(0, 45).filter(Boolean).map((s) => s.name)).slice(0, 300)}`)
  if (slot < 0) return finish()
  next = opened()
  await click(slot)
  w = await next
  check('the amount screen opens, in German', w != null && title(w).includes(de('kaufen', 'Buy')), `window ${title(w)}`)
  if (!w) return finish()
  const from = heard.length
  await click(40)
  check('buying on the amount screen pays and gives', await hear(de('Gekauft: 1× Diamond für $200.00', 'Bought 1× Diamond for $200.00'), from) != null, `heard ${JSON.stringify(heard.slice(from))}`)
  await sleep(1000)
  check('the client holds the one diamond bought, no more', count('diamond') === 1, `holds ${count('diamond')}`)
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(500)
  // It went to the hotbar's first free slot, the one held.
  bot.setQuickBarSlot(bot.inventory.items().find((i) => i.name === 'diamond').slot - 36)
  await sleep(500)
  await command('/sell hand', de('Verkauft: 1× Diamond für $60.00.', 'Sold 1× Diamond for $60.00.'), '/sell hand takes the diamond and pays')
  await sleep(500)
  check('the diamond is gone', count('diamond') === 0, `holds ${count('diamond')}`)
  await command('/shop buy stone 64', de('Gekauft: 64× Stone für $64.00.', 'Bought 64× Stone for $64.00.'), '/shop buy buys without the menu')
  await sleep(500)
  bot.setQuickBarSlot(bot.inventory.items().find((i) => i.name === 'stone').slot - 36)
  await sleep(500)
  await command('/worth', de('Stone verkauft sich für $0.30 pro Stück, $19.20 für 64.', 'Stone sells for $0.30 each, $19.20 for 64.'), '/worth prices the held stack')
  if (withXMoney) await command('/balance', de('Kontostand: $796.00', 'Balance: $796.00'), 'xMoney took 200 and 64, and paid 60')
  finish()
}

// xBackup as an operator uses it in game: Joiner (in German) changes a block, backs up, then plans,
// stages and calls off a restore of the world; replies from xBackup's lang/de.yml. Friend, who
// isn't an operator, can't back up.
async function xbackup () {
  const at = bot.entity.position.floored()
  // Beside Joiner, not under them.
  const block = `${at.x + 2} ${at.y + 1} ${at.z + 2}`
  bot.chat(`/setblock ${block} minecraft:gold_block`)
  await sleep(1000)
  await command('/backup now from the client', de('Gesichert', 'Backed up'), '/backup now backs up, answering in German')
  const sub = await completions('/backup ')
  check('tab completion of /backup', ['now', 'list', 'status', 'restore'].every((s) => sub.includes(s)), `/backup  completed to ${JSON.stringify(sub)}`)
  await command('/backup list', 'from the client', '/backup list shows the backup and its reason')
  bot.chat(`/setblock ${block} minecraft:air`)
  await sleep(1000)
  await command('/backup restore latest world', de('würde', 'would change'), 'a restore is planned first, in German')
  await command('/backup restore latest world confirm', de('Vorbereitet', 'Staged'), 'confirmed, it is staged for the next start')
  await command('/backup status', de('Wiederherstellung wartet', 'Restore waiting'), '/backup status says a restore is waiting')
  await command('/backup restore cancel', de('Abgebrochen', 'Cancelled'), 'the restore is called off')
  const friend = friendBot({ version: false })
  check('Friend joins', await friend.until(() => friend.spawned), 'Friend never spawned')
  await sleep(1500)
  const from = friend.heard.length
  friend.bot.chat('/backup now')
  // On Fabric, Minecraft's command tree hides what a player may not run, so it's an unknown argument there.
  check("a player without xbackup.now can't back up", await friend.hears(onFabric ? 'Incorrect argument' : 'permission', from), `Friend heard ${JSON.stringify(friend.heard.slice(from))}`)
  try { friend.bot.quit() } catch {}
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
