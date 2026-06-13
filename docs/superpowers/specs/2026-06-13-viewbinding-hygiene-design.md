# Spec: ViewBinding-рефлексия (#9) + гигиена (#10)

Дата: 2026-06-13
Ветка: `refactoring`
Статус: одобрено к реализации (автономно)
Основано на: `2026-06-13-architecture-audit.md` направления #9, #10

## Цель

Закрыть два низкорисковых независимых направления аудита: укрепить рефлексию ViewBinding (перф + качество ошибок) и навести гигиену (логирование молчаливых catch, разнесение CaptureConst). Не трогает логику гироскопа/VR/SDK (это #3–#7).

## Ограничение окружения

Код в `:app` — агент НЕ может собрать (`assembleDebug` требует Android SDK + Nexus, недоступные в окружении агента) и не проверяет на камере. Гарантия — статическая корректность (grep, консистентность импортов). CI гоняет только `:lib:test`, который мы не затрагиваем. Финальная сборка/проверка — за пользователем.

## #9 — укрепить ViewBindingUtils

Файл `app/src/main/java/com/arashivision/sdk/demo/util/ViewBindingUtils.kt`. Проблемы (из аудита, подтверждены чтением кода):
1. `getMethod("inflate", ...)` вызывается на КАЖДЫЙ `createBinding` (горячий путь: каждый onCreateViewHolder).
2. `createViewModel` оборачивает в `RuntimeException(e.message)` — теряется cause/stacktrace.
3. `getParameterizedTypeClass` слепо кастит `genericSuperclass as ParameterizedType` и `actualTypeArguments[index] as Class<*>` — невнятный ClassCastException при неверной структуре.

Решения:
1. Кэш `Method` per (Class, signature) в `ConcurrentHashMap` внутри object — резолв один раз.
2. `RuntimeException(e)` вместо `RuntimeException(e.message)` — cause сохраняется.
3. Проверки с понятными сообщениями (`check`/`require`) вместо слепого каста.

Штатное поведение неизменно: меняются только перф (кэш) и качество диагностики.

## #10 — гигиена

### 10a. Логирование молчаливых catch
Добавить `XLog`-логирование в catch-блоки, которые сейчас глотают исключение молча (`// ignore` или пустое тело):
- `GyroOrientationController.stop()` (~стр 95), и второй молчаливый catch (~стр 238 — в updateRawFromEvent).
- Молчаливые `catch` в `VrManager` (destroy и пр.) и `LocalVrManager` (PixelCopy ~стр 300), где сейчас нет лога.

НЕ менять control flow — только добавить вызов лога в существующие блоки. `catch (NoSuchMethodException)` вокруг рефлексии setYaw/setPitch НЕ трогать — это домен #3 (см. [[insta360-reflection-sites]]).

### 10b. Разнести CaptureConst.kt
Файл `app/.../ui/capture/CaptureConst.kt` (307 строк) — не свалка констант, а смесь: map-таблиц (`stepToLoadingTextMap`, `stepToErrorTextMap`) и resId-резолверов (`getCaptureModeTextResId`, `getCaptureSettingNameResId`, `getCaptureSettingValueName`, ...).

Решение — СТРОГО МЕХАНИЧЕСКИЙ перенос: вынести функции-резолверы текста/ресурсов в новый файл `CaptureText.kt` (тот же пакет `com.arashivision.sdk.demo.ui.capture`), оставив в `CaptureConst.kt` чистые константы и map-таблицы. Сигнатуры, имена и тела функций НЕ меняются. Т.к. пакет тот же — импорты потребителей чинить НЕ нужно (top-level функции в одном пакете видны без импорта внутри пакета; для внешних потребителей — пакетный импорт остаётся тем же). Проверить grep-ом потребителей.

## Границы

Не трогаем: логику гироскопа/проекций/VR, рефлексию setYaw/setPitch (#3), static-поля GyroOrientationController (#4), ViewModel/SDK (#6,#7). Только: ViewBindingUtils, логи в catch, перемещение функций CaptureConst.

## Критерий готовности

- Статически: нет осиротевших ссылок (grep на перенесённые функции), импорты консистентны.
- `:lib:test` остаётся зелёным (не затронут).
- Push в refactoring; `assembleDebug` — пользователь.
