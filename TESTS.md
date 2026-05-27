# Запуск тестов — FSOTerminal

## Быстрый старт

```
gradlew.bat test
```

Запускает все синтетические тесты (без железа). Отчёт:
`build/reports/tests/test/index.html`

---

## Синтетические тесты (без железа)

Это основная группа — тестирует протокол через эмулятор канала в памяти.

### Запустить все

```
gradlew.bat test
```

### Запустить один класс

```
gradlew.bat test --tests "fsoterminal.ProtocolIntegrationTest"
gradlew.bat test --tests "fsoterminal.protocol.SlidingWindowSenderTest"
gradlew.bat test --tests "fsoterminal.protocol.AckProcessorTest"
gradlew.bat test --tests "fsoterminal.protocol.SlidingWindowReceiverTest"
gradlew.bat test --tests "fsoterminal.protocol.FrameCodecTest"
gradlew.bat test --tests "fsoterminal.channel.FSOEmulatorTest"
```

### Запустить один тест

```
gradlew.bat test --tests "fsoterminal.ProtocolIntegrationTest.fileTransfer_10KB_efficiencyCheck"
```

---

## Что тестирует каждый класс

| Класс | Что проверяет |
|---|---|
| `FrameCodecTest` | Кодирование/декодирование кадра, CRC, sync recovery |
| `SlidingWindowSenderTest` | Семафор кредитов, SEQ, подтверждения, ретрансмит |
| `SlidingWindowReceiverTest` | Приём в окно, дедупликация, ACK bitmap |
| `AckProcessorTest` | Диспетчеризация ACK/DATA/PROBE кадров |
| `FSOEmulatorTest` | Точность потерь, burst-режим, детерминизм по seed |
| `ProtocolIntegrationTest` | End-to-end: два узла через эмулятор |

### Сценарии в ProtocolIntegrationTest

| Тест | Описание |
|---|---|
| `perfectChannel_allMessagesDelivered` | 0% потерь, 20 сообщений |
| `noisyChannel_allMessagesDeliveredReliably` | 10% потерь данных + 5% потерь ACK |
| `burstLoss_allMessagesDelivered` | Пакетные потери (3 подряд, 20%) |
| `bidirectional_bothSidesReceiveAllMessages` | Двустороннее, 5% потерь |
| `fileTransfer_5percentLoss_fileAssembledCorrectly` | Файл 2 KB, 5% потерь |
| `fileTransfer_10KB_efficiencyCheck` | Файл 10 KB, считает overhead ретрансмитов |

Тест `fileTransfer_10KB_efficiencyCheck` выводит в консоль:
```
[10KB efficiency] Кадров нужно: 43 | Отправлено всего: 50 | Overhead: +7 (16,3%)
[10KB efficiency] Теор. время: 3,66 с | Симул. время: 0,91 с | Раундов: 7
```

---

## Аппаратные тесты (COM-порты)

Нужны два USB-UART адаптера, соединённых накрест:
```
COM10 TX ----> COM11 RX
COM10 RX <---- COM11 TX
```

По умолчанию тесты **пропускаются**. Включить флагом:

```
gradlew.bat test -Dserial.test.enabled=true
```

Другие порты:

```
gradlew.bat test -Dserial.test.enabled=true -Dserial.port.a=COM3 -Dserial.port.b=COM4
```

### Один конкретный тест

```
gradlew.bat test -Dserial.test.enabled=true --tests "fsoterminal.SerialLoopbackTest.fileTransfer_10KB_AtoB"
```

### Что тестирует SerialLoopbackTest

| Тест | Описание | Ожидаемое время |
|---|---|---|
| `singleMessage_AtoB` | Короткое сообщение A → B | < 0.5 с |
| `largeText_multiFrame` | Текст ~600 байт (3 кадра) A → B | < 0.5 с |
| `bidirectional_10messages` | По 10 сообщений в обе стороны | < 2 с |
| `fileTransfer_4KB_AtoB` | Файл 4 KB, байт-в-байт проверка | < 2 с |
| `fileTransfer_10KB_AtoB` | Файл 10 KB, выводит скорость в байт/с | < 5 с |

Тест `fileTransfer_10KB_AtoB` выводит:
```
[10KB serial] Время: 0,94 с | Скорость: 10917 байт/с
```
При 115200 бод теоретический максимум ≈ 11 520 байт/с. Результат ~95% — норма.

---

## Посмотреть вывод тестов в консоли

По умолчанию Gradle скрывает `System.out` тестов. Чтобы видеть:

```
gradlew.bat test --info 2>&1 | findstr /I "STANDARD_OUT efficiency serial"
```

Или открыть HTML-отчёт после прогона:
```
build\reports\tests\test\index.html
```
