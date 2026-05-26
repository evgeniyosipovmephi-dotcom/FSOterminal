package fsoterminal.framesfx;

import fsoterminal.channel.SerialChannel;
import fsoterminal.model.ChatItem;
import fsoterminal.protocol.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.*;
import java.util.prefs.Preferences;

public class MainWindowSC implements Initializable {

    @FXML private BorderPane      rootPane;
    @FXML private ComboBox<String> portCombo;
    @FXML private Button           btnConnect;
    @FXML private Circle           statusDot;
    @FXML private Label            lblStatus;
    @FXML private ListView<ChatItem> chatList;
    @FXML private TextField        txtMessage;
    @FXML private Label            lblCharCount;
    @FXML private Button           btnSend;
    @FXML private Button           btnFile;

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

    // PROBE
    private static final int  PROBE_INTERVAL_SEC = 5;
    private static final int  PROBE_MAX_MISS      = 3;
    private volatile long lastReceivedMs = 0;

    // Настройки
    private static final Preferences PREFS     = Preferences.userNodeForPackage(MainWindowSC.class);
    private static final String      PREF_PORT = "lastPort";

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

        refreshPorts();
        loadSettings();
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

        channel  = new SerialChannel();
        sender   = new SlidingWindowSender(4, channel::send);
        ackProc  = new AckProcessor(sender);
        receiver = new SlidingWindowReceiver(4, channel::send, this::dispatchFrame);
        ackProc.setDataHandler(receiver::onFrame);

        ackProc.setProbeHandler(() -> {
            byte[] resp = FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0]);
            SerialChannel ch = channel;
            if (ch != null) ch.send(resp);
        });
        ackProc.setProbeRespHandler(f -> { /* lastReceivedMs уже обновлён */ });

        channel.setReceiveHandler(data -> {
            lastReceivedMs = System.currentTimeMillis();
            ackProc.feed(data);
        });

        if (channel.open(portItem, 115200)) {
            saveSettings(portItem);
            lastReceivedMs = System.currentTimeMillis();
            startProtocolTimer();
            updateUiState(true);
            addSystem("Подключено к " + portItem);
        } else {
            channel = null;
            addSystem("Не удалось открыть порт");
        }
    }

    /** Диспетчер упорядоченных кадров от SlidingWindowReceiver. */
    private void dispatchFrame(FrameCodec.Frame frame) {
        switch (frame.type) {
            case FrameCodec.TYPE_DATA                             -> assembler.onFrame(frame);
            case FrameCodec.TYPE_FILE_BEGIN,
                 FrameCodec.TYPE_FILE_DATA,
                 FrameCodec.TYPE_FILE_END                        -> fileAssembler.onFrame(frame);
            // TYPE_VOICE — Фаза 3
        }
    }

    private void disconnect() {
        stopProtocolTimer();
        if (assembler     != null) assembler.reset();
        if (fileAssembler != null) fileAssembler.reset();
        if (channel       != null) channel.close();
        channel = null; sender = null; receiver = null;
        ackProc = null; assembler = null; fileAssembler = null;
        incomingFileItem = null;
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

        // Для многокадровых сообщений — отдельный ChatItem с прогресс-баром
        ChatItem item = (totalFrames > 1) ? ChatItem.sentMultiFrame(text) : ChatItem.sent(text);
        chatItems.add(item);
        scrollToBottom();

        SlidingWindowSender s = sender;
        Thread t = new Thread(() -> {
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
        ChatItem item = ChatItem.fileSent(name, data.length);
        chatItems.add(item);
        scrollToBottom();

        SlidingWindowSender s = sender;
        Thread t = new Thread(() -> {
            try {
                if (!s.trySend(FrameCodec.TYPE_FILE_BEGIN, encodeFileBegin(name, data.length), 10_000)) {
                    Platform.runLater(() -> addSystem("Ошибка: канал занят (FILE_BEGIN)"));
                    return;
                }
                int offset = 0;
                while (offset < data.length) {
                    int len = Math.min(FrameCodec.MAX_PAYLOAD, data.length - offset);
                    byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
                    if (!s.trySend(FrameCodec.TYPE_FILE_DATA, chunk, 10_000)) {
                        Platform.runLater(() -> addSystem("Ошибка: канал занят (FILE_DATA)"));
                        return;
                    }
                    offset += len;
                    double p = (double) offset / data.length;
                    Platform.runLater(() -> item.setProgress(p));
                }
                if (!s.trySend(FrameCodec.TYPE_FILE_END, new byte[0], 10_000)) {
                    Platform.runLater(() -> addSystem("Ошибка: канал занят (FILE_END)"));
                    return;
                }
                Platform.runLater(() -> {
                    item.setProgress(1.0);
                    addSystem("Файл отправлен: " + name + " (" + ChatItem.formatSize(data.length) + ")");
                });
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }, "fso-file-send");
        t.setDaemon(true);
        t.start();
    }

    /** FILE_BEGIN payload: [nameLen:1B][name UTF-8][totalSize:4B LE] */
    private static byte[] encodeFileBegin(String name, long totalBytes) {
        byte[] nb  = name.getBytes(StandardCharsets.UTF_8);
        int nameLen = Math.min(nb.length, 245);
        byte[] p   = new byte[1 + nameLen + 4];
        p[0] = (byte) nameLen;
        System.arraycopy(nb, 0, p, 1, nameLen);
        int sz = (int) Math.min(totalBytes, 0xFFFFFFFFL);
        p[1+nameLen]   = (byte)(sz);
        p[2+nameLen]   = (byte)(sz >> 8);
        p[3+nameLen]   = (byte)(sz >> 16);
        p[4+nameLen]   = (byte)(sz >> 24);
        return p;
    }

    /** Сохраняет файл в ~/Downloads, обновляет ChatItem. */
    private void saveReceivedFile(String name, byte[] data, ChatItem item) {
        try {
            Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
            if (!Files.exists(downloads))
                downloads = Paths.get(System.getProperty("user.home"));

            // Уникальное имя
            Path dest = downloads.resolve(name);
            if (Files.exists(dest)) {
                int dot  = name.lastIndexOf('.');
                String base = (dot > 0) ? name.substring(0, dot) : name;
                String ext  = (dot > 0) ? name.substring(dot)    : "";
                for (int n = 1; Files.exists(dest); n++)
                    dest = downloads.resolve(base + " (" + n + ")" + ext);
            }
            Files.write(dest, data);

            Path finalDest = dest;
            if (item != null) {
                item.setSavedPath(finalDest.toString()); // уведомит ChatCell
                item.setProgress(1.0);
            }
            addSystem("Получен: " + finalDest.getFileName() +
                      " (" + ChatItem.formatSize(data.length) + ")");

        } catch (Exception e) {
            addSystem("Ошибка сохранения файла: " + e.getMessage());
        }
    }

    // =========================================================================
    // Таймеры протокола
    // =========================================================================

    private void startProtocolTimer() {
        protocolTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "protocol-timer");
            t.setDaemon(true); return t;
        });
        protocolTimer.scheduleAtFixedRate(() -> {
            SlidingWindowSender s = sender;
            if (s != null) s.retransmitUnconfirmed();
        }, 400, 400, TimeUnit.MILLISECONDS);
        protocolTimer.scheduleAtFixedRate(this::onProbeTimer,
            PROBE_INTERVAL_SEC, PROBE_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void stopProtocolTimer() {
        if (protocolTimer != null) { protocolTimer.shutdownNow(); protocolTimer = null; }
    }

    private void onProbeTimer() {
        SerialChannel ch = channel;
        if (ch == null || !ch.isOpen()) return;
        long silentMs  = System.currentTimeMillis() - lastReceivedMs;
        long timeoutMs = (long) PROBE_INTERVAL_SEC * 1000L * PROBE_MAX_MISS;
        if (silentMs > timeoutMs) {
            Platform.runLater(() -> { addSystem("Соединение потеряно (нет ответа)"); disconnect(); });
            return;
        }
        ch.send(FrameCodec.encode(0, FrameCodec.TYPE_PROBE, new byte[0]));
    }

    // =========================================================================
    // Настройки
    // =========================================================================

    private void saveSettings(String portItem) { PREFS.put(PREF_PORT, portItem); }

    private void loadSettings() {
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
            txtMessage.setDisable(!connected);
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
