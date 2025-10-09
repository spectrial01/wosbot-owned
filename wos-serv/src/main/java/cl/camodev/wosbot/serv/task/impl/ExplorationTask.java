package cl.camodev.wosbot.serv.task.impl;

import java.time.LocalDateTime;

import cl.camodev.wosbot.console.enumerable.EnumConfigurationKey;
import cl.camodev.wosbot.console.enumerable.EnumTemplates;
import cl.camodev.wosbot.console.enumerable.TpDailyTaskEnum;
import cl.camodev.wosbot.ot.DTOImageSearchResult;
import cl.camodev.wosbot.ot.DTOPoint;
import cl.camodev.wosbot.ot.DTOProfiles;
import cl.camodev.wosbot.serv.task.DelayedTask;

public class ExplorationTask extends DelayedTask {

	public ExplorationTask(DTOProfiles profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

	@Override
	protected void execute() {
		logInfo("Starting exploration task.");
		emuManager.tapAtRandomPoint(EMULATOR_NUMBER, new DTOPoint(40, 1190), new DTOPoint(100, 1250));
		sleepTask(500);
		DTOImageSearchResult claimResult = emuManager.searchTemplate(EMULATOR_NUMBER,
				EnumTemplates.EXPLORATION_CLAIM, 95);
		if (claimResult.isFound()) {
			logInfo("Claiming exploration rewards...");
			emuManager.tapAtRandomPoint(EMULATOR_NUMBER, new DTOPoint(560, 900), new DTOPoint(670, 940));
			sleepTask(500);
			emuManager.tapAtRandomPoint(EMULATOR_NUMBER, new DTOPoint(230, 890), new DTOPoint(490, 960));
			sleepTask(500);

			emuManager.tapAtRandomPoint(EMULATOR_NUMBER, new DTOPoint(230, 890), new DTOPoint(490, 960));
			sleepTask(200);
			emuManager.tapAtRandomPoint(EMULATOR_NUMBER, new DTOPoint(230, 890), new DTOPoint(490, 960));
			sleepTask(200);
			emuManager.tapAtRandomPoint(EMULATOR_NUMBER, new DTOPoint(230, 890), new DTOPoint(490, 960));
			sleepTask(200);


			Integer minutes = profile.getConfig(EnumConfigurationKey.INT_EXPLORATION_CHEST_OFFSET, Integer.class);
			LocalDateTime nextSchedule = LocalDateTime.now().plusMinutes(minutes);
			this.reschedule(nextSchedule);
			logInfo("Exploration task completed. Next execution scheduled in " + minutes + " minutes.");

		} else {
			logInfo("No exploration rewards to claim.");
			Integer minutes = profile.getConfig(EnumConfigurationKey.INT_EXPLORATION_CHEST_OFFSET, Integer.class);
			LocalDateTime nextSchedule = LocalDateTime.now().plusMinutes(minutes);
			this.reschedule(nextSchedule);
			logInfo("Exploration task completed. Next execution scheduled in " + minutes + " minutes.");

		}
		tapBackButton();
		sleepTask(500);
	}

}
