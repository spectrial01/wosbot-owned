package cl.camodev.wosbot.serv.task.impl;

import cl.camodev.utiles.UtilRally;
import cl.camodev.utiles.UtilTime;
import cl.camodev.wosbot.almac.entity.DailyTask;
import cl.camodev.wosbot.almac.repo.DailyTaskRepository;
import cl.camodev.wosbot.almac.repo.IDailyTaskRepository;
import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import cl.camodev.wosbot.console.enumerable.EnumTemplates;
import cl.camodev.wosbot.console.enumerable.TpDailyTaskEnum;
import cl.camodev.wosbot.ot.DTOImageSearchResult;
import cl.camodev.wosbot.ot.DTOPoint;
import cl.camodev.wosbot.ot.DTOProfiles;
import cl.camodev.wosbot.serv.impl.ServTaskManager;
import cl.camodev.wosbot.serv.task.DelayedTask;
import cl.camodev.wosbot.serv.task.EnumStartLocation;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class PolarTerrorHuntingTask extends DelayedTask {
    private final int refreshStaminaLevel = 180;
    private final int minStaminaLevel = 100;
    private final IDailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();
    private final ServTaskManager servTaskManager = ServTaskManager.getInstance();
    private Integer currentStamina = null;

    public PolarTerrorHuntingTask(DTOProfiles profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        logInfo("=== Starting Polar Terror Hunting Task ===");

        if (isBearRunning()) {
            LocalDateTime rescheduleTo = LocalDateTime.now().plusMinutes(30);
            logInfo("Bear Hunt is running, rescheduling for " + rescheduleTo);
            reschedule(rescheduleTo);
            return;
        }
        logDebug("Bear Hunt is not running, continuing with Polar Terror Hunting Task");

        String flagString = profile.getConfig(EnumConfigurationKey.POLAR_TERROR_FLAG_STRING, String.class);
        int flagNumber = 0;
        int polarTerrorLevel = profile.getConfig(EnumConfigurationKey.POLAR_TERROR_LEVEL_INT, Integer.class);
        boolean limitedHunting = profile.getConfig(EnumConfigurationKey.POLAR_TERROR_MODE_STRING, String.class)
                .equals("Limited (10)");
        boolean useFlag = false;

        if (flagString != null) {
            try {
                flagNumber = Integer.parseInt(flagString);
                useFlag = true;
            } catch (NumberFormatException e) {
                useFlag = false;
            }
        }

        if (profile.getConfig(EnumConfigurationKey.INTEL_BOOL, Boolean.class)
                && useFlag
                && servTaskManager.getTaskState(profile.getId(), TpDailyTaskEnum.INTEL.getId()).isScheduled()) {
            // Make sure intel isn't about to run
            DailyTask intel = iDailyTaskRepository.findByProfileIdAndTaskName(profile.getId(), TpDailyTaskEnum.INTEL);
            if (ChronoUnit.MINUTES.between(LocalDateTime.now(), intel.getNextSchedule()) < 5) {
                reschedule(LocalDateTime.now().plusMinutes(35)); // Reschedule in 35 minutes, after intel has run
                logWarning("Intel task is scheduled to run soon. Rescheduling Polar Hunt to run 30min after intel.");
                return;
            }
        }

        logInfo(String.format("Configuration: Level %d | %s Mode | Flag: %s",
                polarTerrorLevel,
                limitedHunting ? "Limited (10 hunts)" : "Unlimited",
                useFlag ? "#" + flagString : "None"));

        // Verify if there's enough stamina to hunt, if not, reschedule the task
        if (!hasEnoughStaminaAndMarches(minStaminaLevel, refreshStaminaLevel))
            return;

        // Flag mode: Send single rally
        if (useFlag) {
            logInfo("Launching rally with flag #" + flagNumber);
            int result = launchSingleRally(polarTerrorLevel, useFlag, flagNumber);
            handleRallyResult(result, useFlag);
            return;
        }

        // No-flag mode: Send multiple rallies
        logInfo("Starting rally loop (no-flag mode)");
        int ralliesDeployed = 0;
        while (true) {
            // Check marches before each rally
            if (!checkMarchesAvailable()) {
                logInfo("No marches available after " + ralliesDeployed + " rallies. Waiting for marches to return.");
                reschedule(LocalDateTime.now().plusMinutes(10));
                return;
            }

            // Check limited hunting limit
            if (limitedHunting && !polarsRemaining(polarTerrorLevel)) {
                return; // Already rescheduled in polarsRemaining
            }

            int result = launchSingleRally(polarTerrorLevel, false, 0);

            if (result == -1) {
                // OCR error - can't continue reliably
                logError("OCR error occurred. Rescheduling in 5 minutes.");
                reschedule(LocalDateTime.now().plusMinutes(5));
                return;
            }

            if (result == 0) {
                // Deployment failed - probably out of marches
                logInfo("Deployment failed after " + ralliesDeployed + " rallies. Rescheduling in 5 minutes.");
                reschedule(LocalDateTime.now().plusMinutes(5));
                return;
            }

            if (result == 3) {
                // Low stamina - already rescheduled in launchSingleRally
                logInfo("Stamina too low after " + ralliesDeployed + " rallies. Task rescheduled.");
                return;
            }

            // result == 1: success
            ralliesDeployed++;
            logInfo("Rally #" + ralliesDeployed + " deployed successfully. Current stamina: " + currentStamina);
            sleepTask(1500);
        }

    }

    /**
     * Launches a single polar rally without recursion
     * 
     * @return -1 if OCR error occurred
     * @return 0 if deployment failed
     * @return 1 if deployment successful (no-flag mode)
     * @return 2 if deployment successful (flag mode)
     * @return 3 if deployment successful but stamina too low to continue
     */
    private int launchSingleRally(int polarLevel, boolean useFlag, int flagNumber) {
        ensureCorrectScreenLocation(getRequiredStartLocation());

        // Open polars menu
        logInfo("Navigating to polars menu");
        if (!openPolarsMenu(polarLevel)) {
            logError("Failed to open polars menu.");
            return 0;
        }

        // Open rally menu
        logInfo("Navigating to rally menu");
        if (!openRallyMenu()) {
            logError("Failed to open rally menu.");
            return 0;
        }

        // Tap "Hold a Rally" button
        tapRandomPoint(new DTOPoint(275, 821), new DTOPoint(444, 856), 1, 400);
        sleepTask(500);

        // Select flag if needed
        if (useFlag) {
            tapPoint(UtilRally.getMarchFlagPoint(flagNumber));
            sleepTask(300);
        }

        // Parse travel time
        long travelTimeSeconds = parseTravelTime();

        Integer spentStamina = getSpentStamina();

        // Deploy march
        DTOImageSearchResult deploy = searchTemplateWithRetries(EnumTemplates.DEPLOY_BUTTON, 90, 3);

        if (!deploy.isFound()) {
            logDebug("Deploy button not found. Rescheduling to try again in 5 minutes.");
            return 0;
        }

        tapPoint(deploy.getPoint());
        sleepTask(2000);

        deploy = searchTemplateWithRetries(EnumTemplates.DEPLOY_BUTTON, 90, 3);
        if (deploy.isFound()) {
            // Probably march got taken by auto-join or something
            logInfo("Deploy button still found after trying to deploy march. Rescheduling to try again in 5 minutes.");
            return 0;
        }

        logInfo("March deployed successfully.");

        // Update stamina
        subtractStamina(spentStamina, true);

        // Flag mode: reschedule for march return
        if (useFlag) {
            if (travelTimeSeconds <= 0) {
                logError("Failed to parse travel time via OCR. Cannot accurately reschedule for march return.");
                return -1;
            }
            long returnTimeSeconds = travelTimeSeconds * 2 + 2;
            LocalDateTime rescheduleTime = LocalDateTime.now().plusSeconds(returnTimeSeconds).plusMinutes(5);
            reschedule(rescheduleTime);
            logInfo("Rally with flag scheduled to return in " + UtilTime.localDateTimeToDDHHMMSS(rescheduleTime));
            return 2;
        }

        // No-flag mode: check stamina for next rally
        if (currentStamina <= minStaminaLevel) {
            logInfo("Stamina is at or below minimum. Stopping deployment and rescheduling.");
            reschedule(LocalDateTime.now().plusMinutes(staminaRegenerationTime(currentStamina, refreshStaminaLevel)));
            return 3;
        }

        return 1;
    }

    private void handleRallyResult(int result, boolean useFlag) {
        if (result == -1) {
            if (useFlag) {
                logWarning("March deployed with flag but travel time unknown. Using fallback reschedule.");
                reschedule(LocalDateTime.now().plusMinutes(10));
            } else {
                reschedule(LocalDateTime.now().plusMinutes(5));
            }
            return;
        }

        if (result == 0) {
            if (useFlag) {
                logError("Failed to deploy march. Trying again in 5 minutes.");
                reschedule(LocalDateTime.now().plusMinutes(5));
            }
        }

        // Results 2 and 3 already handle their own rescheduling
    }

    private boolean openRallyMenu() {
        // Search for rally button
        DTOImageSearchResult rallyButton = searchTemplateWithRetries(EnumTemplates.RALLY_BUTTON, 90, 3);
        sleepTask(500);

        if (!rallyButton.isFound()) {
            logDebug("Rally button not found.");
            sleepTask(500);
            return false;
        }

        tapPoint(rallyButton.getPoint());
        sleepTask(1000);
        return true;
    }

    private boolean openPolarsMenu(int polarLevel) {
        // Navigate to the specified polar terror level
        // Open search (magnifying glass)
        tapRandomPoint(new DTOPoint(25, 850), new DTOPoint(67, 898));
        sleepTask(2000);

        // Swipe left to find polar terror icon
        swipe(new DTOPoint(40, 913), new DTOPoint(678, 913));
        sleepTask(500);

        // Search the polar terror search icon
        DTOImageSearchResult polarTerror = searchTemplateWithRetries(EnumTemplates.POLAR_TERROR_SEARCH_ICON, 90, 3);
        logDebug("Searching for Polar Terror icon");
        for (int i = 0; i < 3 && !polarTerror.isFound(); i++) {
            swipe(new DTOPoint(40, 913), new DTOPoint(678, 913));
            sleepTask(500);
            polarTerror = searchTemplateWithRetries(EnumTemplates.POLAR_TERROR_SEARCH_ICON, 90, 3);
        }

        if (!polarTerror.isFound()) {
            logWarning("Failed to find the polar terrors.");
            return false;
        }

        // Need to tap on the polar terror icon and check the current level selected
        tapPoint(polarTerror.getPoint());
        if (polarLevel != -1) {
            logInfo(String.format("Adjusting Polar Terror level to %d", polarLevel));
            swipe(new DTOPoint(435, 1052), new DTOPoint(40, 1052)); // Swipe to level
                                                                    // 1
            sleepTask(300);
            if (polarLevel > 1) {
                tapRandomPoint(new DTOPoint(487, 1055), new DTOPoint(487, 1055),
                        (polarLevel - 1), 200);
            }

        }
        // tap on search button
        logDebug("Tapping on search button...");
        tapRandomPoint(new DTOPoint(301, 1200), new DTOPoint(412, 1229));
        sleepTask(4000);
        return true;
    }

    private boolean polarsRemaining(int polarLevel) {
        if (!openPolarsMenu(polarLevel)) {
            return false;
        }

        // Need to search for the magnifying glass icon to be sure we're on the search
        // screen
        DTOImageSearchResult magnifyingGlass = searchTemplateWithRetries(
                EnumTemplates.POLAR_TERROR_TAB_MAGNIFYING_GLASS_ICON, 90, 3);
        logDebug("Searching for magnifying glass icon");
        sleepTask(500);

        if (!magnifyingGlass.isFound()) {
            return false;
        }

        // Need to scroll down a little bit and search for the remaining hunts "Special
        // Rewards (n left)"
        tapPoint(magnifyingGlass.getPoint());
        sleepTask(2000);

        DTOImageSearchResult specialRewardsCompleted = searchTemplateWithRetries(
                EnumTemplates.POLAR_TERROR_TAB_SPECIAL_REWARDS, 90, 1);
        for (int i = 0; i < 5 && !specialRewardsCompleted.isFound(); i++) {
            specialRewardsCompleted = searchTemplateWithRetries(EnumTemplates.POLAR_TERROR_TAB_SPECIAL_REWARDS,
                    90, 1);
            if (specialRewardsCompleted.isFound()) {
                // Due to limited mode being enabled, and there's no special rewards left,
                // means there's no hunts left
                logWarning(
                        "No special rewards left, meaning there's no hunts left for today. Rescheduling task for reset");
                // Add 30 minutes to let intel and other tasks be processed
                reschedule(UtilTime.getGameReset().plusMinutes(30));
                return false;
            }
            swipe(new DTOPoint(363, 1088), new DTOPoint(363, 1030));
            sleepTask(300);
        }

        // There are still rallies available, continue navigating to world screen
        tapBackButton();
        sleepTask(500);
        return true;
    }

    @Override
    protected EnumStartLocation getRequiredStartLocation() {
        return EnumStartLocation.WORLD;
    }

}
