package co.xenastudios.xstats.data;

import co.xenastudios.core.db.StorageSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Every {@link EngineTest} again on MySQL or MariaDB, for its upserts and network sums. Runs
 * only with XR_MYSQL=host:port:database:user:password, as CI sets, or against a throwaway
 * container: {@code docker run -d -p 3307:3306 -e MARIADB_ROOT_PASSWORD=pw -e MARIADB_DATABASE=xr mariadb}.
 */
@EnabledIfEnvironmentVariable(named = "XR_MYSQL", matches = ".+")
class MySqlTest extends EngineTest {

    String prefix;

    @BeforeEach
    void prefix() {
        // Tables of their own for each test, in the shared database.
        prefix = "xs" + Long.toHexString(System.nanoTime()) + "_";
    }

    @Override
    StorageSettings storage() {
        String[] parts = System.getenv("XR_MYSQL").split(":");
        return new StorageSettings(StorageSettings.Type.MYSQL, prefix,
            new StorageSettings.MySql(parts[0], Integer.parseInt(parts[1]), parts[2], parts[3], parts[4], ""));
    }
}
