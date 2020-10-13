package net.dcnnt.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import net.dcnnt.R
import net.dcnnt.core.*
import net.dcnnt.plugins.FileTransferPlugin
import net.dcnnt.ui.*
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock


class RunningFileView(context: Context,
                      private val fragment: BaseFileFragment,
                      private val entry: FileEntry,
                      notification: ProgressNotification? = null): EntryView(context) {
    val TAG = "DC/RunningFileView"
    val notificationIsPrivate = notification != null
    val notification = notification ?: ProgressNotification(context)
    var thumbnailLoaded = false
    private val THUMBNAIL_SIZE_THRESHOLD = 10 * 1024 * 1024

    init {
        title = entry.name
        text = "${entry.size} ${context.getString(R.string.unit_bytes)}"
        progressView.progress = 0
        iconView.setImageResource(fileIconByPath(entry.name))
        actionView.setImageResource(R.drawable.ic_cancel)
        actionView.setOnClickListener { onActionViewClicked() }
        fragment.selectedEntriesView[entry.idStr] = this
    }

    /**
     * Helper function to create file intent with OPEN or SHARE action
     */
    private fun createFileIntent(isOpen: Boolean = true): Intent? {
        val uri = entry.localUri ?: return null
        val mime = mimeTypeByPath(uri.toString())
        val action = if (isOpen) Intent.ACTION_VIEW else Intent.ACTION_SEND
        val intent = Intent(action)
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (isOpen) {
            intent.setDataAndNormalize(uri)
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.type = mime
        }
        return intent
    }

    /**
     * Handle click on share icon or any other place in view
     */
    private fun openOrShare(isOpen: Boolean = true) {
        val intent = createFileIntent(isOpen) ?: return
        val actionName = context.getString(if (isOpen) R.string.action_open else R.string.action_share)
        context.startActivity(Intent.createChooser(intent, "$actionName ${entry.name}"))
    }

    private fun onActionViewClicked() {
        synchronized(entry) {
            when (entry.status) {
                FileStatus.WAIT -> {
                    entry.status = FileStatus.CANCEL
                    fragment.activity?.runOnUiThread {
                        this.visibility = View.GONE
                        if (!fragment.pluginRunning.get()) {
                            fragment.selectedEntries.remove(entry)
                        }
                    }
                }
                FileStatus.RUN -> {
                    entry.status = FileStatus.CANCEL
                    fragment.activity?.runOnUiThread {
                        actionView.setImageResource(R.drawable.ic_block)
                    }
                }
                FileStatus.DONE -> {
                    // ToDo: Share downloaded file or do nothing
                }
                else -> {}
            }
        }
    }

    /**
     * Load bitmap thumbnail for file
     * @return bitmap or null
     */
    fun loadThumbnail(): Bitmap? {
        if (entry.size > THUMBNAIL_SIZE_THRESHOLD) return null
        val uri = entry.localUri ?: return null
        try {
            val data = context?.contentResolver?.openInputStream(uri)?.readBytes() ?: return null
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            return ThumbnailUtils.extractThumbnail(bitmap, iconView.width, iconView.height)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "$e")
        }
        return null
    }

    /**
     * Load thumbnail and show it in left image view
     */
    fun updateThumbnail(): Bitmap? {
        loadThumbnail()?.also {
            fragment.activity?.runOnUiThread {
                iconView.imageTintList = null
                iconView.setImageBitmap(it)
            }
            thumbnailLoaded = true
            return it
        }
        return null
    }

    /**
     * Update onClick listeners and set image thumbnail if available after successful download
     */
    private fun updateSuccess(bitmap: Bitmap?) {
        actionView.setImageResource(R.drawable.ic_share)
        actionView.setOnClickListener { openOrShare(false) }
        leftView.setOnClickListener { openOrShare(true) }
        bitmap?.also {
            iconView.setImageBitmap(it)
            iconView.imageTintList = null
        }
    }

    /**
     * Update view, notifications and listeners on end of download
     */
    fun updateOnEnd(res: DCResult, unitBytesStr: String, doNotificationStuff: Boolean,
                    notificationDownloadCanceledStr: String, notificationDownloadCompleteStr: String,
                    notificationDownloadFailedStr: String, waitingCount: Int, currentNum: Int) {
        val icon = loadThumbnail()
        text = "${entry.size} $unitBytesStr - ${res.message}"
        if (res.success) {
            updateSuccess(icon)
        } else {
            actionView.setImageResource(R.drawable.ic_block)
        }
        if (!doNotificationStuff) return
        if (entry.status == FileStatus.CANCEL) {
            notification.complete(notificationDownloadCanceledStr, "$currentNum/$waitingCount - ${entry.name}")
        } else {
            if (res.success) {
                val intent = createFileIntent(true)
                notification.complete(notificationDownloadCompleteStr, "$currentNum/$waitingCount - ${entry.name}", icon, intent)
            } else {
                notification.complete(notificationDownloadFailedStr, "$currentNum/$waitingCount - ${entry.name} : ${res.message}", icon)
            }
        }
    }
}




open class BaseFileFragment: BasePluginFargment() {
    override val TAG = "DC/FileUI"
    val selectedEntries = mutableListOf<FileEntry>()
    val selectedEntriesView = mutableMapOf<String, RunningFileView>()
    val pluginRunning = AtomicBoolean(false)
    val WRITE_EXTERNAL_STORAGE_CODE = 1
    val isPluginRunning = AtomicBoolean(false)
    var hasWriteFilePermission = false
    lateinit var selectButton: Button
    lateinit var actionButton: Button
    lateinit var cancelButton: Button
    lateinit var repeatButton: Button
    lateinit var selectedView: VerticalLayout
    private var downloadViewMode = false
    private var mainView: View? = null
    lateinit var scrollView: ScrollView
    private var downloadNotificationPolicy = APP.conf.downloadNotificationPolicy.value
    private lateinit var notification: ProgressNotification
    lateinit var actionStr: String
    lateinit var noEntriesStr: String
    lateinit var unitBytesStr: String
    lateinit var statusCancelStr: String
    lateinit var notificationRunningStr: String
    lateinit var notificationCompleteStr: String
    lateinit var notificationCanceledStr: String
    lateinit var notificationFailedStr: String

    private fun askWritePermission() {
        Log.d(TAG, "activity = $activity")
        val activity = activity ?: return
        if (ContextCompat.checkSelfPermission(activity as Context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "ask permission")
            ActivityCompat.requestPermissions(activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_EXTERNAL_STORAGE_CODE)
        } else {
            Log.d(TAG, "already granted")
            hasWriteFilePermission = true
        }
    }

    fun showNoEntriesText(context: Context) {
        actionButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        repeatButton.visibility = View.GONE
        selectButton.visibility = View.VISIBLE
        selectedView.removeAllViews()
        selectedView.addView(TextBlockView(context, noEntriesStr))
    }

    open fun onSelectedDeviceChanged(context: Context) {}

    open fun selectEntries(context: Context) {}

    open fun processAllEntries(context: Context) {}

    open fun processFailedEntries(context: Context) {}

    private fun cancelAllEntries() {
        selectedEntries.forEach {
            synchronized(it) {
                if ((it.status == FileStatus.WAIT) or (it.status == FileStatus.RUN)) {
                    it.status = FileStatus.CANCEL
                }
            }
        }
    }

    fun fragmentMainView(context: Context) = VerticalLayout(context).apply {
        padding = context.dip(6)
        addView(createDeviceSelectView(context).apply {
            onUpdateOptons = { _, changed, _ -> if (changed) onSelectedDeviceChanged(context) }
        })
        addView(LinearLayout(context).apply {
            val lp = LinearLayout.LayoutParams(LParam.W, LParam.W, .5F)
            addView(Button(context).apply {
                selectButton = this
                text = context.getString(R.string.button_select_file)
                setOnClickListener { selectEntries(context) }
                layoutParams = lp
            })
            addView(Button(context).apply {
                actionButton = this
                text = actionStr
                setOnClickListener { processAllEntries(context) }
                layoutParams = lp
            })
            addView(Button(context).apply {
                cancelButton = this
                text = context.getString(R.string.button_cancel)
                visibility = View.GONE
                setOnClickListener { cancelAllEntries() }
                layoutParams = lp
            })
            addView(Button(context).apply {
                repeatButton = this
                text = context.getString(R.string.retry)
                visibility = View.GONE
                setOnClickListener { processFailedEntries(context) }
                layoutParams = lp
            })
        })
        addView(ScrollView(context).apply {
            LParam.set(this, LParam.mm())
            scrollView = this
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initStrings()
        askWritePermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            WRITE_EXTERNAL_STORAGE_CODE -> hasWriteFilePermission = (grantResults.isNotEmpty() &&
                    (grantResults[0] == PackageManager.PERMISSION_GRANTED))
            else -> {}
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView, mainView = $mainView, downloadViewMode = $downloadViewMode")
        mainView?.also { return it }
        container?.context?.also { return fragmentMainView(it).apply { mainView = this } }
        return null
    }

    open fun initStrings() {
        unitBytesStr = getString(R.string.unit_bytes)
        statusCancelStr = getString(R.string.status_cancel)
    }
}