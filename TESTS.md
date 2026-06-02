# Запуск тестов FSO Terminal

Тесты делятся на два вида:

- **Синтетические** (без железа) — гоняются при обычной сборке, проверяют логику
  протокола на эмуляторе канала `FSOEmulator`.
- **Интеграционные** (`SerialLoopbackTest`) — требуют двух реальных COM-портов,
  замкнутых через STM32-мосты и FSO-канал. Включаются флагом `-Dserial.test.enabled=true`.

---

## Синтетические тесты (без железа)

```bat
gradlew.bat test
```

Гоняет всё, кроме `SerialLoopbackTest` (он скипается без флага). Состав:

| Тест | Что проверяет |
|---|---|
| `BulkProtocolSyntheticTest` | bulk-передача и MSG через `FSOEmulator` с потерями (с пейсингом и без) |
| `FrameCodecTest` | кодирование/декодирование кадра, CRC8, ресинхронизация |
| `FrameCodecStressTest` | декодер на разных размерах кусков (имитация дробления USB) |
| `FSOEmulatorTest` | модель потерь канала |

---

## Интеграционные тесты (реальные COM-порты)

### Подготовка
- Два STM32-моста, замкнутых через FSO-канал, отдают два COM-порта (напр. COM22 и COM25)
- Оба порта свободны (приложение FSOTerminal закрыто)

### Базовая команда (cmd.exe)

```bat
gradlew.bat test --tests "fsoterminal.SerialLoopbackTest.file_AtoB" --rerun-tasks ^
    -Dserial.test.enabled=true -Dserial.port.a=COM22 -Dserial.port.b=COM25
```

Вывод тестов виден прямо в консоли (`showStandardStreams = true` в build.gradle).

### PowerShell — `-D` аргументы в кавычках

```powershell
.\gradlew.bat test --tests "fsoterminal.SerialLoopbackTest.file_AtoB" --rerun-tasks `
    "-Dserial.test.enabled=true" "-Dserial.port.a=COM22" "-Dserial.port.b=COM25"
```

> Кириллица в выводе PowerShell: один раз за сессию `chcp 65001` +
> `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`.

### Git Bash

```bash
./gradlew test --tests "fsoterminal.SerialLoopbackTest.file_AtoB" --rerun-tasks \
    -Dserial.test.enabled=true -Dserial.port.a=COM22 -Dserial.port.b=COM25
```

### Шпаргалка по терминалам

| Терминал | Вызов Gradle | Перенос строки | Особенность |
|---|---|---|---|
| **cmd.exe** | `gradlew.bat` | `^` | Самый простой |
| **PowerShell** | `.\gradlew.bat` | `` ` `` | `-D` аргументы в `"кавычках"` |
| **Git Bash** | `./gradlew` | `\` | Unix-скрипт, не .bat |
| **WSL** | — | — | Не использовать (COM-порты ненадёжны) |

---

## Параметры интеграционных тестов

| Параметр | По умолчанию | Описание |
|---|---|---|
| `-Dserial.test.enabled` | — | `true` включает `SerialLoopbackTest` |
| `-Dserial.port.a` | COM10 | Порт узла A |
| `-Dserial.port.b` | COM11 | Порт узла B |
| `-Dtest.bytes=N` | 102400 | Объём файла для `file_*`, байт |
| `-Dbulk.overdrive=N` | 11 | Over-drive: на сколько мс быстрее пола слать кадры (0 = безопасно) |

---

## Список интеграционных тестов

| Тест | Что делает | Ожид. время |
|---|---|---|
| `file_AtoB` | Передача файла A→B (bulk-блоки), проверка байт-в-байт + печать скорости | ~60 с (100 КБ) |
| `file_BtoA` | То же B→A | ~60 с |
| `msg_AtoB` | Длинный текст A→B (фрагментация MSG), сверка | ~1–3 с |
| `msg_BtoA` | Короткий текст B→A | ~1 с |

Быстрая проверка на меньшем объёме: добавить `-Dtest.bytes=10240` (10 КБ, ~6 с).

**Пример вывода `file_AtoB`:**
```
  [A→B] 102400 Б за 60.0 с = 1707 байт/с
```

---

## Результаты (02.06.2026, COM22↔COM25, кадр 64 Б, over-drive 11 → задержка 20 мс)

| Направление | Объём | Скорость | % от теор. 2400 |
|---|---|---|---|
| A→B | 100 КБ | ~1672 байт/с | 69.6% |
| B→A | 100 КБ | ~1708 байт/с | 71.2% |

Подробный разбор подбора кадра/задержки и размера блока — в `docs/BULK_PROTOCOL_DESIGN.md`.
