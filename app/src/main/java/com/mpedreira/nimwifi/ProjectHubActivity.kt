package com.mpedreira.nimwifi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.mpedreira.nimwifi.databinding.ActivityProjectHubBinding
import com.mpedreira.nimwifi.databinding.ItemRecentProjectBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class ProjectHubActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProjectHubBinding
    private val projectRepository by lazy { ProjectRepository(applicationContext) }
    private val importService by lazy { EsxImportService(applicationContext, projectRepository) }
    private var isBusy = false
    private var recentProjectsLoadJob: Job? = null

    private val selectMapLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(::openCreateProjectForm)
        }

    private val createProjectLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(CreateProjectActivity.EXTRA_PROJECT_ID)?.let(::openSurvey)
            }
        }

    private var pendingBackup: ProjectSummary? = null

    /** Mejora 5: a restorable copy of the whole project, not an analysis export. */
    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
            val project = pendingBackup
            pendingBackup = null
            if (uri != null && project != null) backupProject(project, uri)
        }

    private val openEsxLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(::prepareEsxImport)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectHubBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()
        setupActions()
        onBackPressedDispatcher.addCallback(this) {
            if (!isBusy) finish()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            AppCacheMaintenance(cacheDir).clean()
        }

        if (savedInstanceState == null) {
            intent?.data
                ?.takeIf { intent?.action == Intent.ACTION_VIEW }
                ?.let(::prepareEsxImport)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data
            ?.takeIf { intent.action == Intent.ACTION_VIEW }
            ?.let(::prepareEsxImport)
    }

    override fun onResume() {
        super.onResume()
        refreshRecentProjects()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.projectHubRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupActions() {
        binding.privacyPolicyButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_policy_title)
                .setMessage(R.string.privacy_policy_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.newProjectButton.setOnClickListener {
            if (!isBusy) selectMapLauncher.launch(arrayOf("image/*"))
        }
        binding.openEsxButton.setOnClickListener {
            if (!isBusy) {
                openEsxLauncher.launch(
                    arrayOf(
                        ESX_MIME_TYPE,
                        "application/zip",
                        "application/octet-stream"
                    )
                )
            }
        }
        binding.retryRecentProjectsButton.setOnClickListener { refreshRecentProjects() }
    }

    private fun openCreateProjectForm(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        createProjectLauncher.launch(
            Intent(this, CreateProjectActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun prepareEsxImport(uri: Uri) {
        if (isBusy) return
        lifecycleScope.launch {
            setBusy(true, R.string.importing_project)
            val result = withContext(Dispatchers.IO) {
                runCatching { importService.prepare(uri) }
            }
            setBusy(false)
            result.onSuccess { prepared ->
                if (prepared.esxOnlyFromAnalysisZip) {
                    // An analysis ZIP is not a project copy: say what is (not) imported first.
                    MaterialAlertDialogBuilder(this@ProjectHubActivity)
                        .setTitle(R.string.import_esx_only_title)
                        .setMessage(R.string.import_esx_only_message)
                        .setNegativeButton(R.string.action_cancel) { _, _ -> prepared.close() }
                        .setPositiveButton(R.string.import_esx_only_title) { _, _ -> commitEsxImport(prepared) }
                        .setOnCancelListener { prepared.close() }
                        .show()
                } else {
                    commitEsxImport(prepared)
                }
            }
                .onFailure { error ->
                    if (error is ProjectBackupFileException) {
                        restoreProject(error.file, ProjectBackup.Conflict.FAIL)
                        return@onFailure
                    }
                    if (error is ConfigurationFileException) {
                        // A configuration (JSON, HAR or configuration ZIP) opened from another app.
                        ConfigurationImport(this@ProjectHubActivity).import(uri)
                        return@onFailure
                    }
                    Log.e(TAG, "Cannot import ESX project", error)
                    showError(R.string.project_import_failed, error)
                }
        }
    }

    private fun commitEsxImport(prepared: PreparedEsxImport) {
        lifecycleScope.launch {
            setBusy(true, R.string.importing_project)
            val result = withContext(Dispatchers.IO) { runCatching { prepared.use(importService::commit) } }
            setBusy(false)
            result.onSuccess { openSurvey(it.id) }
                .onFailure { error ->
                    Log.e(TAG, "Cannot import ESX project", error)
                    showError(R.string.project_import_failed, error)
                }
        }
    }

    /** Restores a project copy; an existing project with the same ID asks for a decision. */
    private fun restoreProject(file: java.io.File, conflict: ProjectBackup.Conflict) {
        lifecycleScope.launch {
            setBusy(true, R.string.restoring_project)
            val result = withContext(Dispatchers.IO) { runCatching { projectRepository.restoreProject(file, conflict) } }
            setBusy(false)
            result.onSuccess {
                file.delete()
                openSurvey(it.id)
            }.onFailure { error ->
                if (error is ProjectBackup.ProjectExistsException) {
                    MaterialAlertDialogBuilder(this@ProjectHubActivity)
                        .setTitle(R.string.restore_conflict_title)
                        .setMessage(R.string.restore_conflict_message)
                        .setNegativeButton(R.string.action_cancel) { _, _ -> file.delete() }
                        .setNeutralButton(R.string.restore_replace) { _, _ -> restoreProject(file, ProjectBackup.Conflict.REPLACE) }
                        .setPositiveButton(R.string.restore_as_copy) { _, _ -> restoreProject(file, ProjectBackup.Conflict.AS_COPY) }
                        .setOnCancelListener { file.delete() }
                        .show()
                    return@onFailure
                }
                file.delete()
                Log.e(TAG, "Cannot restore project", error)
                showError(R.string.restore_project_failed, error)
            }
        }
    }

    private fun backupProject(project: ProjectSummary, destination: Uri) {
        if (isBusy) return
        lifecycleScope.launch {
            setBusy(true, R.string.backing_up_project)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Staged and verified before the destination is opened (mejora 18).
                    val staged = java.io.File(cacheDir, "${StagedExport.STAGING_PREFIX}backup-${System.nanoTime()}.zip")
                    try {
                        staged.outputStream().buffered().use { projectRepository.backupProject(project.id, BuildConfig.VERSION_NAME, it) }
                        val manifest = ProjectBackup.verify(staged)
                        StagedExport.deliver(staged) { contentResolver.openOutputStream(destination, "wt") }
                        manifest.files.size
                    } finally {
                        staged.delete()
                    }
                }
            }
            setBusy(false)
            result.onSuccess { files -> Toast.makeText(this@ProjectHubActivity, getString(R.string.backup_project_done, files), Toast.LENGTH_LONG).show() }
                .onFailure { error ->
                    Log.e(TAG, "Cannot back up project", error)
                    val message = if (error is StagedExport.DestinationIncompleteException) {
                        getString(R.string.export_destination_incomplete, error.copiedBytes, error.totalBytes)
                    } else {
                        getString(R.string.backup_project_failed, error.message ?: getString(R.string.unknown_error))
                    }
                    MaterialAlertDialogBuilder(this@ProjectHubActivity).setMessage(message).setPositiveButton(R.string.action_ok, null).show()
                }
        }
    }

    private fun refreshRecentProjects() {
        recentProjectsLoadJob?.cancel()
        showRecentProjectsLoading()
        recentProjectsLoadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { projectRepository.listRecentProjectCatalog() }
            }
            result.onSuccess(::renderRecentProjects)
                .onFailure { error ->
                    Log.e(TAG, "Cannot list recent projects", error)
                    showRecentProjectsError(error)
                }
        }
    }

    private fun renderRecentProjects(catalog: ProjectCatalog) {
        val projects = catalog.projects
        binding.recentProjectsProgress.visibility = View.GONE
        binding.recentProjectsErrorGroup.visibility = View.GONE
        binding.recentProjectsContainer.removeAllViews()
        binding.emptyProjectsText.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        binding.unavailableProjectsText.visibility =
            if (catalog.unavailableProjectCount > 0) View.VISIBLE else View.GONE
        binding.unavailableProjectsText.text = resources.getQuantityString(
            R.plurals.unavailable_projects_warning,
            catalog.unavailableProjectCount,
            catalog.unavailableProjectCount
        )
        val inflater = LayoutInflater.from(this)
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        projects.forEach { project ->
            val itemBinding = ItemRecentProjectBinding.inflate(
                inflater,
                binding.recentProjectsContainer,
                false
            )
            itemBinding.projectTitle.text = project.title
            val details = getString(
                R.string.recent_project_details,
                project.floorName,
                resources.getQuantityString(
                    R.plurals.recent_project_floor_count,
                    project.floorCount,
                    project.floorCount
                ),
                dateFormat.format(Date(project.lastOpenedAt))
            )
            itemBinding.projectDetails.text = details
            itemBinding.projectThumbnail.load(project.mapFile)
            itemBinding.recentProjectCard.contentDescription = getString(
                R.string.recent_project_accessibility,
                project.title,
                details
            )
            itemBinding.recentProjectCard.setOnClickListener {
                if (!isBusy) openSurvey(project.id)
            }
            itemBinding.projectActionsButton.contentDescription = getString(
                R.string.project_actions,
                project.title
            )
            itemBinding.projectActionsButton.setOnClickListener { anchor ->
                if (!isBusy) showProjectActions(anchor, project)
            }
            binding.recentProjectsContainer.addView(itemBinding.root)
        }
    }

    private fun showProjectActions(anchor: View, project: ProjectSummary) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_BACKUP, 0, R.string.backup_project)
            menu.add(0, MENU_DELETE, 1, R.string.delete_project)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_BACKUP -> {
                        pendingBackup = project
                        backupLauncher.launch("${project.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.nimwifi-backup.zip")
                    }
                    else -> showDeleteProjectConfirmation(project)
                }
                true
            }
        }.show()
    }

    private fun showDeleteProjectConfirmation(project: ProjectSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_project_title, project.title))
            .setMessage(R.string.delete_project_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteProject(project.id) }
            .show()
    }

    private fun deleteProject(projectId: String) {
        if (isBusy) return
        lifecycleScope.launch {
            setBusy(true, R.string.deleting_project)
            val result = withContext(Dispatchers.IO) {
                runCatching { projectRepository.deleteProject(projectId) }
            }
            setBusy(false)
            result.onSuccess { refreshRecentProjects() }
                .onFailure { error ->
                    Log.e(TAG, "Cannot delete project", error)
                    showError(R.string.delete_project_failed, error)
                }
        }
    }

    private fun openSurvey(projectId: String) {
        startActivity(
            Intent(this, SurveyActivity::class.java)
                .putExtra(SurveyActivity.EXTRA_PROJECT_ID, projectId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun setBusy(busy: Boolean, @StringRes messageResource: Int? = null) {
        isBusy = busy
        binding.projectBusyOverlay.visibility = if (busy) View.VISIBLE else View.GONE
        messageResource?.let(binding.projectBusyMessage::setText)
        binding.newProjectButton.isEnabled = !busy
        binding.openEsxButton.isEnabled = !busy
    }

    private fun showRecentProjectsLoading() {
        binding.recentProjectsProgress.visibility = View.VISIBLE
        binding.recentProjectsErrorGroup.visibility = View.GONE
        binding.emptyProjectsText.visibility = View.GONE
        binding.unavailableProjectsText.visibility = View.GONE
    }

    private fun showRecentProjectsError(error: Throwable) {
        binding.recentProjectsProgress.visibility = View.GONE
        binding.emptyProjectsText.visibility = View.GONE
        binding.unavailableProjectsText.visibility = View.GONE
        binding.recentProjectsErrorGroup.visibility = View.VISIBLE
        val detail = error.message?.takeIf(String::isNotBlank) ?: getString(R.string.unknown_error)
        binding.recentProjectsErrorText.text = getString(
            R.string.recent_projects_load_failed,
            detail
        )
    }

    private fun showError(messageResource: Int, error: Throwable) {
        val detail = error.message?.takeIf(String::isNotBlank) ?: getString(R.string.unknown_error)
        Toast.makeText(this, getString(messageResource, detail), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ProjectHub"
        private const val MENU_BACKUP = 1
        private const val MENU_DELETE = 2
        private const val ESX_MIME_TYPE = "application/vnd.ekahau.esx"
    }
}
