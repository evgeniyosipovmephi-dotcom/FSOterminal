package fsoterminal.framesfx;

import fsoterminal.audio.AudioRecorder;
import fsoterminal.channel.SerialChannel;
import fsoterminal.model.ChatItem;
import fsoterminal.protocol.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

public class MainWindowSC implements Initializable {

    @FXML private BorderPane        rootPane;
    @FXML private ComboBox<String>  portCombo;
    @FXML private Button            btnConnect;
    @FXML private Circle            statusDot;
    @FXML private Label             lblStatus;
    @FXML private Label             lblRtt;
    @FXML private ListView<ChatItem> chatList;
    @FXML private TextField         txtMessage;
    @FXML private Label             lblCharCount;
    @FXML private Button            btnSend;
    @FXML private Button            btnFile;
    @FXML private Button            btnVoice;

    private final ObservableList<ChatItem> chatItems = FXCollections.observableArrayList();

    // Протокол
    private SerialChannel            channel;
    private SlidingWindowSender      sender;
    private SlidingWindowReceiver    receiver;
    private AckProcessor             ackProc;
    private TextAssembler            assembler;
    private FileAssembler            fileAssembler;
    private ScheduledExecutorService protocolTimer;

    /** ChatItem входящего файла/изображения (для обновления прогресса). */
    private ChatItem incomingFileItem;

    /**
     * Активные потоки отправки файлов/текстов.
     * При disconnect() все прерываются, чтобы не зависать на trySend().
     */
    private final Set<Thread> activeSendThreads =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Голос
    private final AudioRecorder            audioRecorder = new AudioRecorder();
    private final ScheduledExecutorService voiceTimerExec =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voice-timer"); t.setDaemon(true); return t;
        });
    private ScheduledFuture<?> voiceCounterFuture;

    // PROBE и RTT
    private static final int PROBE_INTERVAL_SEC = 5;
    private static final int PROBE_MAX_MISS      = 3;
    private volatile long lastReceivedMs = 0;
    private volatile long probeSentMs    = 0;

    // Настройки
    private static final Preferences PREFS              = Preferences.userNodeForPackage(MainWindowSC.class);
    private static final String      PREF_PORT          = "lastPort";
    private static final String      PREF_WINDOW_SIZE   = "windowSize";
    private static final String      PREF_RETRANSMIT_MS = "retransmitMs";
    private static final String      PREF_PROBE_SEC     = "probeIntervalSec";
    private static final String      PREF_DOWNLOAD_PATH = "downloadPath";

    private final ProtocolConfig config = new ProtocolConfig();

    // =========================================================================
    // Инициализация
    // =========================================================================

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        chatList.setItems(chatItems);
        chatList.setCellFactory(lv -> new ChatCell());

        txtMessage.textProperty().addListener((obs, old, text) -> updateCharCount(text));
        txtMessage.setOnAction(e -> sendText());
        btnSend.setOnAction(e -> sendText());

        loadConfig();
        refreshPorts();
        loadLastPort();
        updateUiState(false);
        setupDragAndDrop();
    }

    // =========================================================================
    // Drag-and-Drop
    // =========================================================================

    private void setupDragAndDrop() {
        rootPane.setOnDragOver(this::onDragOver);
        rootPane.setOnDragDropped(this::onDragDropped);
        rootPane.setOnDragEntered(e -> {
            if (e.getDragboard().hasFiles() && channel != null && channel.isOpen())
                rootPane.setStyle("-fx-border-color: #0078d7; -fx-border-width: 2; -fx-border-insets: 2;");
        });
        rootPane.setOnDragExited(e -> rootPane.setStyle(""));
    }

    private void onDragOver(DragEvent e) {
        if (e.getDragboard().hasFiles() && channel != null && channel.isOpen())
            e.acceptTransferModes(TransferMode.COPY);
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        Dragboard db = e.getDragboard();
        boolean ok = false;
        if (db.hasFiles() && channel != null && channel.isOpen()) {
            for (File file : db.getFiles()) sendFile(file);
            ok = true;
        } else if (!db.hasFiles()) {
            addSystem("Подключитесь к порту перед отправкой файла");
        }
        rootPane.setStyle("");
        e.setDropCompleted(ok);
        e.consume();
    }

    // =========================================================================
    // Подключение
    // =========================================================================

    @FXML private void onRefreshPorts() { refreshPorts(); }

    @FXML
    private void onConnectClicked() {
        if (channel != null && channel.isOpen()) disconnect();
        else connect();
    }

    private void connect() {
        String portItem = portCombo.getValue();
        if (portItem == null || portItem.isBlank()) { addSystem("Выберите COM-порт"); return; }

        assembler = new TextAssembler(text -> Platform.runLater(() -> {
            chatItems.add(ChatItem.received(text));
            scrollToBottom();
        }));

        fileAssembler = new FileAssembler(
            (name, totalBytes) -> Platform.runLater(() -> {
                incomingFileItem = ChatItem.fileReceived(name, totalBytes);
                chatItems.add(incomingFileItem);
                scrollToBottom();
            }),
            (received, total) -> {
                if (total <= 0) return;
                double p = (double) received / total;
                ChatItem item = incomingFileItem;
                if (item != null) Platform.runLater(() -> item.setProgress(p));
            },
            (name, data) -> Platform.runLater(() -> {
                ChatItem item = incomingFileItem;
                incomingFileItem = null;
                saveReceivedFile(name, data, item);
            })
        );
        // Когда отправитель явно отменил передачу — убрать пузырь и показать сообщение
        fileAssembler.setOnCancel(() -> Platform.runLater(() -> {
            ChatItem item = incomingFileItem;
            incomingFileItem = null;
            if (item != null) chatItems.remove(item);
            addSystem("Передача файла отменена отправителем");
        }));

        int ws = config.windowSize;
        channel  = new SerialChannel();
        sender   = new SlidingWindowSender(ws, channel::send);
        ackProc  = new AckProcessor(sender);
        receiver = new SlidingWindowReceiver(ws, channel::send, this::dispatchFrame);
        ackProc.setDataHandler(receiver::onFrame);

        ackProc.setProbeHandler(() -> {
            byte[] resp = FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0]);
            SerialChannel ch = channel;
            if (ch != null) ch.send(resp);
        });
        ackProc.setProbeRespHandler(f -> {
            long rtt = System.currentTimeMillis() - probeSentMs;
            if (rtt > 0 && rtt < 60_000) {
                String rttText = "RTT: " + rtt + " мс";
                Platform.runLater(() -> lblRtt.setText(rttText));
            }
        });

        channel.setReceiveHandler(data -> {
            lastReceivedMs = System.currentTimeMillis();
            ackProc.feed(data);
        });

        if (channel.open(portItem, 115200)) {
            saveLastPort(portItem);
            lastReceivedMs = System.currentTimeMillis();
            startProtocolTimer();
            updateUiState(true);
            addSystem("Подключено к " + portItem +
                      " (окно: " + ws + " кадров)");
        } else {
            channel = null;
            addSystem("Не удалось открыть порт");
        }
    }

    /** Диспетчер упорядоченных кадров от SlidingWindowReceiver. */
    private void dispatchFrame(FrameCodec.Frame frame) {
        switch (frame.type) {
            case FrameCodec.TYPE_DATA                          -> assembler.onFrame(frame);
            case FrameCodec.TYPE_FILE_BEGIN,
                 FrameCodec.TYPE_FILE_DATA,
                 FrameCodec.TYPE_FILE_END,
                 FrameCodec.TYPE_FILE_CANCEL                  -> fileAssembler.onFrame(frame);
            case FrameCodec.TYPE_VOICE                        -> fileAssembler.onFrame(frame);
        }
    }

    private void disconnect() {
        if (audioRecorder.isRecording()) stopRecording();

        // Немедленно прерываем все потоки отправки:
        // trySend() бросит InterruptedException и разблокирует зависший поток.
        for (Thread t : activeSendThreads) t.interrupt();
        activeSendThreads.clear();

        stopProtocolTimer();
        if (assembler     != null) assembler.reset();
        if (fileAssembler != null) fileAssembler.reset();
        if (channel       != null) channel.close();
        channel = null; sender = null; receiver = null;
        ackProc = null; assembler = null; fileAssembler = null;
        // Если обрыв произошёл во время приёма файла — убрать незавершённый пузырь
        if (incomingFileItem != null) {
            chatItems.remove(incomingFileItem);
            incomingFileItem = null;
            addSystem("Передача файла прервана (разрыв соединения)");
        } else {
            incomingFileItem = null;
        }
        updateUiState(false);
    }

    // =========================================================================
    // Отправка текста
    // =========================================================================

    private void sendText() {
        if (sender == null || channel == null || !channel.isOpen()) return;
        String text = txtMessage.getText().trim();
        if (text.isEmpty()) return;
        txtMessage.clear();

        byte[][] payloads = TextAssembler.encodePayloads(text);
        int totalFrames = payloads.length;

        ChatItem item = (totalFrames > 1) ? ChatItem.sentMultiFrame(text) : ChatItem.sent(text);
        chatItems.add(item);
        scrollToBottom();

        SlidingWindowSender s = sender;
        Thread t = new Thread(() -> {
            activeSendThreads.add(Thread.currentThread());
            try {
                for (int i = 0; i < payloads.length; i++) {
                    if (!s.trySend(FrameCodec.TYPE_DATA, payloads[i], 5000)) {
                        Platform.runLater(() -> addSystem("Ошибка: канал занят"));
                        return;
                    }
                    if (totalFrames > 1) {
                        double p = (double)(i + 1) / totalFrames;
                        Platform.runLater(() -> item.setProgress(p));
                    }
                }
                if (totalFrames > 1)
                    Platform.runLater(() -> item.setProgress(1.0));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                activeSendThreads.remove(Thread.currentThread());
            }
        }, "fso-send");
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // Отправка файлов
    // =========================================================================

    @FXML
    private void onFileAttach() {
        if (sender == null || channel == null || !channel.isOpen()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл для отправки");
        File file = chooser.showOpenDialog(chatList.getScene().getWindow());
        if (file != null) sendFile(file);
    }

    private void sendFile(File file) {
        byte[] data;
        try { data = Files.readAllBytes(file.toPath()); }
        catch (Exception e) { addSystem("Ошибка чтения: " + e.getMessage()); return; }

        String name = file.getName();

        // Определяем тип и при необходимости сразу ставим savedPath (исправление #6 и превью)
        ChatItem item;
        if (ChatItem.isVoiceName(name)) {
            item = ChatItem.voiceSent(name, data.length);
            item.setSavedPath(file.getAbsolutePath()); // разрешить воспроизведение сразу
        } else if (ChatItem.isImageName(name)) {
            item = ChatItem.imageSent(name, data.length);
            item.setSavedPath(file.getAbsolutePath()); // показать превью сразу
        } else {
            item = ChatItem.fileSent(name, data.length);
            item.setSavedPath(file.getAbsolutePath()); // открывать оригинал по клику
        }
        chatItems.add(item);
        scrollToBottom();

        // Поддержка отмены
        AtomicBoolean cancelled  = new AtomicBoolean(false);
        Thread[]      threadRef  = { null };
        item.setCancelAction(() -> {
            cancelled.set(true);
            Thread th = threadRef[0];
            if (th != null) th.interrupt();
        });

        SlidingWindowSender s = sender;
        Thread t = new Thread(() -> {
            threadRef[0] = Thread.currentThread();
            activeSendThreads.add(Thread.currentThread());
            try {
                if (cancelled.get()) return;

                // Таймаут на каждый кадр: 30 сек — достаточно для восстановления
                // канала после кратковременной помехи (FSO: атмосферные флуктуации).
                final long frameTimeoutMs = 30_000;

                if (!s.trySend(FrameCodec.TYPE_FILE_BEGIN, encodeFileBegin(name, data.length), frameTimeoutMs)) {
                    Platform.runLater(() -> addSystem("Ошибка: канал недоступен (FILE_BEGIN)"));
                    return;
                }

                int offset = 0;
                while (offset < data.length) {
                    if (cancelled.get()) {
                        final String n = name;
                        Platform.runLater(() -> {
                            chatItems.remove(item);
                            addSystem("Отправка отменена: " + n);
                        });
                        return;
                    }
                    int len      = Math.min(FrameCodec.MAX_PAYLOAD, data.length - offset);
                    byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
                    if (!s.trySend(FrameCodec.TYPE_FILE_DATA, chunk, frameTimeoutMs)) {
                        Platform.runLater(() -> addSystem("Ошибка: канал недоступен (FILE_DATA)"));
                        return;
                    }
                    offset += len;
                    double p = (double) offset / data.length;
                    Platform.runLater(() -> item.setProgress(p));
                }

                if (!s.trySend(FrameCodec.TYPE_FILE_END, new byte[0], frameTimeoutMs)) {
                    Platform.runLater(() -> addSystem("Ошибка: канал недоступен (FILE_END)"));
                    return;
                }

                item.setCancelAction(null); // завершено — отмена недоступна
                Platform.runLater(() -> {
                    item.setProgress(1.0);
                    addSystem("Файл отправлен: " + name +
                              " (" + ChatItem.formatSize(data.length) + ")");
                });
            } catch (InterruptedException ex) {
                // Прерван кнопкой ✕ или disconnect()
                final String n = name;
                if (cancelled.get()) {
                    // Уведомляем получателя (best-effort: ждём кредита до 2 с)
                    try { s.trySend(FrameCodec.TYPE_FILE_CANCEL, new byte[0], 2000); }
                    catch (InterruptedException ignored) {}
                    Platform.runLater(() -> {
                        chatItems.remove(item);
                        addSystem("Отправка отменена: " + n);
                    });
                } else {
                    // Разрыв соединения — оставляем пузырь в ленте
                    Platform.runLater(() ->
                        addSystem("Передача прервана (разрыв соединения): " + n));
                }
                Thread.currentThread().interrupt();
            } finally {
                activeSendThreads.remove(Thread.currentThread());
                item.setCancelAction(null);
            }
        }, "fso-file-send");
        t.setDaemon(true);
        t.start();
    }

    /** FILE_BEGIN payload: [nameLen:1B][name UTF-8][totalSize:4B LE] */
    private static byte[] encodeFileBegin(String name, long totalBytes) {
        byte[] nb     = name.getBytes(StandardCharsets.UTF_8);
        int    nameLen = Math.min(nb.length, 245);
        byte[] p      = new byte[1 + nameLen + 4];
        p[0] = (byte) nameLen;
        System.arraycopy(nb, 0, p, 1, nameLen);
        int sz    = (int) Math.min(totalBytes, 0xFFFFFFFFL);
        p[1+nameLen] = (byte)(sz);
        p[2+nameLen] = (byte)(sz >> 8);
        p[3+nameLen] = (byte)(sz >> 16);
        p[4+nameLen] = (byte)(sz >> 24);
        return p;
    }

    /** Сохраняет принятый файл в папку загрузок, обновляет ChatItem. */
    private void saveReceivedFile(String name, byte[] data, ChatItem item) {
        try {
            Path downloads = getDownloadPath();

            // Уникальное имя
            Path dest = downloads.resolve(name);
            if (Files.exists(dest)) {
                int    dot  = name.lastIndexOf('.');
                String base = (dot > 0) ? name.substring(0, dot) : name;
                String ext  = (dot > 0) ? name.substring(dot)    : "";
                for (int n = 1; Files.exists(dest); n++)
                    dest = downloads.resolve(base + " (" + n + ")" + ext);
            }
            Files.write(dest, data);

            Path finalDest = dest;
            if (item != null) {
                item.setSavedPath(finalDest.toString());
                item.setProgress(1.0);
            }
            addSystem("Получен: " + finalDest.getFileName() +
                      " → " + downloads.toAbsolutePath() +
                      " (" + ChatItem.formatSize(data.length) + ")");

        } catch (Exception e) {
            addSystem("Ошибка сохранения файла: " + e.getMessage());
        }
    }

    /** Возвращает текущую папку для сохранения файлов. */
    private Path getDownloadPath() {
        String dp = config.downloadPath;
        if (dp != null && !dp.isBlank()) {
            Path p = Paths.get(dp);
            if (Files.isDirectory(p)) return p;
        }
        Path dl = Paths.get(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(dl)) return dl;
        return Paths.get(System.getProperty("user.home"));
    }

    // =========================================================================
    // Голосовые сообщения
    // =========================================================================

    @FXML
    private void onVoiceClicked() {
        if (audioRecorder.isRecording()) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        try {
            audioRecorder.start();
            btnVoice.setText("⏹ 0:00");
            btnVoice.setStyle("-fx-text-fill: red;");

            voiceCounterFuture = voiceTimerExec.scheduleAtFixedRate(() ->
                Platform.runLater(() -> {
                    if (audioRecorder.isRecording())
                        btnVoice.setText("⏹ " +
                            AudioRecorder.formatDuration(audioRecorder.elapsedSeconds()));
                }),
                1, 1, TimeUnit.SECONDS
            );
        } catch (Exception e) {
            addSystem("Микрофон недоступен: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (voiceCounterFuture != null) { voiceCounterFuture.cancel(false); voiceCounterFuture = null; }
        btnVoice.setText("🎤");
        btnVoice.setStyle("");

        Thread t = new Thread(() -> {
            try {
                byte[] wav = audioRecorder.stop();
                if (wav.length <= 44) return; // пустая запись

                String name = "voice_" + System.currentTimeMillis() + ".wav";
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("fso_voice_", ".wav");
                java.nio.file.Files.write(tmp, wav);

                Platform.runLater(() -> sendFile(tmp.toFile()));
            } catch (Exception e) {
                Platform.runLater(() -> addSystem("Ошибка записи: " + e.getMessage()));
            }
        }, "voice-stop");
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // Таймеры протокола
    // =========================================================================

    private void startProtocolTimer() {
        protocolTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "protocol-timer");
            t.setDaemon(true); return t;
        });

        // Ретрансмиссия по таймауту — fallback для хвостовых потерянных кадров:
        // onAck() перепосылает только кадры с пробелом (gap-based selective repeat);
        // таймер срабатывает когда окно не продвигалось дольше retransmitInterval мс
        // (ACK за хвостовой кадр так и не пришёл).
        int rtMs = config.retransmitIntervalMs;
        protocolTimer.scheduleAtFixedRate(() -> {
            SlidingWindowSender s = sender;
            if (s == null || s.inFlight() == 0) return;
            long noAdvanceMs = System.currentTimeMillis() - s.getLastAckAdvanceMs();
            if (noAdvanceMs >= rtMs) s.retransmitUnconfirmed();
        }, rtMs, rtMs, TimeUnit.MILLISECONDS);

        // Первый PROBE — через 2 секунды (чтобы RTT появился быстро),
        // потом каждые probeIntervalSec секунд.
        protocolTimer.schedule(this::onProbeTimer, 2, TimeUnit.SECONDS);
        protocolTimer.scheduleAtFixedRate(this::onProbeTimer,
            config.probeIntervalSec, config.probeIntervalSec, TimeUnit.SECONDS);
    }

    private void stopProtocolTimer() {
        if (protocolTimer != null) { protocolTimer.shutdownNow(); protocolTimer = null; }
    }

    private void onProbeTimer() {
        SerialChannel ch = channel;
        if (ch == null || !ch.isOpen()) return;
        long silentMs  = System.currentTimeMillis() - lastReceivedMs;
        long timeoutMs = (long) config.probeIntervalSec * 1000L * config.probeMaxMiss;
        if (silentMs > timeoutMs) {
            Platform.runLater(() -> { addSystem("Соединение потеряно (нет ответа)"); disconnect(); });
            return;
        }
        probeSentMs = System.currentTimeMillis();
        ch.send(FrameCodec.encode(0, FrameCodec.TYPE_PROBE, new byte[0]));
    }

    // =========================================================================
    // Меню: Настройки
    // =========================================================================

    @FXML
    private void onSettings() {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Параметры протокола");
        dlg.setHeaderText("Настройки FSO Terminal");
        dlg.initOwner(chatList.getScene().getWindow());
        dlg.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 20, 10, 10));

        // Размер окна
        Spinner<Integer> spWindow = new Spinner<>(1, 16, config.windowSize);
        spWindow.setEditable(true);
        spWindow.setPrefWidth(80);

        // Интервал ретрансмиссии
        Spinner<Integer> spRetransmit = new Spinner<>(100, 5000, config.retransmitIntervalMs, 100);
        spRetransmit.setEditable(true);
        spRetransmit.setPrefWidth(90);

        // Интервал PROBE
        Spinner<Integer> spProbe = new Spinner<>(1, 60, config.probeIntervalSec);
        spProbe.setEditable(true);
        spProbe.setPrefWidth(80);

        // Папка сохранения
        TextField tfDownload = new TextField(getDownloadPath().toString());
        tfDownload.setPrefWidth(280);
        HBox.setHgrow(tfDownload, Priority.ALWAYS);
        Button btnBrowse = new Button("…");
        btnBrowse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Выберите папку для сохранения файлов");
            try { dc.setInitialDirectory(new File(tfDownload.getText())); } catch (Exception ignored) {}
            File dir = dc.showDialog(dlg.getOwner());
            if (dir != null) tfDownload.setText(dir.getAbsolutePath());
        });
        HBox pathRow = new HBox(5, tfDownload, btnBrowse);

        grid.addRow(0, new Label("Размер окна:"),         spWindow,     new Label("кадров (1–16)"));
        grid.addRow(1, new Label("Ретрансмиссия:"),       spRetransmit, new Label("мс"));
        grid.addRow(2, new Label("Интервал PROBE:"),      spProbe,      new Label("сек"));
        grid.addRow(3, new Label("Папка сохранения:"),    pathRow);

        // Подсказка о применении
        Label hint = new Label("⚠ Изменения вступают в силу при следующем подключении.");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #888888;");
        grid.add(hint, 0, 4, 3, 1);

        dlg.getDialogPane().setContent(grid);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try { config.windowSize = spWindow.getValue(); } catch (Exception ignored) {}
            try { config.retransmitIntervalMs = spRetransmit.getValue(); } catch (Exception ignored) {}
            try { config.probeIntervalSec     = spProbe.getValue();      } catch (Exception ignored) {}
            String path = tfDownload.getText().trim();
            config.downloadPath = path.isEmpty() ? null : path;
            saveConfig();
            addSystem("Настройки сохранены." +
                      (channel != null && channel.isOpen() ? " Переподключитесь для применения." : ""));
        }
    }

    // =========================================================================
    // Меню: Справка
    // =========================================================================

    @FXML
    private void onHelp() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("О программе FSO Terminal");
        alert.setHeaderText("FSO Terminal  —  оптический терминал связи");
        alert.initOwner(chatList.getScene().getWindow());

        String savePath = getDownloadPath().toAbsolutePath().toString();
        String text =
            "РАБОТА С ПРОГРАММОЙ:\n" +
            "  1. Выберите COM-порт и нажмите «Подключить»\n" +
            "  2. Введите сообщение и нажмите «Отправить» или Enter\n" +
            "  3. Отправка файлов: кнопка 📎 или перетащите файл в окно\n" +
            "  4. Голос: нажмите 🎤 для записи, снова 🎤 (⏹) для остановки\n" +
            "  5. Отмена отправки файла: нажмите ✕ рядом с файлом\n\n" +
            "ПАПКА ДЛЯ ПРИНЯТЫХ ФАЙЛОВ:\n" +
            "  " + savePath + "\n" +
            "  (изменить: меню Настройки → Параметры протокола)\n\n" +
            "ПРОТОКОЛ:\n" +
            "  Sliding Window ARQ, окно " + config.windowSize + " кадров\n" +
            "  Кадр: [SOF 0x7E][SEQ][TYPE][LEN][PAYLOAD≤250][CRC8]\n" +
            "  Скорость канала: ~24 кбит/с, RTT ≈ 130 мс\n\n" +
            "СОВМЕСТИМОСТЬ:\n" +
            "  STM32F722 (прозрачный мост USB-CDC ↔ FSO) — изменений не требует";

        alert.setContentText(text);
        alert.getDialogPane().setPrefWidth(520);
        alert.showAndWait();
    }

    // =========================================================================
    // Настройки (загрузка / сохранение)
    // =========================================================================

    private void loadConfig() {
        config.windowSize          = PREFS.getInt(PREF_WINDOW_SIZE,   8);
        config.retransmitIntervalMs = PREFS.getInt(PREF_RETRANSMIT_MS, 400);
        config.probeIntervalSec    = PREFS.getInt(PREF_PROBE_SEC,      5);
        String dp = PREFS.get(PREF_DOWNLOAD_PATH, "");
        config.downloadPath = (dp == null || dp.isBlank()) ? null : dp;
    }

    private void saveConfig() {
        PREFS.putInt(PREF_WINDOW_SIZE,   config.windowSize);
        PREFS.putInt(PREF_RETRANSMIT_MS, config.retransmitIntervalMs);
        PREFS.putInt(PREF_PROBE_SEC,     config.probeIntervalSec);
        if (config.downloadPath != null && !config.downloadPath.isBlank())
            PREFS.put(PREF_DOWNLOAD_PATH, config.downloadPath);
        else
            PREFS.remove(PREF_DOWNLOAD_PATH);
    }

    private void saveLastPort(String portItem) { PREFS.put(PREF_PORT, portItem); }

    private void loadLastPort() {
        String saved = PREFS.get(PREF_PORT, null);
        if (saved == null) return;
        String savedSys = saved.contains(" — ") ? saved.substring(0, saved.indexOf(" — ")) : saved;
        for (String item : portCombo.getItems()) {
            String itemSys = item.contains(" — ") ? item.substring(0, item.indexOf(" — ")) : item;
            if (itemSys.equals(savedSys)) { portCombo.setValue(item); return; }
        }
    }

    // =========================================================================
    // UI helpers
    // =========================================================================

    private void refreshPorts() {
        String prev = portCombo.getValue();
        portCombo.getItems().setAll(SerialChannel.availablePorts());
        if (prev != null && portCombo.getItems().contains(prev)) portCombo.setValue(prev);
        else if (!portCombo.getItems().isEmpty()) portCombo.getSelectionModel().selectFirst();
    }

    private void updateUiState(boolean connected) {
        Platform.runLater(() -> {
            statusDot.setFill(connected ? Color.LAWNGREEN : Color.TOMATO);
            lblStatus.setText(connected ? "Подключено" : "Не подключено");
            btnConnect.setText(connected ? "Отключить" : "Подключить");
            btnSend.setDisable(!connected);
            btnFile.setDisable(!connected);
            btnVoice.setDisable(!connected || !AudioRecorder.isMicAvailable());
            txtMessage.setDisable(!connected);
            lblRtt.setText(connected ? "RTT: —" : "");
        });
    }

    private void updateCharCount(String text) {
        if (text == null || text.isBlank()) { lblCharCount.setText(""); return; }
        int frames = TextAssembler.frameCount(text);
        lblCharCount.setText(frames > 1
            ? text.length() + " симв. / " + frames + " кадра"
            : text.length() + " симв.");
    }

    private void addSystem(String msg) {
        Platform.runLater(() -> { chatItems.add(ChatItem.system(msg)); scrollToBottom(); });
    }

    private void scrollToBottom() {
        if (!chatItems.isEmpty()) chatList.scrollTo(chatItems.size() - 1);
    }

    public void onClose() { disconnect(); }
}
