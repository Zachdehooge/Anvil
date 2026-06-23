package dev.zachdehooge;

import net.dv8tion.jda.api.entities.MessageEmbed;

public record AlertEmbed(String id, MessageEmbed embed, String fullDescription, String eventName) {}
