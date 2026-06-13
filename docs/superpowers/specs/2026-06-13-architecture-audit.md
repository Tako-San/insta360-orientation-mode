# Архитектурный аудит insta360-orientation-mode

Дата: 2026-06-13. Многоагентный аудит (4 линзы + синтез), 44 находок.
Это аналитический отчёт — основа для выбора направлений рефакторинга. Кода не меняет.

## Резюме

Приложение — форк Insta360 SDK demo с дописанной фичей управления панорамой по гироскопу и VR split-screen. Архитектура страдает от трёх системных проблем: (1) две почти идентичные реализации VR (VrManager 615 строк / LocalVrManager 339 строк) с расходящимся error-handling и копипастой PixelCopy-цикла и диалогов; (2) критическая зависимость от приватного SDK API через рефлексию (setYaw/setPitch) с молчаливыми пустыми catch-блоками в трёх местах, которая сломается без ошибки при обновлении SDK; (3) расплывшиеся слои MVVM — CaptureViewModel (649 строк) и Activity'и смешивают SDK-вызовы, сетевую инфраструктуру, рендеринг и бизнес-логику. Хорошая новость: значительная часть критичной математики (Quaternion, EquirectangularProjection, PanoramaFovMath, detection-парсинг) уже чистая или почти чистая и частично покрыта тестами в :lib. Поскольку UI-тестов нет, безопасный путь рефакторинга — сначала вынести чистую логику в :lib и накрыть тестами, а высокорисковые правильные изменения (VR, рефлексия, разбиение god-object) делать уже опираясь на этот safety net.

## Сквозные темы

- **Рефлексия как единственный мост к ориентации плеера — системная хрупкость без compile-time гарантий**
- **Дублирование VR-режима: две копии одного механизма (PixelCopy, диалог настроек, applyVrAdjustments) с расходящимся error-handling**
- **Размытые слои MVVM: SDK-вызовы, сеть, рендеринг и бизнес-логика просочились в ViewModel и Activity**
- **Неявные глобальные зависимости и скрытое состояние: static companion, синглтоны, кросс-модульное связывание capture<->player**
- **Чистая математика и парсинг, готовые к выносу в :lib и покрытию тестами — это safety net для всех остальных правок**
- **Хрупкая generic-рефлексия в инфраструктуре ViewBinding/адаптеров — отдельный от ориентации очаг рефлексии**
- **Захламление утилит и непоследовательность паттернов**

## Приоритизированные направления рефакторинга

Порядок учитывает: тестов на UI нет, safety net есть только для чистой математики в :lib. Поэтому вынос логики в :lib и тесты идут первыми, высокорисковые правки — после.

### #1 — Вынести чистую панорамную математику и парсинг детекций в :lib и накрыть тестами

- **Серьёзность:** high · **Усилие:** medium
- **Файлы:** `EquirectangularProjection.kt`, `PanoramaFovMath.kt`, `VideoDetectionSidecarParser.kt`, `VideoDetectionTimeline.kt`, `VideoDetectionModels.kt`
- **Зависит от:** ничего
- **Обоснование:** Impact высокий: это фундамент safety net, без которого все рискованные правки ниже опасны при отсутствии UI-тестов. Риск низкий (0 Android-зависимостей, частично уже есть тесты), усилие small/medium (перенос файлов + правка 2 импортов). Лучшее соотношение ценность/риск во всём списке — делается первым именно потому, что разблокирует безопасность остальных шагов.

### #2 — Расширить тестовое покрытие чувствительной математики кватернионов (fromRotationMatrix, slerp)

- **Серьёзность:** medium · **Усилие:** small
- **Файлы:** `Quaternion.kt`
- **Зависит от:** ничего
- **Обоснование:** Impact высокий: Quaternion.fromRotationMatrix (алгоритм Шепперда, 4 ветки) и slerp — ядро sensor fusion гироскопа, ошибки здесь дают тихий дрейф ориентации. Уже в :lib, риск минимальный (только добавление тестов, не правка кода), усилие small. Дешёвый способ укрепить safety net перед вмешательством в GyroOrientationController.

### #3 — Ввести типобезопасный интерфейс ISphericalPlayerController и инкапсулировать рефлексию setYaw/setPitch в одну фабрику с логированием

- **Серьёзность:** critical · **Усилие:** large
- **Файлы:** `CaptureActivity.kt`, `VrManager.kt`, `LocalSphericalPlayerActivity.kt`
- **Зависит от:** Вынести чистую панорамную математику и парсинг детекций в :lib и накрыть тестами
- **Обоснование:** Impact критический: рефлексия — единственный путь применения ориентации, сейчас продублирована в 3 местах с пустыми catch, ломается молча при обновлении SDK. Объединение в один адаптер (ReflectiveOrientationController с lazy-Method, логированием всех исключений и проверкой наличия методов при инициализации) одновременно закрывает критическую хрупкость и устраняет молчаливое подавление. Риск средний (правки в трёх UI-местах без тестов), поэтому идёт после выноса математики — поведение применения углов проверяемо отдельно, а сам адаптер тонкий и тестируемый на наличие методов.

### #4 — Убрать static companion sensivity/invertYaw/invertPitch из GyroOrientationController, заменить на shared-состояние через ViewModel/StateFlow

- **Серьёзность:** high · **Усилие:** medium
- **Файлы:** `GyroOrientationController.kt`, `VrManager.kt`, `LocalVrManager.kt`
- **Зависит от:** Вынести чистую панорамную математику и парсинг детекций в :lib и накрыть тестами
- **Обоснование:** Impact высокий: static-поля создают неявную двустороннюю связь capture<->player и race condition; заодно ломают модульность и мешают перенести контроллер. Риск средний/высокий (затрагивает обе VR-реализации и калибровку, UI-тестов нет), поэтому после safety net. Усилие medium. Логичный шаг перед дедупликацией VR, т.к. оба VrManager читают/пишут это поле.

### #5 — Дедуплицировать VR: общий VrManagerBase для PixelCopy-цикла, диалога настроек и applyVrAdjustments, параметризованный sourceView

- **Серьёзность:** high · **Усилие:** large
- **Файлы:** `VrManager.kt`, `LocalVrManager.kt`
- **Зависит от:** Ввести типобезопасный интерфейс ISphericalPlayerController и убрать static sensivity
- **Обоснование:** Impact высокий: ~950 строк копипасты в двух классах с расходящимся error-handling (LocalVrManager не логирует PixelCopy, applyVrAdjustments без try-catch может упасть молча). Единый базовый класс убирает риск рассинхрона багов и выравнивает логирование. Риск высокий (низкоуровневая работа с View-деревом и bitmap, тестов нет), усилие large — поэтому после стабилизации рефлексии (rank 3) и устранения static-связи (rank 4), на которые VR опирается. Чистую stereo-геометрию (eyeScale/spacing/IPD) можно попутно вынести в :lib data class.

### #6 — Извлечь SDK-доступ и сетевую логику CaptureViewModel в CameraSDKAdapter / NetworkingCoordinator (:lib где возможно)

- **Серьёзность:** high · **Усилие:** large
- **Файлы:** `CaptureViewModel.kt`, `CameraOfflineData.kt`, `InstaCameraManagerExt.kt`
- **Зависит от:** Вынести чистую панорамную математику и парсинг детекций в :lib и накрыть тестами
- **Обоснование:** Impact высокий: instaCameraManager вызывается 50+ раз прямо из ViewModel, сетевой bindProcessToNetwork размазан по 3 местам, callback-обёртки suspendCancellableCoroutine без иерархии ошибок. Адаптер, конвертирующий callback-и SDK в suspend fun Result<T>, развязывает слой и делает initCapture тестируемым state machine. Риск высокий (поведение съёмки, тестов нет) — поэтому после safety net; усилие large. Это предпосылка для последующего разбиения god-object.

### #7 — Разбить god-object CaptureViewModel и снять оркестрацию рендеринга/гиро с CaptureActivity

- **Серьёзность:** high · **Усилие:** large
- **Файлы:** `CaptureViewModel.kt`, `CaptureActivity.kt`, `CaptureEvent.kt`
- **Зависит от:** Извлечь SDK-доступ и сетевую логику CaptureViewModel в CameraSDKAdapter / NetworkingCoordinator
- **Обоснование:** Impact высокий, но усилие large и риск высокий: 649-строчный ViewModel смешивает init-flow, управление съёмкой, preview/stream-колбэки и трансформы player-параметров; Activity напрямую дирижирует рендером и гироскопом. Разделение на focused-классы и перевод ориентации в реактивный поток из ViewModel ценно, но безопасно только после того, как SDK-доступ инкапсулирован (rank 6), рефлексия типобезопасна (rank 3) и есть тесты на вынесенную логику. Самая дорогая и зависимая инициатива — отсюда низкий ранг несмотря на high severity.

### #8 — Перевести детекции LocalSphericalPlayerActivity с Handler-polling на реактивный Flow в ViewModel/DetectionService

- **Серьёзность:** medium · **Усилие:** medium
- **Файлы:** `LocalSphericalPlayerActivity.kt`
- **Зависит от:** Вынести чистую панорамную математику и парсинг детекций в :lib и накрыть тестами
- **Обоснование:** Impact средний: парсинг JSON, FOV-математика и трекинг детекций живут в lifecycle Activity и крутятся через 200ms-runnable. После выноса парсинга и проекции в :lib (rank 1) остаётся обернуть это в ViewModel/Flow и убрать пустой onEvent. Риск низкий/средний (логика уже отделяема), усилие medium. Зависит от того, что чистая часть уже в :lib.

### #9 — Укрепить generic-рефлексию ViewBinding: кэшировать Method, сохранять cause, валидировать ParameterizedType

- **Серьёзность:** high · **Усилие:** medium
- **Файлы:** `ViewBindingUtils.kt`, `BaseAdapter.kt`, `BaseListAdapter.kt`
- **Зависит от:** ничего
- **Обоснование:** Impact средний: ViewBindingUtils.createBinding выполняет рефлексию на каждый onCreateViewHolder (хрупкость при смене AGP, потеря cause при оборачивании в RuntimeException, непроверенные предположения о ParameterizedType). Кэширование Method в companion и осмысленные исключения дёшевы и безопасны. Риск средний (инфраструктура адаптеров используется широко), усилие medium. Независимо от остальных линий — отдельный очаг рефлексии.

### #10 — Гигиена: разнести CaptureConst.kt на константы и форматтеры, убрать молчаливые catch в destroy/калибровке, симметричное логирование

- **Серьёзность:** low · **Усилие:** small
- **Файлы:** `CaptureConst.kt`, `VrManager.kt`, `LocalVrManager.kt`, `BaseViewModel.kt`
- **Зависит от:** ничего
- **Обоснование:** Impact низкий, но риск и усилие минимальны (small): разделить 307-строчный CaptureConst на константы и extension-форматтеры, добавить логирование в пустые catch (VrManager.destroy, calibrateGyro, LocalVrManager PixelCopy), убрать неиспользуемый onEvent. Чистый quick-win с высоким обратным риском/усилием, выполняется в любой момент. Состояние калибровки в GyroOrientationController можно попутно поднять в ViewModel.

## Полные находки по линзам

### Линза: MVVM Layer Violations: SLОИ И ОТВЕТСТВЕННОСТИ (Android MVVM Architectural Audit)

#### [high/large/риск:high] God Object: CaptureViewModel oversized with 649 lines, mixed concerns
- Файлы: `CaptureViewModel.kt`
- Evidence: Lines 1-649: Single class handles camera SDK callbacks (IPreviewStatusListener, ICaptureStatusListener at lines 36, 58-59), suspend-coroutine camera control (lines 154-456), complex capture mode switching logic (lines 216-360), live streaming orchestration (lines 238-282), player view parameter updates with asset info transforms (lines 571-647). Combines SDK integration, state management, and presentation logic. 20+ public methods across unrelated domains.
- Рекомендация: Extract into 3-4 focused classes: (1) CaptureInitOrchestrator — handles initCapture() flow (lines 154-214); (2) CaptureControlService — recording/photo/live commands (lines 284-341); (3) PreviewStreamManager — preview/stream callbacks (lines 392-403, 458-461); (4) PlayerParamsAdapter — window crop and offset transforms (lines 571-647). Keep ViewModel thin for state emission only.

#### [high/large/риск:high] MVVM Violation: CaptureActivity directly orchestrates rendering + gyro control
- Файлы: `CaptureActivity.kt`
- Evidence: Lines 47-84: Activity creates and configures GyroOrientationController, binds gyro.applyOrientation callback directly to tryApplyOrientationToPlayer() (line 55). Lines 335-355: displayPreviewStream() sets PlayerViewListener, configures player pipeline, calls instaCameraManager.setPipeline(). Lines 420-456: tryApplyOrientationToPlayer() uses reflection to invoke setYaw/setPitch on player view — this is presentation logic that should be reactive from ViewModel. Activity becomes a coordinator rather than view-only layer.
- Рекомендация: Move gyro controller creation to ViewModel (or dedicated VrOrientationCoordinator service). ViewModel should expose yaw/pitch as LiveData<OrientationUpdate>. Activity observes and applies to view. Replace reflection (lines 433-439) with proper interface or sealed class response from ViewModel.

#### [high/large/риск:high] SDK Calls Mixed in ViewModel: instaCameraManager accessed 50+ times directly
- Файлы: `CaptureViewModel.kt`
- Evidence: Lines 58-59: SDK listener registration in init block. Lines 66-144: getCaptureSettingSupportValueList() is pure SDK query wrapper. Lines 175-176, 185, 437, 444, 451: Direct connectivityManager.bindProcessToNetwork() calls (network config is infrastructure, not VM concern). Lines 204-206, 231-233, 382-383: Conditional logic based on instaCameraManager.supportConfig.supportNewCaptureControlFlow() (SDK capability detection). Lines 571-597: Complex SDK state queries (isFlowStateOn, getConvertAssetInfo, getStabConvertAssetInfo, getPlayerOffsetData) mixed with UI logic.
- Рекомендация: Create CameraSDKAdapter (or CameraCoreService in pure-JVM :lib module) that wraps all SDK interaction. Expose only clean domain models/states to ViewModel. Example: exposeFetchCameraOptions() → suspend fun getCameraOptions(): Result<CameraOptions>, not raw SDK calls. Move capability checks (supportNewCaptureControlFlow) into adapter — ViewModel should not know SDK version-specific behavior.

#### [medium/medium/риск:medium] Network Management Logic in ViewModel (lines 175-180, 437-456)
- Файлы: `CaptureViewModel.kt`
- Evidence: Lines 175-180: fetchCameraOptions() requires NetworkManager.cameraNet check and manual connectivityManager.bindProcessToNetwork(). Lines 437-456: initCameraSupportConfig() repeats same network binding/unbinding around HTTP calls. This infrastructure concern (network routing) should not be in presentation-layer ViewModel. Spreads across 3 locations.
- Рекомендация: Create NetworkingCoordinator class in :lib module. Expose suspend fun initCameraSupportConfig(): Result<ConfigData> that handles network binding internally. Pass injected NetworkManager dependency. ViewModel calls clean method, not raw connectivity APIs.

#### [medium/medium/риск:medium] Activity directly manages VR mode state + bitmap copying (VrManager antipattern)
- Файлы: `VrManager.kt`, `CaptureActivity.kt`
- Evidence: CaptureActivity lines 75-83: Activity instantiates VrManager directly, passes rootContainer + multiple view references. VrManager lines 89-207: Manipulates ViewGroup hierarchy, creates/removes FrameLayout, LinearLayout, and InstaCapturePlayerView dynamically. Lines 333-415: Manages low-level PixelCopy callbacks and bitmap lifecycle directly in VrManager. This belongs in Activity (it's UI tree manipulation), not a service. Coupling between VrManager and CaptureActivity is tight (casts to CaptureActivity at line 163, 261).
- Рекомендация: VrManager should remain lightweight — only expose toggleVrMode(), showVrSettingsDialog(), handle frame copying. Move view creation into CaptureActivity's initView() or a dedicated VrUIController. ViewModel should expose isVrMode state as Flow<Boolean> to drive UI composition. Break the reverse dependency (VrManager accessing CaptureActivity.viewModel — line 163).

#### [medium/small/риск:medium] Reflection-based view method invocation (setYaw/setPitch) bypasses type safety
- Файлы: `CaptureActivity.kt`, `LocalSphericalPlayerActivity.kt`
- Evidence: CaptureActivity lines 428-445: applyTo() helper uses reflection (getMethod('setYaw'), invoke(obj, yaw)). LocalSphericalPlayerActivity lines 376-388: Identical pattern with runCatching blocks. This assumes InstaCapturePlayerView and SphericalGLSurfaceView have setYaw/setPitch but doesn't verify. Silent failures due to try-catch.
- Рекомендация: If InstaCapturePlayerView is internal SDK, wrap it in IOrientableView interface. Pass interface reference to gyro controller instead of raw player view. If setYaw/setPitch must be SDK-internal, create adapter: ViewModel → PlayerOrientationBridge (handles reflection safely, logs errors).

#### [medium/medium/риск:low] Activity responsible for detection JSON parsing + FOV math (LocalSphericalPlayerActivity)
- Файлы: `LocalSphericalPlayerActivity.kt`
- Evidence: Lines 225-245: loadDetectionJson() reads JSON from Uri, parses inline using contentResolver, updates ViewModel. Lines 266-293: updateCurrentDetections() is called every 200ms from detectionUpdateRunnable (line 51-56), queries current playback position, updates currentGazeDirection (line 371). Lines 295-320: updateDirectionArrow() performs FOV math (resolveTargetQuat) and directs overlay rendering. This is business logic (detection tracking, FOV calculation) living in Activity lifecycle.
- Рекомендация: Move to ViewModel or dedicated DetectionService (pure-JVM in :lib). Activity only observes Flow<DetectionUpdate> with arrow directions. ViewModel manages timeline state, queries at playback position, emits arrow angles. Detection parsing should be in separate class (VideoDetectionSidecarParser is good, but needs ViewModel wrapper).

#### [low/small/риск:low] Handler-based polling for detection updates (200ms runnable) in Activity
- Файлы: `LocalSphericalPlayerActivity.kt`
- Evidence: Lines 48-56: detectionUpdateRunnable created as Runnable object in Activity, posted via uiHandler.post() in onResume (line 158). Lines 172, 166: Manually unregistered in onPause/onStop. This is threading/timing logic better expressed as ViewModel.Flow with ticker or snapshotFlow over playback position.
- Рекомендация: Use ViewModel with Flow-based detection updates. Example: snapshotFlow { player?.currentPosition ?: 0L }.collect { updateDetections() }. Or Flow interval(200.ms) combined with player position. Activity becomes observer, not scheduler.

#### [medium/medium/риск:medium] CaptureOfflineData mixes SDK queries with local state cache (lines 56-71, 100-109)
- Файлы: `CameraOfflineData.kt`
- Evidence: Lines 56-71: init{} block immediately calls instaCameraManager.getSupportCaptureSettingList() and getCaptureSettingValue() for all modes (nested loops). Lines 100-109: setCaptureSetting() updates cache via setCaptureSettingValue() callback but also directly reads back via getCaptureSettingValue(). Tight coupling to SDK for reads. Caching logic is intertwined with domain model (CaptureMode, CaptureSetting enums).
- Рекомендация: Split into (1) CaptureSettingsRepository — responsible for fetching/caching from SDK; (2) OfflineSettingsCache — domain model without SDK knowledge. Repository handles all instaCameraManager calls, exposes suspend fun getCaptureSettings(mode): Map<CaptureSetting, Any>. ViewModel depends only on Repository interface, not on SDK.

#### [low/small/риск:low] Preference access mixed throughout (Pref.getLiveRtmp, Pref.getStabCacheFrameNum)
- Файлы: `CaptureViewModel.kt`
- Evidence: Lines 149, 243: Direct Pref.* static calls. This couples ViewModel to SharedPreferences implementation. If Pref is changed, ViewModel must be updated.
- Рекомендация: Inject PreferencesProvider (or similar) into ViewModel. Or expose preferences as LiveData<PreferencesState> from application-level repository. ViewModel should depend on interface, not static helper.

#### [medium/large/риск:medium] Complex suspend-based camera control with manual coroutine wrapping
- Файлы: `CaptureViewModel.kt`
- Evidence: Lines 154-214: initCapture() is 60-line launch {} block with 4 sequential steps (checkCameraSensorMode, fetchCameraOptions, initCameraSupportConfig, openPreviewStream), each returning Boolean. Lines 362-456: Each SDK call wrapped in suspendCancellableCoroutine with ICameraOperateCallback/ICaptureSupportConfigCallback. Callback-heavy SDK forces manual continuation. No error hierarchy — all failures route to Boolean returns.
- Рекомендация: Create CameraSDKCoroutineAdapter that converts all callback-based SDK calls to suspend funs returning Result<T>. Example: suspend fun checkCameraSensorMode(): Result<Unit> = coroutineScope { ... }. Then initCapture becomes cleaner state machine with proper error handling (sealed class CameraInitError). This also enables better unit testing.

#### [low/small/риск:low] BaseViewModel registers/unregisters SDK callback globally in init/onCleared
- Файлы: `BaseViewModel.kt`
- Evidence: Lines 20-28: All BaseViewModel subclasses automatically register as ICameraChangedCallback (line 21). If multiple ViewModels are created, all will receive same callbacks. No filtering by relevance. onCleared unregisters, but timing depends on GC.
- Рекомендация: Use scoped callback registration. Pass InstaCameraManager into ViewModel constructor as dependency. Register callback only in onActive() (if using LiveData) or manually when needed. Unregister in onCleared. Or use ViewModel-level Flow that emits camera events (better: camera SDK emits events to dedicated listener, Activity/Fragment subscribes).

#### [low/small/риск:low] Rendering state mixed with business state (OffsetData, WindowCropInfo in events)
- Файлы: `CaptureEvent.kt`
- Evidence: Lines 34-39: UpdatePlayerViewParamsEvent carries OffsetData, WindowCropInfo, StreamResolution. These are SDK-specific rendering parameters, not domain events. Lines 237-247 in CaptureActivity handle these by directly calling binding.capturePlayerView.setOffset(). Tight coupling between ViewModel events and SDK view configuration.
- Рекомендация: Events should carry domain-level changes ('capture mode changed', 'preview params updated') not raw SDK objects. Create PlayerConfigUpdate sealed class in ViewModel. Activity/Fragment interprets update and applies to views. Decouples from SDK object changes.

#### [medium/medium/риск:medium] LocalVrManager has same structural issues as VrManager (view manipulation + tight coupling)
- Файлы: `LocalVrManager.kt`
- Evidence: Lines 27-44: Takes Activity directly, manipulates sourceView (SphericalGLSurfaceView). Lines 63-86: enableVrMode() and disableVrMode() manage visibility of multiple overlays (lines 67, 81). Lines 96-128: ensureVrSettingsButton() creates ImageButton dynamically, finds parent ViewGroup through reflection/casting. Lines 130-150: showVrSettingsDialog() builds AlertDialog with SeekBar, TextView layouts dynamically.
- Рекомендация: Move UI creation to Activity/Fragment (or dedicated VrUIController composable). LocalVrManager handles only state (isVrMode, eyeScale, eyeSpacingPx) and bitmap copying. Activity observes isVrMode state and handles view visibility/creation. Separate concerns: state management vs. UI composition.

#### [low/small/риск:low] Gyroscope calibration state stored in GyroOrientationController, not ViewModel
- Файлы: `GyroOrientationController.kt`
- Evidence: Lines 66-73, 101-110: calibrationQuaternion, calibrationRawYawDeg, calibrationRawPitchDeg, and calibrated flag stored as private properties in GyroOrientationController. Activity calls gyroController.calibrate() directly (CaptureActivity line 82, 108, 116; LocalSphericalPlayerActivity line 216). Calibration state is ephemeral — lost on controller recreation.
- Рекомендация: Persist calibration state in ViewModel (or dedicated CalibrationManager). ViewModel exposes Flow<CalibrationState>. Activity observes and displays indicator. On ViewModel recreation, restore from saved state. GyroOrientationController becomes pure handler, not state keeper.

#### [low/small/риск:low] Unused/partial onEvent implementations in Activity/Fragment
- Файлы: `LocalSphericalPlayerActivity.kt`
- Evidence: Line 132: onEvent(event: BaseEvent) = Unit (empty implementation, all logic is in separate private methods like updateCurrentDetections, updateDirectionArrow). Activity doesn't use event-driven pattern for detection updates — relies on manual Handler polling instead.
- Рекомендация: Remove empty onEvent() or fill it to receive detection updates from ViewModel as events. Or switch entire detection flow to reactive (Flow-based, not polling). Consistency with event-driven pattern used in CaptureActivity.

#### [low/small/риск:low] Pure-JVM math classes not extracted: EquirectangularProjection, PanoramaFovMath isolated in UI module
- Файлы: `EquirectangularProjection.kt`, `PanoramaFovMath.kt`
- Evidence: EquirectangularProjection.kt and PanoramaFovMath.kt are pure Kotlin (no Android imports), contain only math logic. But located in ui.player.panorama package. Reusable in :lib module for shared calculations (detection tracking, panorama math could be shared with other UI layers or tests).
- Рекомендация: Move EquirectangularProjection, PanoramaFovMath, related data classes (PanoramaDirection, TargetFovState, BboxXyxy, Point2d) to pure-JVM :lib module. Keep only LocalSphericalPlayerActivity display logic in :app.

### Линза: СВЯЗНОСТЬ, ЗАВИСИМОСТИ, ДУБЛИРОВАНИЕ — архитектурный аудит Android Kotlin-приложения

#### [high/large/риск:high] КРИТИЧЕСКОЕ ДУБЛИРОВАНИЕ: VrManager и LocalVrManager — две копии одного механизма PixelCopy для VR-режима
- Файлы: `VrManager.kt (615 строк)`, `LocalVrManager.kt (339 строк)`
- Evidence: Идентичные методы скопированы между файлами:

1. findSurfaceView(v: View): VrManager:333, LocalVrManager:238 — рекурсивный поиск SurfaceView
2. findTextureView(v: View): VrManager:343, LocalVrManager:248 — рекурсивный поиск TextureView
3. startCopyLoop(): VrManager:353, LocalVrManager:262 — цикл копирования через PixelCopy.request() с одинаковой логикой обработки bitmaр и переусловиями (copyIntervalMs=33ms ~30fps)
4. processAndSetBitmap(): VrManager:434 (с параметром dst), LocalVrManager:313 (без параметра) — обработка альфа-канала и наложение чёрного фона идентична
5. stopCopyLoop(): VrManager:458, LocalVrManager:331 — удаление callback и очистка bitmap, идентичная логика
6. Диалог настроек VR (showVrSettingsDialog): VrManager:472–565, LocalVrManager:130–202 — одинаковые SeekBar для Scale/Spacing/Sensitivity с использованием GyroOrientationController.sensivity
7. applyVrAdjustments(): VrManager:567–614, LocalVrManager:204–236 — применение scaleX/scaleY и margin-смещений для левого/правого глаза, одна и та же логика setMarginStartEnd()
- Рекомендация: Извлечь общий код PixelCopy в базовый класс VrManagerBase или расширить существующий VrManager для работы как с capture, так и с player. Интерфейс должен принимать sourceView вместо конкретного типа (rightVrPlayer vs sphericalView). Дублирование затрудняет баги синхронизации и усложняет поддержку — например, LocalVrManager не логирует ошибки PixelCopy (нет кода вроде 'PixelCopy failed with code'), что скрывает проблемы.

#### [high/medium/риск:medium] НЕЯВНАЯ ЗАВИСИМОСТЬ: GyroOrientationController в capture/ используется также player/ и VR-режимами (cross-module coupling)
- Файлы: `GyroOrientationController.kt (288 строк — контроллер гироскопа)`, `VrManager.kt (строки 505–556)`, `LocalVrManager.kt (строки 153–199)`, `LocalSphericalPlayerActivity.kt (строка 23, import)`
- Evidence: GyroOrientationController находится в пакете com.arashivision.sdk.demo.ui.capture, но используется глобально:

1. VrManager.kt:505–506 читает `GyroOrientationController.sensivity` (публичный companion-объект Float)
2. LocalVrManager.kt:153 читает `GyroOrientationController.sensivity`
3. LocalVrManager.kt:194 пишет `GyroOrientationController.sensivity = newSens` — изменение состояния из player-модуля
4. LocalSphericalPlayerActivity:84 создаёт свой экземпляр GyroOrientationController()
5. CaptureActivity:52 создаёт свой экземпляр GyroOrientationController()

companion-объект sensivity создаёт глобальное состояние, которое синхронизируется между capture и player через VR-диалоги. Если пользователь меняет sensitivity в player-VR, это влияет на capture-VR, так как sensivity — статическое поле класса.
- Рекомендация: Переместить GyroOrientationController в базовый модуль (base/) или создать интерфейс OriginatationProvider в ui/common/. Использовать Dependency Injection (Dagger/Hilt) для распределения одного экземпляра через оба модуля вместо static state. Это устранит неявную сигнатуру класса и явит зависимость через конструктор.

#### [medium/medium/риск:high] ЦИКЛИЧЕСКАЯ СВЯЗЬ: capture → player (gyro) и player → capture (GyroOrientationController.sensivity) через static state
- Файлы: `GyroOrientationController.kt (строки 45–55)`, `VrManager.kt (строка 556)`, `LocalVrManager.kt (строка 194)`
- Evidence: companion object в GyroOrientationController:
```
companion object {
    var sensivity: Float = 1.2f  // static, shared across app
    var invertYaw = false
    var invertPitch = true
}
```

Этот static state создаёт неявную двусторонню связь:
- capture/VrManager.kt:556 пишет: `GyroOrientationController.sensivity = newSens`
- player/LocalVrManager.kt:194 пишет: `GyroOrientationController.sensivity = newSens`
- Обе стороны изменяют одно и то же поле, что приводит к race condition при одновременном доступе (если VR-режимы открыты в обоих модулях).

Также это нарушает принцип модульности — capture не должна знать о player и наоборот.
- Рекомендация: Удалить static companion-объект и перейти на instance-переменные. Использовать shared ViewModel (MVVM) или StateFlow для синхронизации sensivity между capture и player модулями. Это сделает зависимость явной и потокобезопасной.

#### [medium/small/риск:low] ЗАХЛАМЛЕНИЕ КОНСТАНТ: CaptureConst.kt — 307 строк с 30+ функциями преобразования enum-значений в строки
- Файлы: `CaptureConst.kt`
- Evidence: Файл содержит:
1. stepToLoadingTextMap, stepToErrorTextMap (строки 38–50) — две карты для init-steps
2. getCaptureModeTextResId() (53–75) — 20 case для enum CaptureMode
3. getCaptureSettingNameResId() (78–104) — 23 case для enum CaptureSetting
4. getCaptureSettingValueName() (107–300+) — огромный when с 25+ branches, каждый с вложенными логиками форматирования

Это не столько констант, сколько утилиты форматирования, которые лучше разместить в объекте Formatter или создать отдельный файл FormatUtils.kt. Функции getCaptureModeTextResId и getCaptureSettingNameResId могли бы быть методами расширения (extension functions) на enum'ах.
- Рекомендация: Разделить CaptureConst.kt на: (1) CaptureConstants.kt — чистые константы и maps, (2) CaptureFormatters.kt или ValueNameResolver.kt — функции преобразования. Или создать extension functions на CaptureSetting и CaptureMode для прямого вызова `mode.textResId()` и `setting.valueName(context, value)`. Это улучшит читаемость и соответствие паттерну Single Responsibility Principle.

#### [medium/small/риск:low] НЕЯВНАЯ ЗАВИСИМОСТЬ: instaCameraManager (ExtensionFunction) — статический синглтон используется из VrManager и других UI-модулей
- Файлы: `InstaCameraManagerExt.kt (строка 33)`, `VrManager.kt (строки 16, 148, 155, 271)`
- Evidence: В InstaCameraManagerExt.kt глобальное определение:
```
val instaCameraManager: InstaCameraManager = InstaCameraManager.getInstance()
```

В VrManager.kt используется напрямую без DI:
- Строка 16: `import com.arashivision.sdk.demo.ext.instaCameraManager`
- Строка 148: `instaCameraManager.setPipeline(rightVrPlayer!!.pipeline)`
- Строка 155: `instaCameraManager.setPipeline(null)`
- Строка 271: `instaCameraManager.setPipeline(capturePlayerView.pipeline)`

Это скрывает зависимость от камеры в пакете VrManager, усложняя тестирование и делая невозможным mock'ирование InstaCameraManager без модификации кода.
- Рекомендация: Передать instaCameraManager в конструктор VrManager (Dependency Injection) вместо использования глобального импорта. Это сделает зависимость явной и обеспечит возможность тестирования с mock-объектами. Также рассмотреть создание интерфейса IPipelineProvider для абстракции.

#### [medium/small/риск:low] ДУБЛИРОВАНИЕ ЛОГИКИ: Диалоги VR-настроек (SeekBar listeners) копируют друг друга с минорными различиями
- Файлы: `VrManager.kt (строки 472–565)`, `LocalVrManager.kt (строки 130–202)`
- Evidence: Обе showVrSettingsDialog() реализуют одну и ту же логику:
1. Создают 3 SeekBar (scale, spacing, sensitivity)
2. Каждый имеет listener, обновляющий label и вызывающий applyVrAdjustments()
3. VrManager добавляет layoutParams явно (MATCH_PARENT, WRAP_CONTENT), LocalVrManager скрывает это в apply { }
4. VrManager:506 вызывает `val currentSens = GyroOrientationController.sensivity`, LocalVrManager:153 вызывает прямо
5. VrManager:514 добавляет комментарий `// стартовое положение`, LocalVrManager этого не имеет

Дифф показывает лишь стилистические отличия, логика идентична. Если нужно изменить диапазоны SeekBar или добавить новый параметр, придётся менять 2 места.
- Рекомендация: Создать VrSettingsDialogHelper или VrSettingsUiBuilder, который принимает параметры (eyeScale, eyeSpacingPx, sensitivity, onApply callback) и возвращает готовый AlertDialog. Использовать из обоих VrManager'ов. Это также упростит тестирование диалога отдельно от логики.

#### [low/small/риск:low] НЕДОКУМЕНТИРОВАННОЕ РАССТРОЙСТВО: Две реализации VR-режима с разными сигнатурами applyVrAdjustments()
- Файлы: `VrManager.kt (строка 567)`, `LocalVrManager.kt (строка 204)`
- Evidence: VrManager.applyVrAdjustments() обёрнута в try-catch и вызывает `leftVrImage?.let {}` и `rightVrPlayer?.let {}` (null-safe). LocalVrManager.applyVrAdjustments() вызывает `sourceView.parent as? LinearLayout ?: return` (небезопасно возвращает, если parent не LinearLayout).

В VrManager строка 598 обёрнута в try-catch, а LocalVrManager этого не имеет. Если setMarginStartEnd вызовет исключение, VrManager залогирует его, а LocalVrManager упадёт молча.
- Рекомендация: Стандартизировать обработку ошибок между обоими. Создать базовый класс с шаблонной логикой и одинаковым уровнем error handling.

#### [low/small/риск:low] ИЗБЫТОЧНОЕ ЛОГИРОВАНИЕ: VrManager логирует PixelCopy ошибки, LocalVrManager игнорирует
- Файлы: `VrManager.kt (строка 405)`, `LocalVrManager.kt (нет аналога)`
- Evidence: VrManager.kt:405: `logger.e("PixelCopy failed with code: $result")` — явная диагностика при ошибке копирования. LocalVrManager.kt:284–289 не логирует failureкод, только успех и перепостит runnable. Это затрудняет отладку VR-режима в player'е.
- Рекомендация: Добавить логирование ошибок PixelCopy в LocalVrManager для симметрии и отладки.

### Линза: Архитектурный аудит: РЕФЛЕКСИЯ И ХРУПКОСТЬ

#### [critical/large/риск:high] КРИТИЧНА: Рефлексивные вызовы setYaw/setPitch на игроках без публичного API
- Файлы: `VrManager.kt`, `CaptureActivity.kt`, `LocalSphericalPlayerActivity.kt`
- Evidence: VrManager.kt:288-297 — getMethod('setYaw', Float) и getMethod('setPitch', Float) вызываются через рефлексию с пустыми catch(_: NoSuchMethodException) блоками. Методы не являются частью публичного API SDK (`InstaCapturePlayerView` и `SphericalGLSurfaceView`). При обновлении SDK или переименовании методов код молча сломается, ориентация не будет применяться без ошибок.

CaptureActivity.kt:433-439 — аналогичная рефлексия в applyOrientation(), но хотя бы с логированием ошибки (logger.e).

LocalSphericalPlayerActivity.kt:381-384 — рефлексивный вызов setYaw/setPitch в tryApplyOrientation(), обёрнут в runCatching, но без логирования NoSuchMethodException — исключение просто проглатывается молча.
- Рекомендация: Создать типобезопасный интерфейс ISphericalPlayerController с методами setYaw(Float) и setPitch(Float). Обёрнуть как InstaCapturePlayerView, так и Media3's SphericalGLSurfaceView во франшизы, реализующие интерфейс. Рефлексию перенести в фабрику инициализации (выполнить 1 раз при создании, а не каждый кадр). На CompileTime гарантировать наличие методов через @RequiresOptIn и документацию SDK версии.

#### [high/small/риск:high] ВЫСОКИЙ РИСК: Пустые catch блоки, подавляющие исключения рефлексии
- Файлы: `VrManager.kt`, `CaptureActivity.kt`
- Evidence: VrManager.kt:291, 296 — catch(_: NoSuchMethodException) { } без тела. Полное подавление сигнала об ошибке. При отсутствии метода setYaw/setPitch игрок не будет реагировать на жесты/гиро, но пользователь не получит никакого уведомления.

CaptureActivity.kt:82 — calibrateGyro = { try { gyroController.calibrate() } catch (_: Exception) {} } — пустой catch для Exception при калибровке гиро. Если калибровка не сработает, пользователь не узнает.

VrManager.kt:327 — catch(_: Exception) при destroy() rightVrPlayer, молчаливый отказ от очистки ресурсов.
- Рекомендация: Заменить пустые catch на логирование ВСЕХ исключений (включая NoSuchMethodException). Хотя бы вывести warning в лог. Для критических методов (setYaw/setPitch) логировать информацию о версии SDK и доступных методах через reflection для отладки. Рассмотреть fallback-стратегию или graceful degradation (отключить VR режим, если методы недоступны).

#### [high/large/риск:high] ВЫСОКИЙ РИСК: Рефлексия в ViewBindingUtils для динамического создания ViewBinding
- Файлы: `ViewBindingUtils.kt`, `BaseAdapter.kt`, `BaseListAdapter.kt`
- Evidence: ViewBindingUtils.kt:18-27 — getMethod('inflate', LayoutInflater::class.java, ViewGroup::class.java, Boolean::class.javaPrimitiveType).invoke(null, ...). Если AGP или Android Gradle обновится и формат генерируемого класса ViewBinding измерится, рефлексия сломается на Runtime.

BaseAdapter.kt:60 и BaseListAdapter.kt:60 — вызов createBinding<T>() через рефлексию из каждого onCreateViewHolder/getView. Это КАЖДЫЙ раз при прокрутке RecyclerView может выполнить рефлексию. Имеет NoSuchMethodException и InvocationTargetException, обёрнутые в RuntimeException, что скрывает истинную причину.

Предпосылка: обобщение <T> требует access к T.inflate() через рефлексию. ViewBinding не поддерживает полиморфизм через интерфейсы.
- Рекомендация: Рассмотреть использование viewbinding.enabled = false и ручное создание ViewBinding через параметр Type/Constructor вместо getMethod(). Или кэшировать Method объекты (сохранять в companion object как WeakHashMap<Class, Method>). Для адаптеров: использовать sealed class или ViewBinder pattern вместо обобщения. Проверить, что ViewBinding код генерируется с правильными сигнатурами на всех versions AGP в CI/CD.

#### [high/small/риск:medium] СРЕДНИЙ РИСК: Рефлексия в tryApplyOrientation (LocalSphericalPlayerActivity) с использованием runCatching
- Файлы: `LocalSphericalPlayerActivity.kt`
- Evidence: LocalSphericalPlayerActivity.kt:376-389 — applyTo() вложенная функция использует runCatching { } без логирования при failure. getMethod('setYaw', Float::class.javaPrimitiveType) и getMethod('setPitch', Float::class.javaPrimitiveType) вызываются внутри try-catch, но NoSuchMethodException молча проглатывается, а onFailure просто не выполняется ничего.
- Рекомендация: Добавить .onFailure { e -> logger.w("Failed to apply orientation: ${e.message}") } к runCatching вызовам. Также: в комментарии (366-367) упоминается что Media3's sensor отключён и setYaw/setPitch — ЕДИНСТВЕННЫЙ источник вращения. Это критично для корректности; документировать как инвариант и проверять при инициализации.

#### [medium/medium/риск:medium] СРЕДНИЙ РИСК: Рефлексивные вызовы в адаптерах (BaseAdapter, BaseListAdapter) не кэшируются
- Файлы: `BaseAdapter.kt`, `BaseListAdapter.kt`
- Evidence: BaseAdapter.kt:60 — onCreateViewHolder() вызывает ViewBindingUtils.createBinding<T>(...) со строка 0 (index для getParameterizedTypeClass). Это означает, что каждый раз при создании ViewHolder выполняется:
  1. Class.getGenericSuperclass() → ParameterizedType
  2. getMethod('inflate', ...) на сгенерированном ViewBinding классе
  3. invoke()

При прокрутке списка с 100 элементами это может быть 100+ рефлексивных вызовов. Производительность не критична (не UI-блокирующая), но хрупкость остаётся.
- Рекомендация: Кэшировать Method объекты в companion object или использовать inline factory functions вместо обобщений. Например: abstract class BaseAdapter<T : ViewBinding, K>(val bindingFactory: (parent: ViewGroup) -> T) вместо рефлексии.

#### [medium/small/риск:medium] СРЕДНИЙ РИСК: Отсутствие версионирования SDK API в рефлексивных вызовах
- Файлы: `VrManager.kt`, `CaptureActivity.kt`, `LocalSphericalPlayerActivity.kt`
- Evidence: Нет проверки версии SDK перед попыткой вызова setYaw/setPitch. Ни в build.gradle, ни в манифесте не указана минимальная версия SDK, где эти методы доступны. Если пользователь обновит SDK на более старую версию (или совместимость сломается), рефлексия не предупредит.

В CLAUDE.md (кэшировано) указана версия SDK: com.arashivision.sdk:sdkcamera и :sdkmedia version 1.8.1_build_06, но это нигде не проверяется.
- Рекомендация: Добавить версионную проверку SDK при инициализации VrManager и LocalSphericalPlayerActivity. Использовать reflection для получения версии (PackageInfo.versionCode или constants класса SDK). При недостаточной версии показать диалог или отключить VR режим с пояснением.

#### [medium/small/риск:low] НИЗКИЙ РИСК: Неконсистентное обращение с исключениями рефлексии
- Файлы: `ViewBindingUtils.kt`
- Evidence: ViewBindingUtils.kt:30-36 — NoSuchMethodException, InvocationTargetException, IllegalAccessException все переводятся в RuntimeException с теми же сообщениями об ошибке. Caller не может различить причину (метод не найден vs. проблема доступа vs. ошибка при вызове). Это затрудняет отладку при проблемах с ViewBinding.
- Рекомендация: Сохранить оригинальное исключение как cause: throw RuntimeException("Failed to create ViewBinding for ${tClass.name}", e). Или вернуть Either<T, Exception> / Result<T> вместо выброса. В адаптерах предусмотреть fallback или явное логирование с context.

#### [low/small/риск:low] НИЗКИЙ РИСК: Проглатывание исключений в VrManager.destroy()
- Файлы: `VrManager.kt`
- Evidence: VrManager.kt:322-329 — destroy() вызывает rightVrPlayer?.destroy() обёрнутым в try-catch(_: Exception) { }. Если rightVrPlayer.destroy() выбросит исключение, оно будет проглочено молча. Это может привести к утечкам ресурсов (bitmap не утечёт через finally, но ссылки могут остаться).
- Рекомендация: Добавить logger.w("Failed to destroy rightVrPlayer: ${e.message}") в catch блок. Или использовать runCatching { rightVrPlayer?.destroy() }.onFailure { logger.w(...) }.

#### [critical/large/риск:high] АРХИТЕКТУРНАЯ УЯЗВИМОСТЬ: Отсутствие интерфейса между приложением и скрытым API SDK
- Файлы: `VrManager.kt`, `CaptureActivity.kt`, `LocalSphericalPlayerActivity.kt`
- Evidence: Код напрямую зависит от внутреннего API InstaCapturePlayerView и SphericalGLSurfaceView (setYaw/setPitch методы). Эти методы не документированы как публичные, не покрыты тестами SDK, не имеют гарантий обратной совместимости. Любое обновление SDK может их переименовать или переместить.

Текущая архитектура: VrManager.applyOrientation() → getMethod() → invoke() — зависимость в Runtime, а не Compile-time.
- Рекомендация: АРХИТЕКТУРНОЕ РЕШЕНИЕ: Запросить у Insta360 официальный публичный API для установки ориентации (setYaw/setPitch). Если невозможно, создать adapter pattern с версионируемыми fallback'ами:

```
interface OrientationController {
  fun setYaw(yaw: Float)
  fun setPitch(pitch: Float)
}

class ReflectiveOrientationController(obj: Any) : OrientationController {
  private val yawMethod by lazy { obj::class.java.getMethod("setYaw", Float::class.javaPrimitiveType) }
  private val pitchMethod by lazy { obj::class.java.getMethod("setPitch", Float::class.javaPrimitiveType) }
  
  override fun setYaw(yaw: Float) {
    try { yawMethod.invoke(obj, yaw) } catch (e: NoSuchMethodException) { logger.w("setYaw not available") }
  }
  override fun setPitch(pitch: Float) {
    try { pitchMethod.invoke(obj, pitch) } catch (e: NoSuchMethodException) { logger.w("setPitch not available") }
  }
}
```

Это обеспечит типобезопасность при разработке и явное управление fallback'ами.

#### [medium/small/риск:medium] ХРУПКОСТЬ: Предположение о структуре ParameterizedType в BaseAdapter
- Файлы: `ViewBindingUtils.kt`
- Evidence: ViewBindingUtils.kt:49-54 — getParameterizedTypeClass() предполагает, что cls.genericSuperclass является ParameterizedType и что actualTypeArguments[index] заполнен. Если:
  1. Класс не имеет параметризованного суперкласса (например, extends Object)
  2. index выходит за границы actualTypeArguments
  3. actualTypeArguments[index] != Class

То будет ClassCastException или ArrayIndexOutOfBoundsException, и оба преобразуются в RuntimeException без контекста.
- Рекомендация: Добавить проверки: if (cls.genericSuperclass !is ParameterizedType) throw IllegalStateException(). Проверить bounds: if (index >= actualTypeArguments.size) throw IndexOutOfBoundsException(). Или использовать kotlin.reflect.typeOf() и reified типы вместо Class<?> рефлексии (если поддерживаемо).

### Линза: TESTABILITY: Pure JVM math extraction and test coverage expansion

#### [high/medium/риск:low] EquirectangularProjection & UnitVector3 & UnitQuaternion — 100% pure math, ready to move to :lib
- Файлы: `EquirectangularProjection.kt`
- Evidence: Lines 1-159. Complete module: 6 top-level functions (fromPixel, fromNormalized, fromYawPitch) + 3 data classes (PanoramaDirection, UnitVector3, UnitQuaternion) with 13 methods. Zero Android imports, only kotlin.math. Conversions: pixel→normalized→yaw/pitch→3D vector + quaternion. Testable in isolation. Already has 2 unit tests (EquirectangularProjectionTest) in app/src/test, but only testing public API. Data classes use init {} for validation — pure validation logic.
- Рекомендация: Move entire file to lib/src/main/kotlin/com/arashivision/panorama/EquirectangularProjection.kt. Current tests (EquirectangularProjectionTest.kt) stay in app but will work against :lib import. Add 3-5 additional tests for edge cases: gimbal lock corners, quaternion normalization bounds, vector rotation identity checks. Effort: extract + adapt imports in app (2 imports only: import com.arashivision.panorama.*).

#### [high/medium/риск:low] PanoramaFovMath — pure spherical math with two independent FOV algorithms (Euler + quaternion-robust)
- Файлы: `PanoramaFovMath.kt`
- Evidence: Lines 1-110. Two pure functions: resolveTarget() (lines 17-42, Euler-based), resolveTargetQuat() (lines 58-92, quaternion-robust via conjugate rotation). Zero Android dependencies. Returns TargetFovState(isInsideFov, yawDeltaRad, pitchDeltaRad, arrowAngleRad?). Private helper wrapToMinusPiPlusPi(). Already tested by PanoramaFovMathTest with 9 test cases (7 for quat variant, all passing), but tests are in app/src/test.
- Рекомендация: Move to lib/src/main/kotlin/com/arashivision/panorama/PanoramaFovMath.kt + TargetFovState data class. Dependency: EquirectangularProjection.PanoramaDirection. Move existing 9 tests to lib/src/test. Add 4-6 new tests: wrapping at ±π for yaw, pitch clamp validation, arrow angle precision for diagonal targets, FOV boundary cases (target exactly at FOV edge). Current coverage is ~85%, target 95%+.

#### [high/small/риск:low] VideoDetectionSidecarParser & VideoDetectionTimeline — pure JSON parsing + time indexing, no Android UI dependencies
- Файлы: `VideoDetectionSidecarParser.kt`, `VideoDetectionTimeline.kt`, `VideoDetectionModels.kt`
- Evidence: Parser (125 lines): org.json only, parseFrame/parseObject/toPoint2d/toBboxXyxy. Data models (39 lines): 5 data classes (VideoDetectionSidecar, VideoDetectionFrame, VideoDetectedObject, BboxXyxy, Point2d). Timeline (42 lines): binarySearchBy on timeSec, frameAt/detectionsAt/frameByIndex. Zero View/Context/Sensor imports. Currently zero tests in app/src/test.
- Рекомендация: Move all 3 files to lib/src/main/kotlin/com/arashivision/detection/. Create lib/src/test/kotlin/com/arashivision/detection/VideoDetectionSidecarParserTest.kt (JSON parsing edge cases: empty arrays, missing fields, malformed JSON, coordinate bounds). Create lib/src/test/kotlin/com/arashivision/detection/VideoDetectionTimelineTest.kt (binary search edge cases: empty timeline, single frame, time between frames, out-of-bounds queries). Target: 12-15 new tests, coverage 100%. Effort: trivial (3-file move + 15 tests).

#### [medium/large/риск:medium] GyroOrientationController.onSensorChanged() — axis remapping + Euler extraction can be factored into pure :lib module
- Файлы: `GyroOrientationController.kt`
- Evidence: Lines 1-288. SensorEventListener (lines 32-36, context + SensorManager dependency). Pure logic inside onSensorChanged (lines 116-228): (1) getRotationMatrixFromVector() [Android but deterministic], (2) remapCoordinateSystem() branching on Surface.ROTATION_* (lines 128-159) [pure rotation matrix ops], (3) fromRotationMatrix + toEulerAngles [via Quaternion in :lib], (4) SLERP smoothing [Quaternion.slerp], (5) computeTargetOrientation [already in :lib]. Lines 199-201 call lib's computeTargetOrientation. Also exposes getRawEulerYawDeg/Pitch via getGazeYawDeg/Pitch methods (lines 260-281) which compute relative angles from calibration pose — pure math.
- Рекомендация: Extract pure orientation math into :lib as OrientationAxisRemapper object: fun remapRotationMatrix(matrix: FloatArray, displayRotation: Int) → FloatArray + extractEulerFromRemappedMatrix(Float[9]) → Triple<Float,Float,Float>. Keep GyroOrientationController in app; inject remapper. Create lib tests for remap logic with all 4 rotation cases. This de-couples the 288-line class from :lib dependency inversion. Medium effort due to surface rotation parameter handling; medium risk due to sensor fusion criticality (needs verification test on device post-extraction). Expected gain: testable rotation matrix ops + Euler extraction for non-Android contexts (e.g., BLE firmware telemetry).

#### [low/medium/риск:high] VrManager.vrIpdYawDeg & LocalVrManager.eyeScale/eyeSpacingPx — stereo geometry calculations are pure math
- Файлы: `VrManager.kt`, `LocalVrManager.kt`
- Evidence: VrManager (616 lines): vrIpdYawDeg = 3.0f (line 58), eyeScale = 0.7f (line 59), eyeSpacingPx = -400 (line 60), applyVrAdjustments() (lines 567-614) modifies scaleX/scaleY and margins. LocalVrManager (340 lines): same pattern — eyeScale, eyeSpacingPx, applyVrAdjustments() (lines 204-236). Both embed stereo geometry inside view-manipulation code. No pure module extracted for: stereo baseline calculations, IPD (inter-pupillary distance) adjustments, eye position offset computation.
- Рекомендация: LOW PRIORITY. Extract to :lib as StereoGeometryConfig data class (scale: Float, spacingPx: Int, ipdYawDeg: Float) + computeEyePositions(config, screenWidth, screenHeight) → Pair<Float,Float>. But risk is HIGH because: (1) these are empirically tuned values; (2) no existing tests; (3) changes here require UI testing on VR devices; (4) marginal test gain. Only extract if stereo parameters need to be shared across multiple capture modes or if you plan to add stereo correction algorithms (e.g., barrel distortion per eye). Skip for now; revisit after FOV math tests stabilize.

#### [low/large/риск:high] DirectionArrowOverlayView.onDraw() — screen-space arrow geometry is pure, but entangled with Canvas
- Файлы: `DirectionArrowOverlayView.kt`
- Evidence: Lines 1-120. View subclass. Pure math: drawSingleArrow (lines 98-118) computes arrowCenterX/Y via cos(angle) * radius, builds Path with hardcoded geometry (moveTo, lineTo, close). Logic could be extracted: fun computeArrowGeometry(centerX, centerY, radius, anglRad, size) → List<Float> for path coordinates. But View rendering is mixed in; abstraction gain is low.
- Рекомендация: SKIP for now. The geometry is trivial (circle + triangle); no test value. Refactor only if: (1) multiple UI implementations (e.g., WebGL overlay), or (2) you need to unit-test arrow positioning math independently. For current app, keep View-coupled. If refactoring, extract computeArrowPathPoints(screenWidth, screenHeight, anglRad, isVrMode) → Pair<ScreenCoord, ScreenCoord> for each eye center; return list of (x,y) tuples for path. Risk of breaking arrow rendering during refactor is too high for marginal gain.

#### [medium/medium/риск:medium] LocalSphericalPlayerActivity.updateCurrentDetections() — detection→panorama coordinate conversion is pure but scattered
- Файлы: `LocalSphericalPlayerActivity.kt`
- Evidence: No direct read available, but CLAUDE.md (line 127) describes: 'currentDetections() gets nearest detection frame via VideoDetectionTimeline → converts centerNorm via EquirectangularProjection.fromNormalized() → calls resolveTargetQuat(). Pure data-flow pipeline. Empirical pattern: [List<VideoDetectedObject>] → [List<PanoramaDirection>] → [List<TargetFovState>].
- Рекомендация: Create :lib utility DetectionProjector object with single pure function: fun projectDetectionsToFov(detections: List<VideoDetectedObject>, gaze: PanoramaDirection, fovConfig: FovConfig) → List<ProjectedDetection> where ProjectedDetection = (detection, fovState, screenPosition?). Encodes the commonpattern. Add 4-5 tests for coordinate mapping correctness (normalized→yaw/pitch→FOV state). Effort: small; risk: low (no UI code, all data structures). Expected gain: isolate detection rendering logic from Activity, reusable in future VR/multi-view renderers.

#### [medium/small/риск:low] Quaternion.fromRotationMatrix() — Shepperd's algorithm is mathematically sensitive; expand test coverage
- Файлы: `Quaternion.kt`
- Evidence: Lines 107-154. Four conditional branches selecting which diagonal element is largest (trace vs m00 vs m11 vs m22). Current tests (OrientationMathTest.kt lines 119-134): only 2 tests covering identity and 180°-about-X (hits m00 branch). Missing: m11 and m22 branches, gimbal lock cases, denormalized input matrices, numerical stability at trace ≈ 0.
- Рекомендация: Add 5-7 tests to OrientationMathTest: (1) 180°-about-Y [hits m11 branch], (2) 180°-about-Z [hits m22 branch], (3) mixed rotations (e.g., 45° yaw + 30° pitch) to verify round-trip matrix→quat→Euler, (4) near-gimbal-lock matrix (trace ≈ 0) to check numerics, (5) denormalized matrix input (rows not unit-length) to verify normalize() call. Use scipy.spatial.transform.Rotation to generate ground-truth matrices. Effort: 2-3h; gain: 95%+ coverage on fromRotationMatrix, critical for GyroOrientationController sensor fusion.

#### [low/small/риск:low] Quaternion.slerp() — only 2 tests at t=0 and t=1; needs interpolation fidelity checks
- Файлы: `Quaternion.kt`
- Evidence: Lines 165-187. SLERP implementation used in GyroOrientationController.onSensorChanged (line 187) with alpha=0.12f for smoothing. Current tests (OrientationMathTest lines 137-154): only 2 assertions (t=0→q1, t=1→q2). Missing: intermediate t values, opposite quaternions (dot < 0 flip), nearly-parallel quaternions (angle small), composition of multiple SLERP steps.
- Рекомендация: Add 4-5 tests: (1) SLERP at t=0.5 between identity and 90°-rotation-about-Z (should be ~45° rotation), (2) opposite quaternions (dot < 0) to verify negate + shortest-path, (3) repeated SLERP steps (5 frames smoothing from q1 to q2) to check convergence, (4) near-parallel quaternions to check numerical stability (angle < 1°). Verify against scipy or Eigen. Expected impact on GyroOrientationController: confirms smoothing is stable; low effort (3-4h).
