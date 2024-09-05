package dse.explorer;

import dse.libods.*;
import io.fair_acc.chartfx.axes.spi.DefaultNumericAxis;
import io.fair_acc.chartfx.ui.ObservableDeque;
import io.fair_acc.dataset.spi.DoubleDataSet;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableListValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableArray;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.StackPane;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


public class MainController {

    private final static Logger log = LoggerFactory.getLogger(MainController.class);


    @FXML private Spinner<Integer> spinnerSensorAvg;
    @FXML private Spinner<Integer> spinnerAverage;
    @FXML private Spinner<Integer> spinnerHistory;

    @FXML private ChoiceBox<String> choiceSensorType;
    @FXML private ChoiceBox<String> choiceSensorSerialPort;
    @FXML private ChoiceBox<Integer> choiceSensorBaudRate;

    @FXML private Button btnStart;
    @FXML private Button btnStop;

    @FXML public StackPane stackPane;

    @FXML private Label labelMessage;
    @FXML private Label labelMeasurement;
    @FXML private Label labelAverage;
    @FXML private Label labelMinimum;
    @FXML private Label labelMaximum;
    @FXML private Label labelFrequency;


    private DefaultNumericAxis xAxis = new DefaultNumericAxis();
    private DefaultNumericAxis yAxis = new DefaultNumericAxis();
    private io.fair_acc.chartfx.XYChart chart = new io.fair_acc.chartfx.XYChart(xAxis, yAxis);;
    private ObservableList<DoubleDataSet> observableList = FXCollections.observableArrayList();


    // DSE Sensor Library
    private SerialSensor serialSensor;
    private DemoSensor demoSensor;

    // FIXME: Hardcodet conversion
    private final MeasurementConverter measurementConverter = new MeasurementConverter(100);

    private String selectedType;
    private String selectedPort;
    private Integer selectedBaud;

    private final StateModel stateModel = new StateModel();
    private EventProcessTask eventProcessTask;
    private Thread thread;

    @FXML public void initialize() {
        log.debug("initialize()");

        choiceSensorType.getItems().addAll("16bit", "18bit");
        choiceSensorType.getSelectionModel().select(0);

        choiceSensorBaudRate.getItems().addAll(38400, 115200);
        choiceSensorBaudRate.getSelectionModel().select(0);

        choiceSensorSerialPort.getItems().add("Demo");
        choiceSensorSerialPort.getItems().addAll(SerialSensor.getSerialPorts());
        choiceSensorSerialPort.getSelectionModel().select(0);

        int idx = 0;
        for(String port : choiceSensorSerialPort.getItems()) {
            if(port.equals("ttyUSB0")) {
                choiceSensorSerialPort.getSelectionModel().select(idx);
            }
            idx++;
        }

        spinnerAverage.getEditor().textProperty().addListener((observable, oldValue, newValue) -> {
            if(!newValue.matches("\\d*")) {
                spinnerSensorAvg.getEditor().setText(oldValue);
            }
        });

        spinnerHistory.getEditor().textProperty().addListener((observable, oldValue, newValue) -> {
            if(!newValue.matches("\\d*")) {
                spinnerHistory.getEditor().setText(oldValue);
            }
        });

        xAxis.setName("Time");
        xAxis.setMinorTickCount(0);
        yAxis.setName("Distance");
        yAxis.setAutoRangePadding(5.0);
        yAxis.setForceZeroInRange(false);

        observableList.add(stateModel.dataSet);
        chart.getDatasets().addAll(observableList);
        chart.autosize();
        
        stackPane.getChildren().add(chart);

        eventProcessTask = new EventProcessTask(stateModel);
        labelMessage.textProperty().bind(eventProcessTask.messageProperty());
        labelMeasurement.textProperty().bindBidirectional(stateModel.measurementValue, measurementConverter);
        labelAverage.textProperty().bindBidirectional(stateModel.averageValue, measurementConverter);
        labelMinimum.textProperty().bindBidirectional(stateModel.minimumValue, measurementConverter);
        labelMaximum.textProperty().bindBidirectional(stateModel.maximumValue, measurementConverter);
        labelFrequency.textProperty().bind(stateModel.frequency.asString());


        //yAxis.lowerBoundProperty().bind(stateModel.lowerBound);
        //yAxis.upperBoundProperty().bind(stateModel.upperBound);

        onButtonStart();
    }


    @FXML private void onSelectModel(ActionEvent ignoredE) {
        //log.info(choiceSensorType.getSelectionModel().getSelectedItem());
        selectedType = choiceSensorType.getSelectionModel().getSelectedItem();
    }


    @FXML private void onSelectPort(ActionEvent ignoredE) {
        //log.info(choiceSensorSerialPort.getSelectionModel().getSelectedItem());
        selectedPort = choiceSensorSerialPort.getSelectionModel().getSelectedItem();
    }


    @FXML private void onSelectBaud(ActionEvent ignoredE) {
        //log.info(String.valueOf(choiceSensorBaudRate.getSelectionModel().getSelectedItem()));
        selectedBaud = choiceSensorBaudRate.getSelectionModel().getSelectedItem();
    }


    @FXML private void onButtonStart() {
        log.debug("onButtonStart()");

        if(!Objects.equals(selectedPort, "Demo") && (selectedPort == null || selectedType == null || selectedBaud == null) ) {
            log.warn("onButtonStart() - options missing");
            labelMessage.setText("Missing options");
            return;
        }

        reset();
        btnStart.setDisable(true);
        btnStop.setDisable(false);


        if(selectedPort.equals("Demo")) {
            demoSensor = new DemoSensor();
            demoSensor.setTelegramHandler(new TelegramHandler16Bit());
            demoSensor.setAverageOver(spinnerSensorAvg.getValue());
            demoSensor.subscribe(eventProcessTask);
            demoSensor.start();
        } else {
            serialSensor = new SerialSensor();
            serialSensor.setAverageOver(spinnerSensorAvg.getValue());
            switch (selectedType) {
                case "16bit" -> serialSensor.setTelegramHandler(new TelegramHandler16Bit());
                case "18bit" -> serialSensor.setTelegramHandler(new TelegramHandler18Bit());
                default -> log.warn("Unknown sensor type: {}", selectedType);
            }
            serialSensor.openPort(selectedPort, selectedBaud);
            serialSensor.subscribe(eventProcessTask);
            serialSensor.start();
        }

        thread = new Thread(eventProcessTask);
        thread.start();
    }


    @FXML private void onButtonStop() throws Exception {
        log.debug("onButtonStop()");

        if(selectedPort.equals("Demo")) {
            demoSensor.stop();
            demoSensor = null;
        } else {
            serialSensor.stop();
            serialSensor = null;
        }

        thread.join();
        btnStart.setDisable(false);
        btnStop.setDisable(true);
    }


    private void reset() {

        // Size of moving data points to calculate average on
        int averageOver = spinnerAverage.getValue();
        stateModel.setAverageOver(averageOver);
        stateModel.setElements(spinnerHistory.getValue());

        stateModel.setConversion(100);
        //stateModel.numberSeries1.getData().clear();




        //xAxis.autoRangingProperty().set(false);
        //xAxis.setUpperBound(spinnerHistory.getValue());
    }

}
