package studio.infra

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import studio.core.sha256

class StudioDb(path: Path) {
    private val db: Database
    private val lock = Any()

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        val config = SQLiteConfig().apply { enforceForeignKeys(true); setBusyTimeout(5000) }
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        fun open() = DriverManager.getConnection(url, config.toProperties())
        Class.forName("org.sqlite.JDBC")
        open().use { migration ->
            migration.createStatement().use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA foreign_keys=OFF") }
            migration.autoCommit = false
            migration.migrate()
            check(migration.rows("PRAGMA foreign_key_check") { getString(1) }.isEmpty()) { "迁移后存在无效外键" }
            migration.commit()
        }
        db = Database.connect(getNewConnection = ::open)
    }

    private fun Connection.migrate() {
        update("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY,sha256 TEXT NOT NULL,applied_at INTEGER NOT NULL)")
        val migrations = listOf("001-studio.sql", "002-attempt-resources.sql", "003-writing.sql")
        val applied = rows("SELECT version,sha256 FROM schema_migrations") { getInt(1) to getString(2) }.toMap()
        check(applied.keys.all { it in 1..migrations.size }) { "数据库需要更新版本的 Local AI Studio" }
        migrations.forEachIndexed { index, name ->
            val sql = checkNotNull(StudioDb::class.java.getResource("/db/$name")).readText()
            val digest = sha256(sql.toByteArray())
            val version = index + 1
            if (applied.containsKey(version)) {
                check(applied[version] == digest) { "已应用迁移内容发生变化: $name" }
            } else {
                // SQLite parses the whole bundled script, including quoted semicolons and trigger bodies.
                createStatement().use { it.executeUpdate(sql) }
                update("INSERT INTO schema_migrations VALUES(?,?,?)", version, digest, System.currentTimeMillis())
            }
        }
    }

    fun <T> tx(block: Connection.() -> T): T = synchronized(lock) {
        transaction(db) {
            maxAttempts = 1
            (connection.connection as Connection).block()
        }
    }
}
internal fun Connection.update(sql: String, vararg params: Any?): Int = prepareStatement(sql).use { st ->
    params.forEachIndexed { index, value -> st.setObject(index + 1, value) }
    st.executeUpdate()
}
internal fun <T> Connection.rows(sql: String, vararg params: Any?, read: ResultSet.() -> T): List<T> =
    prepareStatement(sql).use { st ->
        params.forEachIndexed { index, value -> st.setObject(index + 1, value) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.read()) } }
    }
