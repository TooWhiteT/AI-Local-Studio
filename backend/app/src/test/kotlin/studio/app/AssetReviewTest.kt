package studio.app

import kotlinx.coroutines.runBlocking
import studio.core.StudioError
import studio.domain.TaskStatus.SUCCEEDED
import kotlin.test.*

class AssetReviewTest {
    @Test fun successCreatesCandidateAndApprovalPinsAnImmutableVersion() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            await(submit(), SUCCEEDED)
            val first = studio.catalog.asset(asset.id).versions.single()
            assertNull(studio.catalog.asset(asset.id).activeVersionId)
            studio.catalog.review(asset.id, first.id, 0, true)
            await(submit("第二版"), SUCCEEDED)
            val second = studio.catalog.asset(asset.id).versions.first()
            assertEquals(first.id, second.parentVersionId)
            assertFailsWith<StudioError> { studio.catalog.review(asset.id, second.id, 0, true) }
            val reviewed = studio.catalog.review(asset.id, second.id, 1, true)
            assertEquals(second.id, reviewed.activeVersionId)
            assertTrue(reviewed.versions.all { it.approvalStatus == "APPROVED" })
            restart()
            assertEquals(reviewed, studio.catalog.asset(asset.id))
            assertEquals(first.file, reviewed.versions.last().file)
        } }
    }
}
