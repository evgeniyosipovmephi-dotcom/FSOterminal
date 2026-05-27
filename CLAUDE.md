# FSOTerminal — документация проекта

## Стек
- **Java 21** (BellSoft Liberica Full JDK: `C:\Program Files\BellSoft\LibericaJDK-21-Full`)
- **Gradle 8.7** (wrapper в `gradle/wrapper/`)
- **JavaFX** — Liberica Full уже включает, отдельный плагин не нужен (добавим когда дойдём до UI)
- **JUnit 5** (`junit-jupiter:5.10.2`) — тестирование протокола

## Архитектурные решения

### Протокол: формат кадра
```
[SOF: 0x7E] [SEQ: 1B] [TYPE: 1B] [LEN: 1B] [PAYLOAD: 0–250B] [CRC8: 1B]
```
- **Итого:** максимум 255 байт — ровно под лимит физического кадра STM32
- **SOF = 0x7E** — маркер начала кадра; при сбое синхронизации декодер сканирует до следующего 0x7E
- **SEQ** — rolling 8-bit (0–255), автоинкремент отправителем
- **LEN** — длина PAYLOAD в байтах (0–250)
- **CRC8** — poly 0x07, init 0x00 (стандартный CRC-8)

### Почему CRC8, а не CRC16
STM32 (прозрачный мост) уже проверяет физические кадры своей CRC и отбрасывает испорченные.
CRC8 на прикладном уровне нужна только для ресинхронизации — найти настоящий SOF среди
мусора после потери кадра. Вероятность ложного срабатывания 1/256 приемлема при < 5%
потерь на FSO-канале. Экономия 1 байт даёт +1 байт к payload (250 вместо 249).

### Почему length-delimited, а не byte-stuffing
При потерях < 5% ресинхронизация случается редко. Length-delimited проще в реализации
на обеих сторонах и не имеет worst-case удвоения размера кадра (как byte-stuffing при
бинарных данных с 0x7D/0x7E).

### Flow control: credit-based
Отправитель держит `Semaphore(windowSize)`. Каждый отправленный кадр уменьшает кредит на 1.
ACK от получателя восстанавливает кредиты. Нет busy-wait, нет sleep, нет аппаратного
RTS/CTS (STM32 не поддерживает — прошивка не меняется).

### Размер окна
- **n = 4** для текстовых сообщений и голоса
- **n = 8** для файлов > 50 KB
- При росте скорости канала менять только `ProtocolConfig.windowSize`

### ACK/NACK: bitmap
```
[TYPE=0x02] [WINDOW_BASE: 1B] [BITMAP: 2B]   — 4 байта
```
Каждый бит = один кадр в окне (2 байта = 16-пакетное окно).
Бит 1 = получен, 0 = потерян.

## Типы кадров
| Код | Константа | Назначение |
|---|---|---|
| 0x01 | TYPE_DATA | данные |
| 0x02 | TYPE_ACK | подтверждение (WINDOW_BASE + BITMAP) |
| 0x03 | TYPE_PROBE | запрос состояния (нет ответа → сессия закрыта) |
| 0x04 | TYPE_PROBE_RESP | ответ на PROBE |
| 0x10 | TYPE_FILE_BEGIN | начало передачи файла (имя, размер) |
| 0x11 | TYPE_FILE_END | конец передачи файла |
| 0x20 | TYPE_VOICE | сегмент голосового сообщения |

## Структура исходного кода
```
src/main/java/fsoterminal/
├── protocol/       — чистая Java, нет JavaFX (задел на Android)
│   ├── FrameCodec.java             ✅ + тесты
│   ├── SlidingWindowSender.java    ✅ + тесты
│   ├── SlidingWindowReceiver.java  ✅ + тесты
│   ├── AckProcessor.java           ✅ + тесты
│   ├── TextAssembler.java          ✅ + тесты
│   └── FileAssembler.java          ✅
├── channel/
│   ├── FSOEmulator.java            ✅ + тесты
│   └── SerialChannel.java          ✅ (jSerialComm)
├── model/
│   └── ChatItem.java               ✅ (текст + файл с прогресс-свойством)
├── framesfx/
│   ├── MainWindowSC.java           ✅ контроллер главного окна
│   └── ChatCell.java               ✅ кастомная ячейка (текст + файл)
└── core/
    ├── Main.java                   ✅ точка входа
    └── FSOTerminalApp.java         ✅ JavaFX Application
src/main/resources/fsoterminal/
├── fxml/MainWindow.fxml            ✅
└── css/main.css                    ✅
```

## Статус реализации
| Компонент | Статус | Файл |
|---|---|---|
| FrameCodec (encode/decode/CRC8) | ✅ готово | `protocol/FrameCodec.java` |
| SlidingWindowSender | ✅ готово | `protocol/SlidingWindowSender.java` |
| AckProcessor | ✅ готово | `protocol/AckProcessor.java` |
| SlidingWindowReceiver | ✅ готово | `protocol/SlidingWindowReceiver.java` |
| FSOEmulator | ✅ готово | `channel/FSOEmulator.java` |
| ProtocolIntegrationTest | ✅ готово | `test/.../ProtocolIntegrationTest.java` |
| SerialChannel | ✅ готово | `channel/SerialChannel.java` |
| UI (JavaFX) — текстовый чат | ✅ готово | `framesfx/MainWindowSC.java` |
| TextAssembler (фрагментация текста) | ✅ готово | `protocol/TextAssembler.java` |
| FileAssembler (приём файла) | ✅ готово | `protocol/FileAssembler.java` |
| Передача файлов (📎, прогресс-бар) | ✅ готово | `framesfx/MainWindowSC.java` |
| Голосовые сообщения (🎤 запись/воспроизведение) | ✅ готово | `audio/AudioRecorder.java` |
| Превью изображений в чате | ✅ готово | `framesfx/ChatCell.java` |
| Drag-and-drop файлов в окно | ✅ готово | `framesfx/MainWindowSC.java` |
| Прогресс отправки текста | ✅ готово | `framesfx/ChatCell.java` |
| Portable .exe (jpackage) | ✅ готово | `build.gradle` задача `jpackage` |

### Протокол текстовых сообщений (TYPE_DATA payload)
```
[FLAG: 1B] [текст UTF-8...]
FLAG = 0xFF — промежуточный фрагмент (следует ещё)
FLAG = 0x00 — последний фрагмент (или единственный)
```
Максимум 249 байт текста на кадр (~124 кирилл. символа).
Длинные сообщения автоматически разбиваются на несколько кадров.
`TextAssembler` собирает фрагменты и доставляет полный текст.

### PROBE/PROBE_RESP
- Отправляется каждые 5 секунд при подключении
- Получатель автоматически отвечает PROBE_RESP
- Разрыв при тишине > 15 секунд (PROBE_INTERVAL_SEC × PROBE_MAX_MISS)
- Таймаут — по `lastReceivedMs`: обновляется на **любом** входящем байте (DATA, ACK, PROBE_RESP)
- ⚠️ Нельзя использовать счётчик пропусков — ACK при большой передаче не обновляли счётчик → ложный разрыв

### Сохранение настроек
`java.util.prefs.Preferences` (реестр Windows) — последний выбранный COM-порт восстанавливается при запуске.

### Важное: семантика WINDOW_BASE в ACK
`SlidingWindowReceiver` отправляет `WINDOW_BASE = next_expected` (следующий нужный кадр).
`SlidingWindowSender.onAck` интерпретирует это как: «все кадры до ackBase подтверждены неявно».
Проверка на устаревший ACK: `advance = (ackBase - base_rolling + 256) % 256 > windowSize → ignore`.

### AckProcessor: детали реализации
- Содержит `FrameCodec.Decoder` — хранит состояние между вызовами `feed()`
- ACK payload: `[WINDOW_BASE:1B][BITMAP_LOW:1B][BITMAP_HIGH:1B]` (little-endian bitmap)
- Некорректный ACK (payload < 3 байт) молча игнорируется
- Обработчики необязательны — если не установлен, кадр просто отбрасывается

### FSOEmulator: детали реализации
- Один экземпляр = одно направление; два экземпляра для двунаправленного тестирования
- `pass(frame)` → возвращает `frame` или `null` (потеря)
- `burstSize=1` — одиночные потери (по умолчанию); `burstSize=N` — N последовательных потерь при одном «событии»
- Детерминированный seed → тесты воспроизводимы

### SlidingWindowSender: детали реализации
- `base` и `next` — абсолютные счётчики (не rolling), rolling SEQ = `counter & 0xFF`
- Слот в буфере: `index % windowSize` — работает при `windowSize ≤ 128` (< половины SEQ-пространства)
- `onAck` с неверным `ackBase` молча игнорируется (защита от устаревших/чужих ACK)
- `retransmitUnconfirmed()` вызывается внешним таймером (таймер не встроен в класс намеренно — проще тестировать)
- `frameOutput` должен быть неблокирующим: `doSend()` вызывается под `synchronized`

## Ловушки и важные замечания
- Маска `& (N-1)` в кольцевом буфере работает только при `N = 2^k`. В декодере
  используется `% buf.length` — медленнее, но корректно для любого размера буфера.
- STM32F722 — **чёрный ящик**, прошивка не меняется. Вся протокольная логика только в Java.
- `0x7E` в payload не требует escaping — декодер читает ровно LEN байт после заголовка.

## Сборка и запуск тестов
```bat
set JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-21-Full
gradlew.bat test
```
