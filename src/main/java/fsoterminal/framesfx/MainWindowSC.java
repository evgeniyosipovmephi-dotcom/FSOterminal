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
import java.util.Optional;
import java.util.ResourceBundle;
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

    // Протокол (bulk + MSG)
    private SerialChannel            channel;
    private PacedTransmitter         tx;
    private BulkSender               bulkSender;
    private BulkReceiver             bulkReceiver;
    private MsgChannel               msgChannel;
    private FrameCodec.Decoder       rxDecoder;
    private ScheduledExecutorService protocolTimer;

    /** ChatItem входящего файла/изображения/голоса (для обновления прогресса). */
    private ChatItem incomingFileItem;

    /** Одна исходящая bulk-передача за раз. */
    private final AtomicBoolean sendingFile = new AtomicBoolean(false);

    // Голос
    private final AudioRecorder            audioRecorder = new AudioRecorder();
    private final ScheduledExecutorService voiceTimerExec =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "voice-timer"); t.setDaemon(true); return t;
        });
    private ScheduledFuture<?> voiceCounterFuture;

    // PROBE и RTT
    private volatile long lastReceivedMs = 0;
    private volatile long probeSentMs    = 0;

    // Настройки
    private static final Preferences PREFS              = Preferences.userNodeForPackage(MainWindowSC.class);
    private static final String      PREF_PORT          = "lastPort";
    private static final String      PREF_OVERDRIVE     = "bulkOverdriveMs";
    private static final String      PREF_PROBE_SEC     = "probeIntervalSec";
    private static final String      PREF_PROBE_MISS    = "probeMaxMiss";
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

        channel   = new SerialChannel();
        tx        = new PacedTransmitter(channel::send);
        long delay = BulkProtocol.pacingMs(
            BulkProtocol.dataFrameLen(BulkProtocol.DATA_BYTES), config.bulkOverdriveMs);
        tx.setDataDelayMs(delay);
        rxDecoder = new FrameCodec.Decoder();

        bulkSender = new BulkSender(tx, config);

        msgChannel = new MsgChannel(tx, text -> Platform.runLater(() -> {
            chatItems.add(ChatItem.received(text));
            scrollToBottom();
        }));

        bulkReceiver = new BulkReceiver(tx,
            (kind, name, data) -> Platform.runLater(() -> {
                ChatItem item = incomingFileItem;
                incomingFileItem = null;
                saveReceivedFile(name, data, item);
            }),
            p -> { ChatItem it = incomingFileItem; if (it != null) Platform.runLater(() -> it.setProgress(p)); });
        bulkReceiver.setOnBegin((kind, name, size) -> Platform.runLater(() -> {
            if (incomingFileItem != null) chatItems.remove(incomingFileItem); // прежний приём прерван новым
            incomingFileItem = ChatItem.fileReceived(name, size);
            chatItems.add(incomingFileItem);
            scrollToBottom();
        }));

        channel.setReceiveHandler(this::onBytes);

        if (channel.open(portItem, 115200)) {
            tx.start();
            saveLastPort(portItem);
            lastReceivedMs = System.currentTimeMillis();
            startProtocolTimer();
            updateUiState(true);
            addSystem("Подключено к " + portItem + " (задержка кадра " + delay + " мс)");
        } else {
            if (tx != null) tx.stop();
            channel = null; tx = null; bulkSender = null; bulkReceiver = null; msgChannel = null;
            addSystem("Не удалось открыть порт");
        }
    }

    /** Слушатель байтов канала: декодирование и маршрутизация кадров по TYPE. */
    private void onBytes(byte[] data) {
        lastReceivedMs = System.currentTimeMillis();
        rxDecoder.feed(data);
        FrameCodec.Frame f;
        while ((f = rxDecoder.poll()) != null) route(f);
    }

    private void route(FrameCodec.Frame f) {
        switch (f.type) {
            case BulkProtocol.TYPE_MSG, BulkProtocol.TYPE_MSG_ACK -> {
                if (msgChannel != null) msgChannel.feed(f);
            }
            case BulkProtocol.TYPE_FILE_BEGIN, BulkProtocol.TYPE_DATA,
                 BulkProtocol.TYPE_BLOCK_END, BulkProtocol.TYPE_FILE_END -> {
                if (bulkReceiver != null) bulkReceiver.feed(f);
            }
            case BulkProtocol.TYPE_NACK, BulkProtocol.TYPE_BLOCK_DONE -> {
                if (bulkSender != null) bulkSender.onControlFrame(f);
            }
            case FrameCodec.TYPE_PROBE -> {
                SerialChannel ch = channel;
                if (ch != null) ch.send(FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0]));
            }
            case FrameCodec.TYPE_PROBE_RESP -> {
                long rtt = System.currentTimeMillis() - probeSentMs;
                if (rtt > 0 && rtt < 60_000) {
                    String rttText = "RTT: " + rtt + " мс";
                    Platform.runLater(() -> lblRtt.setText(rttText));
                }
            }
            default -> { /* неизвестный тип */ }
        }
    }

    private void disconnect() {
        if (audioRecorder.isRecording()) stopRecording();

        if (bulkSender != null) bulkSender.cancel();
        stopProtocolTimer();
        if (tx      != null) tx.stop();
        if (channel != null) channel.close();
        channel = null; tx = null; bulkSender = null; bulkReceiver = null;
        msgChannel = null; rxDecoder = null;
        sendingFile.set(false);

        if (incomingFileItem != null) {
            chatItems.remove(incomingFileItem);
            incomingFileItem = null;
            addSystem("Передача файла прервана (разрыв соединения)");
        }
        updateUiState(false);
    }

    // =========================================================================
    // Отправка текста (MSG-дорожка)
    // =========================================================================

    private void sendText() {
        if (msgChannel == null || channel == null || !channel.isOpen()) return;
        String text = txtMessage.getText().trim();
        if (text.isEmpty()) return;
        txtMessage.clear();

        chatItems.add(ChatItem.sent(text));
        scrollToBottom();

        msgChannel.send(text, err -> addSystem("Сообщение не доставлено: " + err));
    }

    // =========================================================================
    // Отправка файлов / голоса / фото (bulk-дорожка)
    // =========================================================================

    @FXML
    private void onFileAttach() {
        if (bulkSender == null || channel == null || !channel.isOpen()) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл для отправки");
        File file = chooser.showOpenDialog(chatList.getScene().getWindow());
        if (file != null) sendFile(file);
    }

    private void sendFile(File file) {
        if (bulkSender == null || channel == null || !channel.isOpen()) return;
        if (!sendingFile.compareAndSet(false, true)) {
            addSystem("Дождитесь завершения текущей передачи файла");
            return;
        }

        byte[] data;
        try { data = Files.readAllBytes(file.toPath()); }
        catch (Exception e) { addSystem("Ошибка чтения: " + e.getMessage()); sendingFile.set(false); return; }

        String name = file.getName();
        int    kind;
        ChatItem item;
        if (ChatItem.isVoiceName(name)) {
            kind = BulkProtocol.KIND_VOICE; item = ChatItem.voiceSent(name, data.length);
        } else if (ChatItem.isImageName(name)) {
            kind = BulkProtocol.KIND_IMAGE; item = ChatItem.imageSent(name, data.length);
        } else {
            kind = BulkProtocol.KIND_FILE;  item = ChatItem.fileSent(name, data.length);
        }
        item.setSavedPath(file.getAbsolutePath()); // открыть оригинал / показать превью сразу
        chatItems.add(item);
        scrollToBottom();

        item.setCancelAction(() -> {
            bulkSender.cancel();
            tx.clearPaced();
            sendingFile.set(false);
            Platform.runLater(() -> { chatItems.remove(item); addSystem("Отправка отменена: " + name); });
        });

        bulkSender.send(kind, name, data,
            p   -> Platform.runLater(() -> item.setProgress(p)),
            ()  -> Platform.runLater(() -> {
                item.setProgress(1.0);
                item.setCancelAction(null);
                sendingFile.set(false);
                addSystem("Файл отправлен: " + name + " (" + ChatItem.formatSize(data.length) + ")");
            }),
            err -> Platform.runLater(() -> {
                item.setCancelAction(null);
                sendingFile.set(false);
                addSystem("Передача прервана: " + name + " — " + err);
            }));
    }

    /** Сохраняет принятый файл в папку загрузок, обновляет ChatItem. */
    private void saveReceivedFile(String name, byte[] data, ChatItem item) {
        try {
            Path downloads = getDownloadPath();
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
    // Таймер PROBE (живучесть соединения)
    // =========================================================================

    private void startProtocolTimer() {
        protocolTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "protocol-timer");
            t.setDaemon(true); return t;
        });
        // Первый PROBE через 2 с (чтобы RTT появился быстро), далее каждые probeIntervalSec.
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

        // Over-drive (насколько слать быстрее физического пола FSO)
        Spinner<Integer> spOver = new Spinner<>(0, 30, config.bulkOverdriveMs);
        spOver.setEditable(true);
        spOver.setPrefWidth(80);

        // Интервал PROBE
        Spinner<Integer> spProbe = new Spinner<>(1, 60, config.probeIntervalSec);
        spProbe.setEditable(true);
        spProbe.setPrefWidth(80);

        // Таймаут (пропусков PROBE до разрыва)
        Spinner<Integer> spMiss = new Spinner<>(1, 10, config.probeMaxMiss);
        spMiss.setEditable(true);
        spMiss.setPrefWidth(80);

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

        grid.addRow(0, new Label("Over-drive:"),       spOver,  new Label("мс (быстрее пола; 0 = безопасно)"));
        grid.addRow(1, new Label("Интервал PROBE:"),   spProbe, new Label("сек"));
        grid.addRow(2, new Label("Таймаут разрыва:"),  spMiss,  new Label("× интервал PROBE"));
        grid.addRow(3, new Label("Папка сохранения:"), pathRow);

        Label hint = new Label("⚠ Изменения вступают в силу при следующем подключении.");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #888888;");
        grid.add(hint, 0, 4, 3, 1);

        dlg.getDialogPane().setContent(grid);

        Optional<ButtonType> result = dlg.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try { config.bulkOverdriveMs = spOver.getValue();  } catch (Exception ignored) {}
            try { config.probeIntervalSec = spProbe.getValue(); } catch (Exception ignored) {}
            try { config.probeMaxMiss     = spMiss.getValue();  } catch (Exception ignored) {}
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
        alert.setHeaderText("FSO Terminal 2.0.1  —  оптический терминал связи");
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
            "  Файлы/голос/фото — блочный bulk-ARQ, кадр 64 Б (data 56 Б)\n" +
            "  Текст — отдельная дорожка MSG с фрагментацией\n" +
            "  Пейсинг = пол FSO − over-drive (по умолч. 11 → задержка 20 мс)\n" +
            "  Кадр: [SOF 0xAA][SEQ][TYPE][LEN][PAYLOAD][CRC8]\n" +
            "  Скорость канала: ~24 кбит/с (≈2400 байт/с), пик ~1700 байт/с\n\n" +
            "СОВМЕСТИМОСТЬ:\n" +
            "  STM32F722 (прозрачный мост USB-CDC ↔ FSO) — изменений не требует";

        alert.setContentText(text);
        alert.getDialogPane().setPrefWidth(540);
        alert.showAndWait();
    }

    // =========================================================================
    // Настройки (загрузка / сохранение)
    // =========================================================================

    private void loadConfig() {
        config.bulkOverdriveMs = PREFS.getInt(PREF_OVERDRIVE,  11);
        config.probeIntervalSec = PREFS.getInt(PREF_PROBE_SEC,  5);
        config.probeMaxMiss     = PREFS.getInt(PREF_PROBE_MISS, 3);
        String dp = PREFS.get(PREF_DOWNLOAD_PATH, "");
        config.downloadPath = (dp == null || dp.isBlank()) ? null : dp;
    }

    private void saveConfig() {
        PREFS.putInt(PREF_OVERDRIVE,  config.bulkOverdriveMs);
        PREFS.putInt(PREF_PROBE_SEC,  config.probeIntervalSec);
        PREFS.putInt(PREF_PROBE_MISS, config.probeMaxMiss);
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
        int bytes  = text.getBytes(StandardCharsets.UTF_8).length;
        int frames = Math.max(1, (int) Math.ceil((double) bytes / BulkProtocol.MSG_BYTES));
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
