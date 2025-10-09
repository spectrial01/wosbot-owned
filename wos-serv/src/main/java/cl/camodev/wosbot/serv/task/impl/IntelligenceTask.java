package cl.camodev.wosbot.serv.task.impl;

import java.awt.*;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cl.camodev.utiles.UtilRally;
import cl.camodev.utiles.number.NumberConverters;
import cl.camodev.utiles.number.NumberValidators;
import cl.camodev.wosbot.almac.repo.DailyTaskRepository;
import cl.camodev.wosbot.almac.repo.IDailyTaskRepository;
import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import cl.camodev.wosbot.console.enumerable.EnumTemplates;
import cl.camodev.wosbot.console.enumerable.TpDailyTaskEnum;
import cl.camodev.wosbot.ot.DTOImageSearchResult;
import cl.camodev.wosbot.ot.DTOPoint;
import cl.camodev.wosbot.ot.DTOProfiles;
import cl.camodev.wosbot.ot.DTOTesseractSettings;
import cl.camodev.wosbot.serv.impl.StaminaService;
import cl.camodev.wosbot.serv.task.DelayedTask;
import cl.camodev.wosbot.serv.task.EnumStartLocation;
import net.sourceforge.tess4j.TesseractException;

public class IntelligenceTask extends DelayedTask {

	private final IDailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();
	private final boolean fcEra;
	private final boolean useSmartProcessing;

	private boolean marchQueueLimitReached = false;
	private boolean beastMarchSent = false;

	public IntelligenceTask(DTOProfiles profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
		this.fcEra = profile.getConfig(EnumConfigurationKey.INTEL_FC_ERA_BOOL, Boolean.class);
		this.useSmartProcessing = profile.getConfig(EnumConfigurationKey.INTEL_SMART_PROCESSING_BOOL, Boolean.class);
	}

	@Override
	protected void execute() {
		logInfo("Starting Intel task.");

		MarchesAvailable marchesAvailable;
		boolean intelFound = false;
		boolean nonBeastIntelFound = false;
		beastMarchSent = false;
		marchQueueLimitReached = !checkMarchesAvailable();

		if (useSmartProcessing) {
			// Check how many marches are available
			marchesAvailable = getMarchesAvailable();
			if (!marchesAvailable.available()) {
				marchQueueLimitReached = true;
			}
		} else {
			marchesAvailable = new MarchesAvailable(true, LocalDateTime.now());
		}

		ensureOnIntelScreen();
		logInfo("Searching for completed missions to claim.");
		for (int i = 0; i < 2; i++) {
			logDebug("Searching for completed missions. Attempt " + (i + 1) + ".");
			List<DTOImageSearchResult> completed = emuManager.searchTemplates(EMULATOR_NUMBER,
					EnumTemplates.INTEL_COMPLETED, 90, 10);

			if (completed.isEmpty()) {
				logInfo("No completed missions found on attempt " + (i + 1) + ".");
				continue;
			} else {
				logInfo("Found " + completed.size() + " completed missions. Claiming them now.");
			}

			// Process all found completed missions
			for (DTOImageSearchResult completedMission : completed) {
				emuManager.tapAtPoint(EMULATOR_NUMBER, completedMission.getPoint());
				sleepTask(500);
				emuManager.tapAtRandomPoint(EMULATOR_NUMBER, new DTOPoint(700, 1270), new DTOPoint(710, 1280), 5,
						250);
				sleepTask(500);
			}
		}

		// check is stamina enough to process any intel
		int staminaValue = StaminaService.getServices().getCurrentStamina(profile.getId());

		int minStaminaRequired = 30; // Minimum stamina required to process intel, make it configurable if needed?
		if (staminaValue < minStaminaRequired) {
			logWarning("Not enough stamina to process intel. Current stamina: " + staminaValue + ". Required: "
					+ minStaminaRequired + ".");
			long minutesToRegen = (long) (minStaminaRequired - staminaValue) * 5L; // 1 stamina every 5 minutes
			LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(minutesToRegen);
			this.reschedule(rescheduleTime);
			return;
		}

		if (profile.getConfig(EnumConfigurationKey.INTEL_BEASTS_BOOL, Boolean.class)) {
			if (!useSmartProcessing || !marchQueueLimitReached) {
				ensureOnIntelScreen();

				boolean fireBeastsEnabled = profile.getConfig(EnumConfigurationKey.INTEL_FIRE_BEAST_BOOL,
						Boolean.class);
				boolean useFlag = profile.getConfig(EnumConfigurationKey.INTEL_USE_FLAG_BOOL, Boolean.class);

				// Search for fire beasts if enabled
				if (fireBeastsEnabled) {
					logInfo("Searching for fire beasts.");
					if (searchAndProcess(EnumTemplates.INTEL_FIRE_BEAST, 5, 90, this::processBeast)) {
						intelFound = true;
					}
				}

				// If flag feature is enabled, only one beast march should be sent per execution
				if (useFlag) {
					if (!marchQueueLimitReached && !beastMarchSent) {
						logInfo("Searching for beasts using grayscale matching (flag mode, only one march allowed).");
						EnumTemplates beastTemplate = fcEra ? EnumTemplates.INTEL_BEAST_GRAYSCALE_FC
								: EnumTemplates.INTEL_BEAST_GRAYSCALE;
						if (searchAndProcessGrayscale(beastTemplate, 5, 90, this::processBeast)) {
							intelFound = true;
						}
					} else if (beastMarchSent) {
						logInfo("Beast march already sent (flag mode), skipping regular beast search.");
					}
				} else {
					// If flag is disabled, allow both fire and regular beast marches in the same
					// execution
					if (!marchQueueLimitReached) {
						logInfo("Searching for beasts using grayscale matching (multi-march mode).");
						EnumTemplates beastTemplate = fcEra ? EnumTemplates.INTEL_BEAST_GRAYSCALE_FC
								: EnumTemplates.INTEL_BEAST_GRAYSCALE;
						if (searchAndProcessGrayscale(beastTemplate, 5, 90, this::processBeast)) {
							intelFound = true;
						}
					}
				}
			} else {
				logInfo("No marches available, will not run beast search");
			}
		}

		if (profile.getConfig(EnumConfigurationKey.INTEL_CAMP_BOOL, Boolean.class)) {
			ensureOnIntelScreen();

			logInfo("Searching for survivor camps using grayscale matching.");
			EnumTemplates survivorTemplate = fcEra ? EnumTemplates.INTEL_SURVIVOR_GRAYSCALE_FC
					: EnumTemplates.INTEL_SURVIVOR_GRAYSCALE;
			if (searchAndProcessGrayscale(survivorTemplate, 5, 90, this::processSurvivor)) {
				intelFound = true;
				nonBeastIntelFound = true;
			}
		}

		if (profile.getConfig(EnumConfigurationKey.INTEL_EXPLORATION_BOOL, Boolean.class)) {
			ensureOnIntelScreen();

			logInfo("Searching for explorations using grayscale matching.");
			EnumTemplates journeyTemplate = fcEra ? EnumTemplates.INTEL_JOURNEY_GRAYSCALE_FC
					: EnumTemplates.INTEL_JOURNEY_GRAYSCALE;
			if (searchAndProcessGrayscale(journeyTemplate, 5, 90, this::processJourney)) {
				intelFound = true;
				nonBeastIntelFound = true;
			}
		}

		sleepTask(500);
		if (intelFound == false) {
			logInfo("No intel items found. Attempting to read the cooldown timer.");
			try {
				String rescheduleTimeStr = emuManager.ocrRegionText(EMULATOR_NUMBER, new DTOPoint(120, 110),
						new DTOPoint(600, 146));
				LocalDateTime rescheduleTime = parseAndAddTime(rescheduleTimeStr);
				this.reschedule(rescheduleTime);
				tapBackButton();
				logInfo("No new intel found. Rescheduling task to run at: " + rescheduleTime);
			} catch (IOException | TesseractException e) {
				this.reschedule(LocalDateTime.now().plusMinutes(5));
				logError("Error reading intel cooldown timer: " + e.getMessage(), e);
			}
		} else if (marchQueueLimitReached && !nonBeastIntelFound && !beastMarchSent) {
			if (useSmartProcessing) {
				this.reschedule(marchesAvailable.rescheduleTo());
				logInfo("March queue is full, and only beasts remain. Rescheduling for when marches will be available at "
						+ marchesAvailable.rescheduleTo());
			} else {
				LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(5);
				this.reschedule(rescheduleTime);
				logInfo("March queue is full, and only beasts remain. Rescheduling for 5 minutes at " + rescheduleTime);
			}
		} else if (!beastMarchSent) {
			this.reschedule(LocalDateTime.now());
			logInfo("Intel tasks processed. Rescheduling immediately to check for more.");
		}

		logInfo("Intel Task finished.");
	}

	/**
	 * Search for a template using grayscale matching and process it with the
	 * provided method.
	 * This method is optimized for icons that have the same shape but different
	 * colors.
	 */
	private boolean searchAndProcessGrayscale(EnumTemplates template, int maxAttempts, int confidence,
			Consumer<DTOImageSearchResult> processMethod) {
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			logDebug("Searching for grayscale template '" + template + "', attempt " + (attempt + 1) + ".");
			DTOImageSearchResult result = emuManager.searchTemplateGrayscale(EMULATOR_NUMBER, template, confidence);

			if (result.isFound()) {
				logInfo("Grayscale template found: " + template);
				processMethod.accept(result);
				return true;
			}
		}
		return false;
	}

	/**
	 * Search for a template and process it with the provided method.
	 * This method is used for non-grayscale templates like fire beasts.
	 */
	private boolean searchAndProcess(EnumTemplates template, int maxAttempts, int confidence,
			Consumer<DTOImageSearchResult> processMethod) {
		for (int attempt = 0; attempt < maxAttempts; attempt++) {
			logDebug("Searching for template '" + template + "', attempt " + (attempt + 1) + ".");
			DTOImageSearchResult result = emuManager.searchTemplate(EMULATOR_NUMBER, template, confidence);

			if (result.isFound()) {
				logInfo("Template found: " + template);
				processMethod.accept(result);
				return true;
			}
		}
		return false;
	}

	private void processJourney(DTOImageSearchResult result) {
		emuManager.tapAtPoint(EMULATOR_NUMBER, result.getPoint());
		sleepTask(2000);

		DTOImageSearchResult view = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.INTEL_VIEW, 90);
		if (view.isFound()) {
			emuManager.tapAtPoint(EMULATOR_NUMBER, view.getPoint());
			sleepTask(500);
			DTOImageSearchResult explore = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.INTEL_EXPLORE, 90);
			if (explore.isFound()) {
				emuManager.tapAtPoint(EMULATOR_NUMBER, explore.getPoint());
				sleepTask(500);
				emuManager.tapAtPoint(EMULATOR_NUMBER, new DTOPoint(520, 1200));
				sleepTask(1000);
				tapBackButton();
                StaminaService.getServices().subtractStamina(profile.getId(),10);
			} else {
				logWarning("Could not find the 'Explore' button for the journey. Going back.");
				tapBackButton(); // Back from journey screen
				return;
			}
		}
	}

	private void processSurvivor(DTOImageSearchResult result) {
		emuManager.tapAtPoint(EMULATOR_NUMBER, result.getPoint());
		sleepTask(2000);

		DTOImageSearchResult view = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.INTEL_VIEW, 90);
		if (view.isFound()) {
			emuManager.tapAtPoint(EMULATOR_NUMBER, view.getPoint());
			sleepTask(500);
			DTOImageSearchResult rescue = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.INTEL_RESCUE, 90);
			if (rescue.isFound()) {
				emuManager.tapAtPoint(EMULATOR_NUMBER, rescue.getPoint());
                StaminaService.getServices().subtractStamina(profile.getId(),12);
			} else {
				logWarning("Could not find the 'Rescue' button for the survivor. Going back.");
				tapBackButton(); // Back from survivor screen
				return;
			}
		}
	}

	private void processBeast(DTOImageSearchResult beast) {
		if (marchQueueLimitReached) {
			logInfo("March queue is full. Skipping beast hunt.");
			return;
		}
		emuManager.tapAtPoint(EMULATOR_NUMBER, beast.getPoint());
		sleepTask(2000);

		DTOImageSearchResult view = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.INTEL_VIEW, 90);
		if (!view.isFound()) {
			logWarning("Could not find the 'View' button for the beast. Going back.");
			tapBackButton();
			return;
		}
		emuManager.tapAtPoint(EMULATOR_NUMBER, view.getPoint());
		sleepTask(500);

		DTOImageSearchResult attack = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.INTEL_ATTACK, 90);
		if (!attack.isFound()) {
			logWarning("Could not find the 'Attack' button for the beast. Going back.");
			tapBackButton();
			return;
		}
		emuManager.tapAtPoint(EMULATOR_NUMBER, attack.getPoint());
		sleepTask(500);

		// Check if the march screen is open before proceeding
		DTOImageSearchResult deployButton = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.DEPLOY_BUTTON, 90);
		if (!deployButton.isFound()) {
			// March queue limit reached, cannot process beast
			logError("March queue is full. Cannot start a new march.");
			marchQueueLimitReached = true;
			return;
		}

		boolean useFlag = profile.getConfig(EnumConfigurationKey.INTEL_USE_FLAG_BOOL, Boolean.class);
		if (useFlag) {
			// Select the specified flag
			int flagToSelect = profile.getConfig(EnumConfigurationKey.INTEL_BEASTS_FLAG_INT, Integer.class);
			DTOPoint flagPoint = UtilRally.getMarchFlagPoint(flagToSelect);
			logInfo("Tapping flag " + flagToSelect);
			emuManager.tapAtPoint(EMULATOR_NUMBER, flagPoint);
			sleepTask(500);
		}

		DTOImageSearchResult equalizeButton = emuManager.searchTemplate(EMULATOR_NUMBER,
				EnumTemplates.RALLY_EQUALIZE_BUTTON, 90);

		if (equalizeButton.isFound()) {
			emuManager.tapAtPoint(EMULATOR_NUMBER, equalizeButton.getPoint());
		}

		try {
			String timeStr = emuManager.ocrRegionText(EMULATOR_NUMBER, new DTOPoint(521, 1141),
					new DTOPoint(608, 1162));
			long travelTimeSeconds = parseTimeToSeconds(timeStr);
            Integer spendedStamina = integerHelper.execute(
                    new DTOPoint(540, 1215),
                    new DTOPoint(590, 1245),
                    5,
                    200L,
                    DTOTesseractSettings.builder()
                            .setRemoveBackground(true)
                            .setTextColor(new Color(255,255,255))
                            //.setDebug(true)
                            .setPageSegMode(DTOTesseractSettings.PageSegMode.SINGLE_LINE)
                            .build(),
                    text -> NumberValidators.matchesPattern(text, Pattern.compile(".*?(\\d+).*")),
                    text -> NumberConverters.regexToInt(text, Pattern.compile(".*?(\\d+).*"))
            );

            if (travelTimeSeconds > 0) {
				emuManager.tapAtPoint(EMULATOR_NUMBER, deployButton.getPoint());
				sleepTask(1000); // Wait for march to start
				long returnTimeSeconds = (travelTimeSeconds * 2) + 2;
				LocalDateTime rescheduleTime = LocalDateTime.now().plusSeconds(returnTimeSeconds);
				this.reschedule(rescheduleTime);
				logInfo("Beast march sent. Task will run again at " + rescheduleTime + ".");
				beastMarchSent = true;
                if (spendedStamina!=null){
                    StaminaService.getServices().subtractStamina(profile.getId(),spendedStamina);
                }else {
                    // if OCR fails, default to 10 stamina
                    StaminaService.getServices().subtractStamina(profile.getId(),10);
                }

			} else {
				logError("Failed to parse march time. Aborting attack.");
				tapBackButton(); // Go back from march screen
				sleepTask(500);
				tapBackButton(); // Go back from beast screen
			}
		} catch (IOException | TesseractException e) {
			logError("Failed to read march time using OCR. Aborting attack. Error: " + e.getMessage(), e);
			tapBackButton(); // Go back from march screen
			sleepTask(500);
			tapBackButton(); // Go back from beast screen
		}
	}

	private long parseTimeToSeconds(String timeString) {
		if (timeString == null || timeString.trim().isEmpty()) {
			return 0;
		}
		timeString = timeString.replaceAll("[^\\d:]", ""); // Clean non-digit/colon characters
		String[] parts = timeString.trim().split(":");
		long seconds = 0;
		try {
			if (parts.length == 2) { // mm:ss
				seconds = Integer.parseInt(parts[0]) * 60L + Integer.parseInt(parts[1]);
			} else if (parts.length == 3) { // HH:mm:ss
				seconds = Integer.parseInt(parts[0]) * 3600L + Integer.parseInt(parts[1]) * 60L
						+ Integer.parseInt(parts[2]);
			}
		} catch (NumberFormatException e) {
			logError("Could not parse time string: " + timeString);
			return 0;
		}
		return seconds;
	}

	public LocalDateTime parseAndAddTime(String ocrText) {
		// Regular expression to capture time in HH:mm:ss format
		Pattern pattern = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})");
		Matcher matcher = pattern.matcher(ocrText);

		if (matcher.find()) {
			try {
				int hours = Integer.parseInt(matcher.group(1));
				int minutes = Integer.parseInt(matcher.group(2));
				int seconds = Integer.parseInt(matcher.group(3));

				return LocalDateTime.now().plus(hours, ChronoUnit.HOURS).plus(minutes, ChronoUnit.MINUTES).plus(seconds,
						ChronoUnit.SECONDS);
			} catch (NumberFormatException e) {
				logError("Error parsing time from OCR text: '" + ocrText + "'", e);
			}
		}

		return LocalDateTime.now().plusMinutes(1); // Default to 1 minute if parsing fails
	}

	private MarchesAvailable getMarchesAvailable() {
		// open active marches panel
		emuManager.tapAtPoint(EMULATOR_NUMBER, new DTOPoint(2, 550));
		sleepTask(500);
		emuManager.tapAtPoint(EMULATOR_NUMBER, new DTOPoint(340, 265));
		sleepTask(500);
		// OCR Search for an empty march
		try {
			for (int i = 0; i < 10; i++) { // search 10x for the OCR text
				String ocrSearchResult = emuManager.ocrRegionText(EMULATOR_NUMBER, new DTOPoint(10, 342),
						new DTOPoint(435, 772));
				Pattern idleMarchesPattern = Pattern.compile("idle");
				Matcher m = idleMarchesPattern.matcher(ocrSearchResult.toLowerCase());
				if (m.find()) {
					logInfo("Idle marches detected, continuing with intel");
					return new MarchesAvailable(true, null);
				} else {
					logInfo("No idle marches detected, trying again (Attempt " + (i + 1) + "/10).");
				}
			}
		} catch (IOException | TesseractException e) {
			logDebug("OCR attempt failed: " + e.getMessage());
		}
		logInfo("No idle marches detected. Checking for used march queues...");

		if (checkMarchesAvailable()) {
			return new MarchesAvailable(true, null);
		}

		// Collect active march queue counts
		int totalMarchesAvailable = profile.getConfig(EnumConfigurationKey.GATHER_ACTIVE_MARCH_QUEUE_INT,
				Integer.class);
		int activeMarchQueues = 0;
		LocalDateTime earliestAvailableMarch = LocalDateTime.now().plusHours(14); // Set to earliest available march to
																					// a very long time (impossible for
																					// gatherer to take so long)
		for (GatherType gatherType : GatherType.values()) { // iterate over all the gather types
            DTOImageSearchResult resource = searchTemplateWithRetries(gatherType.getTemplate());
            if (!resource.isFound()) {
                logInfo("March queue for " + gatherType.getName()
                        + " is not used. Checking for next available march queue... (Used: " + activeMarchQueues + "/"
                        + totalMarchesAvailable + ")");
                continue;
            }
            // march is detected
            activeMarchQueues++;
            logInfo("March queue for " + gatherType.getName() + " found. (Used: "
                    + activeMarchQueues + "/" + totalMarchesAvailable + ")");
            LocalDateTime task = iDailyTaskRepository
                    .findByProfileIdAndTaskName(profile.getId(), gatherType.getTask()).getNextSchedule();
            if (task.isBefore(earliestAvailableMarch)) {
                earliestAvailableMarch = task;
                logInfo("Updated earliest available march: " + earliestAvailableMarch);
            }
		}
		if (activeMarchQueues >= totalMarchesAvailable) {
			logInfo("All march queues used. Earliest available march: " + earliestAvailableMarch);
			return new MarchesAvailable(false, earliestAvailableMarch);
		}
		// there MAY be some returning marches, rescheduling for the near future to
		// check later
		logInfo("No idle marches detected. Not all marches are used. Suspected auto-rally marches. Setting 5 minute delay for any marches to return. ");
		return new MarchesAvailable(false, LocalDateTime.now().plusMinutes(5));
	}

	@Override
	protected EnumStartLocation getRequiredStartLocation() {
		return EnumStartLocation.WORLD;
	}

    @Override
    protected boolean consumesStamina() {
        return true;
    }

	private enum GatherType {
		MEAT("meat", EnumTemplates.GAME_HOME_SHORTCUTS_MEAT, TpDailyTaskEnum.GATHER_MEAT),
		WOOD("wood", EnumTemplates.GAME_HOME_SHORTCUTS_WOOD, TpDailyTaskEnum.GATHER_WOOD),
		COAL("coal", EnumTemplates.GAME_HOME_SHORTCUTS_COAL, TpDailyTaskEnum.GATHER_COAL),
		IRON("iron", EnumTemplates.GAME_HOME_SHORTCUTS_IRON, TpDailyTaskEnum.GATHER_IRON);

		final String name;
		final EnumTemplates template;
		final TpDailyTaskEnum task;

		GatherType(String name, EnumTemplates enumTemplate, TpDailyTaskEnum task) {
			this.name = name;
			this.template = enumTemplate;
			this.task = task;
		}

		public String getName() {
			return name;
		}

		public EnumTemplates getTemplate() {
			return template;
		}

		public TpDailyTaskEnum getTask() {
			return task;
		}
	}

	public record MarchesAvailable(boolean available, LocalDateTime rescheduleTo) {
	}
}
