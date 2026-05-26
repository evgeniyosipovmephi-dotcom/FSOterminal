package fsoterminal.framesfx;

import fsoterminal.model.ChatItem;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;

/**
 * Ячейка чата.
 *
 * Kind.TEXT  — пузырь с текстом; если progress != null — маленькая полоса
 *              отправки (исчезает при 1.0)
 * Kind.FILE  — иконка + имя + прогресс-бар + размер
 * Kind.IMAGE — то же, что FILE + превью когда savedPath появился
 */
public class ChatCell extends ListCell<ChatItem> {

    // --- общая структура ---
    private final HBox root   = new HBox();
    private final VBox bubble = new VBox(3);

    // --- текст ---
    private final Label       lblText    = new Label();
    private final Label       lblTime    = new Label();
    private final ProgressBar txtProgress = new ProgressBar(0); // только для TEXT

    // --- файл / изображение ---
    private final Label       lblFileName = new Label();
    private final ProgressBar fileProgress = new ProgressBar(0);
    private final Label       lblFileMeta  = new Label();  // размер · время
    private final ImageView   thumbnail    = new ImageView();

    // --- управление listener'ами ---
    private ChatItem            boundItem;
    private ChangeListener<Number> progressListener;
    private ChangeListener<String> pathListener;

    // -------------------------------------------------------------------------

    public ChatCell() {
        setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        lblText.setWrapText(true);
        lblText.setMaxWidth(380);
        lblTime.getStyleClass().add("chat-time");

        txtProgress.setPrefWidth(200);
        txtProgress.setPrefHeight(4);
        txtProgress.getStyleClass().add("send-progress-bar");

        lblFileName.getStyleClass().add("file-name-label");
        lblFileName.setWrapText(false);
        lblFileName.setMaxWidth(300);

        fileProgress.setPrefWidth(280);
        fileProgress.getStyleClass().add("file-progress-bar");
        HBox.setHgrow(fileProgress, Priority.ALWAYS);

        lblFileMeta.getStyleClass().add("chat-time");

        thumbnail.setFitWidth(280);
        thumbnail.setFitHeight(200);
        thumbnail.setPreserveRatio(true);
        thumbnail.setSmooth(true);
        thumbnail.setVisible(false);
        thumbnail.setManaged(false);

        bubble.setPadding(new Insets(6, 12, 4, 12));
        bubble.setMaxWidth(420);
        root.setPadding(new Insets(3, 8, 3, 8));
        root.setFillHeight(false);
        setGraphic(root);
    }

    // -------------------------------------------------------------------------

    @Override
    protected void updateItem(ChatItem item, boolean empty) {
        super.updateItem(item, empty);

        // Убираем старые listener'ы
        unbindCurrent();

        if (empty || item == null) {
            setGraphic(null);
            return;
        }

        bubble.getChildren().clear();

        switch (item.kind) {
            case TEXT  -> buildTextBubble(item);
            case FILE  -> buildFileBubble(item);
            case IMAGE -> buildImageBubble(item);
        }

        applyAlignment(item.direction);
        setGraphic(root);
    }

    // -------------------------------------------------------------------------

    private void buildTextBubble(ChatItem item) {
        lblText.setText(item.text);
        lblTime.setText(item.time);

        if (item.progressProperty() != null && item.getProgress() < 1.0) {
            // Многокадровое сообщение — показываем полосу отправки
            txtProgress.setVisible(true);
            txtProgress.setManaged(true);
            txtProgress.progressProperty().bind(item.progressProperty());

            progressListener = (obs, ov, nv) -> {
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

    private void buildFileBubble(ChatItem item) {
        lblFileName.setText("📎 " + item.fileName);
        lblFileMeta.setText(ChatItem.formatSize(item.fileSize) + "  ·  " + item.time);

        fileProgress.progressProperty().unbind();
        fileProgress.progressProperty().bind(item.progressProperty());

        progressListener = (obs, ov, nv) -> {
            if (nv.doubleValue() >= 1.0) {
                fileProgress.setVisible(false);
                fileProgress.setManaged(false);
            }
        };
        item.progressProperty().addListener(progressListener);
        boundItem = item;

        // Скрыть полосу если уже завершено
        if (item.getProgress() >= 1.0) {
            fileProgress.setVisible(false);
            fileProgress.setManaged(false);
        }

        bubble.getChildren().addAll(lblFileName, fileProgress, lblFileMeta);
    }

    private void buildImageBubble(ChatItem item) {
        // Сначала строим как FILE
        buildFileBubble(item);

        // Добавляем ImageView — он появится когда придёт savedPath
        thumbnail.setVisible(false);
        thumbnail.setManaged(false);
        thumbnail.setImage(null);
        bubble.getChildren().add(thumbnail);

        // Если путь уже есть — сразу показываем
        String existingPath = item.getSavedPath();
        if (existingPath != null) {
            loadThumbnail(existingPath);
        } else {
            // Ждём появления пути
            pathListener = (obs, ov, nv) -> {
                if (nv != null) loadThumbnail(nv);
            };
            item.savedPathProperty().addListener(pathListener);
        }
        // boundItem уже установлен в buildFileBubble
    }

    private void loadThumbnail(String path) {
        try {
            Image img = new Image(new File(path).toURI().toString(),
                                  280, 200, true, true, true);
            thumbnail.setImage(img);
            thumbnail.setVisible(true);
            thumbnail.setManaged(true);
        } catch (Exception ignored) { /* не удалось загрузить — не показываем */ }
    }

    // -------------------------------------------------------------------------

    private void applyAlignment(ChatItem.Direction direction) {
        root.getChildren().setAll(bubble);
        switch (direction) {
            case SENT -> {
                bubble.getStyleClass().setAll("bubble-sent");
                bubble.setAlignment(Pos.TOP_RIGHT);
                lblTime.setStyle("-fx-alignment: center-right;");
                lblFileMeta.setStyle("-fx-alignment: center-right;");
                root.setAlignment(Pos.CENTER_RIGHT);
            }
            case RECEIVED -> {
                bubble.getStyleClass().setAll("bubble-received");
                bubble.setAlignment(Pos.TOP_LEFT);
                lblTime.setStyle("-fx-alignment: center-left;");
                lblFileMeta.setStyle("-fx-alignment: center-left;");
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
        boundItem       = null;
        progressListener = null;
        pathListener    = null;
    }
}
