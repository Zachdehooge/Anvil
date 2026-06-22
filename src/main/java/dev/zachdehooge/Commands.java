package dev.zachdehooge;

import net.dv8tion.jda.api.JDA;
import static net.dv8tion.jda.api.interactions.commands.OptionType.*;

import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import static net.dv8tion.jda.api.interactions.commands.build.Commands.*;

public class Commands {
    public Commands(JDA jda) {
        CommandListUpdateAction commands = jda.updateCommands();

        commands.addCommands(slash("setswschannel", "Sets the channel for SWS alerts to post too")
          .addOption(STRING, "channel", "where should the bot post updates too", true).setIntegrationTypes(IntegrationType.ALL));

        commands.addCommands(slash("setseverechannel", "Sets the channel for Svr-T-Storm alerts to post too").addOption(STRING, "channel", "where should the bot post updates too", true).setIntegrationTypes(IntegrationType.ALL));

        commands.addCommands(slash("settorchannel", "Sets the channel for tornado alerts to post too").addOption(STRING, "channel", "where should the bot post updates too", true).setIntegrationTypes(IntegrationType.ALL));

        commands.addCommands(slash("setwinterchannel", "Sets the channel for winter alerts to post too").addOption(STRING, "channel", "where should the bot post updates too", true).setIntegrationTypes(IntegrationType.ALL));

        commands.addCommands(slash("setpdschannel", "Sets the channel for pds alerts to post too").addOption(STRING, "channel", "where should the bot post updates too", true).setIntegrationTypes(IntegrationType.ALL));

        commands.addCommands(slash("setfloodchannel", "Sets the channel for flood alerts to post too").addOption(STRING, "channel", "where should the bot post updates too", true).setIntegrationTypes(IntegrationType.ALL));

        commands.addCommands(slash("setwatchchannel", "Sets the channel for watch alerts to post too").addOption(STRING, "channel", "where should the bot post updates too", true).setIntegrationTypes(IntegrationType.ALL));

        commands.addCommands(slash("gettempestobs", "Get Tempest observations")
        .setIntegrationTypes(IntegrationType.ALL));

        commands.queue();
    }
}
