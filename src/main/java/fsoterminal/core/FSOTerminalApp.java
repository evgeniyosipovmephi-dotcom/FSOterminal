package fsoterminal.core;

import fsoterminal.framesfx.MainWindowSC;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class FSOTerminalApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/fsoterminal/fxml/MainWindow.fxml"));
        Parent root = loader.load();

        MainWindowSC controller = loader.getController();

        primaryStage.setTitle("FSO Terminal");
        primaryStage.setScene(new Scene(root, 620, 520));
        primaryStage.setMinWidth(420);
        primaryStage.setMinHeight(350);
        primaryStage.setOnCloseRequest(e -> controller.onClose());
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
