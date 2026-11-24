package studio.infra

import java.nio.file.Files
import kotlin.test.*
import studio.application.*
import studio.core.*

class CatalogTest {
    @Test fun upgradesPriorDatabaseWithExistingProject() {
        val dir = Files.createTempDirectory("studio-migration")
        val path = dir.resolve("studio.db")
        val id = uid()
        val old = checkNotNull(javaClass.getResource("/db/001-studio.sql")).readText()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.update("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,sha256 TEXT NOT NULL,applied_at INTEGER NOT NULL)")
            c.createStatement().use { it.executeUpdate(old) }
            c.update("INSERT INTO schema_migrations VALUES(1,?,0)", sha256(old.toByteArray()))
            c.update("INSERT INTO projects VALUES(?,'迁移前项目','','','9:16','ACTIVE',1,0)", id)
        }
        val upgraded = StudioDb(path)
        assertEquals("迁移前项目", SqlCatalog(upgraded).project(id).title)
        upgraded.tx {
            assertTrue("peak_vram_bytes" in rows("PRAGMA table_info(task_attempts)") { getString("name") })
            assertTrue(rows("PRAGMA foreign_key_check") { getString(1) }.isEmpty())
            update("UPDATE schema_migrations SET sha256='changed' WHERE version=1")
        }
        assertFailsWith<IllegalStateException> { StudioDb(path) }
        dir.toFile().deleteRecursively()
    }
    @Test fun migrationsAndOptimisticArchiveSurviveReopen() {
        val path = Files.createTempDirectory("studio-test").resolve("studio.db")
        val catalog = SqlCatalog(StudioDb(path))
        val project = catalog.create(ProjectInput("  黎明  "))
        assertEquals("黎明", project.title)
        val asset = catalog.createAsset(project.id, AssetInput("女主"))
        catalog.edit(project.id, ProjectEdit(1, status = "ARCHIVED"))
        assertFailsWith<StudioError> { catalog.edit(project.id, ProjectEdit(1, title = "冲突")) }
        val reopened = StudioDb(path)
        assertEquals("ARCHIVED", SqlCatalog(reopened).projects(Window()).items.single().status)
        assertEquals(asset, SqlCatalog(reopened).asset(asset.id))
        reopened.tx {
            assertEquals(listOf("wal"), rows("PRAGMA journal_mode") { getString(1) })
            assertEquals(listOf(1), rows("PRAGMA foreign_keys") { getInt(1) })
            assertEquals(listOf(1,2,3,4,5,6), rows("SELECT version FROM schema_migrations ORDER BY version") { getInt(1) })
        }
    }
    @Test fun paginationAndInvalidWrites() {
        val catalog = SqlCatalog(StudioDb(Files.createTempDirectory("studio-test").resolve("db")))
        assertFailsWith<StudioError> { catalog.create(ProjectInput(" ")) }
        val ids = (1..3).map { catalog.create(ProjectInput("项目 $it")).id }.reversed()
        val first = catalog.projects(Window(limit = 2))
        val last = catalog.projects(Window(first.nextCursor!!.toLong(), 2))
        assertEquals(ids, (first.items + last.items).map { it.id })
        assertNull(last.nextCursor)
    }
}
