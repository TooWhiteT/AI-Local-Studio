package studio.domain

enum class TaskStatus {
    PENDING, READY, SUBMITTING, SUBMITTED, RUNNING, UNKNOWN, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELED;

    val terminal get() = this in setOf(SUCCEEDED, FAILED, CANCELED)
    fun allows(next: TaskStatus): Boolean = next in when (this) {
        PENDING -> setOf(READY, CANCELED, FAILED)
        READY -> setOf(SUBMITTING, CANCELED, FAILED)
        SUBMITTING -> setOf(SUBMITTED, UNKNOWN, CANCEL_REQUESTED, FAILED)
        SUBMITTED -> setOf(RUNNING, UNKNOWN, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELED)
        RUNNING -> setOf(UNKNOWN, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELED)
        CANCEL_REQUESTED -> setOf(CANCELED, UNKNOWN)
        UNKNOWN -> setOf(SUBMITTED, CANCEL_REQUESTED, FAILED)
        FAILED -> setOf(PENDING)
        SUCCEEDED, CANCELED -> emptySet()
    }
}

fun acyclic(dependencies: Map<String, List<String>>): Boolean {
    val visited = mutableSetOf<String>()
    val path = mutableSetOf<String>()
    fun visit(id: String): Boolean {
        if (id in visited) return true
        if (!path.add(id)) return false
        if (dependencies[id].orEmpty().any { !visit(it) }) return false
        path.remove(id)
        visited.add(id)
        return true
    }
    return dependencies.keys.all(::visit)
}
