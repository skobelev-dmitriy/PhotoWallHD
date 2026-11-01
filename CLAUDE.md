# PhotoWall HD — карта проекта для ассистента

Android-приложение (Kotlin, без внешних библиотек) показывает фото на проекторе/ТВ + веб-страница управления, встроенный HTTP-сервер,
дискотека, плейлисты и папки, «живые» фото. Владелец собирает приложение локально в Android Studio и ставит его на проектор Wanbo T2 Ultra
(Android TV, микрофон только в пульте). Общаться по-русски; честно сообщать, что проверено, а что нет.

## Где что лежит

- `app/` — приложение (`app/src/main/java/app/photowall/`), веб-страница `app/src/main/assets/web/index.html`, страница отправки звука `sender.html`.
- `vision/` — Python-сервис распознавания областей для «живых» фото (Docker, тесты `pytest`).
- `mcp/` — MCP-сервер управления проектором (Node.js, `npm test`).
- `tools/jvm-check/` — проверка логики хранилища и плейлистов на JVM (`gradle run`).
- `docs/STATUS.md` — **читать первым**: что сделано, что проверено, принятые решения, очередь работ, вопросы к владельцу.
- `docs/` — документация: `ARCHITECTURE`, `API`, `EFFECT-SPEC`, `LIVE-PHOTOS`, `PLAYLISTS`, `DISCO`, `MCP`, `ADB-TESTING`, `ROADMAP`, `PRODUCT-IDEAS`, `EFFECTS-IDEAS`, `FILMSTRIPS`, `STATUS`.
- `.claude/skills/wallpaper-engine/` — заметки по Wallpaper Engine (своими словами; чужую документацию в репозиторий не копировать).

## Правила работы

- Владелец часто работает урывками и просит «делай сам»: принимай разумные решения, записывай их в `docs/STATUS.md`, вопросы копи там же, не блокируйся.

- Работать в ветке `ccr-9574af34-ocqfdd`; коммитить и пушить, PR не создавать без просьбы. Владелец сам подтягивает и собирает.
- Сборка: AGP 9.4, Gradle 9.8, JDK 21, compile/target SDK 36, minSdk 23. В облачной среде нет Android SDK и доступа к HuggingFace/docs.wallpaperengine.io;
  код проверяется компиляцией против `android-all` (см. `tools/jvm-check/build.gradle.kts`) и CI в GitHub Actions.
- Формулы эффектов живут в двух местах (`vision/app/effects.py` и `LiveMath.kt`); менять только вместе и пересоздавать `vision/tests/reference.json`.
- Веб-страницу проверять Playwright-тестами на подставном сервере (подход: см. историю коммитов), приложение на реальном проекторе — только у владельца.
