package com.ticketsystem.api.archfixture.portal;

import com.ticketsystem.api.ticket.agent.AgentTicketViews.InternalNoteView;
import com.ticketsystem.api.ticket.clientdata.InternalNoteRepository.InternalNoteRow;
import java.util.List;

/**
 * Test-only negative fixture for ArchitectureTest: a portal response type that carries internal
 * notes. The rules that keep internal-note and agent types out of portal and public packages must
 * reject it. Not annotated, so it never becomes a Spring bean.
 */
public record LeakyPortalView(
    String reference, List<InternalNoteRow> notes, InternalNoteView one) {}
