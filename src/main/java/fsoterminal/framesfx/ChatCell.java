package fsoterminal.framesfx;

import fsoterminal.audio.AudioRecorder;
import fsoterminal.model.ChatItem;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import javax.sound.sampled.*;
import java.io.File;

/**
 * Ячейка чата.
 *
 * Kind.TEXT  — текстовый пузырь; если progress != null — полоса отправки
 * Kind.FILE  — иконка + прогресс + размер
 * Kind.IMAGE — как FILE + превью после сохранения
 * Kind.VOICE — иконка 🎤 + кнопка ▶/⏹ + длительность
 */
public class ChatCell extends ListCell<ChatItem> {

    // --- Структура ---
    private final HBox root   = new HBox();
    private final VBox bubble = new VBox(3);

    // --- Текст ---
    private final Label       lblText     = new Label();
    private final Label       lblTime     = new Label();
    private final ProgressBar txtProgress = new ProgressBar(0);

    // --- Файл / изображение ---
    private final Label       lblFileName = new Label();
    private final ProgressBar fileProgress = new ProgressBar(0);
    private final Label       lblFileMeta  = new Label();
    private final ImageView   thumbnail    = new ImageView();

    // --- Голос ---
    private final HBox   voiceRow    = new HBox(8);
    private final Label  lblVoiceIcon = new Label("🎤");
    private final Button btnPlay      = new Button("▶");
    private final Label  lblDuration  = new Label("0:00");
    private final ProgressBar voiceBar = new ProgressBar(0);
    private final Label  lblVoiceMeta = new Label();

    // Воспроизведение
    private Clip    clip;
    private Timeline playUpdater;

    // --- Listener management ---
    private ChatItem               boundItem;
    private ChangeListener<Number> progressListener;
    private ChangeListener<String> pathListener;

    // =========================================================================

    public ChatCell() {
        setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        // Текст
        lblText.setWrapText(true);
        lblText.setMaxWidth(380);
        lblTime.getStyleClass().add("chat-time");
        txtProgress.setPrefHeight(3);
        txtProgress.getStyleClass().add("send-progress-bar");

        // Файл
        lblFileName.getStyleClass().add("file-name-label");
        lblFileName.setMaxWidth(300);
        fileProgress.setPrefWidth(280);
        fileProgress.getStyleClass().add("file-progress-bar");
        HBox.setHgrow(fileProgress, Priority.ALWAYS);
        lblFileMeta.getStyleClass().add("chat-time");

        // Изображение
        thumbnail.setFitWidth(280);
        thumbnail.setFitHeight(200);
        thumbnail.setPreserveRatio(true);
        thumbnail.setSmooth(true);
        thumbnail.setVisible(false);
        thumbnail.setManaged(false);

        // Голос
        lblVoiceIcon.setStyle("-fx-font-size: 16px;");
        btnPlay.getStyleClass().add("btn-play-voice");
        btnPlay.setMinWidth(32);
        voiceBar.setPrefWidth(140);
        voiceBar.setPrefHeight(6);
        voiceBar.getStyleClass().add("voice-progress-bar");
        lblDuration.getStyleClass().add("chat-time");
        voiceRow.setAlignment(Pos.CENTER_LEFT);
        voiceRow.getChildren().addAll(lblVoiceIcon, btnPlay, voiceBar, lblDuration);
        lblVoiceMeta.getStyleClass().add("chat-time");

        bubble.setPadding(new Insets(6, 12, 4, 12));
        bubble.setMaxWidth(420);
        root.setPadding(new Insets(3, 8, 3, 8));
        root.setFillHeight(false);
        setGraphic(root);
    }

    // =========================================================================

    @Override
    protected void updateItem(ChatItem item, boolean empty) {
        super.updateItem(item, empty);
        unbindCurrent();
        stopPlayback();

        if (empty || item == null) { setGraphic(null); return; }

        bubble.getChildren().clear();

        switch (item.kind) {
            case TEXT  -> buildText(item);
            case FILE  -> buildFile(item);
            case IMAGE -> buildImage(item);
            case VOICE -> buildVoice(item);
        }

        applyAlignment(item.direction);
        setGraphic(root);
    }

    // =========================================================================
    // Builders
    // =========================================================================

    private void buildText(ChatItem item) {
        lblText.setText(item.text);
        lblTime.setText(item.time);
        if (item.progressProperty() != null && item.getProgress() < 1.0) {
            txtProgress.setVisible(true);
            txtProgress.setManaged(true);
            txtProgress.progressProperty().bind(item.progressProperty());
            progressListener = (o, ov, nv) -> {
                if (nv.doubleValue() >= 1.0) {
                    txtProgress.setVisible(false);
                    txtProgress.setManaged(false);
                    txtProgress.progressProperty().unbind();
                }
            };
            item.progressProperty().addListener(progressListener);
            boundItem = item;
            bubble.getChildren().addAll(lblText, txtProgress, lblTime);
        } else {
            txtProgress.setVisible(false);
            txtProgress.setManaged(false);
            bubble.getChildren().addAll(lblText, lblTime);
        }
    }

    private void buildFile(ChatItem item) {
        lblFileName.setText("📎 " + item.fileName);
        lblFileMeta.setText(ChatItem.formatSize(item.fileSize) + "  ·  " + item.time);
        bindFileProgress(item);
        thumbnail.setVisible(false);
        thumbnail.setManaged(false);
        bubble.getChildren().addAll(lblFileName, fileProgress, lblFileMeta);
    }

    private void buildImage(ChatItem item) {
        buildFile(item);
        thumbnail.setImage(null);
        thumbnail.setVisible(false);
        thumbnail.setManaged(false);
        bubble.getChildren().add(thumbnail);

        String path = item.getSavedPath();
        if (path != null) { loadThumbnail(path); }
        else {
            pathListener = (o, ov, nv) -> { if (nv != null) loadThumbnail(nv); };
            item.savedPathProperty().addListener(pathListener);
            if (boundItem == null) boundItem = item;
        }
    }

    private void buildVoice(ChatItem item) {
        double dur = AudioRecorder.durationSeconds(item.fileSize);
        lblDuration.setText(AudioRecorder.formatDuration(dur));
        voiceBar.setProgress(0);

        // Кнопка play активна только после получения файла
        boolean ready = item.getProgress() >= 1.0 && item.getSavedPath() != null;
        btnPlay.setDisable(!ready);
        btnPlay.setText("▶");

        if (ready) {
            btnPlay.setOnAction(e -> togglePlayback(item.getSavedPath()));
        } else {
            // Ждём завершения получения
            bindFileProgress(item);
            ChangeListener<String> pl = (o, ov, nv) -> {
                if (nv != null) {
                    btnPlay.setDisable(false);
                    btnPlay.setOnAction(e -> togglePlayback(nv));
                }
            };
            item.savedPathProperty().addListener(pl);
            pathListener = pl;
            if (boundItem == null) boundItem = item;
        }

        lblVoiceMeta.setText(AudioRecorder.formatDuration(dur) + "  ·  " + item.time);
        bubble.getChildren().addAll(voiceRow, fileProgress, lblVoiceMeta);
    }

    // =========================================================================
    // Воспроизведение
    // =========================================================================

    private void togglePlayback(String path) {
        if (clip != null && clip.isRunning()) {
            stopPlayback();
        } else {
            startPlayback(path);
        }
    }

    private void startPlayback(String path) {
        stopPlayback();
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new File(path));
            clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();
            btnPlay.setText("⏹");

            playUpdater = new Timeline(new KeyFrame(Duration.millis(100), e -> {
                if (clip == null || !clip.isRunning()) {
                    stopPlayback();
                } else {
                    double p = (double) clip.getMicrosecondPosition() / clip.getMicrosecondLength();
                    voiceBar.setProgress(p);
                    lblDuration.setText(AudioRecorder.formatDuration(
                        clip.getMicrosecondPosition() / 1_000_000.0) + " / " +
                        AudioRecorder.formatDuration(clip.getMicrosecondLength() / 1_000_000.0));
                }
            }));
            playUpdater.setCycleCount(Timeline.INDEFINITE);
            playUpdater.play();
        } catch (Exception ex) {
            btnPlay.setText("▶");
        }
    }

    private void stopPlayback() {
        if (playUpdater != null) { playUpdater.stop(); playUpdater = null; }
        if (clip != null) { if (clip.isRunning()) clip.stop(); clip.close(); clip = null; }
        btnPlay.setText("▶");
        voiceBar.setProgress(0);
    }

    // =========================================================================
    // Вспомогательные
    // =========================================================================

    private void bindFileProgress(ChatItem item) {
        fileProgress.progressProperty().unbind();
        fileProgress.progressProperty().bind(item.progressProperty());
        fileProgress.setVisible(item.getProgress() < 1.0);
        fileProgress.setManaged(item.getProgress() < 1.0);

        ChangeListener<Number> pl = (o, ov, nv) -> {
            if (nv.doubleValue() >= 1.0) {
                fileProgress.setVisible(false);
                fileProgress.setManaged(false);
            }
        };
        item.progressProperty().addListener(pl);
        progressListener = pl;
        boundItem = item;
    }

    private void loadThumbnail(String path) {
        try {
            thumbnail.setImage(new Image(new File(path).toURI().toString(),
                                         280, 200, true, true, true));
            thumbnail.setVisible(true);
            thumbnail.setManaged(true);
        } catch (Exception ignored) {}
    }

    private void applyAlignment(ChatItem.Direction dir) {
        root.getChildren().setAll(bubble);
        switch (dir) {
            case SENT -> {
                bubble.getStyleClass().setAll("bubble-sent");
                bubble.setAlignment(Pos.TOP_RIGHT);
                root.setAlignment(Pos.CENTER_RIGHT);
            }
            case RECEIVED -> {
                bubble.getStyleClass().setAll("bubble-received");
                bubble.setAlignment(Pos.TOP_LEFT);
                root.setAlignment(Pos.CENTER_LEFT);
            }
            case SYSTEM -> {
                bubble.getStyleClass().setAll("bubble-system");
                bubble.setAlignment(Pos.CENTER);
                root.setAlignment(Pos.CENTER);
            }
        }
    }

    private void unbindCurrent() {
        txtProgress.progressProperty().unbind();
        fileProgress.progressProperty().unbind();
        if (boundItem != null) {
            if (progressListener != null)
                boundItem.progressProperty().removeListener(progressListener);
            if (pathListener != null)
                boundItem.savedPathProperty().removeListener(pathListener);
        }
        boundItem = null; progressListener = null; pathListener = null;
    }
}
