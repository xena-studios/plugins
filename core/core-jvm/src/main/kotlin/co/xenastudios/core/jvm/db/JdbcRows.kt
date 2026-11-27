package co.xenastudios.core.jvm.db

import co.xenastudios.core.common.db.Row
import java.sql.ResultSet

/**
 * Every row of a result read from a connection the core didn't open (an import reading another
 * plugin's database with its own driver), as the core's sessions read theirs: the same values,
 * column names lowercase. In a file of its own, so nothing loads it until it's used.
 */
public fun ResultSet.rows(): List<Row> = JdbcSession.read(this)
