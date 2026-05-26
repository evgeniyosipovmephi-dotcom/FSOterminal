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
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.ResourceBundle;
import java.util.concurrent.*;
import java.util.prefs.Preferences;

public class MainWindowSC implements Initializable {

    // --- FXML-узлы ---
    @FXML private ComboBox<String>   portCombo;
    @FXML private Button             btnConnect;
    @FXML private Circle             statusDot;
    @FXML private Label              lblStatus;
    @FXML private ListView<ChatItem> chatList;
    @FXML private TextField          txtMessage;
    @FXML private Label              lblCharCount;
    @FXML private Button             btnSend;
    @FXML private Button             btnFile;

    // --- Данные чата ---
    private final ObservableList<ChatItem> chatItems = FXCollections.observableArrayList();

    // --- Протокол ---
    private SerialChannel            channel;
    private SlidingWindowSender      sender;
    private SlidingWindowReceiver    receiver;
    private AckProcessor             ackProc;
    private TextAssembler            assembler;
    private FileAssembler            fileAssembler;
    private ScheduledExecutorService protocolTimer;

    /** ChatItem текущего входящего файла (для обновления прогресса). */
    private ChatItem incomingFileItem;

    // --- PROBE ---
    private static final int  PROBE_INTERVAL_SEC = 5;
    private static final int  PROBE_MAX_MISS      = 3;
    /** Время последнего получения любых байт от удалённой стороны (мс). */
    private volatile long lastReceivedMs = 0;

    // --- Настройки ---
    private static final Preferences PREFS    = Preferences.userNodeForPackage(MainWindowSC.class);
    private static final String      PREF_PORT = "lastPort";

    // -------------------------------------------------------------------------

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        chatList.setItems(chatItems);
        chatList.setCellFactory(lv -> new ChatCell());

        txtMessage.textProperty().addListener((obs, old, text) -> updateCharCount(text));

        refreshPorts();
        loadSettings();

        txtMessage.setOnAction(e -> sendText());
        btnSend.setOnAction(e -> sendText());

        updateUiState(false);
    }

    // -------------------------------------------------------------------------
    // Подключение
    // -------------------------------------------------------------------------

    @FXML private void onRefreshPorts() { refreshPorts(); }

    @FXML
    private void onConnectClicked() {
        if (channel != null && channel.isOpen()) disconnect();
        else connect();
    }

    private void connect() {
        String portItem = portCombo.getValue();
        if (portItem == null || portItem.isBlank()) {
            addSystem("Выберите COM-порт");
            return;
        }

        // --- TextAssembler ---
        assembler = new TextAssembler(text -> Platform.runLater(() -> {
            chatItems.add(ChatItem.received(text));
            scrollToBottom();
        }));

        // --- FileAssembler ---
        fileAssembler = new FileAssembler(
            // onBegin: создаём пузырь файла в чате
            (name, totalBytes) -> Platform.runLater(() -> {
                incomingFileItem = ChatItem.fileReceived(name, totalBytes);
                chatItems.add(incomingFileItem);
                scrollToBottom();
            }),
            // onProgress: обновляем прогресс-бар
            (received, total) -> {
                if (total <= 0) return;
                double p = (double) received / total;
                ChatItem item = incomingFileItem;
                if (item != null) Platform.runLater(() -> item.setFileProgress(p));
            },
            // onComplete: сохраняем файл
            (name, data) -> Platform.runLater(() -> {
                ChatItem item = incomingFileItem;
                incomingFileItem = null;
                if (item != null) item.setFileProgress(1.0);
                saveReceivedFile(name, data);
            })
        );

        // --- Стек протокола ---
        channel  = new SerialChannel();
        sender   = new SlidingWindowSender(4, channel::send);
        ackProc  = new AckProcessor(sender);

        // Receiver доставляет кадры в правильном порядке → диспетчер по типу
        receiver = new SlidingWindowReceiver(4, channel::send, this::dispatchFrame);
        ackProc.setDataHandler(receiver::onFrame);

        // Отвечаем на PROBE от удалённой стороны
        ackProc.setProbeHandler(() -> {
            byte[] resp = FrameCodec.encode(0, FrameCodec.TYPE_PROBE_RESP, new byte[0]);
            SerialChannel ch = channel;
            if (ch != null) ch.send(resp);
        });

        // PROBE_RESP обрабатывается как любые входящие байты (lastReceivedMs уже обновлён)
        ackProc.setProbeRespHandler(f -> { /* ничего дополнительного */ });

        // Любой входящий байт обновляет время активности
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

    /**
     * Диспетчер: SlidingWindowReceiver отдаёт сюда упорядоченные кадры.
     * Маршрутизируем по типу к нужному обработчику прикладного уровня.
     */
    private void dispatchFrame(FrameCodec.Frame frame) {
        switch (frame.type) {
            case FrameCodec.TYPE_DATA                             -> assembler.onFrame(frame);
            case FrameCodec.TYPE_FILE_BEGIN,
                 FrameCodec.TYPE_FILE_DATA,
                 FrameCodec.TYPE_FILE_END                        -> fileAssembler.onFrame(frame);
            // VOICE — Фаза 3
        }
    }

    private void disconnect() {
        stopProtocolTimer();
        if (assembler    != null) assembler.reset();
        if (fileAssembler!= null) fileAssembler.reset();
        if (channel      != null) channel.close();
        channel       = null;
        sender        = null;
        receiver      = null;
        ackProc       = null;
        assembler     = null;
        fileAssembler = null;
        incomingFileItem = null;
        updateUiState(false);
    }

    // -------------------------------------------------------------------------
    // Отправка текста
    // -------------------------------------------------------------------------

    private void sendText() {
        if (sender == null || channel == null || !channel.isOpen()) return;

        String text = txtMessage.getText().trim();
        if (text.isEmpty()) return;
        txtMessage.clear();

        byte[][] payloads = TextAssembler.encodePayloads(text);
        chatItems.add(ChatItem.sent(text));
        scrollToBottom();

        SlidingWindowSender s = sender;
        Thread t = new Thread(() -> {
            try {
                for (byte[] payload : payloads) {
                    if (!s.trySend(FrameCodec.TYPE_DATA, payload, 5000)) {
                        Platform.runLater(() -> addSystem("Ошибка: канал занят"));
                        return;
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "fso-send");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Отправка файла
    // -------------------------------------------------------------------------

    @FXML
    private void onFileAttach() {
        if (sender == null || channel == null || !channel.isOpen()) return;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите файл для отправки");
        File file = chooser.showOpenDialog(chatList.getScene().getWindow());
        if (file == null) return;

        sendFile(file);
    }

    private void sendFile(File file) {
        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            addSystem("Ошибка чтения файла: " + e.getMessage());
            return;
        }

        String name   = file.getName();
        long   size   = data.length;

        ChatItem item = ChatItem.fileSent(name, size);
        chatItems.add(item);
        scrollToBottom();

        SlidingWindowSender s = sender;
        Thread t = new Thread(() -> {
            try {
                // 1. FILE_BEGIN
                byte[] begin = encodeFileBegin(name, size);
                if (!s.trySend(FrameCodec.TYPE_FILE_BEGIN, begin, 10_000)) {
                    Platform.runLater(() -> addSystem("Ошибка: канал занят (FILE_BEGIN)"));
                    return;
                }

                // 2. FILE_DATA chunks
                int offset = 0;
                while (offset < data.length) {
                    int len   = Math.min(FrameCodec.MAX_PAYLOAD, data.length - offset);
                    byte[] chunk = Arrays.copyOfRange(data, offset, offset + len);
                    if (!s.trySend(FrameCodec.TYPE_FILE_DATA, chunk, 10_000)) {
                        Platform.runLater(() -> addSystem("Ошибка: канал занят (FILE_DATA)"));
                        return;
                    }
                    offset += len;
                    double progress = (double) offset / data.length;
                    Platform.runLater(() -> item.setFileProgress(progress));
                }

                // 3. FILE_END
                if (!s.trySend(FrameCodec.TYPE_FILE_END, new byte[0], 10_000)) {
                    Platform.runLater(() -> addSystem("Ошибка: канал занят (FILE_END)"));
                    return;
                }

                Platform.runLater(() -> {
                    item.setFileProgress(1.0);
                    addSystem("Файл отправлен: " + name);
                });

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "fso-file-send");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Кодирует payload для FILE_BEGIN:
     *   [nameLen: 1B][name UTF-8][totalSize: 4B LE]
     *
     * nameLen ограничен 245 байтами (MAX_PAYLOAD=250 − 1 − 4 = 245).
     */
    private static byte[] encodeFileBegin(String name, long totalBytes) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        int nameLen = Math.min(nameBytes.length, 245);

        byte[] payload = new byte[1 + nameLen + 4];
        payload[0] = (byte) nameLen;
        System.arraycopy(nameBytes, 0, payload, 1, nameLen);

        int size = (int) Math.min(totalBytes, 0xFFFFFFFFL); // 4 байта
        payload[1 + nameLen]     = (byte)(size & 0xFF);
        payload[1 + nameLen + 1] = (byte)((size >> 8)  & 0xFF);
        payload[1 + nameLen + 2] = (byte)((size >> 16) & 0xFF);
        payload[1 + nameLen + 3] = (byte)((size >> 24) & 0xFF);
        return payload;
    }

    /**
     * Сохраняет принятый файл в папку Загрузки пользователя.
     * Если файл с таким именем уже существует — добавляет суффикс "(1)", "(2)"…
     */
    private void saveReceivedFile(String name, byte[] data) {
        Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
        try {
            if (!Files.exists(downloads)) downloads = Paths.get(System.getProperty("user.home"));

            // Выбираем уникальное имя
            Path dest = downloads.resolve(name);
            if (Files.exists(dest)) {
                int dot = name.lastIndexOf('.');
                String base = (dot > 0) ? name.substring(0, dot) : name;
                String ext  = (dot > 0) ? name.substring(dot)    : "";
                int n = 1;
                do {
                    dest = downloads.resolve(base + " (" + n + ")" + ext);
                    n++;
                } while (Files.exists(dest));
            }

            Files.write(dest, data);
            Path finalDest = dest;
            addSystem("Получен файл: " + finalDest.getFileName() +
                      " (" + ChatItem.formatSize(data.length) + ") → " + finalDest.getParent());

        } catch (Exception e) {
            addSystem("Ошибка сохранения файла: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Таймеры протокола
    // -------------------------------------------------------------------------

    private void startProtocolTimer() {
        protocolTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "protocol-timer");
            t.setDaemon(true);
            return t;
        });
        // Ретрансмит каждые 400 мс
        protocolTimer.scheduleAtFixedRate(() -> {
            SlidingWindowSender s = sender;
            if (s != null) s.retransmitUnconfirmed();
        }, 400, 400, TimeUnit.MILLISECONDS);

        // PROBE каждые 5 секунд
        protocolTimer.scheduleAtFixedRate(this::onProbeTimer,
            PROBE_INTERVAL_SEC, PROBE_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void stopProtocolTimer() {
        if (protocolTimer != null) {
            protocolTimer.shutdownNow();
            protocolTimer = null;
        }
    }

    private void onProbeTimer() {
        SerialChannel ch = channel;
        if (ch == null || !ch.isOpen()) return;

        long silentMs  = System.currentTimeMillis() - lastReceivedMs;
        long timeoutMs = (long) PROBE_INTERVAL_SEC * 1000L * PROBE_MAX_MISS;

        if (silentMs > timeoutMs) {
            Platform.runLater(() -> {
                addSystem("Соединение потеряно (нет ответа)");
                disconnect();
            });
            return;
        }

        byte[] probe = FrameCodec.encode(0, FrameCodec.TYPE_PROBE, new byte[0]);
        ch.send(probe);
    }

    // -------------------------------------------------------------------------
    // Настройки
    // -------------------------------------------------------------------------

    private void saveSettings(String portItem) {
        PREFS.put(PREF_PORT, portItem);
    }

    private void loadSettings() {
        String saved = PREFS.get(PREF_PORT, null);
        if (saved == null) return;
        String savedSys = saved.contains(" — ") ? saved.substring(0, saved.indexOf(" — ")) : saved;
        for (String item : portCombo.getItems()) {
            String itemSys = item.contains(" — ") ? item.substring(0, item.indexOf(" — ")) : item;
            if (itemSys.equals(savedSys)) {
                portCombo.setValue(item);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы UI
    // -------------------------------------------------------------------------

    private void refreshPorts() {
        String prev = portCombo.getValue();
        portCombo.getItems().setAll(SerialChannel.availablePorts());
        if (prev != null && portCombo.getItems().contains(prev))
            portCombo.setValue(prev);
        else if (!portCombo.getItems().isEmpty())
            portCombo.getSelectionModel().selectFirst();
    }

    private void updateUiState(boolean connected) {
        Platform.runLater(() -> {
            statusDot.setFill(connected ? Color.LAWNGREEN : Color.TOMATO);
            lblStatus.setText(connected ? "Подключено" : "Не подключено");
            btnConnect.setText(connected ? "Отключить" : "Подключить");
            btnSend.setDisable(!connected);
            btnFile.setDisable(!connected); // активна при подключении
            txtMessage.setDisable(!connected);
        });
    }

    private void updateCharCount(String text) {
        if (text == null || text.isBlank()) {
            lblCharCount.setText("");
            return;
        }
        int frames = TextAssembler.frameCount(text);
        if (frames > 1)
            lblCharCount.setText(text.length() + " симв. / " + frames + " кадра");
        else
            lblCharCount.setText(text.length() + " симв.");
    }

    private void addSystem(String msg) {
        Platform.runLater(() -> {
            chatItems.add(ChatItem.system(msg));
            scrollToBottom();
        });
    }

    private void scrollToBottom() {
        if (!chatItems.isEmpty())
            chatList.scrollTo(chatItems.size() - 1);
    }

    public void onClose() { disconnect(); }
}
