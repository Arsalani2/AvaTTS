package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.ChunkEntity
import com.example.data.db.ConversionLogEntity
import com.example.data.db.ProjectEntity
import com.example.data.pref.AppSettings
import com.example.data.pref.ThemeMode
import com.example.data.pref.ThemePalette
import com.example.data.pref.UserPreferences
import com.example.data.repository.AppRepository
import com.example.data.vault.ApiKeyItem
import com.example.data.vault.ApiKeyVaultManager
import com.example.service.player.AudioPlayerManager
import com.example.service.player.PlayerState
import com.example.service.queue.QueueState
import com.example.service.queue.QueueEngineHolder
import com.example.service.queue.TtsQueueEngine
import com.example.service.tts.GeminiTtsService
import com.example.service.tts.TtsNetworkException
import com.example.util.FileManager
import com.example.util.PersianDigits
import com.example.util.SmartTextChunker
import com.example.util.WavAudioUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    // BUG-01: نمونه‌های stateful (گاوصندوق، تنظیمات، صف) از holder سطح اپ می‌آیند تا
    // با بستن اپ/بازسازی ViewModel نمونهٔ دوم نسازند و پردازش پس‌زمینه زنده بماند
    val repository = AppRepository(db.projectDao(), db.chunkDao(), db.conversionLogDao())
    val vaultManager = QueueEngineHolder.vaultManager(application)
    val userPreferences = QueueEngineHolder.userPreferences(application)
    val fileManager = FileManager(application, userPreferences)
    val ttsService = GeminiTtsService()
    val playerManager = AudioPlayerManager(application)
    val queueEngine = QueueEngineHolder.engine(application)

    // Observables
    val projects: StateFlow<List<ProjectEntity>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allChunks: StateFlow<List<ChunkEntity>> = repository.allChunks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keys: StateFlow<List<ApiKeyItem>> = vaultManager.keys
    val settings: StateFlow<AppSettings> = userPreferences.settings
    val queueState: StateFlow<QueueState> = queueEngine.queueState
    val playerState: StateFlow<PlayerState> = playerManager.playerState

    // Selected Project
    private val _selectedProject = MutableStateFlow<ProjectEntity?>(null)
    val selectedProject: StateFlow<ProjectEntity?> = _selectedProject.asStateFlow()

    // Real-time reactive chunks directly synced with Room database
    @OptIn(ExperimentalCoroutinesApi::class)
    val chunks: StateFlow<List<ChunkEntity>> = _selectedProject
        .flatMapLatest { project ->
            if (project == null) flowOf(emptyList())
            else repository.getChunks(project.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Project-specific logs (Requirement: هر پروژه لاگ مخصوص به خودش رو داره)
    @OptIn(ExperimentalCoroutinesApi::class)
    val projectLogs: StateFlow<List<ConversionLogEntity>> = _selectedProject
        .flatMapLatest { project ->
            if (project == null) flowOf(emptyList())
            else repository.getLogsForProject(project.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<ConversionLogEntity>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Project settings locked status (Requirement: قفل شدن تنظیمات و بسته شدن پنل‌ها)
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    // Studio Editing State
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    private val _chunkSizeInput = MutableStateFlow("500")
    val chunkSizeInput: StateFlow<String> = _chunkSizeInput.asStateFlow()

    private val _selectedVoiceName = MutableStateFlow("Kore")
    val selectedVoiceName: StateFlow<String> = _selectedVoiceName.asStateFlow()

    // Multi-selection of chunks
    private val _selectedChunkIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedChunkIds: StateFlow<Set<Long>> = _selectedChunkIds.asStateFlow()

    // BUG-06: تعداد بخشهای COMPLETED که با بازتقسیم از دست می‌روند — null یعنی دیالوگ بسته است
    private val _pendingResplitConfirmation = MutableStateFlow<Int?>(null)
    val pendingResplitConfirmation: StateFlow<Int?> = _pendingResplitConfirmation.asStateFlow()

    // User Feedback Toast/Snackbar message
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val existingProjects = repository.allProjects.firstOrNull() ?: emptyList()
            val hasSeeded = userPreferences.hasSeededInitialProject()

            // Deduplicate any previous erroneously created duplicates of "پروژه نمونه اول"
            val sampleDuplicates = existingProjects.filter { it.name == "پروژه نمونه اول" }
            if (sampleDuplicates.size > 1) {
                val toKeep = sampleDuplicates.maxByOrNull { it.updatedAt } ?: sampleDuplicates.first()
                sampleDuplicates.filter { it.id != toKeep.id }.forEach { dup ->
                    repository.deleteProject(dup)
                }
            }

            val cleanedProjects = repository.allProjects.firstOrNull() ?: emptyList()

            if (cleanedProjects.isEmpty() && !hasSeeded) {
                // First run of the app on fresh install: create the initial welcome project once
                val sampleText = "به سامانه پیشرفته آوا تی‌تی‌اس خوش آمدید. این برنامه با اتصال به پیشرفته‌ترین مدل‌های هوش مصنوعی صوتی گوگل، متون طولانی شما را با دقت بالا به صوت تبدیل می‌کند.\n\nشما می‌توانید کلیدهای API خود را در گاوصندوق امن ذخیره کنید تا در صورت اتمام سهمیه، سوئیچ هوشمند و خودکار انجام شود."
                val defaultProject = ProjectEntity(
                    name = "پروژه نمونه اول",
                    description = "پروژه پیش‌فرض تبدیل متن به گفتار",
                    rawText = sampleText,
                    chunkSize = settings.value.defaultChunkSize,
                    selectedVoice = settings.value.lastSelectedVoice,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val newId = repository.saveProject(defaultProject)
                userPreferences.setSeededInitialProject(true)
                userPreferences.setLastActiveProjectId(newId)
                val savedProject = defaultProject.copy(id = newId)
                _selectedProject.value = savedProject
                loadProjectData(savedProject)
            } else if (cleanedProjects.isNotEmpty()) {
                userPreferences.setSeededInitialProject(true)
                val lastId = userPreferences.getLastActiveProjectId()
                val targetProject = cleanedProjects.firstOrNull { it.id == lastId } ?: cleanedProjects.first()
                _selectedProject.value = targetProject
                loadProjectData(targetProject)
            } else {
                _selectedProject.value = null
                _currentText.value = ""
            }

            // Observe ongoing project changes and remember last active project
            projects.collect { list ->
                val current = _selectedProject.value
                val lastSavedId = userPreferences.getLastActiveProjectId()

                if (current != null) {
                    val matching = list.firstOrNull { it.id == current.id }
                    if (matching == null) {
                        if (list.isNotEmpty()) {
                            val target = list.firstOrNull { it.id == lastSavedId } ?: list.first()
                            selectProject(target)
                        } else {
                            _selectedProject.value = null
                            _currentText.value = ""
                        }
                    } else if (matching != current) {
                        _selectedProject.value = matching
                        _isLocked.value = matching.isLocked
                    }
                } else if (list.isNotEmpty()) {
                    val target = list.firstOrNull { it.id == lastSavedId } ?: list.first()
                    selectProject(target)
                }
            }
        }
    }

    fun selectProject(project: ProjectEntity) {
        _selectedProject.value = project
        _currentText.value = project.rawText
        _chunkSizeInput.value = project.chunkSize.toString()
        _selectedVoiceName.value = project.selectedVoice
        _isLocked.value = project.isLocked
        _selectedChunkIds.value = emptySet()
        userPreferences.setLastActiveProjectId(project.id)
        loadProjectData(project)
    }

    private fun loadProjectData(project: ProjectEntity) {
        _isLocked.value = project.isLocked
        viewModelScope.launch(Dispatchers.IO) {
            val chunkList = repository.getChunksDirect(project.id)
            if (chunkList.isEmpty() && project.rawText.isNotBlank()) {
                // Generate chunks for existing project text
                generateAndSaveChunks(project, project.rawText, project.chunkSize)
            }
        }
    }

    fun setProjectLocked(locked: Boolean) {
        _isLocked.value = locked
        val project = _selectedProject.value ?: return
        val updated = project.copy(isLocked = locked, updatedAt = System.currentTimeMillis())
        _selectedProject.value = updated
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateProjectLock(project.id, locked)
            val msg = if (locked) "تنظیمات پروژه قفل و پنل‌ها بسته شدند." else "قفل تنظیمات باز شد؛ اکنون می‌توانید ویرایش کنید."
            showSnackbar(msg)
        }
    }

    fun createProject(name: String, description: String, text: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val size = resolveChunkSize() // BUG-12
            val voice = _selectedVoiceName.value
            val newProject = ProjectEntity(
                name = name.ifBlank { "پروژه جدید" },
                description = description,
                rawText = text,
                chunkSize = size,
                selectedVoice = voice,
                isLocked = text.isNotBlank(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val id = repository.saveProject(newProject)
            userPreferences.setLastActiveProjectId(id)
            userPreferences.setSeededInitialProject(true)
            val fullProject = newProject.copy(id = id)
            _selectedProject.value = fullProject
            _currentText.value = text
            _isLocked.value = fullProject.isLocked
            if (text.isNotBlank()) {
                generateAndSaveChunks(fullProject, text, size)
            }
            val timeSdf = java.text.SimpleDateFormat("yyyy/MM/dd ساعت HH:mm", java.util.Locale.getDefault())
            val dateStr = timeSdf.format(java.util.Date(fullProject.createdAt))
            repository.logConversion(
                projectId = id,
                projectName = fullProject.name,
                chunkIndex = 0,
                keyName = "سیستم",
                keyMasked = "---",
                status = "INFO",
                httpCode = 200,
                latencyMs = 0L,
                message = "پروژه «${fullProject.name}» ساخته شد در تاریخ $dateStr"
            )
            showSnackbar("پروژه «${fullProject.name}» ایجاد شد.")
        }
    }

    fun updateProjectDetails(name: String, description: String) {
        val current = _selectedProject.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val oldName = current.name
            val newName = name.ifBlank { current.name }
            val updated = current.copy(
                name = newName,
                description = description,
                updatedAt = System.currentTimeMillis()
            )
            repository.saveProject(updated)
            _selectedProject.value = updated
            if (oldName != newName) {
                val timeSdf = java.text.SimpleDateFormat("yyyy/MM/dd ساعت HH:mm", java.util.Locale.getDefault())
                val dateStr = timeSdf.format(java.util.Date(updated.updatedAt))
                repository.logConversion(
                    projectId = current.id,
                    projectName = newName,
                    chunkIndex = 0,
                    keyName = "سیستم",
                    keyMasked = "---",
                    status = "INFO",
                    httpCode = 200,
                    latencyMs = 0L,
                    message = "پروژه «$oldName» تغییر نام داد به «$newName» در تاریخ $dateStr"
                )
            }
            showSnackbar("مشخصات پروژه به‌روزرسانی شد.")
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // BUG-06: پوشهٔ صوتی پروژه نباید orphan بماند — فایلها + پوشه best-effort
            // پاک می‌شوند؛ شکست پاک‌سازی هرگز حذف خود پروژه را بلاک نمی‌کند (فقط لاگ)
            try {
                repository.getChunksDirect(project.id).forEach { chunk ->
                    fileManager.deleteAudioFile(chunk.audioFilePath)
                }
                fileManager.deleteProjectFolder(project.name, project.folderName)
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "BUG-06 cleanup failed for ${project.name}", e)
            }
            repository.deleteProject(project)
            val remaining = repository.allProjects.firstOrNull()?.filter { it.id != project.id } ?: emptyList()
            if (_selectedProject.value?.id == project.id) {
                if (remaining.isNotEmpty()) {
                    selectProject(remaining.first())
                } else {
                    _selectedProject.value = null
                    _currentText.value = ""
                    userPreferences.setLastActiveProjectId(-1L)
                }
            }
            showSnackbar("پروژه «${project.name}» حذف گردید.")
        }
    }

    // Debounce job for saving text without freezing the main thread on every character typed
    private var saveTextDebounceJob: Job? = null

    // Text & Chunking Operations
    fun updateCurrentText(newText: String) {
        if (_isLocked.value) return
        _currentText.value = newText
        val project = _selectedProject.value ?: return
        saveTextDebounceJob?.cancel()
        saveTextDebounceJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500L) // 500ms debounce prevents SQLite write storms on keystrokes
            repository.saveProject(project.copy(rawText = newText, updatedAt = System.currentTimeMillis()))
        }
    }

    fun updateChunkSizeInput(sizeStr: String) {
        if (_isLocked.value) return
        // BUG-12: ورودی فقط state محلی را آپدیت می‌کند — ذخیره (پروژه + پیش‌فرض سراسری)
        // فقط هنگام applyChunking انجام می‌شود؛ تایپ موقت دیگر پیش‌فرض همهٔ
        // پروژه‌های بعدی را بازنویسی نمی‌کند
        _chunkSizeInput.value = sizeStr
    }

    /** BUG-12: ارقام فارسی/عربی پذیرفته و در بازهٔ ۱۰۰ تا ۵۰۰۰ محدود می‌شود */
    private fun resolveChunkSize(): Int {
        val fallback = _selectedProject.value?.chunkSize ?: settings.value.defaultChunkSize
        val raw = PersianDigits.normalize(_chunkSizeInput.value).toIntOrNull() ?: return fallback.coerceIn(100, 5000)
        if (raw < 100 || raw > 5000) {
            showSnackbar("اندازهٔ هر بخش باید بین ۱۰۰ تا ۵۰۰۰ کاراکتر باشد؛ مقدار در همین محدوده تنظیم شد.")
        }
        return raw.coerceIn(100, 5000)
    }

    fun selectVoice(voiceName: String) {
        if (_isLocked.value) return
        _selectedVoiceName.value = voiceName
        userPreferences.updateLastVoice(voiceName)
        val project = _selectedProject.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveProject(project.copy(selectedVoice = voiceName, updatedAt = System.currentTimeMillis()))
        }
    }

    fun applyChunking() {
        // BUG-06: بازتقسیم روی پروژهٔ دارای بخش COMPLETED فقط با تأیید صریح کاربر —
        // یک ویرایش/ایمپورت کوچک نباید تبدیل‌های موفق را بی‌هشدار پاک کند
        val completedCount = chunks.value.count { it.status == ChunkEntity.STATUS_COMPLETED }
        if (completedCount > 0) {
            _pendingResplitConfirmation.value = completedCount
            return
        }
        performChunking()
    }

    /** BUG-06: کاربر تأیید کرد — فایل‌های صوتی قدیمی پاک و تقسیم‌بندی مجدد اجرا می‌شود */
    fun confirmResplit() {
        _pendingResplitConfirmation.value = null
        viewModelScope.launch(Dispatchers.IO) {
            // فایل‌های wav چانکهای COMPLETED قبلی از دیسک پاک شوند تا orphan نمانند
            chunks.value
                .filter { it.status == ChunkEntity.STATUS_COMPLETED }
                .forEach { chunk -> fileManager.deleteAudioFile(chunk.audioFilePath) }
            performChunking()
        }
    }

    /** BUG-06: کاربر انصراف زد — هیچ چیزی پاک یا بازتقسیم نمی‌شود */
    fun cancelResplit() {
        _pendingResplitConfirmation.value = null
        showSnackbar("بازتقسیم لغو شد؛ تبدیل‌های قبلی دست‌نخورده باقی ماند.")
    }

    private fun performChunking() {
        val text = _currentText.value
        if (text.isBlank()) {
            showSnackbar("لطفاً ابتدا متنی وارد کنید.")
            return
        }
        val size = resolveChunkSize() // BUG-12: نرمال‌سازی ارقام فارسی + محدودهٔ ۱۰۰..۵۰۰۰
        val project = _selectedProject.value
        viewModelScope.launch(Dispatchers.IO) {
            val targetProject = if (project == null) {
                val newProject = ProjectEntity(
                    name = "پروژه ۱",
                    description = "",
                    rawText = text,
                    chunkSize = size,
                    selectedVoice = _selectedVoiceName.value,
                    isLocked = true,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val id = repository.saveProject(newProject)
                userPreferences.setLastActiveProjectId(id)
                userPreferences.setSeededInitialProject(true)
                val created = newProject.copy(id = id)
                _selectedProject.value = created
                created
            } else {
                val updated = project.copy(
                    rawText = text,
                    chunkSize = size,
                    selectedVoice = _selectedVoiceName.value,
                    isLocked = true,
                    updatedAt = System.currentTimeMillis()
                )
                repository.saveProject(updated)
                _selectedProject.value = updated
                updated
            }
            _isLocked.value = true
            val count = generateAndSaveChunks(targetProject, text, size)
            showSnackbar("متن به $count بخش تقسیم و تنظیمات با موفقیت قفل شد.")
        }
    }

    private suspend fun generateAndSaveChunks(project: ProjectEntity, text: String, targetSize: Int): Int {
        val chunkStrings = SmartTextChunker.chunkText(text, targetSize)
        val chunkEntities = chunkStrings.mapIndexed { index, partText ->
            ChunkEntity(
                projectId = project.id,
                chunkIndex = index + 1,
                text = partText,
                status = ChunkEntity.STATUS_PENDING
            )
        }
        repository.replaceChunks(project.id, chunkEntities)
        return chunkEntities.size
    }

    fun toggleChunkSelection(chunkId: Long) {
        val current = _selectedChunkIds.value.toMutableSet()
        if (current.contains(chunkId)) {
            current.remove(chunkId)
        } else {
            current.add(chunkId)
        }
        _selectedChunkIds.value = current
    }

    fun selectAllChunks() {
        _selectedChunkIds.value = chunks.value.map { it.id }.toSet()
    }

    /**
     * Quick batch selection of unconverted chunks in sequential order (3, 5, 10 chunks).
     */
    fun selectNextUnconvertedChunks(count: Int) {
        val unconverted = chunks.value
            .filter { it.status != ChunkEntity.STATUS_COMPLETED }
            .take(count)
            .map { it.id }
            .toSet()
        if (unconverted.isEmpty()) {
            showSnackbar("تمام بخش‌های این پروژه قبلاً تبدیل شده‌اند.")
        } else {
            _selectedChunkIds.value = unconverted
            showSnackbar("${unconverted.size} بخش تبدیل‌نشده انتخاب شد.")
        }
    }

    fun setSquareLayout(isSquare: Boolean) {
        userPreferences.updateSquareLayout(isSquare)
    }

    fun clearChunkSelection() {
        _selectedChunkIds.value = emptySet()
    }

    fun deselectChunk(chunkId: Long) {
        val current = _selectedChunkIds.value.toMutableSet()
        if (current.remove(chunkId)) {
            _selectedChunkIds.value = current
        }
    }

    // Queue / Conversion Actions
    fun convertSingleChunk(chunkId: Long) {
        val project = _selectedProject.value
        if (project == null) {
            showSnackbar("لطفاً ابتدا پروژه را انتخاب یا ایجاد کنید.")
            return
        }
        queueEngine.startQueue(project, listOf(chunkId)) { completedId ->
            deselectChunk(completedId)
        }
    }

    fun convertSelectedChunks() {
        val project = _selectedProject.value
        if (project == null) {
            showSnackbar("لطفاً ابتدا پروژه را انتخاب یا ایجاد کنید.")
            return
        }
        val ids = _selectedChunkIds.value.toList()
        if (ids.isEmpty()) {
            showSnackbar("هیچ بخشی انتخاب نشده است.")
            return
        }
        queueEngine.startQueue(project, ids) { completedId ->
            deselectChunk(completedId)
        }
    }

    fun convertAllChunks() {
        val project = _selectedProject.value
        if (project == null) {
            showSnackbar("لطفاً ابتدا پروژه را انتخاب یا ایجاد کنید.")
            return
        }
        // BUG-42: «تبدیل همه» فقط بخش‌های ناتمام را به Gemini می‌فرستد — چانک‌های COMPLETED
        // دوباره تبدیل نمی‌شوند تا سهمیهٔ کلیدها نسوزد و فایل‌های سالم بازنویسی نشوند
        val pendingIds = chunks.value
            .filter { it.status != ChunkEntity.STATUS_COMPLETED }
            .map { it.id }
        if (pendingIds.isEmpty()) {
            showSnackbar("تمام بخش‌های این پروژه قبلاً تبدیل شده‌اند.")
            return
        }
        queueEngine.startQueue(project, pendingIds) { completedId ->
            deselectChunk(completedId)
        }
    }

    fun cancelQueue() {
        queueEngine.cancelQueue()
        showSnackbar("پردازش صف لغو شد.")
    }

    // Audio Playback
    fun playChunk(chunk: ChunkEntity) {
        val path = chunk.audioFilePath
        if (path.isNullOrBlank()) {
            showSnackbar("فایل صوتی برای این بخش هنوز تولید نشده است.")
            return
        }
        // BUG-34: قبل از فراخوانی پلیر، وجود واقعی فایل چک شود — فایل گم‌شده نباید «دکمهٔ بی‌عمل» باشد
        if (!File(path).exists() || !File(path).isFile) {
            showSnackbar("فایل صوتی این بخش روی حافظه یافت نشد (شاید حذف شده باشد)؛ لطفاً این بخش را دوباره تبدیل کنید.")
            return
        }
        playerManager.playOrPause(path, chunk.id)
    }

    fun togglePlayPause() {
        if (playerState.value.isPlaying) {
            playerManager.pause()
        } else {
            playerManager.resume()
        }
    }

    fun seekPlayer(posMs: Long) {
        playerManager.seekTo(posMs)
    }

    fun closePlayer() {
        playerManager.stop()
    }

    // BUG-34: پاک‌کردن خطای پلیر بعد از نمایش به کاربر
    fun clearPlayerError() {
        playerManager.clearError()
    }

    // Vault Actions
    fun addApiKey(name: String, rawKey: String) {
        if (rawKey.isBlank()) {
            showSnackbar("کلید API نمی‌تواند خالی باشد.")
            return
        }
        // BUG-07: خروجی null یعنی KeyStore در دسترس نبود و کلید خام ذخیره نشده است
        if (vaultManager.addKey(name, rawKey) == null) {
            showSnackbar("ذخیره امن روی این دستگاه ممکن نیست؛ دوباره تلاش کنید یا دستگاه را ری‌استارت کنید.")
            return
        }
        showSnackbar("کلید جدید در گاوصندوق امن ذخیره شد.")
    }

    fun updateApiKey(id: String, name: String, rawKey: String?) {
        // BUG-07: رمزنگاری ناموفق کلید جدید نباید موفق جلوه کند
        if (!vaultManager.updateKey(id, name, rawKey)) {
            showSnackbar("ذخیره امن روی این دستگاه ممکن نیست؛ دوباره تلاش کنید یا دستگاه را ری‌استارت کنید.")
            return
        }
        showSnackbar("اطلاعات کلید به‌روز شد.")
    }

    fun deleteApiKey(id: String) {
        vaultManager.deleteKey(id)
        showSnackbar("کلید از گاوصندوق حذف گردید.")
    }

    fun toggleKeyEnabled(id: String) {
        vaultManager.toggleKeyEnabled(id)
        val item = vaultManager.keys.value.find { it.id == id }
        val state = if (item?.isEnabled == true) "فعال (روشن)" else "خاموش (غیرفعال)"
        showSnackbar("کلید «${item?.name ?: ""}» $state شد.")
    }

    fun resetKeyCooldown(id: String) {
        vaultManager.resetCooldown(id)
        showSnackbar("دوره خنک‌سازی کلید بازنشانی شد.")
    }

    fun resetAllCooldowns() {
        vaultManager.resetAllCooldowns()
        showSnackbar("کلیه دوره‌های خنک‌سازی لغو و تمام کلیدها فعال شدند.")
    }

    /**
     * Concatenates/merges all completed chunk WAV files for a project into {Project_Name} - merged.wav
     */
    fun mergeProjectAudio(project: ProjectEntity, onDone: ((File) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val projectChunks = repository.getChunksDirect(project.id).sortedBy { it.chunkIndex }
            val completedChunks = projectChunks.filter { it.status == ChunkEntity.STATUS_COMPLETED }

            if (completedChunks.isEmpty()) {
                showSnackbar("هیچ بخش صوتی تکمیل‌شده‌ای برای پروژه «${project.name}» یافت نشد.")
                return@launch
            }

            val missingChunks = completedChunks.filter { chunk ->
                val path = chunk.audioFilePath
                path.isNullOrBlank() || !File(path).exists() || !File(path).isFile || File(path).length() <= 44
            }
            if (missingChunks.isNotEmpty()) {
                // BUG-33: merge ناقص بدتر از عدم merge است — به‌جای حذف بی‌صدای فصل‌های گم‌شده،
                // دقیقاً بگوییم کدام بخش‌ها فایل صوتی ندارند تا دوباره تبدیل شوند.
                val missingIndices = missingChunks.map { it.chunkIndex }
                val shown = if (missingIndices.size > 10) {
                    missingIndices.take(10).joinToString("، ") + " و ${missingIndices.size - 10} مورد دیگر"
                } else {
                    missingIndices.joinToString("، ")
                }
                showSnackbar("ادغام متوقف شد: بخش‌های $shown فایل صوتی ندارند. ابتدا آن‌ها را دوباره تبدیل کنید.")
                return@launch
            }

            val audioFiles = completedChunks.mapNotNull { chunk ->
                val path = chunk.audioFilePath
                if (!path.isNullOrBlank()) {
                    val f = File(path)
                    if (f.exists() && f.isFile && f.length() > 44) f else null
                } else null
            }

            if (audioFiles.isEmpty()) {
                showSnackbar("فایل‌های صوتی بخش‌های تکمیل‌شده در حافظه یافت نشدند.")
                return@launch
            }

            val outputFile = fileManager.getMergedAudioOutputFile(project.name, project.folderName)
            showSnackbar("در حال ادغام ${audioFiles.size} فایل صوتی پروژه «${project.name}»...")

            val result: Result<File> = WavAudioUtil.mergeWavFiles(audioFiles, outputFile)
            val file: File? = result.getOrNull()
            if (file != null) {
                val sizeBytes = file.length()
                val sizeStr = if (sizeBytes >= 1024 * 1024) {
                    String.format(java.util.Locale.US, "%.2f مگابایت", sizeBytes.toDouble() / (1024 * 1024))
                } else {
                    "${sizeBytes / 1024} کیلوبایت"
                }
                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = 0,
                    keyName = "سیستم",
                    keyMasked = "---",
                    status = "MERGED",
                    httpCode = 200,
                    latencyMs = 0L,
                    message = "پروژه «${project.name}» همه فایل‌ها ادغام شد با حجم $sizeStr در مسیر ${file.absolutePath} ذخیره شد",
                    filePath = file.absolutePath,
                    fileSize = sizeBytes
                )
                showSnackbar("فایل‌های صوتی با موفقیت ادغام شدند: ${file.name}")
                onDone?.invoke(file)
            } else {
                val err = result.exceptionOrNull()?.message ?: "خطای ناشناخته در ادغام"
                showSnackbar("خطا در ادغام فایل‌های صوتی: $err")
            }
        }
    }

    fun clearCurrentProjectLogs() {
        val project = _selectedProject.value ?: return
        clearLogs(project.id)
    }

    fun clearLogs(projectId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            if (projectId != null && projectId != -1L) {
                repository.clearLogsForProject(projectId)
                val p = repository.getProjectDirect(projectId)
                showSnackbar("گزارش‌های پروژه «${p?.name ?: ""}» پاکسازی شدند.")
            } else {
                repository.clearLogs()
                showSnackbar("تمام گزارش‌ها پاکسازی شدند.")
            }
        }
    }

    // BUG-35: کلیدی که در حال تست اتصال است — دکمهٔ «تست» حالت loading می‌گیرد
    private val _testingKeyId = MutableStateFlow<String?>(null)
    val testingKeyId: StateFlow<String?> = _testingKeyId.asStateFlow()

    fun testKey(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _testingKeyId.value = id
            try {
                val key = vaultManager.getDecryptedKey(id)
                if (key.isNullOrBlank()) {
                    showSnackbar("کلید معتبر نیست.")
                    return@launch
                }
                try {
                    ttsService.synthesizeSpeech(key, "تست اتصال کلید", "Kore")
                    vaultManager.recordSuccess(id)
                    showSnackbar("اتصال به سرویس صوتی گوگل موفقیت‌آمیز بود.")
                } catch (e: TtsNetworkException) {
                    // BUG-03: خطای شبکه جریمهٔ کلید نیست — آمار کلید دست‌نخورده می‌ماند
                    showSnackbar("ارتباط شبکه برقرار نیست؛ اینترنت خود را بررسی کنید. (کلید شما جریمه نشد)")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    vaultManager.recordError(id, isQuotaLimit = e.message?.contains("429") == true)
                    showSnackbar("خطا در تست کلید: ${e.message}")
                }
            } finally {
                _testingKeyId.value = null
            }
        }
    }

    // Settings & Theme
    fun setThemeMode(mode: ThemeMode) {
        userPreferences.updateThemeMode(mode)
    }

    fun setThemePalette(palette: ThemePalette) {
        userPreferences.updateThemePalette(palette)
    }

    fun setCircularChart(isCircular: Boolean) {
        userPreferences.updateCircularChart(isCircular)
    }

    fun setStorageFolder(folder: String) {
        userPreferences.updateStorageFolder(folder)
    }

    fun setStorageTreeUri(uri: String) {
        userPreferences.updateStorageTreeUri(uri)
    }

    fun resetSettingsToDefault() {
        userPreferences.resetToDefaults()
        showSnackbar("تنظیمات به حالت پیش‌فرض بازگشت.")
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearLogs()
            showSnackbar("کلیه لاگ‌ها پاکسازی شدند.")
        }
    }

    fun updateTtsModel(modelName: String) {
        // BUG-04: پیش‌فرض با UserPreferences یکی شد (gemini-2.5-flash-preview-tts) — یک source of truth
        val trimmed = modelName.trim().ifBlank { "gemini-2.5-flash-preview-tts" }
        userPreferences.updateTtsModelName(trimmed)
        // BUG-27: مدل سفارشی خارج از دو مدل رسمی، هشدار صریح دارد
        val isOfficial = trimmed == "gemini-2.5-flash-preview-tts" || trimmed == "gemini-2.5-pro-tts"
        showSnackbar(
            if (isOfficial) "مدل صوتی با موفقیت روی «$trimmed» تنظیم شد."
            else "مدل «$trimmed» ذخیره شد؛ هشدار: مدل سفارشی خارج از دو مدل رسمی TTS ممکن است خطا بدهد."
        )
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
        // BUG-01: صف دیگر به چرخهٔ حیات ViewModel گره ندارد — cancelQueue حذف شد.
        // لغو فقط از: دکمهٔ توقف کاربر، اکشن «توقف صف» اعلان سرویس، یا پایان طبیعی صف.
    }
}
