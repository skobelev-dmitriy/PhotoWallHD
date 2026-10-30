# HTTP API

Базовый адрес: `http://<ip-проектора>:8080`. Все ответы — JSON, кроме страницы и изображений.
Если в настройках задан PIN, к каждому запросу (кроме `/` и `/api/ping`) нужен заголовок `X-Pin: <pin>`
или параметр `?pin=<pin>`; иначе ответ `401`.

| Метод и путь | Описание |
|---|---|
| `GET /` | Страница управления. |
| `GET /api/ping` | `{"ok":true,"pin":false}`: проверка, что сервер жив, и нужен ли PIN. |
| `GET /api/state` | Фото (`name` — путь вида `Папка/файл.jpg`, `folder`, `size`, `modified`, `live` — есть ли анимация), `folders` (имя и число фото), `rootCount`, `playlists` (с числом фото `count`), `effectivePlaylist` (что играет сейчас), настройки, адрес, `active` (идёт ли показ), занятое и свободное место. |
| `GET /api/thumb/<имя>` | JPEG-миниатюра до 480 px (кэшируется); `?s=960` — до 960 px. |
| `GET /api/now` | Что на экране: `mode` (`slideshow`/`disco`/`idle`); для показа — `photo`, `paused`, `night`; для дискотеки — `effect`, `effectTitle`, `auto`, `source`. |
| `GET /api/frame` | Снимок экрана дискотеки (JPEG 640 px) для предпросмотра. Вызывайте не чаще 2 раз в секунду; ответ кэшируется на 400 мс. `404`, если дискотека не открыта. |
| `GET /photo/<имя>` | Оригинал. |
| `POST /api/upload` | Тело — **сам файл**, заголовок `X-Filename: <имя, URL-кодированное>`; необязательный `X-Folder: <папка, URL-кодированное>` — положить в папку (создаётся при необходимости). Ответ `{"name","size"}`. Ошибки: `400` пусто, `413` больше 300 МБ, `415` не изображение, `507` нет места. Если имя занято, добавляется ` (1)`. |
| `POST /api/live/analyze` | `{"name":"Папка/a.jpg"}` — проектор отправляет уменьшенное фото сервису распознавания (адрес `visionUrl`), сохраняет рецепт, маски, превью и возвращает слои. Может идти до минуты. `400` — адрес не задан, `502` — сервис недоступен. |
| `GET /api/live/recipe?name=` | Рецепт фото: `{exists, backend, layers:[{id, class, class_ru, effect, enabled, strength, confidence, area, note}], hasPreview, hasOverlay}`. |
| `POST /api/live/update` | `{"name","layers":[{"id","enabled","strength"}]}` — включить/выключить область, сила (1 = рекомендованная). |
| `POST /api/live/delete` | `{"name"}` — убрать анимацию. |
| `GET /api/live/preview?name=` · `GET /api/live/overlay?name=` | Анимированный предпросмотр (GIF) и картинка с областями (PNG), сохранённые при анализе. |
| `POST /api/folders` | `{"action":"create"\|"rename"\|"delete","name":"...","to":"...","deletePhotos":false}`. При `delete` без `deletePhotos` фото переезжают из папки наружу. `400` при ошибке (нет папки, имя занято). |
| `POST /api/move` | `{"names":["a.jpg","Море/b.jpg"],"folder":"Дети"}` — переместить фото в папку (`""` — наружу). Ответ `{"moved":N}`. |
| `POST /api/playlists` | `{"action":"save","playlist":{id,name,folders,photos,scheduleEnabled,from,to}}` (пустой `id` — создать), `{"action":"delete","id"}`, `{"action":"play","id"}` (`id` пустой — все фото; включает ручной режим). |
| `POST /api/delete` | `{"names":["a.jpg","b.jpg"]}`: удалить фото. |
| `POST /api/settings` | JSON с любыми полями из настроек, ответ — текущие настройки. Поле `pin` задаёт или снимает PIN (пустая строка). |
| `POST /api/audio` | Кадр звука из браузера для дискотеки: `{"b":[48 чисел],"w":[256 чисел],"r":число}` (полосы ×10000, волна −100..100, громкость ×10000). Ответ: `{"ok":true,"mode":"disco","source":"remote"}`. |
| `GET /sender` | Страница-отправитель звука (захват вкладки/системы в браузере). |
| `POST /api/control` | `{"action":"next"\|"prev"\|"pause"\|"resume"\|"toggle"\|"start"\|"disco"\|"dnext"\|"dprev"\|"stop"}`. `disco` запускает режим дискотеки, `dnext`/`dprev` меняют эффект, `stop` закрывает показ или дискотеку. В `/api/state` поле `mode`: `slideshow`, `disco` или `idle`. |

## Настройки (`/api/settings`)

`intervalSec` (3–86400), `transition` (`fade`/`slide`/`none`), `fit` (`fit`/`fill`/`auto`), `kenBurns`, `shuffle`,
`clock`, `clockPos` (`tl`/`tr`/`bl`/`br`/`bc`), `clockSize` (`s`/`m`/`l`), `clock24`, `clockDate`, `dim` (0–80 %),
`scheduleEnabled`, `onMinutes`, `offMinutes` (минуты от полуночи), `autostart`, `pin`, `activePlaylist` (id или `""`), `playlistMode` (`manual`/`schedule`), `visionUrl`, `live`, `liveStrength` (0–400 %).
Режим дискотеки: `discoSource` (`mic`/`output`/`demo`), `discoVisual` (`auto`/`bars`/`circle`/`rings`/`wave`/`lamps`/`fireworks`/`warp`/`hills`),
`discoAutoSec` (5–600), `discoSens` (40–400 %), `discoFlash`.

## Примеры

```bash
# загрузить фото
curl -X POST --data-binary @photo.jpg -H "X-Filename: photo.jpg" http://192.168.1.50:8080/api/upload

# следующее фото
curl -X POST -d '{"action":"next"}' http://192.168.1.50:8080/api/control

# интервал 1 минута
curl -X POST -d '{"intervalSec":60}' http://192.168.1.50:8080/api/settings
```

CORS: ответы `/api/*` разрешают запросы с любых адресов (нужно для страницы `/sender`, открытой из файла); `OPTIONS` отвечает 204. Поэтому задайте PIN.

Безопасность: сервер рассчитан на домашнюю сеть, трафик не шифруется. PIN защищает от случайных гостей,
но не от прослушивания. Не пробрасывайте порт в интернет.
