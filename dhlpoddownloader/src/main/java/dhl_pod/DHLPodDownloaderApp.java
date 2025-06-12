package dhl_pod;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.scene.control.Alert;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.Select;

public class DHLPodDownloaderApp extends Application {

    // Settings
    private String apiKey = System.getenv("DHL_API_KEY");
    private String chromeDriverPath = "dhlpoddownloader/chromedriver.exe";
    private String chromeBinaryPath = "dhlpoddownloader/chrome-win64/chrome.exe";
    private File downloadDirectory = new File(System.getProperty("user.dir"));

    // UI
    private TextArea resultArea;
    private TextArea errorArea;
    private Button downloadButton;
    private Button parallelDownloadButton;
    private TextField inputField;
    private Button selectFolderButton;
    private Label folderLabel;
    private ProgressBar progressBar;
    private CheckBox darkModeToggle;
    private MenuBar menuBar;

    // For multi-threaded downloads
    private ExecutorService executor;

    private static final String SETTINGS_FILE = "user_settings.txt";

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        loadSettingsFromFile();

        // Menu bar with About/Help and Settings
        menuBar = new MenuBar();
        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(_ -> showAboutDialog());
        helpMenu.getItems().add(aboutItem);

        Menu settingsMenu = new Menu("Settings");
        MenuItem openSettings = new MenuItem("Configure...");
        openSettings.setOnAction(_ -> showSettingsDialog(primaryStage));
        settingsMenu.getItems().add(openSettings);

        menuBar.getMenus().addAll(settingsMenu, helpMenu);

        Label label = new Label("Enter DHL tracking numbers (comma separated):");

        inputField = new TextField();
        inputField.setPromptText("e.g. 1234567890,9876543210");

        downloadButton = new Button("Download PODs (Single Thread)");
        parallelDownloadButton = new Button("Download PODs (Parallel)");

        resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setPrefHeight(200);

        // Error details area (collapsible)
        TitledPane errorPane = new TitledPane();
        errorPane.setText("Error Details (click to expand)");
        errorArea = new TextArea();
        errorArea.setEditable(false);
        errorArea.setPrefHeight(80);
        errorPane.setContent(errorArea);
        errorPane.setExpanded(false);

        selectFolderButton = new Button("Select Download Location");
        folderLabel = new Label("Download folder: " + downloadDirectory.getAbsolutePath());

        selectFolderButton.setOnAction(_ -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Download Folder");
            chooser.setInitialDirectory(downloadDirectory);
            File selected = chooser.showDialog(primaryStage);
            if (selected != null && selected.isDirectory()) {
                downloadDirectory = selected;
                folderLabel.setText("Download folder: " + downloadDirectory.getAbsolutePath());
            }
        });

        // Progress bar
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setVisible(false);

        // Dark mode toggle
        darkModeToggle = new CheckBox("Dark Mode");
        darkModeToggle.setOnAction(_ -> toggleDarkMode(primaryStage.getScene(), darkModeToggle.isSelected()));

        downloadButton.setOnAction(_ -> {
            downloadButton.setDisable(true);
            parallelDownloadButton.setDisable(true);
            resultArea.clear();
            errorArea.clear();
            progressBar.setProgress(0);
            progressBar.setVisible(true);
            new Thread(() -> processTrackingNumbers(false)).start();
        });

        parallelDownloadButton.setOnAction(_ -> {
            downloadButton.setDisable(true);
            parallelDownloadButton.setDisable(true);
            resultArea.clear();
            errorArea.clear();
            progressBar.setProgress(0);
            progressBar.setVisible(true);
            new Thread(() -> processTrackingNumbers(true)).start();
        });

        HBox buttonBox = new HBox(10, downloadButton, parallelDownloadButton);

        VBox root = new VBox(10, menuBar, label, inputField, selectFolderButton, folderLabel, buttonBox,
                progressBar, resultArea, errorPane, darkModeToggle);
        root.setPadding(new Insets(20));
        root.setPrefWidth(650);

        Scene scene = new Scene(root, 650, 540);
        scene.getStylesheets().add(getClass().getResource("/dhlpod.css").toExternalForm());

        primaryStage.setTitle("DHL POD Downloader");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    // Multi-threaded or single-threaded download and progress bar
    private void processTrackingNumbers(boolean parallel) {
        String input = inputField.getText();
        String cleaned = input.replaceAll("[^0-9,]", "");
        String[] codes = cleaned.split(",");
        List<String> trackingNumbers = new ArrayList<>();
        for (String code : codes) {
            String trimmed = code.trim();
            if (!trimmed.isEmpty())
                trackingNumbers.add(trimmed);
        }

        if (trackingNumbers.isEmpty()) {
            appendResult("No valid tracking numbers entered.");
            enableButtons();
            Platform.runLater(() -> progressBar.setVisible(false));
            return;
        }

        if (apiKey == null || apiKey.isEmpty()) {
            appendResult("ERROR: DHL_API_KEY environment variable not set.");
            enableButtons();
            Platform.runLater(() -> progressBar.setVisible(false));
            return;
        }

        HttpClient client = HttpClient.newHttpClient();
        int total = trackingNumbers.size();
        AtomicInteger completed = new AtomicInteger(0);

        if (parallel) {
            // Use a thread pool for parallel downloads
            executor = Executors.newFixedThreadPool(Math.min(4, total));
            List<Future<?>> futures = new ArrayList<>();

            for (String trackingNumber : trackingNumbers) {
                futures.add(executor.submit(() -> {
                    processSingleTrackingNumber(trackingNumber, client);
                    int done = completed.incrementAndGet();
                    Platform.runLater(() -> progressBar.setProgress((double) done / total));
                    if (done == total) {
                        Platform.runLater(() -> {
                            enableButtons();
                            progressBar.setVisible(false);
                            showCompletionNotification();
                        });
                        executor.shutdown();
                    }
                }));
            }
        } else {
            // Single-threaded: process one by one
            for (String trackingNumber : trackingNumbers) {
                processSingleTrackingNumber(trackingNumber, client);
                int done = completed.incrementAndGet();
                Platform.runLater(() -> progressBar.setProgress((double) done / total));
            }
            Platform.runLater(() -> {
                enableButtons();
                progressBar.setVisible(false);
                showCompletionNotification();
            });
        }
    }

    private void processSingleTrackingNumber(String trackingNumber, HttpClient client) {
        try {
            appendResult("Processing: " + trackingNumber);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api-eu.dhl.com/track/shipments?trackingNumber=" + trackingNumber))
                    .header("DHL-API-Key", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject json = new JSONObject(response.body());
            if (!json.has("shipments")) {
                appendResult(trackingNumber
                        + ": FAILED (No shipments found in API response. The tracking number may be invalid or not available.)");
                return;
            }
            JSONArray shipments = json.getJSONArray("shipments");

            if (shipments.length() == 0) {
                appendResult(trackingNumber + ": FAILED (No shipment found)");
                return;
            }

            JSONObject shipment = shipments.getJSONObject(0);
            if (shipment.has("details")) {
                JSONObject details = shipment.getJSONObject("details");
                if (details.has("proofOfDelivery")) {
                    JSONObject pod = details.getJSONObject("proofOfDelivery");
                    if (pod.has("documentUrl")) {
                        String pdfUrl = pod.getString("documentUrl");

                        // Try direct download first
                        HttpRequest pdfRequest = HttpRequest.newBuilder()
                                .uri(URI.create(pdfUrl))
                                .header("DHL-API-Key", apiKey)
                                .GET()
                                .build();

                        HttpResponse<byte[]> pdfResponse = client.send(pdfRequest,
                                HttpResponse.BodyHandlers.ofByteArray());
                        String contentType = pdfResponse.headers().firstValue("Content-Type").orElse("");
                        if (contentType.equalsIgnoreCase("application/pdf")) {
                            String fileName = "POD_" + trackingNumber + ".pdf";
                            Path savePath = downloadDirectory.toPath().resolve(fileName);
                            Files.write(savePath, pdfResponse.body());
                            appendResult(trackingNumber + ": PASSED (PDF saved as " + savePath + ")");
                        } else {
                            appendResult(
                                    trackingNumber + ": Direct download failed, using browser automation...");
                            boolean seleniumResult = automateWithSelenium(pdfUrl, trackingNumber);
                            if (seleniumResult) {
                                appendResult(trackingNumber + ": PASSED (Downloaded via browser)");
                            } else {
                                appendResult(trackingNumber + ": FAILED (Browser automation error)");
                            }
                        }
                    } else {
                        appendResult(trackingNumber + ": FAILED (No documentUrl in POD)");
                    }
                } else {
                    appendResult(trackingNumber + ": FAILED (No proofOfDelivery)");
                }
            } else {
                appendResult(trackingNumber + ": FAILED (No details in shipment)");
            }
        } catch (Exception ex) {
            appendResult(trackingNumber + ": FAILED (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
            appendError(ex);
        }
    }

    private void appendResult(String msg) {
        Platform.runLater(() -> resultArea.appendText(msg + "\n"));
    }

    private void appendError(Exception ex) {
        Platform.runLater(() -> errorArea.appendText(ex.toString() + "\n"));
    }

    private void enableButtons() {
        Platform.runLater(() -> {
            downloadButton.setDisable(false);
            parallelDownloadButton.setDisable(false);
        });
    }

    // Notification on completion
    private void showCompletionNotification() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Download Complete");
        alert.setHeaderText(null);
        alert.setContentText("All downloads are finished!");
        if (darkModeToggle.isSelected()) {
            alert.getDialogPane().getStylesheets().add(getClass().getResource("/dhlpod-dark.css").toExternalForm());
        } else {
            alert.getDialogPane().getStylesheets().clear();
        }
        alert.showAndWait();
    }

    // Settings dialog
    private void showSettingsDialog(Stage owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Settings");
        dialog.setHeaderText("Configure application settings");

        Label apiKeyLabel = new Label("API Key:");
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setText(apiKey != null ? apiKey : "");

        Label chromeDriverLabel = new Label("ChromeDriver Path:");
        TextField chromeDriverField = new TextField(chromeDriverPath);

        Label chromeBinaryLabel = new Label("Chrome Binary Path:");
        TextField chromeBinaryField = new TextField(chromeBinaryPath);

        Label downloadDirLabel = new Label("Default Download Folder:");
        TextField downloadDirField = new TextField(downloadDirectory.getAbsolutePath());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        grid.add(apiKeyLabel, 0, 0);
        grid.add(apiKeyField, 1, 0);
        grid.add(chromeDriverLabel, 0, 1);
        grid.add(chromeDriverField, 1, 1);
        grid.add(chromeBinaryLabel, 0, 2);
        grid.add(chromeBinaryField, 1, 2);
        grid.add(downloadDirLabel, 0, 3);
        grid.add(downloadDirField, 1, 3);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        if (darkModeToggle.isSelected()) {
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/dhlpod-dark.css").toExternalForm());
        } else {
            dialog.getDialogPane().getStylesheets().clear();
        }

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) {
                apiKey = apiKeyField.getText();
                chromeDriverPath = chromeDriverField.getText();
                chromeBinaryPath = chromeBinaryField.getText();
                downloadDirectory = new File(downloadDirField.getText());
                folderLabel.setText("Download folder: " + downloadDirectory.getAbsolutePath());
                saveSettingsToFile(apiKey, downloadDirectory.getAbsolutePath());
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About DHL POD Downloader");
        alert.setHeaderText("DHL POD Downloader");
        alert.setContentText(
                "Enter one or more DHL tracking numbers (comma separated) and download their Proof of Delivery PDFs.\n\n"
                        + "Features:\n"
                        + "- Multi-threaded downloads\n"
                        + "- Progress bar\n"
                        + "- Error details\n"
                        + "- Dark mode\n"
                        + "- Settings dialog\n"
                        + "- Custom download location\n\n"
                        + "Developed by Bhargav Suresh.");

        if (darkModeToggle.isSelected()) {
            alert.getDialogPane().getStylesheets().add(getClass().getResource("/dhlpod-dark.css").toExternalForm());
        } else {
            alert.getDialogPane().getStylesheets().clear();
        }

        alert.showAndWait();
    }

    // Dark mode toggle
    private void toggleDarkMode(Scene scene, boolean dark) {
        if (dark) {
            scene.getStylesheets().add(getClass().getResource("/dhlpod-dark.css").toExternalForm());
        } else {
            scene.getStylesheets().remove(getClass().getResource("/dhlpod-dark.css").toExternalForm());
        }
    }

    private boolean automateWithSelenium(String podUrl, String trackingNumber) {
        try {
            System.setProperty("webdriver.chrome.driver", chromeDriverPath);
            ChromeOptions options = new ChromeOptions();
            options.setBinary(chromeBinaryPath);
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("download.default_directory", downloadDirectory.getAbsolutePath());
            options.setExperimentalOption("prefs", prefs);

            WebDriver driver = new ChromeDriver(options);

            try {
                driver.get(podUrl);
                Thread.sleep(3000);

                Select signatureDropdown = new Select(
                        driver.findElement(By.id("frmWebPOD:validationTable:0:validationType1")));
                signatureDropdown.selectByVisibleText("No Signature");

                WebElement agreeCheckbox = driver.findElement(By.id("frmWebPOD:agreementCheckBox"));
                if (!agreeCheckbox.isSelected()) {
                    agreeCheckbox.click();
                }

                WebElement submitButton = driver.findElement(By.id("frmWebPOD:submit"));
                submitButton.click();

                Thread.sleep(5000);
            } finally {
                driver.quit();
            }
            return true;
        } catch (Exception e) {
            appendError(e);
            return false;
        }
    }

    private void loadSettingsFromFile() {
        try {
            if (Files.exists(Paths.get(SETTINGS_FILE))) {
                List<String> lines = Files.readAllLines(Paths.get(SETTINGS_FILE), StandardCharsets.UTF_8);
                for (String line : lines) {
                    if (line.startsWith("API_KEY=")) {
                        apiKey = line.substring("API_KEY=".length());
                    } else if (line.startsWith("DOWNLOAD_DIR=")) {
                        String dir = line.substring("DOWNLOAD_DIR=".length());
                        if (!dir.isEmpty()) {
                            downloadDirectory = new File(dir);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Optionally log or show error
        }
    }

    private void saveSettingsToFile(String key, String downloadDir) {
        try {
            Files.write(Paths.get(SETTINGS_FILE),
                    Arrays.asList(
                            "API_KEY=" + key,
                            "DOWNLOAD_DIR=" + downloadDir),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Optionally log or show error
        }
    }
}