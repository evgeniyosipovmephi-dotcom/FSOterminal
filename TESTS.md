# Запуск тестов FSO Terminal

## Подготовка

- COM25 подключён к STM32 (оптика на COM22)
- COM22 подключён к COM25 обратным проводом
- Оба порта свободны (приложение FSOTerminal закрыто)

---

## Из какого терминала запускать

Тесты работают из **любого** терминала Windows. Различается только синтаксис переноса строки и способ вызова Gradle.

### cmd.exe — рекомендуется

Стандартная командная строка Windows. Самый простой вариант.

```bat
gradlew.bat test -Dserial.test.enabled=true -Dserial.port.a=COM25 -Dserial.port.b=COM22 ^
    --tests "fsoterminal.SerialLoopbackTest.bulk_100KB_clean_AtoB" --rerun-tasks
```

- Перенос строки: `^` в конце строки (без пробела после `^`)
- Вызов: `gradlew.bat` (или просто `gradlew`)
- Открыть: `Win+R` → `cmd` → перейти в папку проекта

### PowerShell (Windows PowerShell 5.1 или PowerShell 7)

Встроен в Windows, также открывается через Windows Terminal.

```powershell
.\gradlew.bat test `
    "-Dserial.test.enabled=true" `
    "-Dserial.port.a=COM25" `
    "-Dserial.port.b=COM22" `
    --tests "fsoterminal.SerialLoopbackTest.bulk_100KB_clean_AtoB" `
    --rerun-tasks
```

- Перенос строки: `` ` `` (backtick, не `^`)
- Вызов: `.\gradlew.bat` (точка-слэш обязательна — иначе PowerShell ищет команду, а не файл)
- **`-D` аргументы — в кавычках**: PowerShell видит `=` в аргументе и пытается его разобрать сам. Без кавычек `enabled=true` может отвалиться молча
- **Кириллица в выводе** — перед первым запуском в сессии выполни:
  ```powershell
  chcp 65001
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  ```
  Или добавь эти две строки в свой PowerShell-профиль (`$PROFILE`), чтобы не вводить каждый раз.
- Открыть: `Win+X` → Windows PowerShell

### Git Bash

Устанавливается вместе с Git for Windows. Unix-синтаксис на Windows.

```bash
./gradlew test -Dserial.test.enabled=true -Dserial.port.a=COM25 -Dserial.port.b=COM22 \
    --tests "fsoterminal.SerialLoopbackTest.bulk_100KB_clean_AtoB" --rerun-tasks
```

- Перенос строки: `\` (обратный слэш)
- Вызов: `./gradlew` (Unix-скрипт без `.bat`)
- COM-порты: указываются как обычно — `COM25`, `COM22` (не `/dev/ttyS25`)
- Открыть: правая кнопка мыши в папке проекта → Git Bash Here

> **Если Git Bash не находит Java:** добавь в `~/.bashrc`:
> ```bash
> export JAVA_HOME="C:/Program Files/BellSoft/LibericaJDK-21-Full"
> ```

### Windows Terminal

Это просто оболочка-вкладки. Внутри работает cmd, PowerShell или Git Bash — используй синтаксис соответствующего терминала. Открыть нужную вкладку: `+` → выбрать нужный.

### IDE-терминал (Eclipse, IntelliJ, VS Code)

Встроенный терминал в IDE — это тот же PowerShell или cmd. Используй синтаксис PowerShell (`` ` `` для переноса, `.\gradlew.bat`). Преимущество: не нужно переходить в папку проекта — IDE сам открывает терминал в корне проекта.

### WSL (Windows Subsystem for Linux) — не подходит

WSL видит COM-порты только частично и ненадёжно. Для serial-тестов не использовать.

---

## Особенности разных терминалов: шпаргалка

| Терминал | Вызов Gradle | Перенос строки | Особенность |
|---|---|---|---|
| **cmd.exe** | `gradlew.bat` | `^` | Самый простой |
| **PowerShell** | `.\gradlew.bat` | `` ` `` | Точка-слэш обязательна; `-D` аргументы в `"кавычках"` |
| **Git Bash** | `./gradlew` | `\` | Unix-скрипт, не .bat |
| **Windows Terminal** | зависит от вкладки | зависит от вкладки | Обёртка над cmd/PS/Bash |
| **IDE-терминал** | `.\gradlew.bat` | `` ` `` | Обычно PowerShell |
| **WSL** | — | — | Не использовать для serial |

> **Совет:** если команда длинная и не хочется думать о переносах — пиши в одну строку без `^` / `` ` `` / `\`. Gradle читает все параметры одинаково.

---

## Базовая команда (cmd.exe)

```bat
gradlew.bat test -Dserial.test.enabled=true -Dserial.port.a=COM25 -Dserial.port.b=COM22 ^
    --tests "fsoterminal.SerialLoopbackTest.ИМЯ_ТЕСТА" --rerun-tasks
```

Вывод тестов виден прямо в консоли (`showStandardStreams = true` в build.gradle).

---

## Параметры из командной строки

| Параметр | По умолчанию | Описание |
|---|---|---|
| `-Dtest.bytes=N` | 10240 / 102400 / 51200 | Объём данных (байт), зависит от теста |
| `-Dbulk.payload=N` | 238 (clean) / 200 (noisy) | Байт данных в bulk-кадре |
| `-Dbulk.delay=N` | авто по формуле | Задержка между кадрами, мс |
| `-Dwindow.payload=N` | 240 | Байт данных в WINDOW=1 кадре |

**Примеры (cmd.exe):**

```bat
:: Быстрая проверка bulk на 10 KB вместо 100 KB
gradlew.bat test -Dserial.test.enabled=true -Dserial.port.a=COM25 -Dserial.port.b=COM22 ^
    -Dtest.bytes=10240 ^
    --tests "fsoterminal.SerialLoopbackTest.bulk_clean_AtoB" --rerun-tasks

:: Подобрать payload и задержку вручную
gradlew.bat test -Dserial.test.enabled=true -Dserial.port.a=COM25 -Dserial.port.b=COM22 ^
    -Dtest.bytes=10240 -Dbulk.payload=128 -Dbulk.delay=95 ^
    --tests "fsoterminal.SerialLoopbackTest.bulk_clean_AtoB" --rerun-tasks
```

**Примеры (PowerShell) — все `-D` аргументы в кавычках:**

```powershell
.\gradlew.bat test `
    "-Dserial.test.enabled=true" `
    "-Dserial.port.a=COM25" `
    "-Dserial.port.b=COM22" `
    "-Dtest.bytes=10240" `
    "-Dbulk.payload=128" `
    "-Dbulk.delay=95" `
    --tests "fsoterminal.SerialLoopbackTest.bulk_clean_AtoB" `
    --rerun-tasks
```

Если `-Dbulk.delay` задан — в выводе появится строка `[Override] задержка: 117 мс → 95 мс`.

---

## Все тесты

### Сообщения (WINDOW=1 текст, ~1–2 с)

| Тест | Что делает |
|---|---|
| `msg_AtoB` | Одно сообщение A→B |
| `msg_BtoA` | Одно сообщение B→A |
| `msg_bidirectional` | 10 сообщений одновременно в обе стороны |

---

### Передача данных (WINDOW=1)

| Тест | Дефолт | Ожид. время |
|---|---|---|
| `window_AtoB` | 10 КБ | ~6 с |
| `window_BtoA` | 10 КБ | ~6 с |

Переопределить: `-Dtest.bytes=102400` → 100 КБ (~60 с)

**Пример вывода:**
```
────────────────────────────────────────────────────────────
  WINDOW=1 10KB A→B | 240 Б/кадр | 10240 байт
  Время: 5.72 с | Скорость: 1790 байт/с (74.6% от 2400)
  Ретрансмитов: 0 — канал чистый
────────────────────────────────────────────────────────────
```

---

### Bulk-протокол: чистый канал

| Тест | Дефолт | Ожид. время |
|---|---|---|
| `bulk_clean_AtoB` | 100 КБ | ~50–65 с |
| `bulk_clean_BtoA` | 100 КБ | ~50–65 с |

Переопределить: `-Dtest.bytes=10240` → 10 КБ (~6 с) — удобно для быстрой диагностики

**Пример нормального вывода:**
```
────────────────────────────────────────────────────────────
  BULK 100KB clean A→B | 238 Б/кадр | 102400 байт | 0% шум
  Кадров: 431 | Задержка: 117 мс/кадр | Теор.: 2400 байт/с
────────────────────────────────────────────────────────────
  Раунд 1 — отправляем 431 кадров...
  Раунд 1 — DONE ✓
────────────────────────────────────────────────────────────
  Время:      50.45 с
  Скорость:   2030 байт/с (84.6% от теор. 2400)
  Раунды:     1 | Ретрансм.: 0/431 | Дубли RX: 0
  Байт→Decoder: 111213 (ожид. ≈ 111213, разница: 0)
  Данные:     OK (102400 байт совпадают)
────────────────────────────────────────────────────────────
```

**Диагностика 3 раундов:**
- `Байт→Decoder` << ожидаемого → jSerialComm теряет байты (USB-драйвер, не оптика)
- `Байт→Decoder` ≈ ожидаемому, но кадры пропущены → CRC-ресинхронизация в Decoder
- `Пропущены кадры: 0 107 215` (регулярные интервалы) → проблема с таймингом каждые N кадров
- `Пропущены кадры: 3 47 289` (случайные) → редкие ложные CRC или реальная оптическая потеря

---

### Bulk-протокол: 50% шум

| Тест | Дефолт | Ожид. время |
|---|---|---|
| `bulk_noisy50_AtoB` | 50 КБ | ~60–70 с |
| `bulk_noisy50_BtoA` | 50 КБ | ~60–70 с |

**Норма:** 1–2 раунда, < 5% ретрансмитов, скорость ~850–950 байт/с.

---

### Sweep-тесты (несколько минут)

| Тест | Описание | Ожид. время |
|---|---|---|
| `sweep_window_3KB` | WINDOW=1 по 10 размерам кадра, 3 KB/точка | ~3 мин |
| `sweep_bulk_clean_10KB` | Bulk чистый, 6 размеров, 10 KB/точка | ~10 мин |
| `sweep_bulk_noisy50_10KB` | Bulk 50% шум, 6 размеров, 10 KB/точка | ~15 мин |

---

## Запуск нескольких тестов подряд

```bat
gradlew.bat test -Dserial.test.enabled=true -Dserial.port.a=COM25 -Dserial.port.b=COM22 ^
    --tests "fsoterminal.SerialLoopbackTest.msg_AtoB" ^
    --tests "fsoterminal.SerialLoopbackTest.window_AtoB" ^
    --tests "fsoterminal.SerialLoopbackTest.bulk_clean_AtoB" ^
    --rerun-tasks
```

---

## Синтетические тесты (без железа)

```bat
gradlew.bat test --tests "fsoterminal.BulkVsWindowTest"
```

---

## Результаты (01.06.2026, COM25→COM22)

| Протокол | Канал | Скорость | % от теор. |
|---|---|---|---|
| WINDOW=1, 240 Б | Чистый | **1791 байт/с** | 74.6% |
| Bulk, 238 Б, delay=117мс | Чистый | TBD | TBD |
| Bulk, 200 Б, 50% шум | Загруженный | 877 байт/с | 73.1% от 1200 |
