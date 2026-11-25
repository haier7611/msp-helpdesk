package com.ticketsystem.api.sla;

/** Which clock a priority's targets are measured on. */
public enum ClockType {
  /** Every minute counts, day or night, holidays included. Always used for P1. */
  TWENTY_FOUR_SEVEN,
  /** Only the organisation's business hours count. */
  BUSINESS_HOURS
}
