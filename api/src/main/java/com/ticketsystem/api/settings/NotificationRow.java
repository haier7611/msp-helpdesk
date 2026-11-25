package com.ticketsystem.api.settings;

/** One notification choice: an event, sent by email and/or in the app (storage only). */
public record NotificationRow(String event, boolean email, boolean inApp) {}
