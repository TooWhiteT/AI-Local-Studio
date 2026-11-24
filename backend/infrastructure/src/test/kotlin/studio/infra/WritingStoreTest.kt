package studio.infra

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*
import studio.application.*
import studio.application.WritingKind.*
import studio.core.*

class WritingStoreTest {
    @Test fun removingEpisodePreservesItsIdentityAndPreviousScript() {
        val db=StudioDb(Files.createTempDirectory("episode-history").resolve("db"))
        val p=SqlCatalog(db).create(ProjectInput("两集"));val store=SqlWriting(db)
        val story=store.save(OUTLINE,p.id,WritingEdit(0,outline))
        store.approve(OUTLINE,p.id,story.id,1)
        val brief=plan.getValue("episodes").jsonArray.single()
        val two=buildJsonObject { put("episodes",JsonArray(listOf(brief,brief))) }
        val first=store.save(EPISODE_PLAN,p.id,WritingEdit(0,two,story.id))
        val old=store.episodes(p.id).episodes.last()
        val previous=store.save(SCRIPT,old.id,WritingEdit(0,script,story.id,first.id))
        val reduced=store.save(EPISODE_PLAN,p.id,WritingEdit(1,plan,story.id))
        assertEquals(old,store.episodes(p.id).retired.single())
        assertEquals(previous.id,store.revisions(SCRIPT,old.id,Window()).items.single().id)
        assertFailsWith<StudioError> { store.save(SCRIPT,old.id,WritingEdit(1,script,story.id,reduced.id)) }
        store.save(EPISODE_PLAN,p.id,WritingEdit(2,two,story.id))
        assertEquals(old.id,store.episodes(p.id).episodes.last().id)
    }

    private val outline = json.parseToJsonElement("""{"title":"末班车","logline":"寄出最后一封信","world":"山城",
        "characters":[{"key":"lin","name":"林青","description":"站员"}],"locations":[{"key":"post","name":"邮局","description":"旧柜台"}],"beats":["发现退信"]}""").jsonObject
    private val plan = json.parseToJsonElement("""{"episodes":[{"title":"来信","summary":"找到信件","targetDurationMs":60000}]}""").jsonObject
    private val script = json.parseToJsonElement("""{"title":"来信","scenes":[{"locationKey":"post","characters":["lin"],"action":"展开信纸",
        "durationMs":60000,"dialogues":[{"speaker":"lin","text":"就是它。","emotion":"平静"}]}]}""").jsonObject

    @Test fun approvalPinnedReferencesStaleHistoryAndArchivedProjects() {
        val db = StudioDb(Files.createTempDirectory("studio-writing").resolve("db"))
        val catalog = SqlCatalog(db); val store = SqlWriting(db)
        val p = catalog.create(ProjectInput("剧集")); val other = catalog.create(ProjectInput("另一个项目"))
        val story = store.save(OUTLINE,p.id,WritingEdit(0,outline))
        val planEdit = WritingEdit(0,plan,story.id)
        assertFailsWith<StudioError> { store.save(EPISODE_PLAN,p.id,planEdit) }
        store.approve(OUTLINE,p.id,story.id,1)
        assertFailsWith<StudioError> { store.save(EPISODE_PLAN,other.id,planEdit) }
        val approvedPlan = store.save(EPISODE_PLAN,p.id,planEdit)
        val ep = store.episodes(p.id).episodes.single()
        val edit = WritingEdit(0,script,story.id,approvedPlan.id)
        for (bad in listOf(script.toString().replace("post","unknown"),script.toString().replace("\"speaker\":\"lin\"","\"speaker\":\"other\""))) {
            assertFailsWith<StudioError> { store.save(SCRIPT,ep.id,edit.copy(content=json.parseToJsonElement(bad).jsonObject)) }
        }
        val version = store.save(SCRIPT,ep.id,edit)
        store.approve(SCRIPT,ep.id,version.id,1)
        store.save(OUTLINE,p.id,WritingEdit(1,JsonObject(outline+("title" to JsonPrimitive("新标题")))))
        assertFailsWith<StudioError> { store.save(SCRIPT,ep.id,edit.copy(expectedRevision=1)) }
        val old = store.revisions(SCRIPT,ep.id,Window()).items.single()
        assertTrue(old.stale); assertEquals("APPROVED",old.approvalStatus); assertEquals(script,old.content)
        assertTrue(store.episodes(p.id).plan!!.stale)
        assertFailsWith<StudioError> { store.approve(OUTLINE,p.id,story.id,1) }
        catalog.edit(p.id,ProjectEdit(1,status="ARCHIVED"))
        assertFailsWith<StudioError> { store.save(OUTLINE,p.id,WritingEdit(2,outline)) }
        assertEquals(script,store.revisions(SCRIPT,ep.id,Window()).items.single().content)
        assertTrue(db.tx { rows("PRAGMA foreign_key_check") { getString(1) }.isEmpty() })
    }
    @Test fun migratesPopulatedM0QueueWithoutChangingRowsForeignKeysOrSequence() {
        val path = Files.createTempDirectory("m0-writing-upgrade").resolve("old.db")
        val p=uid(); val asset=uid(); val wf=uid(); val task=uid(); val attempt=uid(); val file=uid()
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.update("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY,sha256 TEXT NOT NULL,applied_at INTEGER NOT NULL)")
            for ((i,name) in listOf("001-studio.sql","002-attempt-resources.sql").withIndex()) {
                val sql=javaClass.getResource("/db/$name")!!.readText()
                c.createStatement().use { it.executeUpdate(sql) }
                c.update("INSERT INTO schema_migrations VALUES(?,?,0)",i+1,sha256(sql.toByteArray()))
            }
            c.update("INSERT INTO projects VALUES(?,'项目','','','9:16','ACTIVE',1,0)",p)
            c.update("INSERT INTO assets VALUES(?,?,'SCENE','场景',NULL,0)",asset,p)
            c.update("INSERT INTO workflows VALUES(?,'mock','1','mock','','{}','{}','hash',1,NULL,'mock',0)",wf)
            val snapshot = """{"workflowId":"$wf","workflowVersion":"1","templateHash":"hash","parameters":{},"models":[],"engineVersion":"mock"}"""
            c.update("INSERT INTO render_tasks(rowid,id,project_id,target_type,target_id,task_type,workflow_id,input_snapshot_json,request_hash,status,priority,idempotency_key,created_at) VALUES(47,?,?,'ASSET',?,'IMAGE',?,?,'h','SUCCEEDED',0,?,0)",task,p,asset,wf,snapshot,uid())
            c.update("INSERT INTO task_attempts(id,task_id,attempt_no,engine_id,status,created_at) VALUES(?,?,1,'mock','SUCCEEDED',0)",attempt,task)
            c.update("INSERT INTO media_files VALUES(?,?,'projects/old.png','digest','image/png',20,2,2,NULL,0)",file,p)
            c.update("INSERT INTO render_results VALUES(?,?,?,'IMAGE',0,0)",uid(),attempt,file)
            c.update("INSERT INTO task_events VALUES(73,?,'task.updated','{}',0)",task)
        }
        val db=StudioDb(path);val tasks=SqlTasks(db)
        val migrated=tasks.get(task)
        assertEquals("IMAGE",migrated.kind); assertEquals(asset,migrated.assetId); assertNull(migrated.snapshot.prompt)
        assertEquals(file,migrated.results.single().id); assertEquals(attempt,migrated.attempts.single().id)
        assertEquals(73L,tasks.eventBounds().last)
        db.tx {
            assertEquals(47L,rows("SELECT rowid FROM render_tasks") { getLong(1) }.single())
            assertTrue(rows("PRAGMA foreign_key_check") { getString(1) }.isEmpty())
            assertFailsWith<java.sql.SQLException> { update("UPDATE render_tasks SET target_id=? WHERE id=?",uid(),task) }
        }
        StudioDb(path)
    }
}
