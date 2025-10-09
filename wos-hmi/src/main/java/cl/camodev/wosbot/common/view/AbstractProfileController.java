package cl.camodev.wosbot.common.view;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import cl.camodev.wosbot.console.enumerable.PrioritizableItem;
import cl.camodev.wosbot.ot.DTOPriorityItem;
import cl.camodev.wosbot.profile.model.IProfileChangeObserver;
import cl.camodev.wosbot.profile.model.IProfileLoadListener;
import cl.camodev.wosbot.profile.model.IProfileObserverInjectable;
import cl.camodev.wosbot.profile.model.ProfileAux;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;

public abstract class AbstractProfileController implements IProfileLoadListener, IProfileObserverInjectable {

	protected final Map<CheckBox, EnumConfigurationKey> checkBoxMappings = new HashMap<>();
	protected final Map<TextField, EnumConfigurationKey> textFieldMappings = new HashMap<>();
	protected final Map<RadioButton, EnumConfigurationKey> radioButtonMappings = new HashMap<>();
	protected final Map<ComboBox<?>, EnumConfigurationKey> comboBoxMappings = new HashMap<>();
	protected final Map<PriorityListView, EnumConfigurationKey> priorityListMappings = new HashMap<>();
	protected final Map<PriorityListView, Class<? extends Enum<?>>> priorityListEnumClasses = new HashMap<>();
	protected IProfileChangeObserver profileObserver;
	protected boolean isLoadingProfile = false;

	@Override
	public void setProfileObserver(IProfileChangeObserver observer) {
		this.profileObserver = observer;
	}

	protected <T extends Enum<T> & PrioritizableItem> void registerPriorityList(
			PriorityListView priorityListView,
			EnumConfigurationKey configKey,
			Class<T> enumClass) {
		priorityListMappings.put(priorityListView, configKey);
		priorityListEnumClasses.put(priorityListView, enumClass);
	}

	protected void initializeChangeEvents() {
		checkBoxMappings.forEach(this::setupCheckBoxListener);
		textFieldMappings.forEach(this::setupTextFieldUpdateOnFocusOrEnter);
		radioButtonMappings.forEach(this::setupRadioButtonListener);
		comboBoxMappings.forEach(this::setupComboBoxListener);
		priorityListMappings.forEach(this::setupPriorityListListener);
        priorityListEnumClasses.forEach(this::initializePriorityListFromEnum);
	}

	protected void createToggleGroup(RadioButton... radioButtons) {
		ToggleGroup toggleGroup = new ToggleGroup();
		for (RadioButton radioButton : radioButtons) {
			radioButton.setToggleGroup(toggleGroup);
		}
	}

	protected void setupRadioButtonListener(RadioButton radioButton, EnumConfigurationKey configKey) {
		radioButton.selectedProperty().addListener((obs, oldVal, newVal) -> {
			if (!isLoadingProfile) {
				profileObserver.notifyProfileChange(configKey, newVal);
			}
		});
	}

	protected void setupCheckBoxListener(CheckBox checkBox, EnumConfigurationKey configKey) {
		checkBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
			if (!isLoadingProfile) {
				profileObserver.notifyProfileChange(configKey, newVal);
			}
		});
	}

	protected void setupTextFieldUpdateOnFocusOrEnter(TextField textField, EnumConfigurationKey configKey) {
		textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
			if (!isNowFocused && !isLoadingProfile) {
				updateProfile(textField, configKey);
			}
		});

		textField.setOnAction(event -> {
			if (!isLoadingProfile) {
				updateProfile(textField, configKey);
			}
		});
	}

	protected void setupComboBoxListener(ComboBox<?> comboBox, EnumConfigurationKey configKey) {
		comboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!isLoadingProfile && newVal != null) {
				profileObserver.notifyProfileChange(configKey, newVal);
			}
		});
	}

	protected void setupPriorityListListener(PriorityListView priorityListView, EnumConfigurationKey configKey) {
		priorityListView.setOnChangeCallback(() -> {
			if (!isLoadingProfile) {
				String configValue = priorityListView.toConfigString();
				profileObserver.notifyProfileChange(configKey, configValue);
			}
		});
	}

	private void updateProfile(TextField textField, EnumConfigurationKey configKey) {
		String newVal = textField.getText();
		if (configKey.getType() == Integer.class) {
			if (isValidPositiveInteger(newVal)) {
				profileObserver.notifyProfileChange(configKey, Integer.valueOf(newVal));
			} else {
				textField.setText(configKey.getDefaultValue());
			}
		} else {
			// For String values, just pass them through
			profileObserver.notifyProfileChange(configKey, newVal);
		}
	}

	private boolean isValidPositiveInteger(String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}
		try {
			int number = Integer.parseInt(value);
			return number >= 0 && number <= 99999999;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public void onProfileLoad(ProfileAux profile) {
		isLoadingProfile = true;
		try {
			checkBoxMappings.forEach((checkBox, key) -> {
				Boolean value = profile.getConfiguration(key);
				checkBox.setSelected(value);
			});

			textFieldMappings.forEach((textField, key) -> {
				Object value = profile.getConfiguration(key);
				if (value != null) {
					textField.setText(value.toString());
				} else {
					textField.setText(key.getDefaultValue());
				}
			});

			radioButtonMappings.forEach((radioButton, key) -> {
				Boolean value = profile.getConfiguration(key);
				radioButton.setSelected(value);
			});

			comboBoxMappings.forEach((comboBox, key) -> {
				Object value = profile.getConfiguration(key);
				if (value != null) {
					@SuppressWarnings("unchecked")
					ComboBox<Object> uncheckedComboBox = (ComboBox<Object>) comboBox;
					uncheckedComboBox.setValue(value);
				}
			});

			priorityListMappings.forEach((priorityListView, key) -> {
				String value = profile.getConfiguration(key);
				if (value != null && !value.trim().isEmpty()) {
					priorityListView.fromConfigString(value);
				} else {
                    Class<? extends Enum<?>> enumClass = priorityListEnumClasses.get(priorityListView);
					if (enumClass != null) {
						reinitializePriorityListWithDefaults(priorityListView, enumClass);
					}
				}
			});

		} finally {
			isLoadingProfile = false;
		}
	}

	protected <T extends Enum<T> & PrioritizableItem> void initializePriorityListFromEnum(
			PriorityListView priorityListView,
			Class<? extends Enum<?>> enumClass) {

		List<DTOPriorityItem> items = new ArrayList<>();
		T[] enumConstants = ((Class<T>) enumClass).getEnumConstants();

		for (int i = 0; i < enumConstants.length; i++) {
			items.add(new DTOPriorityItem(
				enumConstants[i].getIdentifier(),
				enumConstants[i].getDisplayName(),
				i + 1,
				false
			));
		}

		priorityListView.setItems(items);
	}

	private <T extends Enum<T> & PrioritizableItem> void reinitializePriorityListWithDefaults(
			PriorityListView priorityListView,
			Class<? extends Enum<?>> enumClass) {

		List<DTOPriorityItem> items = new ArrayList<>();
		T[] enumConstants = ((Class<T>) enumClass).getEnumConstants();

		for (int i = 0; i < enumConstants.length; i++) {
			items.add(new DTOPriorityItem(
				enumConstants[i].getIdentifier(),
				enumConstants[i].getDisplayName(),
				i + 1,
				false // All disabled
			));
		}

		priorityListView.setItems(items);
	}

	protected <T extends Enum<T> & PrioritizableItem> void mergeEnumWithSavedPriorities(
			PriorityListView priorityListView,
			Class<T> enumClass,
			EnumConfigurationKey configKey) {

		List<DTOPriorityItem> currentItems = priorityListView.getItems();

		if (currentItems.isEmpty()) {
			return;
		}

		Map<String, DTOPriorityItem> savedItemsMap = new HashMap<>();
		for (DTOPriorityItem item : currentItems) {
			savedItemsMap.put(item.getIdentifier(), item);
		}

		List<DTOPriorityItem> mergedItems = new ArrayList<>();
		T[] enumConstants = enumClass.getEnumConstants();

		for (T enumItem : enumConstants) {
			String identifier = enumItem.getIdentifier();

			if (savedItemsMap.containsKey(identifier)) {
				DTOPriorityItem savedItem = savedItemsMap.get(identifier);
				mergedItems.add(new DTOPriorityItem(
					identifier,
					enumItem.getDisplayName(),
					savedItem.getPriority(),
					savedItem.isEnabled()
				));
			}
		}

		mergedItems.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));

		List<DTOPriorityItem> newItems = new ArrayList<>();
		for (T enumItem : enumConstants) {
			String identifier = enumItem.getIdentifier();

			if (!savedItemsMap.containsKey(identifier)) {
				newItems.add(new DTOPriorityItem(
					identifier,
					enumItem.getDisplayName(),
					0,
					false
				));
			}
		}

		mergedItems.addAll(newItems);

		for (int i = 0; i < mergedItems.size(); i++) {
			mergedItems.get(i).setPriority(i + 1);
		}

		if (mergedItems.size() != currentItems.size() ||
			!haveSameIdentifiers(mergedItems, currentItems)) {

			priorityListView.setItems(mergedItems);

			if (profileObserver != null && !isLoadingProfile) {
				String mergedConfig = priorityListView.toConfigString();
				profileObserver.notifyProfileChange(configKey, mergedConfig);
			}
		} else {
			priorityListView.setItems(mergedItems);
		}
	}

	private boolean haveSameIdentifiers(List<DTOPriorityItem> list1, List<DTOPriorityItem> list2) {
		if (list1.size() != list2.size()) {
			return false;
		}

		for (int i = 0; i < list1.size(); i++) {
			if (!list1.get(i).getIdentifier().equals(list2.get(i).getIdentifier())) {
				return false;
			}
		}

		return true;
	}
}
