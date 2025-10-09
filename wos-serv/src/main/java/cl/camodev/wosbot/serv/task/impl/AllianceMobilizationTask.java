package cl.camodev.wosbot.serv.task.impl;

import cl.camodev.utiles.UtilTime;
import cl.camodev.wosbot.console.enumerable.EnumTemplates;
import cl.camodev.wosbot.console.enumerable.TpDailyTaskEnum;
import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import cl.camodev.wosbot.ot.DTOImageSearchResult;
import cl.camodev.wosbot.ot.DTOPoint;
import cl.camodev.wosbot.ot.DTOProfiles;
import cl.camodev.wosbot.serv.task.DelayedTask;
import cl.camodev.wosbot.serv.task.EnumStartLocation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task responsible for Alliance Mobilization.
 * It will use OCR and image recognition to select and perform tasks.
 */
public class AllianceMobilizationTask extends DelayedTask {

    private static final Pattern ATTEMPTS_PATTERN = Pattern.compile("(\\d{1,2})\\s*/\\s*(\\d{0,3})");
    private static final DTOPoint ATTEMPTS_COUNTER_TOP_LEFT = new DTOPoint(168, 528);
    private static final DTOPoint ATTEMPTS_COUNTER_BOTTOM_RIGHT = new DTOPoint(235, 565);

    public AllianceMobilizationTask(DTOProfiles profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        logInfo("Alliance Mobilization - Starting task for profile: " + profile.getName());

        // Check if Alliance Mobilization is enabled
        boolean allianceMobilizationEnabled = profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_BOOL, Boolean.class);
        if (!allianceMobilizationEnabled) {
            logInfo("Alliance Mobilization is disabled. Skipping task.");
            this.setRecurring(false);
            return;
        }

        // Read Auto Accept configuration
        boolean autoAcceptEnabled = profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_AUTO_ACCEPT_BOOL, Boolean.class);

        // Navigate to the Alliance Mobilization screen
        if (!navigateToAllianceMobilization()) {
            LocalDateTime nextMonday = UtilTime.getNextMondayUtc();
            logInfo("Failed to navigate. Event may not be active. Retrying on next Monday at " + nextMonday + ".");
            this.reschedule(nextMonday);
            return;
        }

        AttemptStatus attemptStatus = readAttemptsCounter();
        if (attemptStatus != null) {
            String totalDisplay = attemptStatus.total() != null && attemptStatus.total() > 0
                    ? attemptStatus.total().toString()
                    : "?";
            logInfo("Detected attempts counter: " + attemptStatus.remaining() + "/" + totalDisplay);
            if (attemptStatus.remaining() <= 0) {
                LocalDateTime nextReset = UtilTime.getGameReset();
                logInfo("No attempts remaining. Rescheduling for next UTC reset at " + nextReset + ".");
                this.reschedule(nextReset);
                return;
            }
        } else {
            logWarning("Unable to detect attempts counter. Proceeding with default processing.");
        }

        // Analyze and perform tasks
        boolean rescheduleWasSet = analyzeAndPerformTasks(autoAcceptEnabled);

        // Default reschedule: Check again in 5 minutes if no other reschedule was set
        if (!rescheduleWasSet) {
            logInfo("No tasks processed. Checking again in 5 minutes.");
            LocalDateTime nextRun = LocalDateTime.now().plusMinutes(5);
            this.reschedule(nextRun);
        }

        logInfo("Alliance Mobilization - Task completed");
    }

    private boolean navigateToAllianceMobilization() {
        logInfo("Navigating to Alliance Mobilization...");

        // Step 1: Click the Events button to open the events screen
        logDebug("Searching for Events button on home screen...");
        DTOImageSearchResult eventsButton = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.HOME_EVENTS_BUTTON, 90);
        if (!eventsButton.isFound()) {
            logWarning("Events button not found on home screen.");
                return false;
        }
        logDebug("Events button found at: " + eventsButton.getPoint());
        emuManager.tapAtPoint(EMULATOR_NUMBER, eventsButton.getPoint());
        sleepTask(2000); // Increased wait time for events screen to load

        // Step 2: Try to find the event via tabs, similar to JourneyOfLight
        logDebug("Searching for Alliance Mobilization tabs...");
        DTOImageSearchResult selectedTab = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.ALLIANCE_MOBILIZATION_TAB, 90);
        DTOImageSearchResult unselectedTab = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.ALLIANCE_MOBILIZATION_UNSELECTED_TAB, 90);

        logDebug("Selected tab found: " + selectedTab.isFound());
        logDebug("Unselected tab found: " + unselectedTab.isFound());

        if (selectedTab.isFound()) {
            logInfo("Alliance Mobilization tab is already selected at: " + selectedTab.getPoint());
        } else if (unselectedTab.isFound()) {
            logInfo("Found unselected Alliance Mobilization tab at: " + unselectedTab.getPoint() + ", clicking it.");
            emuManager.tapAtPoint(EMULATOR_NUMBER, unselectedTab.getPoint());
            sleepTask(2000);
        } else {
            logInfo("Alliance Mobilization tabs not found, swiping to search for them...");

            // Search for tabs by swiping left to right (shows tabs to the right)
            boolean tabFound = false;
            for (int i = 0; i < 2; i++) {
                logDebug("Tab search attempt " + (i + 1) + "/2");

                // Check for tabs after each swipe
                DTOImageSearchResult selectedTabAfterSwipe = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.ALLIANCE_MOBILIZATION_TAB, 90);
                DTOImageSearchResult unselectedTabAfterSwipe = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.ALLIANCE_MOBILIZATION_UNSELECTED_TAB, 90);

                if (selectedTabAfterSwipe.isFound()) {
                    logInfo("Found selected Alliance Mobilization tab after swipe " + (i + 1) + " at: " + selectedTabAfterSwipe.getPoint());
                    tabFound = true;
                    break;
                } else if (unselectedTabAfterSwipe.isFound()) {
                    logInfo("Found unselected Alliance Mobilization tab after swipe " + (i + 1) + " at: " + unselectedTabAfterSwipe.getPoint());
                    emuManager.tapAtPoint(EMULATOR_NUMBER, unselectedTabAfterSwipe.getPoint());
                    sleepTask(2000);
                    tabFound = true;
                    break;
                }

                // Swipe left to right to see more tabs (centered for 720x1280)
                if (i < 4) {
                    logDebug("Swiping left to right...");
                    emuManager.executeSwipe(EMULATOR_NUMBER, new DTOPoint(100, 158), new DTOPoint(620, 158));
                    sleepTask(500);
                }
            }

            if (!tabFound) {
                logWarning("Alliance Mobilization tabs not found after multiple swipes. Event may not be active.");
                return false;
            }
        }

        logInfo("Successfully navigated to Alliance Mobilization.");
        return true;
    }

    private boolean analyzeAndPerformTasks(boolean autoAcceptEnabled) {
        // Get user configuration for reward percentage filter
        String rewardsPercentage = profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_REWARDS_PERCENTAGE_STRING, String.class);
    int legacyMinimumPoints = profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_MINIMUM_POINTS_INT, Integer.class);
    int minimumPoints200 = resolveMinimumPointsThreshold(profile, EnumConfigurationKey.ALLIANCE_MOBILIZATION_MINIMUM_POINTS_200_INT, legacyMinimumPoints);
    int minimumPoints120 = resolveMinimumPointsThreshold(profile, EnumConfigurationKey.ALLIANCE_MOBILIZATION_MINIMUM_POINTS_120_INT, legacyMinimumPoints);

        logInfo("Searching for tasks (Filter: " + rewardsPercentage + ", Min points 200%: " + minimumPoints200 + ", Min points 120%: " + minimumPoints120 + ", Auto-accept: " + autoAcceptEnabled + ")");

        // Search and process tasks based on bonus percentage
        // Returns true if a reschedule was set
        return searchAndProcessTasksByBonus(rewardsPercentage, minimumPoints200, minimumPoints120, autoAcceptEnabled);
    }

    private AttemptStatus readAttemptsCounter() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String ocrResult = emuManager.ocrRegionText(EMULATOR_NUMBER, ATTEMPTS_COUNTER_TOP_LEFT, ATTEMPTS_COUNTER_BOTTOM_RIGHT);
                debugOCRArea("Attempts counter (attempt " + attempt + ")", ATTEMPTS_COUNTER_TOP_LEFT, ATTEMPTS_COUNTER_BOTTOM_RIGHT, ocrResult);

                if (ocrResult == null || ocrResult.trim().isEmpty()) {
                    sleepTask(200);
                    continue;
                }

                String normalized = ocrResult
                        .replace('O', '0')
                        .replace('o', '0')
                        .replace('I', '1')
                        .replace('l', '1')
                        .replace('|', '1')
                        .replaceAll("[^0-9/]+", " ")
                        .trim();
                Matcher matcher = ATTEMPTS_PATTERN.matcher(normalized);
                if (matcher.find()) {
                    int remaining = Integer.parseInt(matcher.group(1));
                    Integer total = null;
                    String totalGroup = matcher.group(2);
                    if (totalGroup != null && !totalGroup.isEmpty()) {
                        total = Integer.parseInt(totalGroup);
                        if (total == 0) {
                            logDebug("OCR attempts counter returned total=0, retrying...");
                            continue;
                        }
                    }
                    return new AttemptStatus(remaining, total);
                }

                String condensed = normalized.replaceAll("\\s+", "");
                if (condensed.startsWith("0/")) {
                    Integer total = null;
                    String afterSlash = condensed.substring(2).replaceAll("[^0-9]", "");
                    if (!afterSlash.isEmpty()) {
                        total = Integer.parseInt(afterSlash);
                    }
                    return new AttemptStatus(0, total);
                }
            } catch (Exception e) {
                logDebug("Failed to read attempts counter via OCR (attempt " + attempt + "): " + e.getMessage());
            }

            sleepTask(200);
        }

        logWarning("Unable to capture attempts counter after multiple tries.");
        return null;
    }

    private int resolveMinimumPointsThreshold(DTOProfiles profile, EnumConfigurationKey key, int fallbackValue) {
        boolean hasExplicitConfig = profile.getConfigs().stream()
            .anyMatch(cfg -> cfg.getConfigurationName().equalsIgnoreCase(key.name()));

        if (hasExplicitConfig) {
            return profile.getConfig(key, Integer.class);
        }

        profile.setConfig(key, fallbackValue);
        return fallbackValue;
    }

    private boolean searchAndProcessTasksByBonus(String rewardsPercentage, int minimumPoints200, int minimumPoints120, boolean autoAcceptEnabled) {
        // First, check for completed tasks to collect rewards
        checkAndCollectCompletedTasks();

        // Check for free mission button
        checkAndUseFreeMission();

        // Check for Alliance Monuments button
        checkAndUseAllianceMonuments();

        // Track the shortest cooldown time from all refreshed tasks
        int shortestCooldownSeconds = Integer.MAX_VALUE;
        boolean rescheduleWasSet = false;

    // Determine which templates to search for based on user selection
    boolean accept200 = rewardsPercentage.equals("200%") || rewardsPercentage.equals("Both") || rewardsPercentage.equals("Any");
    boolean accept120 = rewardsPercentage.equals("120%") || rewardsPercentage.equals("Both") || rewardsPercentage.equals("Any");

    // Always inspect both bonus levels so non-selected ones can be refreshed
    boolean search200 = accept200 || rewardsPercentage.equals("120%");
    boolean search120 = accept120 || rewardsPercentage.equals("200%");

        // First check if ANY task is already running - only one task can run at a time
        // If a task is running, we can still refresh other tasks but cannot accept them
        boolean anyTaskRunning = false;

        // Check 200% task
        if (search200) {
            DTOImageSearchResult result200 = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.AM_200_PERCENT, 85);
            if (result200.isFound() && isTaskAlreadyRunning(result200.getPoint())) {
                logInfo("Task at 200% is already running");
                anyTaskRunning = true;
            }
        }

        // Check 120% tasks
        if (!anyTaskRunning && search120) {
            List<DTOImageSearchResult> results120 = emuManager.searchTemplates(EMULATOR_NUMBER, EnumTemplates.AM_120_PERCENT, 85, 2);
            if (results120 != null && !results120.isEmpty()) {
                for (DTOImageSearchResult result120 : results120) {
                    if (isTaskAlreadyRunning(result120.getPoint())) {
                        logInfo("Task at 120% (" + result120.getPoint() + ") is already running");
                        anyTaskRunning = true;
                        break;
                    }
                }
            }
        }

        if (anyTaskRunning) {
            logInfo("A task is already running - only one task can run at a time");
            logInfo("Other tasks will be refreshed to get better options");
        }

        // Search for available tasks to accept or refresh (if another task is running, treat all as disabled)
        // Search for 200% bonus (only 1 can exist)
        if (search200) {
            logDebug("Searching for 200% bonus task...");
            DTOImageSearchResult result200 = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.AM_200_PERCENT, 85);
            debugTemplateSearch("AM_200_PERCENT", result200, 85);

            if (result200.isFound()) {

                // Skip this task if it's already running (has timer bar)
                if (isTaskAlreadyRunning(result200.getPoint())) {
                    logInfo("Task at 200% is already running - skipping this one");
                    // Don't return yet, check if there are 120% tasks available
                } else {
                    // Read points from overview screen BEFORE clicking
                    int detectedPoints = readPointsNearBonus(result200.getPoint());
                    if (detectedPoints < 0) {
                        logWarning("Could not read points for 200% task - skipping");
                        // Continue to check 120% tasks
                    } else {
                        // Check task type near the bonus indicator
                        EnumTemplates taskType = detectTaskTypeNearBonus(result200.getPoint());
                        if (taskType != null) {
                            logInfo("Task type detected: " + taskType.name());
                            boolean isEnabled = isTaskTypeEnabled(taskType);

                            // Decide what to do based on task criteria
                            TaskProcessResult result = processTaskWithPoints(result200.getPoint(), taskType, isEnabled, detectedPoints, minimumPoints200, anyTaskRunning, autoAcceptEnabled, "200%", rewardsPercentage);
                            if (result.cooldownSeconds > 0 && result.cooldownSeconds < shortestCooldownSeconds) {
                                shortestCooldownSeconds = result.cooldownSeconds;
                            }
                            if (result.shouldStop) {
                                return true; // Stop checking if we're waiting, reschedule was set
                            }
                            // Otherwise continue to check 120% tasks
                        } else {
                            logInfo("Task type not detected");
                        }
                    }
                }
            }
        }

        // Search for 120% bonus tasks (up to 2 can exist)
        if (search120) {
            logDebug("Searching for 120% bonus tasks (max 2 positions)...");
            List<DTOImageSearchResult> results120 = emuManager.searchTemplates(EMULATOR_NUMBER, EnumTemplates.AM_120_PERCENT, 85, 2);

            if (results120 != null && !results120.isEmpty()) {
                logInfo("Found " + results120.size() + " x 120% bonus task(s)");

                for (DTOImageSearchResult result120 : results120) {
                    debugTemplateSearch("AM_120_PERCENT", result120, 85);

                    // Skip this task if it's already running (has timer bar)
                    if (isTaskAlreadyRunning(result120.getPoint())) {
                        logInfo("Task at 120% (" + result120.getPoint() + ") is already running - skipping this one");
                        continue; // Skip to next 120% task
                    }

                    // Read points from overview screen BEFORE clicking
                    int detectedPoints = readPointsNearBonus(result120.getPoint());
                    if (detectedPoints < 0) {
                        logWarning("Could not read points for 120% task - skipping");
                        continue; // Try next 120% task
                    }

                    // Check task type near the bonus indicator
                    EnumTemplates taskType = detectTaskTypeNearBonus(result120.getPoint());
                    if (taskType != null) {
                        logInfo("Task type detected: " + taskType.name());
                        boolean isEnabled = isTaskTypeEnabled(taskType);

                        // Decide what to do based on task criteria
                        TaskProcessResult result = processTaskWithPoints(result120.getPoint(), taskType, isEnabled, detectedPoints, minimumPoints120, anyTaskRunning, autoAcceptEnabled, "120%", rewardsPercentage);
                        if (result.cooldownSeconds > 0 && result.cooldownSeconds < shortestCooldownSeconds) {
                            shortestCooldownSeconds = result.cooldownSeconds;
                        }
                        if (result.shouldStop) {
                            return true; // Stop checking if we're waiting, reschedule was set
                        }
                        // Otherwise continue to check next 120% task
                    } else {
                        logInfo("Task type not detected at " + result120.getPoint());
                    }
                }
            } else {
                logDebug("No 120% bonus tasks found");
            }
        }

        // After processing all tasks, reschedule based on shortest cooldown
        if (shortestCooldownSeconds < Integer.MAX_VALUE) {
            LocalDateTime nextRun = LocalDateTime.now().plusSeconds(shortestCooldownSeconds + 5);
            this.reschedule(nextRun);
            logInfo("Rescheduling based on shortest cooldown: " + shortestCooldownSeconds + " seconds -> " + nextRun);
            rescheduleWasSet = true;
        }

        // If no tasks were found/processed, check for task availability timers
        if (!rescheduleWasSet) {
            int timerSeconds = readTaskAvailabilityTimers();
            if (timerSeconds > 0) {
                LocalDateTime nextRun = LocalDateTime.now().plusSeconds(timerSeconds + 10);
                this.reschedule(nextRun);
                logInfo("Next check in " + (timerSeconds / 60) + "min (task availability timer)");
                rescheduleWasSet = true;
            }
        }

        return rescheduleWasSet;
    }

    private EnumTemplates detectTaskTypeNearBonus(DTOPoint bonusLocation) {
        logDebug("Detecting task type near bonus location: " + bonusLocation);

        // Define task type templates to search for
        EnumTemplates[] taskTypeTemplates = {
            EnumTemplates.AM_BUILD_SPEEDUPS,
            EnumTemplates.AM_BUY_PACKAGE,
            EnumTemplates.AM_CHIEF_GEAR_CHARM,
            EnumTemplates.AM_CHIEF_GEAR_SCORE,
            EnumTemplates.AM_DEFEAT_BEASTS,
            EnumTemplates.AM_FIRE_CRYSTAL,
            EnumTemplates.AM_GATHER_RESOURCES,
            EnumTemplates.AM_HERO_GEAR_STONE,
            EnumTemplates.AM_MYTHIC_SHARD,
            EnumTemplates.AM_RALLY,
            EnumTemplates.AM_TRAIN_TROOPS,
            EnumTemplates.AM_TRAINING_SPEEDUPS,
            EnumTemplates.AM_USE_GEMS,
            EnumTemplates.AM_USE_SPEEDUPS
        };

        // Search for task type icon near the bonus indicator (typically to the left)
        for (EnumTemplates template : taskTypeTemplates) {
            // Search for multiple matches of the same template (max 5)
            List<DTOImageSearchResult> results = emuManager.searchTemplates(EMULATOR_NUMBER, template, 85, 5);

            if (results != null && !results.isEmpty()) {
                // Check each match to find one near the bonus
                for (DTOImageSearchResult result : results) {
                    // Verify the task type icon is near the bonus indicator
                    int deltaX = Math.abs(result.getPoint().getX() - bonusLocation.getX());
                    int deltaY = Math.abs(result.getPoint().getY() - bonusLocation.getY());

                    logDebug("  " + template.name() + " found at (" + result.getPoint().getX() + "," +
                            result.getPoint().getY() + ") - Distance from bonus: ΔX=" + deltaX + "px, ΔY=" + deltaY + "px");

                    // Task icon should be within reasonable distance (adjust based on UI layout)
                    if (deltaX < 150 && deltaY < 100) {
                        logInfo("✅ Detected task type: " + template.name() + " at " + result.getPoint() +
                               " (ΔX=" + deltaX + "px, ΔY=" + deltaY + "px)");
                        return template;
                    } else {
                        logDebug("  ❌ Too far from bonus (max: ΔX=150px, ΔY=100px)");
                    }
                }
            }
        }

        logWarning("No task type detected near bonus location");
        return null;
    }

    private boolean isTaskAlreadyRunning(DTOPoint bonusLocation) {
        logDebug("Checking if task is already running near: " + bonusLocation);

        // Search for AM_Bar_X.png (timer bar with only fixed frame parts, variable progress excluded) below the bonus indicator
        // The bar appears approximately 150-200px below the bonus icon
        DTOPoint searchTopLeft = new DTOPoint(bonusLocation.getX() - 50, bonusLocation.getY() + 100);
        DTOPoint searchBottomRight = new DTOPoint(bonusLocation.getX() + 250, bonusLocation.getY() + 250);

        DTOImageSearchResult barResult = emuManager.searchTemplate(
            EMULATOR_NUMBER,
            EnumTemplates.AM_BAR_X,
            searchTopLeft,
            searchBottomRight,
            85
        );

        if (barResult.isFound()) {
            logInfo("✅ Timer bar detected at " + barResult.getPoint() + " - task is already running");
            return true;
        }

        logDebug("No timer bar detected - task is available");
        return false;
    }

    private boolean isTaskTypeEnabled(EnumTemplates taskType) {
        logDebug("Checking if task type is enabled: " + taskType.name());

        // Map template to configuration key
        switch (taskType) {
            case AM_BUILD_SPEEDUPS:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_BUILD_SPEEDUPS_BOOL, Boolean.class);
            case AM_BUY_PACKAGE:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_BUY_PACKAGE_BOOL, Boolean.class);
            case AM_CHIEF_GEAR_CHARM:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_CHIEF_GEAR_CHARM_BOOL, Boolean.class);
            case AM_CHIEF_GEAR_SCORE:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_CHIEF_GEAR_SCORE_BOOL, Boolean.class);
            case AM_DEFEAT_BEASTS:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_DEFEAT_BEASTS_BOOL, Boolean.class);
            case AM_FIRE_CRYSTAL:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_FIRE_CRYSTAL_BOOL, Boolean.class);
            case AM_GATHER_RESOURCES:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_GATHER_RESOURCES_BOOL, Boolean.class);
            case AM_HERO_GEAR_STONE:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_HERO_GEAR_STONE_BOOL, Boolean.class);
            case AM_MYTHIC_SHARD:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_MYTHIC_SHARD_BOOL, Boolean.class);
            case AM_RALLY:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_RALLY_BOOL, Boolean.class);
            case AM_TRAIN_TROOPS:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_TRAIN_TROOPS_BOOL, Boolean.class);
            case AM_TRAINING_SPEEDUPS:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_TRAINING_SPEEDUPS_BOOL, Boolean.class);
            case AM_USE_GEMS:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_USE_GEMS_BOOL, Boolean.class);
            case AM_USE_SPEEDUPS:
                return profile.getConfig(EnumConfigurationKey.ALLIANCE_MOBILIZATION_USE_SPEEDUPS_BOOL, Boolean.class);
            default:
                logWarning("Unknown task type: " + taskType.name());
                return false;
        }
    }

    private static class TaskProcessResult {
        boolean shouldStop;
        int cooldownSeconds;

        TaskProcessResult(boolean shouldStop, int cooldownSeconds) {
            this.shouldStop = shouldStop;
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    private TaskProcessResult processTaskWithPoints(DTOPoint bonusLocation, EnumTemplates taskType, boolean isTaskTypeEnabled,
                                      int detectedPoints, int minimumPoints, boolean anyTaskRunning, boolean autoAcceptEnabled,
                                      String currentBonusPercentage, String rewardsPercentage) {
        logInfo("Found: " + taskType.name() + " (" + detectedPoints + "pts, " + currentBonusPercentage + ", enabled: " + isTaskTypeEnabled + ")");

        // Check if we should refresh based on bonus mismatch
        boolean shouldRefreshDueToBonus = false;
        if (rewardsPercentage.equals("200%") && currentBonusPercentage.equals("120%")) {
            shouldRefreshDueToBonus = true;
            logInfo("→ Refreshing (120% task but 200% is selected)");
        } else if (rewardsPercentage.equals("120%") && currentBonusPercentage.equals("200%")) {
            shouldRefreshDueToBonus = true;
            logInfo("→ Refreshing (200% task but 120% is selected)");
        }

        if (shouldRefreshDueToBonus) {
            int cooldown = clickAndRefreshTask(bonusLocation);
            return new TaskProcessResult(false, cooldown);
        }

        // Decision logic based on points, enabled status, and whether another task is running
        boolean pointsMeetMinimum = detectedPoints >= minimumPoints;

        if (!isTaskTypeEnabled) {
            logInfo("→ Refreshing (disabled)");
            int cooldown = clickAndRefreshTask(bonusLocation);
            return new TaskProcessResult(false, cooldown);
        } else if (!pointsMeetMinimum) {
            logInfo("→ Refreshing (low points: " + detectedPoints + " < " + minimumPoints + ")");
            int cooldown = clickAndRefreshTask(bonusLocation);
            return new TaskProcessResult(false, cooldown);
        } else if (anyTaskRunning) {
            logInfo("→ Waiting 1h (task good but another task running)");
            LocalDateTime nextRun = LocalDateTime.now().plusHours(1);
            this.reschedule(nextRun);
            return new TaskProcessResult(true, 0);
        } else {
            if (autoAcceptEnabled) {
                logInfo("→ Accepting task");
                clickAndAcceptTask(bonusLocation);
                return new TaskProcessResult(false, 0);
            } else {
                logInfo("→ Skipping (auto-accept disabled)");
                return new TaskProcessResult(false, 0);
            }
        }
    }

    private int clickAndRefreshTask(DTOPoint bonusLocation) {
        emuManager.tapAtPoint(EMULATOR_NUMBER, bonusLocation);
        sleepTask(2000);

        DTOPoint refreshButtonLocation = new DTOPoint(200, 805);
        emuManager.tapAtPoint(EMULATOR_NUMBER, refreshButtonLocation);
        sleepTask(1500);

        DTOPoint timerTopLeft = new DTOPoint(375, 610);
        DTOPoint timerBottomRight = new DTOPoint(490, 642);
        int cooldownSeconds = readRefreshCooldownFromPopup(timerTopLeft, timerBottomRight);

        DTOPoint refreshConfirmButtonLocation = new DTOPoint(510, 790);
        emuManager.tapAtPoint(EMULATOR_NUMBER, refreshConfirmButtonLocation);
        sleepTask(1500);

        logInfo("  Cooldown: " + cooldownSeconds + "s");
        return cooldownSeconds;
    }

    private void clickAndAcceptTask(DTOPoint bonusLocation) {
        emuManager.tapAtPoint(EMULATOR_NUMBER, bonusLocation);
        sleepTask(2000);

        DTOPoint acceptButtonLocation = new DTOPoint(500, 805);
        emuManager.tapAtPoint(EMULATOR_NUMBER, acceptButtonLocation);
        sleepTask(1500);
    }

    private int readRefreshCooldownFromPopup(DTOPoint topLeft, DTOPoint bottomRight) {
        logDebug("Reading refresh cooldown from popup: " + topLeft + " to " + bottomRight);

        // Wait a bit for popup to fully render
        sleepTask(500);

        try {
            String ocrResult = emuManager.ocrRegionText(EMULATOR_NUMBER, topLeft, bottomRight);
            logInfo("OCR popup result: '" + (ocrResult != null ? ocrResult : "null") + "'");
            debugOCRArea("Cooldown popup", topLeft, bottomRight, ocrResult);

            if (ocrResult != null && !ocrResult.trim().isEmpty()) {
                // Parse time format: "5mins!", "10mins", "1min", etc.
                int seconds = parseTimeToSeconds(ocrResult);
                if (seconds > 0) {
                    logInfo("✅ Cooldown from popup: " + seconds + " seconds (from: '" + ocrResult + "')");
                    return seconds;
                } else {
                    logWarning("OCR returned text but could not parse time: '" + ocrResult + "'");
                }
            } else {
                logWarning("OCR returned empty or null result");
            }
        } catch (Exception e) {
            logWarning("Failed to read cooldown from popup: " + e.getMessage());
            e.printStackTrace();
        }

        logWarning("Could not read cooldown from confirmation popup, using default 5 minutes");
        return 300; // Default 5 minutes if OCR fails
    }

    private int parseTimeToSeconds(String timeString) {
        // Remove all spaces and normalize common variations
        String cleaned = timeString.replaceAll("\\s+", "").toLowerCase();

        // Remove common prefix words
        cleaned = cleaned.replaceAll("^(in|r|ear)", "");

        // Fix common OCR errors for HH:mm:ss format
        // Numbers: O/o -> 0, l/I -> 1, Z -> 2, S -> 5
        cleaned = cleaned.replaceAll("O", "0");
        cleaned = cleaned.replaceAll("o", "0");
        cleaned = cleaned.replaceAll("l(?=\\d|:)", "1"); // l followed by digit or colon
        cleaned = cleaned.replaceAll("I(?=\\d|:)", "1"); // I followed by digit or colon
        cleaned = cleaned.replaceAll("Z(?=\\d|:)", "2");
        cleaned = cleaned.replaceAll("S(?=\\d|:)", "5");

        // Colon errors: i, l, I, | -> :
        cleaned = cleaned.replaceAll("(?<=\\d)[il\\|I](?=\\d)", ":");

        // Fix common OCR errors: "Smins" -> "5mins", "Zmins" -> "2mins"
        cleaned = cleaned.replaceAll("smins", "5mins");
        cleaned = cleaned.replaceAll("zmins", "2mins");

        // Normalize: "mins" -> "m", "min" -> "m", "secs" -> "s", "sec" -> "s"
        cleaned = cleaned.replaceAll("mins?", "m");
        cleaned = cleaned.replaceAll("secs?", "s");
        cleaned = cleaned.replaceAll("hours?", "h");
        // Remove exclamation marks and other punctuation
        cleaned = cleaned.replaceAll("[!.,]", "");

        try {
            // Primary Format: "HH:mm:ss" or "H:mm:ss" (hours:minutes:seconds)
            if (cleaned.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
                String[] parts = cleaned.split(":");
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                int seconds = Integer.parseInt(parts[2]);
                return hours * 3600 + minutes * 60 + seconds;
            }

            // Fallback Format: "mm:ss" or "m:ss" (minutes:seconds)
            if (cleaned.matches("\\d{1,2}:\\d{2}")) {
                String[] parts = cleaned.split(":");
                int minutes = Integer.parseInt(parts[0]);
                int seconds = Integer.parseInt(parts[1]);
                return minutes * 60 + seconds;
            }

            // Format: "5m30s" or "5m 30s"
            if (cleaned.contains("m") && cleaned.contains("s")) {
                String minutesStr = cleaned.substring(0, cleaned.indexOf("m"));
                String secondsStr = cleaned.substring(cleaned.indexOf("m") + 1, cleaned.indexOf("s"));
                int minutes = Integer.parseInt(minutesStr);
                int seconds = Integer.parseInt(secondsStr);
                return minutes * 60 + seconds;
            }

            // Format: "5m" (only minutes)
            if (cleaned.matches("\\d+m")) {
                String minutesStr = cleaned.replace("m", "");
                int minutes = Integer.parseInt(minutesStr);
                return minutes * 60;
            }

            // Format: "330s" or "330" (only seconds)
            if (cleaned.matches("\\d+s?")) {
                String secondsStr = cleaned.replace("s", "");
                return Integer.parseInt(secondsStr);
            }

            // Format: "5h" (hours - convert to seconds)
            if (cleaned.matches("\\d+h")) {
                String hoursStr = cleaned.replace("h", "");
                int hours = Integer.parseInt(hoursStr);
                return hours * 3600;
            }

            // Format: "5h30m" (hours and minutes)
            if (cleaned.contains("h") && cleaned.contains("m")) {
                String hoursStr = cleaned.substring(0, cleaned.indexOf("h"));
                String minutesStr = cleaned.substring(cleaned.indexOf("h") + 1, cleaned.indexOf("m"));
                int hours = Integer.parseInt(hoursStr);
                int minutes = Integer.parseInt(minutesStr);
                return hours * 3600 + minutes * 60;
            }

        } catch (Exception e) {
            logDebug("Failed to parse time string '" + timeString + "': " + e.getMessage());
        }

        return 0;
    }

    private int readPointsNearBonus(DTOPoint bonusLocation) {
        logDebug("Reading points near bonus location: " + bonusLocation);

        // The points are displayed at a specific offset from the bonus indicator
        // Based on testing: 120% symbol at (83,632) -> points at (195,790) to (270,824)
        // Offset: X+112px, Y+158px, Width: 75px, Height: 34px

        int offsetX = 112;
        int offsetY = 158;
        int width = 75;
        int height = 34;

        DTOPoint topLeft = new DTOPoint(bonusLocation.getX() + offsetX, bonusLocation.getY() + offsetY);
        DTOPoint bottomRight = new DTOPoint(topLeft.getX() + width, topLeft.getY() + height);

        logDebug("OCR area for points: " + topLeft + " to " + bottomRight);

        // Retry up to 3 times to read the points
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String ocrResult = emuManager.ocrRegionText(EMULATOR_NUMBER, topLeft, bottomRight);
                debugOCRArea("Points near bonus (attempt " + attempt + ")", topLeft, bottomRight, ocrResult);

                if (ocrResult != null && !ocrResult.trim().isEmpty()) {
                    // Extract numeric value from OCR result (remove any non-digit characters)
                    String numericValue = ocrResult.replaceAll("[^0-9]", "");

                    if (!numericValue.isEmpty()) {
                        int points = Integer.parseInt(numericValue);
                        logInfo("✅ Detected points on overview: " + points + " (attempt " + attempt + ")");
                        return points;
                    } else {
                        logWarning("OCR result contains no numeric value: '" + ocrResult + "' (attempt " + attempt + ")");
                    }
                } else {
                    logWarning("OCR result is empty (attempt " + attempt + ")");
                }
            } catch (Exception e) {
                logWarning("Failed to read points via OCR (attempt " + attempt + "): " + e.getMessage());
            }

            // Wait 500ms before retry (except after last attempt)
            if (attempt < maxRetries) {
                sleepTask(500);
            }
        }

        logWarning("Could not read points near bonus after " + maxRetries + " attempts");
        return -1;
    }

    private void checkAndUseFreeMission() {
        logDebug("Checking for free mission button...");

        // Search for AM_+_1_Free_Mission.png around position (256, 527) with 90% threshold
        DTOImageSearchResult freeMissionResult = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.AM_PLUS_1_FREE_MISSION, 90);

        if (freeMissionResult.isFound()) {
            DTOPoint location = freeMissionResult.getPoint();

            // Verify it's near expected position (256, 527) - allow tolerance of ±50px
            int deltaX = Math.abs(location.getX() - 256);
            int deltaY = Math.abs(location.getY() - 527);

            if (deltaX <= 50 && deltaY <= 50) {
                logInfo("✅ Free mission button found at " + location + " (near expected 256,527) - using it");

                // Click on the free mission button
                emuManager.tapAtPoint(EMULATOR_NUMBER, location);
                sleepTask(1500);

                // Second click at coordinates (340, 780)
                DTOPoint confirmLocation = new DTOPoint(340, 780);
                logInfo("Clicking confirm at: " + confirmLocation);
                emuManager.tapAtPoint(EMULATOR_NUMBER, confirmLocation);
                sleepTask(1500);

                logInfo("✅ Free mission used successfully");
            } else {
                logDebug("Free mission button found at " + location + " but too far from expected position (256,527), skipping");
            }
        } else {
            logDebug("No free mission button found");
        }
    }

    private void checkAndUseAllianceMonuments() {
        logDebug("Checking for Alliance Monuments button...");

        // Search for AM_Alliance_Monuments.png in the upper right area with 94% threshold
        DTOImageSearchResult monumentsResult = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.AM_ALLIANCE_MONUMENTS, 94);

        if (monumentsResult.isFound()) {
            DTOPoint location = monumentsResult.getPoint();
            logInfo("✅ Alliance Monuments button found at " + location + " - using it");

            // Click on the Alliance Monuments button
            emuManager.tapAtPoint(EMULATOR_NUMBER, location);
            sleepTask(1500);

            // First click at coordinates (366, 1014)
            DTOPoint firstClick = new DTOPoint(366, 1014);
            logInfo("Clicking first position at: " + firstClick);
            emuManager.tapAtPoint(EMULATOR_NUMBER, firstClick);
            sleepTask(1500);

            // Second click at coordinates (250, 870)
            DTOPoint secondClick = new DTOPoint(250, 870);
            logInfo("Clicking second position at: " + secondClick);
            emuManager.tapAtPoint(EMULATOR_NUMBER, secondClick);
            sleepTask(1000);

            // Third click at coordinates (366, 1014)
            DTOPoint thirdClick = new DTOPoint(366, 1014);
            logInfo("Clicking third position at: " + thirdClick);
            emuManager.tapAtPoint(EMULATOR_NUMBER, thirdClick);
            sleepTask(500);

            // Fourth click at coordinates (154, 1002)
            DTOPoint fourthClick = new DTOPoint(154, 1002);
            logInfo("Clicking fourth position at: " + fourthClick);
            emuManager.tapAtPoint(EMULATOR_NUMBER, fourthClick);
            sleepTask(500);

            // Click back button twice to close
            DTOPoint backButton = new DTOPoint(50, 50);
            logInfo("Clicking back button (1/2) at: " + backButton);
            emuManager.tapAtPoint(EMULATOR_NUMBER, backButton);
            sleepTask(500);

            logInfo("Clicking back button (2/2) at: " + backButton);
            emuManager.tapAtPoint(EMULATOR_NUMBER, backButton);
            sleepTask(500);

            logInfo("✅ Alliance Monuments used successfully");
        } else {
            logDebug("No Alliance Monuments button found");
        }
    }

    private int readTaskAvailabilityTimers() {
        logDebug("Reading task availability timers from empty task slots...");

        // Timer positions for the two upper task slots
        // Left timer: 162,705 to 280,743
        // Right timer: 486,705 to 595,743
        DTOPoint leftTimerTopLeft = new DTOPoint(162, 705);
        DTOPoint leftTimerBottomRight = new DTOPoint(280, 743);
        DTOPoint rightTimerTopLeft = new DTOPoint(486, 705);
        DTOPoint rightTimerBottomRight = new DTOPoint(595, 743);

        int leftTimerSeconds = readTimerFromRegion(leftTimerTopLeft, leftTimerBottomRight, "Left");
        int rightTimerSeconds = readTimerFromRegion(rightTimerTopLeft, rightTimerBottomRight, "Right");

        // Return the shorter timer (or 0 if both failed)
        if (leftTimerSeconds > 0 && rightTimerSeconds > 0) {
            int shortestTimer = Math.min(leftTimerSeconds, rightTimerSeconds);
            logInfo("✅ Found task availability timers - using shortest: " + shortestTimer + " seconds");
            return shortestTimer;
        } else if (leftTimerSeconds > 0) {
            logInfo("✅ Found left timer: " + leftTimerSeconds + " seconds");
            return leftTimerSeconds;
        } else if (rightTimerSeconds > 0) {
            logInfo("✅ Found right timer: " + rightTimerSeconds + " seconds");
            return rightTimerSeconds;
        } else {
            logDebug("No task availability timers found");
            return 0;
        }
    }

    private int readTimerFromRegion(DTOPoint topLeft, DTOPoint bottomRight, String timerName) {
        try {
            String ocrResult = emuManager.ocrRegionText(EMULATOR_NUMBER, topLeft, bottomRight);
            debugOCRArea("Task availability timer (" + timerName + ")", topLeft, bottomRight, ocrResult);

            if (ocrResult != null && !ocrResult.trim().isEmpty()) {
                int seconds = parseTimeToSeconds(ocrResult);
                if (seconds > 0) {
                    logInfo("✅ " + timerName + " timer: " + ocrResult + " = " + seconds + " seconds");
                    return seconds;
                } else {
                    logDebug(timerName + " timer OCR failed to parse: '" + ocrResult + "'");
                }
            }
        } catch (Exception e) {
            logDebug(timerName + " timer OCR failed: " + e.getMessage());
        }
        return 0;
    }

    private void checkAndCollectCompletedTasks() {
        logDebug("Checking for completed tasks to collect rewards...");

        // Search for AM_Completed.png indicator
        DTOImageSearchResult completedResult = emuManager.searchTemplate(EMULATOR_NUMBER, EnumTemplates.AM_COMPLETED, 85);

        if (completedResult.isFound()) {
            logInfo("✅ Completed task found at " + completedResult.getPoint() + " - collecting rewards");

            // Click on the completed task
            emuManager.tapAtPoint(EMULATOR_NUMBER, completedResult.getPoint());
            sleepTask(1500);

            logInfo("Rewards collected from completed task");
        } else {
            logDebug("No completed tasks found");
        }
    }

    // Helper method to visualize OCR area
    private void debugOCRArea(String description, DTOPoint topLeft, DTOPoint bottomRight, String ocrResult) {
        logDebug("[OCR] " + description + " - Region: TL=" + topLeft + ", BR=" + bottomRight + ", Result='" + ocrResult + "'");
    }

    // Helper method to log template search results
    private void debugTemplateSearch(String templateName, DTOImageSearchResult result, int threshold) {
        if (result.isFound()) {
            logInfo("✅ Template FOUND: " + templateName + " at (" +
                   result.getPoint().getX() + "," + result.getPoint().getY() +
                   ") match: " + String.format("%.1f", result.getMatchPercentage()) + "% (threshold: " + threshold + "%)");
        } else {
            logInfo("❌ Template NOT FOUND: " + templateName + " (threshold: " + threshold + "%)");
        }
    }


    @Override
    public EnumStartLocation getRequiredStartLocation() {
        return EnumStartLocation.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return false; // Or true, depending on whether this task contributes to daily missions
    }

    private record AttemptStatus(int remaining, Integer total) {}
}
