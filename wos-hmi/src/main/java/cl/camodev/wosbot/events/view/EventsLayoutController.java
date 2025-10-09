package cl.camodev.wosbot.events.view;

import cl.camodev.wosbot.common.view.AbstractProfileController;
import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

public class EventsLayoutController extends AbstractProfileController {
    @FXML
    private CheckBox checkBoxTundraEvent, checkBoxTundraUseGems, checkBoxTundraSSR, checkBoxHeroMission, 
                     checkBoxMercenaryEvent, checkBoxJourneyofLight, checkBoxMyriadBazaar, checkBoxTundraEventActivationHour;

    @FXML
    private TextField textfieldTundraActivationHour;
    
    @FXML
    private ComboBox<Integer> comboBoxMercenaryFlag, comboBoxHeroMissionFlag;

    @FXML
    private ComboBox<String> comboBoxHeroMissionMode;

    @FXML
    private void initialize() {
        // Set up flag combobox with integer values
        comboBoxMercenaryFlag.getItems().addAll(0, 1, 2, 3, 4, 5, 6, 7, 8);
        comboBoxHeroMissionFlag.getItems().addAll(0, 1, 2, 3, 4, 5, 6, 7, 8);
        comboBoxHeroMissionMode.getItems().addAll("Limited (10)", "Unlimited");

        // Map UI elements to configuration keys
        comboBoxMappings.put(comboBoxMercenaryFlag, EnumConfigurationKey.MERCENARY_FLAG_INT);
        checkBoxMappings.put(checkBoxTundraEvent, EnumConfigurationKey.TUNDRA_TRUCK_EVENT_BOOL);
        checkBoxMappings.put(checkBoxTundraUseGems, EnumConfigurationKey.TUNDRA_TRUCK_USE_GEMS_BOOL);
        checkBoxMappings.put(checkBoxTundraSSR, EnumConfigurationKey.TUNDRA_TRUCK_SSR_BOOL);
        checkBoxMappings.put(checkBoxTundraEventActivationHour, EnumConfigurationKey.TUNDRA_TRUCK_ACTIVATION_HOUR_BOOL);
        checkBoxMappings.put(checkBoxHeroMission, EnumConfigurationKey.HERO_MISSION_EVENT_BOOL);
        checkBoxMappings.put(checkBoxMercenaryEvent, EnumConfigurationKey.MERCENARY_EVENT_BOOL);
        checkBoxMappings.put(checkBoxJourneyofLight, EnumConfigurationKey.JOURNEY_OF_LIGHT_BOOL);
        checkBoxMappings.put(checkBoxMyriadBazaar, EnumConfigurationKey.MYRIAD_BAZAAR_EVENT_BOOL);

        comboBoxMappings.put(comboBoxHeroMissionFlag, EnumConfigurationKey.HERO_MISSION_FLAG_INT);
        comboBoxMappings.put(comboBoxHeroMissionMode, EnumConfigurationKey.HERO_MISSION_MODE_STRING);

        // Map the activation hour text field
        textFieldMappings.put(textfieldTundraActivationHour, EnumConfigurationKey.TUNDRA_TRUCK_ACTIVATION_HOUR_INT);
        
        // Set default value (0-23)
        textfieldTundraActivationHour.setText("0");

        initializeChangeEvents();
    }
}
