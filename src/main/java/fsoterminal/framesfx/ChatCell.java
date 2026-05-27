package fsoterminal.framesfx;

import fsoterminal.audio.AudioRecorder;
import fsoterminal.model.ChatItem;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
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
import java.awt.Desktop;
import java.io.File;

/**
 * Ячейка чата.
 *
 * Kind.TEXT  — текстовый пузырь; если progress != null — полоса отправки
 * Kind.FILE  — иконка + прогресс + размер; кнопка ✕ для SENT в процессе
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
    private final Label       lblFileName  = new Label();
    private final ProgressBar fileProgress = new ProgressBar(0);
    private final Label       lblFileMeta  = new Label();
    private final ImageView   thumbnail    = new ImageView();

    // --- Отмена ---
    private final Button btnCancel = new Button("✕");

    // --- Голос ---
    private final HBox   voiceRow     = new HBox(8);
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

        // Кнопка отмены
        btnCancel.getStyleClass().add("btn-cancel");
        btnCancel.setVisible(false);
        btnCancel.setManaged(false);

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
        if (item.progressProperty() != null) {
            txtProgress.progressProperty().bind(item.progressProperty());
            // Visibility через Binding — корректно работает при любом текущем progress
            txtProgress.visibleProperty().bind(
                Bindings.createBooleanBinding(() -> item.getProgress() < 1.0, item.progressProperty()));
            txtProgress.managedProperty().bind(
                Bindings.createBooleanBinding(() -> item.getProgress() < 1.0, item.progressProperty()));
            bubble.getChildren().addAll(lblText, txtProgress, lblTime);
        } else {
            txtProgress.setVisible(false);
            txtProgress.setManaged(false);
            bubble.getChildren().addAll(lblText, lblTime);
        }
    }

    private void buildFile(ChatItem item) {
        lblFileName.setText("📎 " + item.fileName);
        lblFileName.setStyle("-fx-cursor: hand;");
        lblFileName.setOnMouseClicked(e -> openFile(item.getSavedPath()));
        lblFileMeta.setText(ChatItem.formatSize(item.fileSize) + "  ·  " + item.time);
        thumbnail.setVisible(false);
        thumbnail.setManaged(false);
        bindFileProgressUi(item);
        HBox metaRow = buildMetaRow(item, lblFileMeta);
        bubble.getChildren().addAll(lblFileName, fileProgress, metaRow);
    }

    private void buildImage(ChatItem item) {
        buildFile(item);
        thumbnail.setImage(null);
        thumbnail.setVisible(false);
        thumbnail.setManaged(false);
        thumbnail.setStyle("-fx-cursor: hand;");
        thumbnail.setOnMouseClicked(e -> openFile(item.getSavedPath()));
        bubble.getChildren().add(thumbnail);

        String path = item.getSavedPath();
        if (path != null) {
            loadThumbnail(path);
        } else {
            ChangeListener<String> pl = (o, ov, nv) -> { if (nv != null) loadThumbnail(nv); };
            item.savedPathProperty().addListener(pl);
            pathListener = pl;
            if (boundItem == null) boundItem = item;
        }
    }

    private void buildVoice(ChatItem item) {
        double dur = AudioRecorder.durationSeconds(item.fileSize);
        lblDuration.setText(AudioRecorder.formatDuration(dur));
        voiceBar.setProgress(0);
        voiceBar.setVisible(true);
        voiceBar.setManaged(true);
        bindFileProgressUi(item);

        // Кнопка play: SENT — можно слушать сразу (файл уже записан локально),
        //              RECEIVED — только после сохранения (savedPath != null)
        btnPlay.setText("▶");
        String savedPath = item.getSavedPath();
        boolean sentReady    = (item.direction == ChatItem.Direction.SENT && savedPath != null);
        boolean receivedReady = (item.direction == ChatItem.Direction.RECEIVED
                                 && savedPath != null && item.getProgress() >= 1.0);

        if (sentReady || receivedReady) {
            btnPlay.setDisable(false);
            btnPlay.setOnAction(e -> togglePlayback(savedPath));
        } else {
            btnPlay.setDisable(true);
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
        HBox metaRow = buildMetaRow(item, lblVoiceMeta);
        bubble.getChildren().addAll(voiceRow, fileProgress, metaRow);
    }

    /**
     * Привязывает fileProgress к прогрессу элемента через Binding.
     * Скрывает/показывает прогресс-бар реактивно — корректно работает
     * даже если updateItem() вызван после завершения передачи.
     */
    private void bindFileProgressUi(ChatItem item) {
        if (item.progressProperty() == null) {
            fileProgress.setVisible(false);
            fileProgress.setManaged(false);
            return;
        }
        // Явно устанавливаем видимость до привязки: защита от "грязного" состояния
        // при повторном использовании ячейки (cell reuse в ListView).
        boolean inProgress = item.getProgress() < 1.0;
        fileProgress.setVisible(inProgress);
        fileProgress.setManaged(inProgress);

        fileProgress.progressProperty().bind(item.progressProperty());
        fileProgress.visibleProperty().bind(
            Bindings.createBooleanBinding(() -> item.getProgress() < 1.0, item.progressProperty()));
        fileProgress.managedProperty().bind(
            Bindings.createBooleanBinding(() -> item.getProgress() < 1.0, item.progressProperty()));
        boundItem = item;
    }

    /** Строит строку с мета-данными и кнопкой ✕ (для SENT-кадров в процессе передачи). */
    private HBox buildMetaRow(ChatItem item, Label metaLabel) {
        if (item.direction == ChatItem.Direction.SENT && item.progressProperty() != null) {
            btnCancel.setOnAction(e -> { Runnable ca = item.getCancelAction(); if (ca != null) ca.run(); });
            btnCancel.visibleProperty().bind(
                Bindings.createBooleanBinding(() -> item.getProgress() < 1.0, item.progressProperty()));
            btnCancel.managedProperty().bind(
                Bindings.createBooleanBinding(() -> item.getProgress() < 1.0, item.progressProperty()));
        } else {
            btnCancel.setVisible(false);
            btnCancel.setManaged(false);
        }
        HBox row = new HBox(6, metaLabel, btnCancel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // =========================================================================
    // Воспроизведение
    // =========================================================================

    private void togglePlayback(String path) {
        if (clip != null && clip.isRunning()) stopPlayback();
        else startPlayback(path);
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

    /**
     * Открывает файл в приложении по умолчанию, а если не получается — показывает
     * папку с файлом в Проводнике.
     */
    private void openFile(String path) {
        if (path == null) return;
        new Thread(() -> {
            File f = new File(path);
            try {
                if (f.exists()) {
                    Desktop.getDesktop().open(f);
                } else {
                    // Файл перемещён/удалён — открываем папку
                    File dir = f.getParentFile();
                    if (dir != null && dir.exists()) Desktop.getDesktop().open(dir);
                }
            } catch (Exception ex) {
                // Fallback: explorer /select — выделяет файл в Проводнике
                try {
                    new ProcessBuilder("explorer.exe", "/select,", f.getAbsolutePath()).start();
                } catch (Exception ignored) {}
            }
        }, "open-file").start();
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
        // Снять все Binding-и
        txtProgress.progressProperty().unbind();
        txtProgress.visibleProperty().unbind();
        txtProgress.managedProperty().unbind();
        fileProgress.progressProperty().unbind();
        fileProgress.visibleProperty().unbind();
        fileProgress.managedProperty().unbind();
        btnCancel.visibleProperty().unbind();
        btnCancel.managedProperty().unbind();
        btnCancel.setVisible(false);
        btnCancel.setManaged(false);

        // Снять слушателей savedPath
        if (boundItem != null && pathListener != null)
            boundItem.savedPathProperty().removeListener(pathListener);
        boundItem = null; pathListener = null;
    }
}
