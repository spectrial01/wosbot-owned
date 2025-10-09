package cl.camodev.wosbot.alliance.view;

import cl.camodev.wosbot.common.view.AbstractProfileController;
import cl.camodev.wosbot.common.view.PriorityListView;
import cl.camodev.wosbot.console.enumerable.AllianceShopItem;
import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

public class AllianceShopController extends AbstractProfileController {

    @FXML
    private CheckBox enableAllianceShopCheckbox;

    @FXML
    private PriorityListView allianceShopPriorities;

    @FXML
    private Label instructionsLabel;

    @FXML
    private TextField minCoinsToActivateTextField;

    @FXML
    private TextField minCoinsTextField;

    @FXML
    public void initialize() {
        setupMappings();
        initializeChangeEvents();
    }

    private void setupMappings() {
        checkBoxMappings.put(enableAllianceShopCheckbox, EnumConfigurationKey.ALLIANCE_SHOP_ENABLED_BOOL);
        registerPriorityList(allianceShopPriorities, EnumConfigurationKey.ALLIANCE_SHOP_PRIORITIES_STRING, AllianceShopItem.class);
        textFieldMappings.put(minCoinsToActivateTextField, EnumConfigurationKey.ALLIANCE_SHOP_MIN_COINS_TO_ACTIVATE_INT);
        textFieldMappings.put(minCoinsTextField, EnumConfigurationKey.ALLIANCE_SHOP_MIN_COINS_INT);
    }

}
