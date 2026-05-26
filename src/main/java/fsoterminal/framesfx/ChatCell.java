package fsoterminal.framesfx;

import fsoterminal.model.ChatItem;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Ячейка чата: пузырь текстового сообщения или файловый пузырь с прогресс-баром. */
public class ChatCell extends ListCell<ChatItem> {

    // --- Общие элементы ---
    private final HBox  root   = new HBox();
    private final VBox  bubble = new VBox(3);

    // --- Текстовое сообщение ---
    private final Label lblText = new Label();
    private final Label lblTime = new Label();

    // --- Файловый пузырь ---
    private final Label       lblFileName  = new Label();
    private final Label       lblFileSize  = new Label();
    private final ProgressBar progressBar  = new ProgressBar(0);
    private final Label       lblFileMeta  = new Label(); // "HH:mm  · размер"

    public ChatCell() {
        setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        // --- Текст ---
        lblText.setWrapText(true);
        lblText.setMaxWidth(380);
        lblTime.getStyleClass().add("chat-time");

        // --- Файл ---
        lblFileName.getStyleClass().add("file-name-label");
        lblFileName.setWrapText(false);
        lblFileName.setMaxWidth(300);

        progressBar.setPrefWidth(280);
        progressBar.getStyleClass().add("file-progress-bar");

        lblFileSize.getStyleClass().add("file-size-label");
        lblFileMeta.getStyleClass().add("chat-time");

        HBox.setHgrow(progressBar, Priority.ALWAYS);

        bubble.setPadding(new Insets(6, 12, 4, 12));
        bubble.setMaxWidth(420);

        root.setPadding(new Insets(3, 8, 3, 8));
        root.setFillHeight(false);
        setGraphic(root);
    }

    @Override
    protected void updateItem(ChatItem item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            setGraphic(null);
            return;
        }

        // Unbind предыдущую привязку прогресса
        progressBar.progressProperty().unbind();

        bubble.getChildren().clear();

        if (item.kind == ChatItem.Kind.FILE) {
            buildFileBubble(item);
        } else {
            buildTextBubble(item);
        }

        applyAlignment(item.direction);
        setGraphic(root);
    }

    // -------------------------------------------------------------------------

    private void buildTextBubble(ChatItem item) {
        lblText.setText(item.text);
        lblTime.setText(item.time);
        bubble.getChildren().addAll(lblText, lblTime);
    }

    private void buildFileBubble(ChatItem item) {
        // Имя файла
        String icon = "📎 "; // 📎
        lblFileName.setText(icon + item.fileName);

        // Прогресс-бар: привязываем к свойству ChatItem
        progressBar.progressProperty().bind(item.fileProgressProperty());

        // Строка с размером и временем
        lblFileMeta.setText(ChatItem.formatSize(item.fileSize) + "  ·  " + item.time);

        bubble.getChildren().addAll(lblFileName, progressBar, lblFileMeta);
    }

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
                lblTime.setStyle("-fx-alignment: center;");
                root.setAlignment(Pos.CENTER);
            }
        }
    }
}
