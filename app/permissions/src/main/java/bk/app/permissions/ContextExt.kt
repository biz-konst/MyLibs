package bk.app.permissions

import android.content.Context

fun Context.withPermissions(permissions: Array<out String>) = Permissions.from(this, permissions)

fun Context.withPermissions(
    permissions: Array<out String>,
    grant: () -> Unit,
    deny: ((denied: Array<out String>) -> Unit)? = null,
    showRationaleFlag: Int = Permissions.SHOW_RATIONALE_FLAG_BLOCKED_ONLY,
    rationale: ((denied: Array<out String>, blocked: Boolean) -> Unit)? = null
) {
    Permissions.from(this, permissions)
        .grant(grant)
        .also { if (deny != null) it.deny(deny) }
        .also { if (rationale != null) it.rationale(rationale) }
        .check(showRationaleFlag)
}