// Starts a plugin in the rewrite's layout (ROADMAP.md, Architecture): a skeleton that builds
// and passes its tests, from one command.
//
//   node scripts/new-rewrite-plugin.ts <id> <Name> [--platforms] [--no-build]
//   node scripts/new-rewrite-plugin.ts xshop Shop
//
// The id is the plugin's lowercase name: its folder, packages, command and permission prefix.
// The name follows the id's first letter, as it's shown: xShop. It writes:
//   <id>/api      the API other plugins compile against (xena.kmp-api: JVM and wasmWasi, its
//                 signatures committed in <id>/api/api/ and checked by `check`);
//   <id>/common   the plugin's logic (xena.kmp): its config, permissions and command, and a
//                 test that runs the command on core-testing's fake platform;
// and with --platforms, the adapters, stubbed on the platform modules:
//   <id>/bukkit   the Bukkit-family jar: the core loader's Java shim as main class, a Kotlin
//                 entry point, plugin.yml, boot checks, R8 and a size budget (xena.r8);
//   <id>/fabric   the Fabric mod: an entry point and fabric.mod.json.
// The adapters depend on :core:platform-bukkit and :core:platform-fabric, so pass --platforms
// only once those exist: settings.gradle.kts includes every folder here, and a project that
// names a missing one fails the whole build's configuration.
//
// Then it writes the API's signatures and builds what it wrote (not with --no-build). The
// brand, docs and store pages are still the brand repo's `pnpm new-plugin` and
// scripts/new-plugin.ts's steps (CONTRIBUTING.md); this is the code.
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const ROOT = path.resolve(import.meta.dirname, "..");
const flags = new Set(process.argv.slice(2).filter((a) => a.startsWith("--")));
const [id, name] = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const platforms = flags.has("--platforms");
const build = !flags.has("--no-build");

const fail = (message: string): never => {
  console.error(message);
  process.exit(1);
};
for (const flag of flags) {
  if (flag !== "--platforms" && flag !== "--no-build") fail(`Unknown option ${flag}`);
}
if (!id || !name) fail("Usage: node scripts/new-rewrite-plugin.ts <id> <Name> [--platforms] [--no-build], such as: node scripts/new-rewrite-plugin.ts xshop Shop");
if (!/^[a-z][a-z0-9]{1,30}$/.test(id)) fail(`${id}: an id is lowercase letters and digits, such as xshop`);
if (!/^[A-Z][A-Za-z0-9]*$/.test(name)) fail(`${name}: a name is a capitalised word, such as Shop`);
if (fs.existsSync(path.join(ROOT, id))) fail(`${id}/ already exists`);
if (platforms) {
  for (const module of ["platform-bukkit", "platform-fabric"]) {
    if (fs.existsSync(path.join(ROOT, "core", module, "build.gradle.kts"))) continue;
    // Without it, every Gradle build in the repo fails: only to look at what it writes.
    if (build) fail(`--platforms needs core/${module}, which isn't here yet. Leave the flag off, or add --no-build to only write the files (and delete them before building anything).`);
    console.warn(`core/${module} isn't here yet: the build fails until it is, or until ${id}/ is deleted.`);
  }
}

const display = id[0] + name; // xShop
const Class = display[0].toUpperCase() + display.slice(1); // XShop
const pkg = `co.xenastudios.${id}`;
const dir = pkg.replace(/\./g, "/");

const files: Record<string, string> = {};

// The API.
files[`${id}/api/build.gradle.kts`] = `plugins {
    id("xena.kmp-api")
}

group = "co.xenastudios"
description = "${display}'s API, for other plugins: the same on every platform."
`;
files[`${id}/api/src/commonMain/kotlin/${dir}/api/${Class}Api.kt`] = `package ${pkg}.api

/**
 * ${display}'s API for other plugins, the same on every platform. Platform-neutral and by UUID;
 * \`CompletableFuture\` for I/O on the JVM, no value classes or \`suspend\` in signatures
 * (ROADMAP.md, Developer APIs).
 */
public interface ${Class}Api {
    /** The running ${display}'s version. */
    public val version: String
}
`;

// The logic.
files[`${id}/common/build.gradle.kts`] = `plugins {
    id("xena.kmp")
}

group = "co.xenastudios"
description = "${display}'s logic, for every platform: nothing from java.* or a platform."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":${id}:api"))
            implementation(project(":core:core-common"))
        }
        commonTest.dependencies {
            implementation(project(":core:core-testing"))
        }
    }
}
`;
files[`${id}/common/src/commonMain/kotlin/${dir}/${Class}.kt`] = `package ${pkg}

import ${pkg}.api.${Class}Api
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.text.Component

/** ${display}'s config. */
public object ${Class}Config : ConfigSchema(1, "${display}'s settings.") {
    // TODO: ${display}'s settings.
    public val enabled: ConfigKey<Boolean> = boolean("enabled", true, "Whether ${display} is on.")
}

/** ${display}'s permissions. */
public object ${Class}Permissions : PermissionSet("${id}") {
    public val use: Permission = permission("use", "Use /${id}.", PermissionDefault.EVERYONE)
}

/**
 * The plugin, the same on every platform: each platform's adapter makes one with its
 * [Platform] and passes it the commands players type. [settings] gives the config.
 */
public class ${Class}(
    private val platform: Platform,
    private val settings: () -> Config = { ${Class}Config.defaults },
) : ${Class}Api {

    override val version: String get() = VERSION

    /** What runs the plugin's commands. */
    public val dispatcher: CommandDispatcher = CommandDispatcher(commands(), platform.clock, platform.log)

    // TODO: ${display}'s commands.
    private fun commands(): CommandTree = commands {
        root("${id}", "Shows ${display}'s version.", permission = ${Class}Permissions.use) {
            executes { ctx -> ctx.reply(Component.text("${display} \$VERSION")) }
        }
    }

    public companion object {
        /** ${display}'s version. TODO: from the build. */
        public const val VERSION: String = "0.1.0"
    }
}
`;
files[`${id}/common/src/commonTest/kotlin/${dir}/${Class}Test.kt`] = `package ${pkg}

import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ${display}, run the way its adapters run it: commands typed on the fake platform. */
class ${Class}Test {

    private val platform = FakePlatform()
    private val plugin = ${Class}(platform)

    @Test
    fun theCommandSaysTheVersion() {
        val console = FakeCommandSource.console()
        assertEquals(CommandResult.Ran, plugin.dispatcher.dispatch(console, "${id}"))
        assertEquals(listOf("${display} \${${Class}.VERSION}"), console.replies)
    }

    @Test
    fun theDefaultConfigHasEverySetting() {
        assertTrue("enabled: true" in Configs.defaultText(${Class}Config))
    }
}
`;

if (platforms) {
  // The Bukkit family's adapter.
  files[`${id}/bukkit/build.gradle.kts`] = `plugins {
    id("xena.kotlin-jvm")
    id("xena.fetched-libraries")
    id("com.gradleup.shadow")
    // Shrinks the jar to build/r8/, against its line in gradle/jar-budgets.txt.
    id("xena.r8")
}

group = "co.xenastudios"
version = "0.1.0"
description = "${display} for Paper, Folia, Spigot and Bukkit."

dependencies {
    compileOnly(libs.spigot.api.floor) { isTransitive = false }
    implementation(project(":core:core-loader"))
    implementation(project(":${id}:common"))
    // TODO: stubbed on the platform layer, which is being written (M1).
    implementation(project(":core:platform-bukkit"))
    // Downloaded by the server (libraries:) or by the loader, never shaded.
    fetched(libs.kotlin.stdlib) { isTransitive = false }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "libraries" to fetchedCoordinates.get().joinToString("") { "\\n  - \$it" },
    )
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveFileName = "${display}.jar"
    // Everything on the runtime class path but Kotlin's own, which is fetched.
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
    relocate("co.xenastudios.core", "${pkg}.core")
    exclude("META-INF/*.kotlin_module", "META-INF/maven/**")
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

tasks.assemble { dependsOn(tasks.shadowJar) }

xenaR8 {
    packageName = "${pkg}.r8"
}
`;
  files[`${id}/bukkit/src/main/java/${dir}/bukkit/${Class}Bootstrap.java`] = `package ${pkg}.bukkit;

import co.xenastudios.core.loader.bukkit.BukkitBootstrap;

/** The main class: the core's Java shim, pointed at the Kotlin entry point. */
public final class ${Class}Bootstrap extends BukkitBootstrap {
    @Override
    protected String entryPoint() {
        return "${pkg}.bukkit.${Class}Bukkit";
    }
}
`;
  files[`${id}/bukkit/src/main/kotlin/${dir}/bukkit/${Class}Bukkit.kt`] = `package ${pkg}.bukkit

import co.xenastudios.core.loader.PluginEntry
import org.bukkit.plugin.java.JavaPlugin

/** ${display} on the Bukkit family: loaded by the shim once the stdlib is visible. */
public class ${Class}Bukkit(private val plugin: JavaPlugin) : PluginEntry {

    override fun enable() {
        // TODO: start ${pkg}.${Class} on platform-bukkit's platform, and register its commands.
        plugin.logger.info("${display} enabled")
    }

    override fun disable() {
        plugin.logger.info("${display} disabled")
    }
}
`;
  files[`${id}/bukkit/src/main/resources/plugin.yml`] = `name: ${display}
version: '\${version}'
main: ${pkg}.bukkit.${Class}Bootstrap
api-version: '1.13'
folia-supported: true
description: TODO
# Spigot 1.16.5 (May 2021 builds on) and Paper download these; older servers ignore the key,
# and the shim downloads them instead.
libraries:\${libraries}
commands:
  ${id}:
    description: Shows ${display}'s version.
`;
  files[`${id}/bukkit/boot-checks.txt`] = `# ${display} on a real server (scripts/boot-test.sh --checks).
logged: ${display} enabled
`;

  // Fabric's adapter.
  files[`${id}/fabric/build.gradle.kts`] = `plugins {
    id("xena.kotlin-jvm")
}

group = "co.xenastudios"
version = "0.1.0"
description = "${display} for Fabric."

// TODO: stubbed on the platform layer, which is being written (M1); its conventions bring
// Loom, the Fabric API and fabric-language-kotlin.
dependencies {
    implementation(project(":${id}:common"))
    implementation(project(":core:platform-fabric"))
}
`;
  files[`${id}/fabric/src/main/kotlin/${dir}/fabric/${Class}Fabric.kt`] = `package ${pkg}.fabric

/** ${display} on Fabric: the entry point fabric.mod.json names. */
public class ${Class}Fabric {
    // TODO: implement Fabric's ModInitializer and start ${pkg}.${Class} on platform-fabric.
    public fun onInitialize() {}
}
`;
  files[`${id}/fabric/src/main/resources/fabric.mod.json`] = JSON.stringify({
    schemaVersion: 1,
    id,
    version: "${version}",
    name: display,
    description: "TODO",
    environment: "*",
    entrypoints: { main: [{ adapter: "kotlin", value: `${pkg}.fabric.${Class}Fabric` }] },
    depends: { fabricloader: "*", "fabric-language-kotlin": "*" },
  }, null, 2) + "\n";
}

const written: string[] = [];
for (const [file, text] of Object.entries(files)) {
  const target = path.join(ROOT, file);
  fs.mkdirSync(path.dirname(target), { recursive: true });
  // "wx" creates the file, or fails if it's there: nothing is overwritten.
  fs.writeFileSync(target, text, { flag: "wx" });
  written.push(file);
}

// The Bukkit jar's size budget: today's rule for a new plugin (DECISIONS.md, jar budgets).
if (platforms) {
  fs.appendFileSync(path.join(ROOT, "gradle/jar-budgets.txt"), `:${id}:bukkit 150 # a new plugin's start, raised on purpose as it grows\n`);
  written.push("gradle/jar-budgets.txt");
}
console.log(`Wrote ${written.length} files for ${display}:\n  ${written.join("\n  ")}`);

const run = (args: string[]) => {
  console.log(`\n$ ./gradlew ${args.join(" ")}`);
  execFileSync(path.join(ROOT, "gradlew"), args, { cwd: ROOT, stdio: "inherit" });
};
if (build) {
  // The API's signatures are committed: write them first, so check has something to compare.
  run([`:${id}:api:updateKotlinAbi`]);
  run([`:${id}:api:build`, `:${id}:common:build`, ...(platforms ? [`:${id}:bukkit:build`, `:${id}:fabric:build`] : [])]);
}

console.log(`
${display} is set up${build ? " and builds" : ""}. What's left: the TODOs in the files above${platforms ? "" : ", and its adapters (--platforms) once core/platform-bukkit and core/platform-fabric exist"}.`);
