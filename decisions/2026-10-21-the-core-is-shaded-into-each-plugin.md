# The core is shaded into each plugin, never installed on its own (2026-10-21)

Server owners install one jar per plugin and nothing else. Each plugin relocates its copy of the core (`co.xenastudios.<plugin>.core`), so plugins built on different core versions never clash, and `minimize()` drops the core classes a plugin doesn't use.
