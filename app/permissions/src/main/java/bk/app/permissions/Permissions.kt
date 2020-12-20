package bk.app.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import bk.app.tools.log.Logger
import bk.app.tools.log.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference
import kotlin.properties.Delegates

@Suppress("unused", "MemberVisibilityCanBePrivate")
class Permissions private constructor(context: Context, val permissions: Array<out String>) {

    val context = WeakReference(context)

    private var grantCallback: (() -> Unit)? = null
    private var denyCallback: ((denied: Array<out String>) -> Unit)? = null
    private var rationaleCallback: ((denied: Array<out String>, blocked: Boolean) -> Unit)? = null
    private var showRationaleFlag: Int = SHOW_RATIONALE_FLAG_DEFAULT

    fun grant(callback: () -> Unit) = this.apply { grantCallback = callback }

    fun deny(callback: (denied: Array<out String>) -> Unit) = this.apply { denyCallback = callback }

    fun rationale(callback: (denied: Array<out String>, blocked: Boolean) -> Unit) =
        this.apply { rationaleCallback = callback }

    fun check(showRationaleFlag: Int = SHOW_RATIONALE_FLAG_DEFAULT) {
        this.showRationaleFlag = showRationaleFlag
        checkPermissions(permissions, true)
    }

    private fun checkPermissions(permissions: Array<out String>, requestNeeded: Boolean) {
        context.get()?.let { requiredContext ->
            log?.i(TAG, "Check permissions $permissions")
            val denied = permissions.filter {
                ActivityCompat.checkSelfPermission(
                    requiredContext, it
                ) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            when {
                denied.isEmpty() -> grantCallback()
                requestNeeded -> request(requiredContext, denied)
                else -> denyCallback(denied)
            }

        } ?: throw IllegalAccessException("Context not accessible")
    }

    private fun request(context: Context, denied: Array<out String>) {
        coroutineScope.launch {
            mutex.lock(Permissions)
            try {
                log?.i(TAG, "Start permissions stub activity")
                StubActivity.paramHelper = this@Permissions
                context.startActivity(
                    Intent(context, StubActivity::class.java)
                        .putExtra(StubActivity.EXTRA_KEY_PERMISSIONS, denied)
                        .putExtra(StubActivity.EXTRA_KEY_SHOW_RATIONALE_FLAG, showRationaleFlag)
                    // TODO ("Добавить флаги")
                )
            } catch (ex: Exception) {
                mutex.unlock(Permissions)
            }
        }
    }

    private fun grantCallback() {
        log?.i(TAG, "Permissions granted")
        grantCallback?.invoke()
    }

    private fun denyCallback(denied: Array<out String>) {
        log?.i(TAG, "Permissions deny $denied")
        denyCallback?.invoke(denied)
    }

    interface OnPermissionsStubActivityCreate {
        fun onCreate(activity: Activity, savedInstanceState: Bundle?): Boolean
    }

    internal class StubActivity : Activity() {

        private var helper by Delegates.notNull<Permissions>()
        private var permissions by Delegates.notNull<Array<out String>>()
        private var showRationaleFlag: Int = SHOW_RATIONALE_FLAG_DEFAULT

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            log?.i(TAG, "Permissions stub activity started $intent")

            if (onPermissionsStubActivityCreate?.onCreate(this, savedInstanceState) == false) {
                return
            }

            if (savedInstanceState != null) {
                helper = lastNonConfigurationInstance as? Permissions
                    ?: throw IllegalAccessException("Can't access for permissions helper")

            } else {
                helper = paramHelper
                    ?: throw IllegalAccessException("Can't access for permissions helper")

                intent.extras?.let {
                    permissions = it.getStringArray(EXTRA_KEY_PERMISSIONS) as Array<out String>
                    showRationaleFlag =
                        it.getInt(EXTRA_KEY_SHOW_RATIONALE_FLAG, SHOW_RATIONALE_FLAG_DEFAULT)

                    val rationale = permissions.filter { s ->
                        ActivityCompat.shouldShowRequestPermissionRationale(this, s)
                    }
                    val blocked = rationale.size < permissions.size

                    when {
                        showRationaleFlag == SHOW_RATIONALE_FLAG_ALWAYS ||
                                showRationaleFlag == SHOW_RATIONALE_FLAG_BLOCKED_ONLY && blocked -> {
                            if (showRationale(permissions, blocked)) {
                                return
                            }
                        }
                        showRationaleFlag == SHOW_RATIONALE_FLAG_NEVER && blocked -> {
                            helper.denyCallback(permissions)
                        }
                        /* SHOW_RATIONALE_FLAG_NEVER || SHOW_RATIONALE_FLAG_SKIP */
                        else -> {
                            if (requestPermissions(permissions)) {
                                return
                            }
                        }
                    }
                }
            }

            finish()
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            when (requestCode) {
                REQUEST_CODE_SHOW_RATIONALE -> {
                    when (resultCode) {
                        RATIONALE_RESULT_SETTINGS -> {
                            showSettings()
                            return
                        }
                        RESULT_OK -> {
                            if (requestPermissions(permissions)) {
                                return
                            }
                        }
                    }
                }
                REQUEST_CODE_SHOW_SETTINGS -> {
                    helper.checkPermissions(permissions, false)
                }
            }

            finish()
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ) {
            if (requestCode == REQUEST_CODE_PERMISSIONS) {
                val denied =
                    if (grantResults.isEmpty()) {
                        this.permissions
                    } else {
                        permissions
                            .filterIndexed { index, _ ->
                                grantResults[index] != PackageManager.PERMISSION_GRANTED
                            }.toTypedArray()
                    }

                if (denied.isEmpty()) {
                    helper.grantCallback()
                } else {
                    helper.denyCallback(denied)
                }
            }

            finish()
        }

        override fun onRetainNonConfigurationInstance() = helper

        override fun onDestroy() {
            val finished = isFinishing
            try {
                super.onDestroy()
            } finally {
                if (finished) {
                    log?.i(TAG, "Permissions stub activity finished")
                    releaseMutex()
                }
            }
        }

        private fun requestPermissions(permissions: Array<out String>) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                log?.i(TAG, "Request permissions")
                requestPermissions(permissions, REQUEST_CODE_PERMISSIONS)
                true
            } else {
                helper.denyCallback(permissions)
                false
            }


        private fun showRationale(permissions: Array<out String>, blocked: Boolean) =
            showRationaleIntent?.let {
                log?.i(TAG, "Start rationale intent")
                val intent = it.clone() as Intent
                intent.putExtra(EXTRA_KEY_PERMISSIONS, permissions)
                intent.putExtra(EXTRA_KEY_BLOCKED_PERMISSIONS, blocked)
                startActivityForResult(it, REQUEST_CODE_SHOW_RATIONALE)
                true
            } ?: false

        private fun showSettings() {
            log?.i(TAG, "Start settings intent")
            startActivityForResult(
                showSettingsIntent
                    ?: Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    ),
                REQUEST_CODE_SHOW_SETTINGS
            )
        }

        companion object {

            const val TAG = "Permissions.StubActivity"

            const val EXTRA_KEY_PERMISSIONS = "Permissions"
            const val EXTRA_KEY_SHOW_RATIONALE_FLAG = "ShowRationaleFlag"
            const val EXTRA_KEY_BLOCKED_PERMISSIONS = "BlockedPermissions"

            private const val REQUEST_CODE_PERMISSIONS = 1
            private const val REQUEST_CODE_SHOW_SETTINGS = 2
            private const val REQUEST_CODE_SHOW_RATIONALE = 3

            internal var paramHelper: Permissions? = null

        }

    }

    companion object {

        const val TAG = "Permissions"

        const val SHOW_RATIONALE_FLAG_NEVER = 0
        const val SHOW_RATIONALE_FLAG_SKIP = 1
        const val SHOW_RATIONALE_FLAG_BLOCKED_ONLY = 2
        const val SHOW_RATIONALE_FLAG_ALWAYS = 3

        const val SHOW_RATIONALE_FLAG_DEFAULT = SHOW_RATIONALE_FLAG_BLOCKED_ONLY

        const val RATIONALE_RESULT_SETTINGS = Activity.RESULT_FIRST_USER + 1

        var log: Logger? = logger

        var showRationaleIntent: Intent? = null
        val showSettingsIntent: Intent? = null
        var onPermissionsStubActivityCreate: OnPermissionsStubActivityCreate? = null

        private val coroutineScope = CoroutineScope(Dispatchers.Default)
        private val mutex = Mutex()

        fun from(context: Context, permissions: Array<out String>) =
            Permissions(context, permissions)

        private fun releaseMutex() {
            mutex.unlock(this)
        }

    }

}