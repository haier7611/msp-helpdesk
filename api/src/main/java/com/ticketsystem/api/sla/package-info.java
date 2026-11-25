/**
 * SLA engine: business calendars and SLA due-time calculation as pure logic, with no database, HTTP
 * or scheduled jobs. The ticket code wires it to tickets, the every-minute warning job and
 * notifications.
 *
 * <p>Rules implemented here, with their source:
 *
 * <ul>
 *   <li>Default targets per priority, P1 on a 24/7 clock and P2-P4 on business hours: {@link
 *       com.ticketsystem.api.sla.SlaPolicy#defaults()}.
 *   <li>Default business hours Monday-Friday 08:30-17:30 in the organisation's timezone with a
 *       holiday list; a business day is one full set of business hours: {@link
 *       com.ticketsystem.api.sla.BusinessCalendar}.
 *   <li>Which statuses run, pause or stop the clock: {@link
 *       com.ticketsystem.api.sla.TicketStatus#clockEffect(boolean)}.
 *   <li>Paused time is excluded, reopen continues the clock, the final Resolved counts, and a
 *       priority change recalculates from creation: {@link com.ticketsystem.api.sla.SlaCalculator}.
 *   <li>"About to breach" at 80% of the target used: {@link com.ticketsystem.api.sla.SlaWarning}.
 * </ul>
 *
 * <p>All results are internal only: SLA targets, due times and met/breached never reach a customer.
 * Nothing in this package is exposed over HTTP.
 */
package com.ticketsystem.api.sla;
